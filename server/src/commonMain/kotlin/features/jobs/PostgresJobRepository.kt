package features.jobs

import infrastructure.database.InstantJobCatalog
import infrastructure.database.PersistenceJson
import io.github.kormium.DelicateKormiumApi
import io.github.kormium.Scope

@OptIn(DelicateKormiumApi::class)
class PostgresJobRepository(private val scope: Scope<InstantJobCatalog>) : JobRepository {
    override fun lockIdempotencyKey(producerService: String, idempotencyKey: String) {
        scope.execute(
            "SELECT pg_advisory_xact_lock(hashtext(:key))",
            mapOf("key" to "$producerService:$idempotencyKey"),
            emptyList(),
        ) { Unit }
    }

    override fun findByIdempotencyKey(producerService: String, idempotencyKey: String): StoredJob? =
        scope.execute(
            "SELECT request_fingerprint, body::text FROM jobs WHERE producer_service=:producer AND idempotency_key=:key",
            mapOf("producer" to producerService, "key" to idempotencyKey),
            emptyList(),
        ) { StoredJob(PersistenceJson.decodeJob(it.getString(1)!!), it.getString(0)!!) }.firstOrNull()

    override fun findById(id: String, forUpdate: Boolean): Job? {
        val lock = if (forUpdate) " FOR UPDATE" else ""
        return scope.execute(
            "SELECT body::text FROM jobs WHERE id=CAST(:id AS uuid)$lock",
            mapOf("id" to id),
            emptyList(),
        ) { PersistenceJson.decodeJob(it.getString(0)!!) }.firstOrNull()
    }

    override fun listVisible(
        service: String,
        targetService: String?,
        taskName: String?,
        status: JobStatus?,
        offset: Int,
        limit: Int,
    ): List<Job> {
        val clauses = mutableListOf("(producer_service=:service OR target_service=:service)")
        val params = mutableMapOf<String, Any?>("service" to service, "limit" to limit, "offset" to offset)
        targetService?.let { clauses += "target_service=:target"; params["target"] = it }
        taskName?.let { clauses += "task_name=:task"; params["task"] = it }
        status?.let { clauses += "status=:status"; params["status"] = it.name }
        return scope.execute(
            "SELECT body::text FROM jobs WHERE ${clauses.joinToString(" AND ")} ORDER BY created_at,id LIMIT :limit OFFSET :offset",
            params,
            emptyList(),
        ) { PersistenceJson.decodeJob(it.getString(0)!!) }
    }

    override fun findDue(limit: Int): List<Job> = scope.execute(
        "SELECT body::text FROM jobs WHERE status='ACTIVE' AND next_run_at<=now() ORDER BY next_run_at FOR UPDATE SKIP LOCKED LIMIT :limit",
        mapOf("limit" to limit),
        emptyList(),
    ) { PersistenceJson.decodeJob(it.getString(0)!!) }

    override fun insert(job: Job, requestFingerprint: String) {
        val delivery = if (job.delivery is Delivery.Pull) "PULL" else "HTTP_CALLBACK"
        scope.executeUpdate(
            "INSERT INTO jobs(id,producer_service,target_service,task_name,idempotency_key,request_fingerprint,status,delivery_type,next_run_at,execution_count,body,created_at,updated_at) VALUES(CAST(:id AS uuid),:producer,:target,:task,:key,:fingerprint,:status,:delivery,CAST(:next AS timestamptz),:count,CAST(:body AS jsonb),CAST(:created AS timestamptz),CAST(:updated AS timestamptz))",
            mapOf(
                "id" to job.id, "producer" to job.producerService, "target" to job.targetService,
                "task" to job.taskName, "key" to job.idempotencyKey, "fingerprint" to requestFingerprint,
                "status" to job.status.name, "delivery" to delivery, "next" to job.nextRunAt?.toString(),
                "count" to job.executionCount, "body" to PersistenceJson.encode(job),
                "created" to job.createdAt.toString(), "updated" to job.updatedAt.toString(),
            ),
            emptyList(),
        )
    }

    override fun update(job: Job) {
        scope.executeUpdate(
            "UPDATE jobs SET status=:status,next_run_at=CAST(:next AS timestamptz),execution_count=:count,body=CAST(:body AS jsonb),updated_at=CAST(:updated AS timestamptz) WHERE id=CAST(:id AS uuid)",
            mapOf(
                "status" to job.status.name, "next" to job.nextRunAt?.toString(),
                "count" to job.executionCount, "body" to PersistenceJson.encode(job),
                "updated" to job.updatedAt.toString(), "id" to job.id,
            ),
            emptyList(),
        )
    }
}

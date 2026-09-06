package infrastructure.database

import features.executions.Attempt
import features.executions.AttemptRepository
import features.executions.Execution
import features.executions.ExecutionRepository
import features.executions.StoredExecution
import features.jobs.Delivery
import features.jobs.Job
import features.jobs.JobRepository
import features.jobs.JobStatus
import features.jobs.StoredJob
import features.scheduling.SchedulerTransactionManager
import features.scheduling.SchedulerUnitOfWork
import io.github.kormium.DelicateKormiumApi
import io.github.kormium.Scope
import io.github.kormium.transaction

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

@OptIn(DelicateKormiumApi::class)
class PostgresExecutionRepository(private val scope: Scope<InstantJobCatalog>) : ExecutionRepository {
    override fun findById(id: String, forUpdate: Boolean): StoredExecution? {
        val lock = if (forUpdate) " FOR UPDATE" else ""
        return scope.execute(
            "SELECT body::text,lease_token::text FROM executions WHERE id=CAST(:id AS uuid)$lock",
            mapOf("id" to id),
            emptyList(),
        ) { StoredExecution(PersistenceJson.decodeExecution(it.getString(0)!!), it.getString(1)) }.firstOrNull()
    }

    override fun listByJob(jobId: String, offset: Int, limit: Int): List<Execution> = scope.execute(
        "SELECT body::text FROM executions WHERE job_id=CAST(:id AS uuid) ORDER BY created_at,id LIMIT :limit OFFSET :offset",
        mapOf("id" to jobId, "limit" to limit, "offset" to offset),
        emptyList(),
    ) { PersistenceJson.decodeExecution(it.getString(0)!!) }

    override fun hasActive(jobId: String): Boolean = scope.execute(
        "SELECT EXISTS(SELECT 1 FROM executions WHERE job_id=CAST(:id AS uuid) AND status IN ('PENDING','LEASED'))",
        mapOf("id" to jobId),
        emptyList(),
    ) { it.getBoolean(0)!! }.single()

    override fun hasLeased(jobId: String): Boolean = scope.execute(
        "SELECT EXISTS(SELECT 1 FROM executions WHERE job_id=CAST(:id AS uuid) AND status='LEASED')",
        mapOf("id" to jobId),
        emptyList(),
    ) { it.getBoolean(0)!! }.single()

    override fun findClaimable(
        targetService: String?,
        taskNames: List<String>?,
        deliveryType: String,
        limit: Int,
    ): List<StoredExecution> {
        val params = mutableMapOf<String, Any?>("limit" to limit, "delivery" to deliveryType)
        val where = mutableListOf("status='PENDING'", "scheduled_at<=now()", "delivery_type=:delivery")
        targetService?.let { where += "target_service=:target"; params["target"] = it }
        if (!taskNames.isNullOrEmpty()) {
            val names = taskNames.mapIndexed { index, name -> "task$index".also { params[it] = name } }
            where += "task_name IN (${names.joinToString(",") { ":$it" }})"
        }
        return scope.execute(
            "SELECT body::text,lease_token::text FROM executions WHERE ${where.joinToString(" AND ")} ORDER BY scheduled_at,id FOR UPDATE SKIP LOCKED LIMIT :limit",
            params,
            emptyList(),
        ) { StoredExecution(PersistenceJson.decodeExecution(it.getString(0)!!), it.getString(1)) }
    }

    override fun findExpiredLeases(): List<StoredExecution> = scope.execute(
        "SELECT body::text,lease_token::text FROM executions WHERE status='LEASED' AND lease_until<=now() FOR UPDATE SKIP LOCKED",
        emptyMap(),
        emptyList(),
    ) { StoredExecution(PersistenceJson.decodeExecution(it.getString(0)!!), it.getString(1)) }

    override fun insert(execution: Execution, deliveryType: String) {
        scope.executeUpdate(
            "INSERT INTO executions(id,job_id,target_service,task_name,delivery_type,status,scheduled_at,body,created_at,updated_at) VALUES(CAST(:id AS uuid),CAST(:job AS uuid),:target,:task,:delivery,:status,CAST(:scheduled AS timestamptz),CAST(:body AS jsonb),CAST(:created AS timestamptz),CAST(:updated AS timestamptz))",
            mapOf(
                "id" to execution.id, "job" to execution.jobId, "target" to execution.targetService,
                "task" to execution.taskName, "delivery" to deliveryType, "status" to execution.status.name,
                "scheduled" to execution.scheduledAt.toString(), "body" to PersistenceJson.encode(execution),
                "created" to execution.createdAt.toString(), "updated" to execution.updatedAt.toString(),
            ),
            emptyList(),
        )
    }

    override fun update(execution: Execution, leaseToken: String?) {
        scope.executeUpdate(
            "UPDATE executions SET status=:status,scheduled_at=CAST(:scheduled AS timestamptz),lease_token=CAST(:token AS uuid),lease_until=CAST(:until AS timestamptz),body=CAST(:body AS jsonb),updated_at=CAST(:updated AS timestamptz) WHERE id=CAST(:id AS uuid)",
            mapOf(
                "status" to execution.status.name, "scheduled" to execution.scheduledAt.toString(),
                "token" to leaseToken, "until" to execution.leaseUntil?.toString(),
                "body" to PersistenceJson.encode(execution), "updated" to execution.updatedAt.toString(),
                "id" to execution.id,
            ),
            emptyList(),
        )
    }
}

@OptIn(DelicateKormiumApi::class)
class PostgresAttemptRepository(private val scope: Scope<InstantJobCatalog>) : AttemptRepository {
    override fun listByExecution(executionId: String): List<Attempt> = scope.execute(
        "SELECT body::text FROM attempts WHERE execution_id=CAST(:id AS uuid) ORDER BY attempt_number",
        mapOf("id" to executionId),
        emptyList(),
    ) { PersistenceJson.decodeAttempt(it.getString(0)!!) }

    override fun insert(executionId: String, attempt: Attempt) {
        scope.executeUpdate(
            "INSERT INTO attempts(execution_id,attempt_number,body,started_at) VALUES(CAST(:id AS uuid),:number,CAST(:body AS jsonb),CAST(:started AS timestamptz))",
            mapOf(
                "id" to executionId, "number" to attempt.number,
                "body" to PersistenceJson.encode(attempt), "started" to attempt.startedAt.toString(),
            ),
            emptyList(),
        )
    }

    override fun update(executionId: String, attempt: Attempt) {
        scope.executeUpdate(
            "UPDATE attempts SET body=CAST(:body AS jsonb),finished_at=CAST(:finished AS timestamptz) WHERE execution_id=CAST(:id AS uuid) AND attempt_number=:number",
            mapOf(
                "body" to PersistenceJson.encode(attempt), "finished" to attempt.finishedAt?.toString(),
                "id" to executionId, "number" to attempt.number,
            ),
            emptyList(),
        )
    }
}

private class PostgresSchedulerUnitOfWork(scope: Scope<InstantJobCatalog>) : SchedulerUnitOfWork {
    override val jobs: JobRepository = PostgresJobRepository(scope)
    override val executions: ExecutionRepository = PostgresExecutionRepository(scope)
    override val attempts: AttemptRepository = PostgresAttemptRepository(scope)
}

class PostgresSchedulerTransactionManager(private val database: PostgresDatabase) : SchedulerTransactionManager {
    override suspend fun <T> transaction(readOnly: Boolean, block: SchedulerUnitOfWork.() -> T): T =
        database.handle.transaction(readOnly = readOnly) { block(PostgresSchedulerUnitOfWork(this)) }

    override fun isReady(): Boolean = database.ping()
}

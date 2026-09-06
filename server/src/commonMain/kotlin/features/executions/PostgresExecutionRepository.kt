package features.executions

import infrastructure.database.InstantJobCatalog
import infrastructure.database.PersistenceJson
import io.github.kormium.DelicateKormiumApi
import io.github.kormium.Scope

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

package features.executions

import infrastructure.database.InstantJobCatalog
import io.github.kormium.DelicateKormiumApi
import io.github.kormium.Scope
import io.github.kormium.eq
import io.github.kormium.inList
import kotlin.uuid.Uuid

/** Stores executions and lease metadata in PostgreSQL through a transaction-bound Kormium scope. */
class PostgresExecutionRepository(private val scope: Scope<InstantJobCatalog>) : ExecutionRepository {
    override fun findById(id: String, forUpdate: Boolean): StoredExecution? {
        val uuid = Uuid.parse(id)
        if (forUpdate && !lockById(uuid)) return null
        return with(scope) { ExecutionsTable.findOne { where { ExecutionsTable.id eq uuid } } }
            ?.toStoredExecution()
    }

    override fun listByJob(jobId: String, offset: Int, limit: Int): List<Execution> = with(scope) {
        ExecutionsTable.find {
            where { ExecutionsTable.jobId eq Uuid.parse(jobId) }
            orderBy ASC ExecutionsTable.createdAt
            orderBy ASC ExecutionsTable.id
            this.offset = offset
            this.limit = limit
        }
    }.map { it.toStoredExecution().execution }

    override fun hasActive(jobId: String): Boolean = with(scope) {
        ExecutionsTable.count {
            where { ExecutionsTable.jobId eq Uuid.parse(jobId) }
            where { ExecutionsTable.status inList listOf(ExecutionStatus.PENDING, ExecutionStatus.LEASED) }
        }
    } > 0

    override fun hasLeased(jobId: String): Boolean = with(scope) {
        ExecutionsTable.count {
            where { ExecutionsTable.jobId eq Uuid.parse(jobId) }
            where { ExecutionsTable.status eq ExecutionStatus.LEASED }
        }
    } > 0

    override fun findClaimable(
        targetService: String?,
        taskNames: List<String>?,
        deliveryType: String,
        limit: Int,
    ): List<StoredExecution> = loadLocked(lockClaimableIds(targetService, taskNames, deliveryType, limit))

    override fun findExpiredLeases(): List<StoredExecution> = loadLocked(lockExpiredLeaseIds())

    override fun insert(execution: Execution, deliveryType: String) {
        with(scope) { ExecutionsTable.insert(execution.toEntity(deliveryType)) }
    }

    override fun update(execution: Execution, leaseToken: String?) {
        val id = Uuid.parse(execution.id)
        val affected = with(scope) {
            ExecutionsTable.update(execution.toUpdateEntity(leaseToken)) { where { ExecutionsTable.id eq id } }
        }
        check(affected == 1L) { "Execution does not exist: ${execution.id}" }
    }

    private fun loadLocked(ids: List<Uuid>): List<StoredExecution> {
        if (ids.isEmpty()) return emptyList()
        val byId = with(scope) {
            ExecutionsTable.find { where { ExecutionsTable.id inList ids } }
        }.associateBy { it.id }
        return ids.mapNotNull { byId[it]?.toStoredExecution() }
    }

    @OptIn(DelicateKormiumApi::class)
    private fun lockById(id: Uuid): Boolean = scope.execute(
        "SELECT id::text FROM executions WHERE id=:id FOR UPDATE",
        mapOf("id" to id),
        emptyList(),
    ) { it.getString(0)!! }.isNotEmpty()

    @OptIn(DelicateKormiumApi::class)
    private fun lockClaimableIds(
        targetService: String?,
        taskNames: List<String>?,
        deliveryType: String,
        limit: Int,
    ): List<Uuid> {
        val params = mutableMapOf<String, Any?>("limit" to limit, "delivery" to deliveryType)
        val where = mutableListOf("status='PENDING'", "scheduled_at<=now()", "delivery_type=:delivery")
        targetService?.let { where += "target_service=:target"; params["target"] = it }
        if (!taskNames.isNullOrEmpty()) {
            val names = taskNames.mapIndexed { index, name -> "task$index".also { params[it] = name } }
            where += "task_name IN (${names.joinToString(",") { ":$it" }})"
        }
        return scope.execute(
            "SELECT id::text FROM executions WHERE ${where.joinToString(" AND ")} " +
                    "ORDER BY scheduled_at,id FOR UPDATE SKIP LOCKED LIMIT :limit",
            params,
            emptyList(),
        ) { Uuid.parse(it.getString(0)!!) }
    }

    @OptIn(DelicateKormiumApi::class)
    private fun lockExpiredLeaseIds(): List<Uuid> = scope.execute(
        "SELECT id::text FROM executions WHERE status='LEASED' AND lease_until<=now() " +
                "FOR UPDATE SKIP LOCKED",
        emptyMap(),
        emptyList(),
    ) { Uuid.parse(it.getString(0)!!) }
}

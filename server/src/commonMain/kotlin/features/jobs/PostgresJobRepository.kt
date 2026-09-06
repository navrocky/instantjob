package features.jobs

import infrastructure.database.InstantJobCatalog
import io.github.kormium.DelicateKormiumApi
import io.github.kormium.Scope
import io.github.kormium.and
import io.github.kormium.eq
import io.github.kormium.inList
import io.github.kormium.or
import kotlin.uuid.Uuid

/** Stores jobs in PostgreSQL through a transaction-bound Kormium scope. */
class PostgresJobRepository(private val scope: Scope<InstantJobCatalog>) : JobRepository {
    @OptIn(DelicateKormiumApi::class)
    override fun lockIdempotencyKey(producerService: String, idempotencyKey: String) {
        scope.execute(
            "SELECT pg_advisory_xact_lock(hashtext(:key))",
            mapOf("key" to "$producerService:$idempotencyKey"),
            emptyList(),
        ) { Unit }
    }

    override fun findByIdempotencyKey(producerService: String, idempotencyKey: String): StoredJob? =
        with(scope) {
            JobsTable.findOne {
                where {
                    (JobsTable.producerService eq producerService) and
                            (JobsTable.idempotencyKey eq idempotencyKey)
                }
            }
        }?.toStoredJob()

    override fun findById(id: String, forUpdate: Boolean): Job? {
        val uuid = Uuid.parse(id)
        if (forUpdate && !lockById(uuid)) return null
        return with(scope) { JobsTable.findOne { where { JobsTable.id eq uuid } } }
            ?.toStoredJob()
            ?.job
    }

    override fun listVisible(
        service: String,
        targetService: String?,
        taskName: String?,
        status: JobStatus?,
        offset: Int,
        limit: Int,
    ): List<Job> = with(scope) {
        JobsTable.find {
            where { (JobsTable.producerService eq service) or (JobsTable.targetService eq service) }
            targetService?.let { value -> where { JobsTable.targetService eq value } }
            taskName?.let { value -> where { JobsTable.taskName eq value } }
            status?.let { value -> where { JobsTable.status eq value } }
            orderBy ASC JobsTable.createdAt
            orderBy ASC JobsTable.id
            this.offset = offset
            this.limit = limit
        }
    }.map { it.toStoredJob().job }

    override fun findDue(limit: Int): List<Job> {
        val ids = lockDueIds(limit)
        if (ids.isEmpty()) return emptyList()
        val byId = with(scope) {
            JobsTable.find { where { JobsTable.id inList ids } }
        }.associateBy { it.id }
        return ids.mapNotNull { byId[it]?.toStoredJob()?.job }
    }

    override fun insert(job: Job, requestFingerprint: String) {
        with(scope) { JobsTable.insert(job.toEntity(requestFingerprint)) }
    }

    override fun update(job: Job) {
        val id = Uuid.parse(job.id)
        val affected = with(scope) {
            JobsTable.update(job.toUpdateEntity()) { where { JobsTable.id eq id } }
        }
        check(affected == 1L) { "Job does not exist: ${job.id}" }
    }

    @OptIn(DelicateKormiumApi::class)
    private fun lockById(id: Uuid): Boolean = scope.execute(
        "SELECT id::text FROM jobs WHERE id=:id FOR UPDATE",
        mapOf("id" to id),
        emptyList(),
    ) { it.getString(0)!! }.isNotEmpty()

    @OptIn(DelicateKormiumApi::class)
    private fun lockDueIds(limit: Int): List<Uuid> = scope.execute(
        "SELECT id::text FROM jobs WHERE status='ACTIVE' AND next_run_at<=now() " +
                "ORDER BY next_run_at FOR UPDATE SKIP LOCKED LIMIT :limit",
        mapOf("limit" to limit),
        emptyList(),
    ) { Uuid.parse(it.getString(0)!!) }
}

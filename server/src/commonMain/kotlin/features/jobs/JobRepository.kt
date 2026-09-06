package features.jobs

/**
 * Job record with repository-only idempotency metadata.
 *
 * @property job Stored domain job.
 * @property requestFingerprint Canonical fingerprint of the original registration command.
 */
data class StoredJob(val job: Job, val requestFingerprint: String)

interface JobRepository {
    fun lockIdempotencyKey(producerService: String, idempotencyKey: String)
    fun findByIdempotencyKey(producerService: String, idempotencyKey: String): StoredJob?
    fun findById(id: String, forUpdate: Boolean = false): Job?
    fun listVisible(
        service: String,
        targetService: String?,
        taskName: String?,
        status: JobStatus?,
        offset: Int,
        limit: Int,
    ): List<Job>

    fun findDue(limit: Int): List<Job>
    fun insert(job: Job, requestFingerprint: String)
    fun update(job: Job)
}

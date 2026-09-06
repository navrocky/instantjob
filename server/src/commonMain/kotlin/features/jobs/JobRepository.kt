package features.jobs

/**
 * Job record with repository-only idempotency metadata.
 */
data class StoredJob(
    /** Stored domain job. */
    val job: Job,
    /** Canonical fingerprint of the original registration command. */
    val requestFingerprint: String,
)

/** Persists jobs and provides the queries required by scheduling workflows. */
interface JobRepository {
    /** Serializes registration for the producer-scoped [idempotencyKey] within the active transaction. */
    fun lockIdempotencyKey(producerService: String, idempotencyKey: String)

    /** Returns the job registered with [idempotencyKey] by [producerService], or null when absent. */
    fun findByIdempotencyKey(producerService: String, idempotencyKey: String): StoredJob?

    /** Returns the job with [id], optionally locking it for update, or null when it does not exist. */
    fun findById(id: String, forUpdate: Boolean = false): Job?

    /** Lists jobs visible to [service], applying the optional filters and offset pagination. */
    fun listVisible(
        service: String,
        targetService: String?,
        taskName: String?,
        status: JobStatus?,
        offset: Int,
        limit: Int,
    ): List<Job>

    /** Returns at most [limit] active jobs whose next execution is due, locked for processing. */
    fun findDue(limit: Int): List<Job>

    /** Stores [job] together with the canonical [requestFingerprint] used for idempotency checks. */
    fun insert(job: Job, requestFingerprint: String)

    /** Replaces the persisted state of [job]. */
    fun update(job: Job)
}

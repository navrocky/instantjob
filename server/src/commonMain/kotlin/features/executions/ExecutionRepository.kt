package features.executions

/**
 * Execution record with repository-only lease metadata.
 */
data class StoredExecution(
    /** Stored domain execution. */
    val execution: Execution,
    /** Token associated with the active lease, if any. */
    val leaseToken: String?,
)

/** Persists executions, lease metadata, and scheduler-facing execution queries. */
interface ExecutionRepository {
    /** Returns [id] with its lease metadata, optionally locking it for update, or null when absent. */
    fun findById(id: String, forUpdate: Boolean = false): StoredExecution?

    /** Lists executions belonging to [jobId] using offset pagination. */
    fun listByJob(jobId: String, offset: Int, limit: Int): List<Execution>

    /** Returns whether [jobId] has a pending or leased execution. */
    fun hasActive(jobId: String): Boolean

    /** Returns whether [jobId] has an execution with an active lease. */
    fun hasLeased(jobId: String): Boolean

    /** Returns and locks claimable executions matching the target, task, and delivery filters. */
    fun findClaimable(
        targetService: String?,
        taskNames: List<String>?,
        deliveryType: String,
        limit: Int,
    ): List<StoredExecution>

    /** Returns and locks leased executions whose lease has expired. */
    fun findExpiredLeases(): List<StoredExecution>

    /** Stores [execution] with the persistence discriminator [deliveryType]. */
    fun insert(execution: Execution, deliveryType: String)

    /** Replaces [execution] and stores its current [leaseToken], if any. */
    fun update(execution: Execution, leaseToken: String?)
}

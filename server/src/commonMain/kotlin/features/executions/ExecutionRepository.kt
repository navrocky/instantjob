package features.executions

/**
 * Execution record with repository-only lease metadata.
 *
 * @property execution Stored domain execution.
 * @property leaseToken Token associated with the active lease, if any.
 */
data class StoredExecution(val execution: Execution, val leaseToken: String?)

interface ExecutionRepository {
    fun findById(id: String, forUpdate: Boolean = false): StoredExecution?
    fun listByJob(jobId: String, offset: Int, limit: Int): List<Execution>
    fun hasActive(jobId: String): Boolean
    fun hasLeased(jobId: String): Boolean
    fun findClaimable(
        targetService: String?,
        taskNames: List<String>?,
        deliveryType: String,
        limit: Int,
    ): List<StoredExecution>
    fun findExpiredLeases(): List<StoredExecution>
    fun insert(execution: Execution, deliveryType: String)
    fun update(execution: Execution, leaseToken: String?)
}

package features.executions

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

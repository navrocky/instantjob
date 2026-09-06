package features.executions

interface AttemptRepository {
    fun listByExecution(executionId: String): List<Attempt>
    fun insert(executionId: String, attempt: Attempt)
    fun update(executionId: String, attempt: Attempt)
}

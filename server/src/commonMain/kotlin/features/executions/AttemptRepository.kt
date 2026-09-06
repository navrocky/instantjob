package features.executions

/** Persists execution attempts and exposes their ordered history. */
interface AttemptRepository {
    /** Lists attempts for [executionId] in ascending attempt-number order. */
    fun listByExecution(executionId: String): List<Attempt>

    /** Stores [attempt] as part of the execution identified by [executionId]. */
    fun insert(executionId: String, attempt: Attempt)

    /** Replaces the stored [attempt] for [executionId]. */
    fun update(executionId: String, attempt: Attempt)
}

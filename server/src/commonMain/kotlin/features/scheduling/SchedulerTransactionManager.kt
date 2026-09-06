package features.scheduling

import features.executions.AttemptRepository
import features.executions.ExecutionRepository
import features.jobs.JobRepository

/** Groups table-specific repositories that participate in one scheduler transaction. */
interface SchedulerUnitOfWork {
    /** Job repository bound to the active transaction. */
    val jobs: JobRepository

    /** Execution repository bound to the active transaction. */
    val executions: ExecutionRepository

    /** Attempt repository bound to the active transaction. */
    val attempts: AttemptRepository
}

/** Executes scheduler operations with a shared transactional unit of work. */
interface SchedulerTransactionManager {
    /** Runs [block] atomically, optionally marking the database transaction as [readOnly]. */
    suspend fun <T> transaction(
        readOnly: Boolean = false,
        block: SchedulerUnitOfWork.() -> T,
    ): T

    /** Returns whether the transaction backend is ready to serve requests. */
    fun isReady(): Boolean
}

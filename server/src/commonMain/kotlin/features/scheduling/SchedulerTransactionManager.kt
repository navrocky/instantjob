package features.scheduling

import features.executions.AttemptRepository
import features.executions.ExecutionRepository
import features.jobs.JobRepository

interface SchedulerUnitOfWork {
    val jobs: JobRepository
    val executions: ExecutionRepository
    val attempts: AttemptRepository
}

interface SchedulerTransactionManager {
    suspend fun <T> transaction(
        readOnly: Boolean = false,
        block: SchedulerUnitOfWork.() -> T,
    ): T

    fun isReady(): Boolean
}

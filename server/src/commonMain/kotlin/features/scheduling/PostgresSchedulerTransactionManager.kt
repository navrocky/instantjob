package features.scheduling

import features.executions.AttemptRepository
import features.executions.ExecutionRepository
import features.executions.PostgresAttemptRepository
import features.executions.PostgresExecutionRepository
import features.jobs.JobRepository
import features.jobs.PostgresJobRepository
import infrastructure.database.InstantJobCatalog
import infrastructure.database.PostgresDatabase
import io.github.kormium.Scope
import io.github.kormium.transaction

/** Creates PostgreSQL repository adapters that share the active Kormium transaction. */
private class PostgresSchedulerUnitOfWork(scope: Scope<InstantJobCatalog>) : SchedulerUnitOfWork {
    override val jobs: JobRepository = PostgresJobRepository(scope)
    override val executions: ExecutionRepository = PostgresExecutionRepository(scope)
    override val attempts: AttemptRepository = PostgresAttemptRepository(scope)
}

/** Runs scheduler units of work inside PostgreSQL transactions. */
class PostgresSchedulerTransactionManager(
    private val database: PostgresDatabase,
) : SchedulerTransactionManager {
    override suspend fun <T> transaction(readOnly: Boolean, block: SchedulerUnitOfWork.() -> T): T =
        database.handle.transaction(readOnly = readOnly) { block(PostgresSchedulerUnitOfWork(this)) }

    override fun isReady(): Boolean = database.ping()
}

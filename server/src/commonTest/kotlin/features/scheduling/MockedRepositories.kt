package features.scheduling

import features.executions.Attempt
import features.executions.Execution
import features.executions.ExecutionStatus
import features.jobs.Job
import features.jobs.JobStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import features.executions.AttemptRepository
import features.executions.ExecutionRepository
import features.executions.StoredExecution
import features.jobs.JobRepository
import features.jobs.StoredJob
import kotlin.time.Clock

class MockedJobRepository(
    private val rows: LinkedHashMap<String, StoredJob> = linkedMapOf(),
    private val clock: Clock = Clock.System,
) : JobRepository {
    override fun lockIdempotencyKey(producerService: String, idempotencyKey: String) = Unit

    override fun findByIdempotencyKey(producerService: String, idempotencyKey: String): StoredJob? =
        rows.values.firstOrNull {
            it.job.producerService == producerService && it.job.idempotencyKey == idempotencyKey
        }

    override fun findById(id: String, forUpdate: Boolean): Job? = rows[id]?.job

    override fun listVisible(
        service: String,
        targetService: String?,
        taskName: String?,
        status: JobStatus?,
        offset: Int,
        limit: Int,
    ): List<Job> = rows.values.asSequence().map(StoredJob::job)
        .filter { it.producerService == service || it.targetService == service }
        .filter { targetService == null || it.targetService == targetService }
        .filter { taskName == null || it.taskName == taskName }
        .filter { status == null || it.status == status }
        .sortedWith(compareBy(Job::createdAt, Job::id))
        .drop(offset)
        .take(limit)
        .toList()

    override fun findDue(limit: Int): List<Job> = rows.values.asSequence().map(StoredJob::job)
        .filter { it.status == JobStatus.ACTIVE && it.nextRunAt != null && it.nextRunAt <= clock.now() }
        .sortedWith(compareBy(Job::nextRunAt, Job::id))
        .take(limit)
        .toList()

    override fun insert(job: Job, requestFingerprint: String) {
        check(rows.values.none {
            it.job.producerService == job.producerService && it.job.idempotencyKey == job.idempotencyKey
        })
        rows[job.id] = StoredJob(job, requestFingerprint)
    }

    override fun update(job: Job) {
        val old = rows[job.id] ?: error("Job does not exist: ${job.id}")
        rows[job.id] = old.copy(job = job)
    }
}

class MockedExecutionRepository(
    private val rows: LinkedHashMap<String, StoredExecution> = linkedMapOf(),
    private val deliveryTypes: MutableMap<String, String> = mutableMapOf(),
    private val clock: Clock = Clock.System,
) : ExecutionRepository {
    override fun findById(id: String, forUpdate: Boolean): StoredExecution? = rows[id]

    override fun listByJob(jobId: String, offset: Int, limit: Int): List<Execution> =
        rows.values.asSequence().map(StoredExecution::execution)
            .filter { it.jobId == jobId }
            .sortedWith(compareBy(Execution::createdAt, Execution::id))
            .drop(offset)
            .take(limit)
            .toList()

    override fun hasActive(jobId: String): Boolean = rows.values.any {
        it.execution.jobId == jobId && it.execution.status in setOf(ExecutionStatus.PENDING, ExecutionStatus.LEASED)
    }

    override fun hasLeased(jobId: String): Boolean = rows.values.any {
        it.execution.jobId == jobId && it.execution.status == ExecutionStatus.LEASED
    }

    override fun findClaimable(
        targetService: String?,
        taskNames: List<String>?,
        deliveryType: String,
        limit: Int,
    ): List<StoredExecution> = rows.values.asSequence()
        .filter { it.execution.status == ExecutionStatus.PENDING && it.execution.scheduledAt <= clock.now() }
        .filter { deliveryTypes[it.execution.id] == deliveryType }
        .filter { targetService == null || it.execution.targetService == targetService }
        .filter { taskNames.isNullOrEmpty() || it.execution.taskName in taskNames }
        .sortedWith(compareBy({ it.execution.scheduledAt }, { it.execution.id }))
        .take(limit)
        .toList()

    override fun findExpiredLeases(): List<StoredExecution> = rows.values.filter {
        it.execution.status == ExecutionStatus.LEASED &&
            (it.execution.leaseUntil ?: kotlin.time.Instant.DISTANT_FUTURE) <= clock.now()
    }

    override fun insert(execution: Execution, deliveryType: String) {
        check(execution.id !in rows)
        rows[execution.id] = StoredExecution(execution, null)
        deliveryTypes[execution.id] = deliveryType
    }

    override fun update(execution: Execution, leaseToken: String?) {
        check(execution.id in rows)
        rows[execution.id] = StoredExecution(execution, leaseToken)
    }
}

class MockedAttemptRepository(
    private val rows: MutableMap<String, MutableList<Attempt>> = mutableMapOf(),
) : AttemptRepository {
    override fun listByExecution(executionId: String): List<Attempt> =
        rows[executionId].orEmpty().sortedBy(Attempt::number)

    override fun insert(executionId: String, attempt: Attempt) {
        val attempts = rows.getOrPut(executionId) { mutableListOf() }
        check(attempts.none { it.number == attempt.number })
        attempts += attempt
    }

    override fun update(executionId: String, attempt: Attempt) {
        val attempts = rows[executionId] ?: error("Execution has no attempts: $executionId")
        val index = attempts.indexOfFirst { it.number == attempt.number }
        check(index >= 0)
        attempts[index] = attempt
    }
}

class MockedSchedulerTransactionManager(clock: Clock = Clock.System) : SchedulerTransactionManager {
    private val mutex = Mutex()
    private val jobRows = linkedMapOf<String, StoredJob>()
    private val executionRows = linkedMapOf<String, StoredExecution>()
    private val deliveryTypes = mutableMapOf<String, String>()
    private val attemptRows = mutableMapOf<String, MutableList<Attempt>>()

    private val unitOfWork = object : SchedulerUnitOfWork {
        override val jobs: JobRepository = MockedJobRepository(jobRows, clock)
        override val executions: ExecutionRepository =
            MockedExecutionRepository(executionRows, deliveryTypes, clock)
        override val attempts: AttemptRepository = MockedAttemptRepository(attemptRows)
    }

    override suspend fun <T> transaction(readOnly: Boolean, block: SchedulerUnitOfWork.() -> T): T = mutex.withLock {
        val jobsSnapshot = LinkedHashMap(jobRows)
        val executionsSnapshot = LinkedHashMap(executionRows)
        val deliverySnapshot = HashMap(deliveryTypes)
        val attemptsSnapshot = attemptRows.mapValuesTo(mutableMapOf()) { (_, attempts) -> attempts.toMutableList() }
        try {
            block(unitOfWork)
        } catch (error: Throwable) {
            jobRows.clear()
            jobRows.putAll(jobsSnapshot)
            executionRows.clear()
            executionRows.putAll(executionsSnapshot)
            deliveryTypes.clear()
            deliveryTypes.putAll(deliverySnapshot)
            attemptRows.clear()
            attemptRows.putAll(attemptsSnapshot)
            throw error
        }
    }

    override fun isReady(): Boolean = true
}

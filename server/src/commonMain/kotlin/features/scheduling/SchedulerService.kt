package features.scheduling

import common.DomainErrorKind
import common.DomainException
import common.DelayProvider
import common.JsonValue
import features.executions.*
import features.jobs.*
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
/** Application boundary for job scheduling and execution workflows. */
interface JobService {
    suspend fun create(producer: String, request: CreateJobCommand): CreateJobResult
    suspend fun getJob(service: String, id: String): Job
    suspend fun listJobs(
        service: String,
        target: String?,
        task: String?,
        status: JobStatus?,
        cursor: String?,
        limit: Int,
    ): Pair<List<Job>, String?>

    suspend fun cancel(service: String, id: String): Job
}

interface ExecutionService {
    suspend fun getExecution(service: String, id: String): Pair<Execution, List<Attempt>>
    suspend fun listExecutions(
        service: String,
        jobId: String,
        cursor: String?,
        limit: Int,
    ): Pair<List<Execution>, String?>

    suspend fun claim(
        targetService: String,
        taskNames: List<String>?,
        maxItems: Int,
        waitSeconds: Int,
    ): List<ClaimedExecution>

    suspend fun claimCallbacks(maxItems: Int): List<ClaimedExecution>
    suspend fun heartbeat(targetService: String, executionId: String, token: String): Instant
    suspend fun complete(
        targetService: String,
        executionId: String,
        request: CompleteExecutionCommand,
    ): Pair<Execution, List<Attempt>>
}

interface SchedulerMaintenanceService {
    suspend fun tick()
    fun isReady(): Boolean
}

interface SchedulerService : JobService, ExecutionService, SchedulerMaintenanceService

class DefaultSchedulerService(
    private val transactions: SchedulerTransactionManager,
    private val clock: Clock,
    private val schedulePlanner: SchedulePlanner,
    private val delayProvider: DelayProvider,
    private val idGenerator: () -> String,
    private val randomFactor: (Double, Double) -> Double,
    private val leaseSeconds: Long = 60,
) : SchedulerService {
    override suspend fun create(producer: String, request: CreateJobCommand): CreateJobResult = transactions.transaction {
        validateRequest(request)
        jobs.lockIdempotencyKey(producer, request.idempotencyKey)
        val fingerprint = request.fingerprint()
        jobs.findByIdempotencyKey(producer, request.idempotencyKey)?.let { existing ->
            if (existing.requestFingerprint != fingerprint) conflict("Idempotency key is already used with another request")
            return@transaction CreateJobResult(false, existing.job)
        }
        val now = clock.now()
        var job = Job(
            id = newId(), producerService = producer, targetService = request.targetService,
            taskName = request.taskName, idempotencyKey = request.idempotencyKey,
            parameters = request.parameters, schedule = request.schedule, delivery = request.delivery,
            retryPolicy = normalizedRetry(request.retryPolicy),
            executionPolicy = request.executionPolicy ?: ExecutionPolicy(), status = JobStatus.ACTIVE,
            createdAt = now, updatedAt = now, nextRunAt = schedulePlanner.firstRunAt(request.schedule, now),
            executionCount = 0,
        )
        jobs.insert(job, fingerprint)
        if (job.nextRunAt!! <= now) job = materialize(job, now)
        CreateJobResult(true, job)
    }

    override suspend fun getJob(service: String, id: String): Job = transactions.transaction(readOnly = true) {
        visibleJob(service, id)
    }

    override suspend fun listJobs(
        service: String,
        target: String?,
        task: String?,
        status: JobStatus?,
        cursor: String?,
        limit: Int,
    ): Pair<List<Job>, String?> = transactions.transaction(readOnly = true) {
        val offset = cursor?.toIntOrNull() ?: 0
        val rows = jobs.listVisible(service, target, task, status, offset, limit + 1)
        rows.take(limit) to if (rows.size > limit) (offset + limit).toString() else null
    }

    override suspend fun cancel(service: String, id: String): Job = transactions.transaction {
        var job = visibleJob(service, id, forUpdate = true)
        if (job.producerService != service) forbidden("Only the producer may cancel a job")
        if (job.status in setOf(JobStatus.CANCELLED, JobStatus.COMPLETED, JobStatus.FAILED)) return@transaction job
        job = job.copy(
            status = if (executions.hasLeased(job.id)) JobStatus.CANCELLING else JobStatus.CANCELLED,
            nextRunAt = null,
            updatedAt = clock.now(),
        )
        jobs.update(job)
        job
    }

    override suspend fun getExecution(service: String, id: String): Pair<Execution, List<Attempt>> =
        transactions.transaction(readOnly = true) {
            val execution = executions.findById(id)?.execution ?: notFound("Execution", id)
            visibleJob(service, execution.jobId)
            execution to attempts.listByExecution(id)
        }

    override suspend fun listExecutions(
        service: String,
        jobId: String,
        cursor: String?,
        limit: Int,
    ): Pair<List<Execution>, String?> = transactions.transaction(readOnly = true) {
        visibleJob(service, jobId)
        val offset = cursor?.toIntOrNull() ?: 0
        val rows = executions.listByJob(jobId, offset, limit + 1)
        rows.take(limit) to if (rows.size > limit) (offset + limit).toString() else null
    }

    override suspend fun claim(
        targetService: String,
        taskNames: List<String>?,
        maxItems: Int,
        waitSeconds: Int,
    ): List<ClaimedExecution> {
        val deadline = clock.now() + waitSeconds.seconds
        do {
            tick()
            val result = claimRows(targetService, taskNames, maxItems, "PULL")
            if (result.isNotEmpty() || clock.now() >= deadline) return result
            delayProvider.delay(100.milliseconds)
        } while (true)
    }

    override suspend fun claimCallbacks(maxItems: Int): List<ClaimedExecution> {
        tick()
        return claimRows(null, null, maxItems, "HTTP_CALLBACK")
    }

    override suspend fun heartbeat(targetService: String, executionId: String, token: String): Instant =
        transactions.transaction {
            val execution = requireLease(targetService, executionId, token)
            val until = clock.now() + leaseSeconds.seconds
            executions.update(execution.copy(leaseUntil = until, updatedAt = clock.now()), token)
            until
        }

    override suspend fun complete(
        targetService: String,
        executionId: String,
        request: CompleteExecutionCommand,
    ): Pair<Execution, List<Attempt>> = transactions.transaction {
        var execution = requireLease(targetService, executionId, request.leaseToken)
        var job = visibleJob(targetService, execution.jobId, forUpdate = true)
        val now = clock.now()
        val finished = attempts.listByExecution(executionId).last().copy(finishedAt = now, error = request.error)
        attempts.update(executionId, finished)
        if (request.outcome == CompletionOutcome.SUCCEEDED) {
            execution = execution.copy(
                status = ExecutionStatus.SUCCEEDED, leaseUntil = null, result = request.result,
                error = null, updatedAt = now,
            )
            job = finishJob(job, true, now)
        } else {
            val retry = request.retryable != false && execution.attemptCount < job.retryPolicy.maxAttempts
            execution = if (retry) execution.copy(
                status = ExecutionStatus.PENDING, leaseUntil = null, error = request.error,
                scheduledAt = now + retryDelay(job.retryPolicy, execution.attemptCount).seconds, updatedAt = now,
            ) else execution.copy(
                status = ExecutionStatus.FAILED, leaseUntil = null, error = request.error, updatedAt = now,
            )
            if (!retry) job = finishJob(job, false, now)
        }
        executions.update(execution, null)
        jobs.update(job)
        execution to attempts.listByExecution(executionId)
    }

    override suspend fun tick() = transactions.transaction {
        recoverExpired(clock.now())
        jobs.findDue(100).forEach { job ->
            if (!executions.hasActive(job.id) || job.executionPolicy.allowOverlap == true) {
                materialize(job, clock.now())
            }
        }
    }

    override fun isReady(): Boolean = transactions.isReady()

    private suspend fun claimRows(
        target: String?,
        tasks: List<String>?,
        max: Int,
        delivery: String,
    ): List<ClaimedExecution> = transactions.transaction {
        val now = clock.now()
        executions.findClaimable(target, tasks, delivery, max).map { stored ->
            val token = newId()
            val until = now + leaseSeconds.seconds
            val execution = stored.execution.copy(
                status = ExecutionStatus.LEASED,
                attemptCount = stored.execution.attemptCount + 1,
                leaseUntil = until,
                updatedAt = now,
            )
            executions.update(execution, token)
            attempts.insert(execution.id, Attempt(number = execution.attemptCount, startedAt = now))
            ClaimedExecution(execution, token, until)
        }
    }

    private fun SchedulerUnitOfWork.materialize(source: Job, now: Instant): Job {
        val due = source.nextRunAt ?: return source
        val execution = Execution(
            id = newId(), jobId = source.id, producerService = source.producerService,
            targetService = source.targetService, taskName = source.taskName, parameters = source.parameters,
            scheduledAt = due, status = ExecutionStatus.PENDING, attemptCount = 0,
            createdAt = now, updatedAt = now,
        )
        executions.insert(execution, if (source.delivery is Delivery.Pull) "PULL" else "HTTP_CALLBACK")
        val count = source.executionCount + 1
        val updated = source.copy(
            executionCount = count,
            nextRunAt = schedulePlanner.nextRunAt(source.schedule, due, now, count),
            updatedAt = now,
        )
        jobs.update(updated)
        return updated
    }

    private fun SchedulerUnitOfWork.visibleJob(service: String, id: String, forUpdate: Boolean = false): Job =
        (jobs.findById(id, forUpdate) ?: notFound("Job", id)).also {
            if (it.producerService != service && it.targetService != service) {
                forbidden("Resource is not visible to this service")
            }
        }

    private fun SchedulerUnitOfWork.requireLease(service: String, id: String, token: String): Execution {
        val stored: StoredExecution = executions.findById(id, forUpdate = true) ?: notFound("Execution", id)
        val execution = stored.execution
        if (
            execution.targetService != service || execution.status != ExecutionStatus.LEASED ||
            stored.leaseToken != token || (execution.leaseUntil ?: Instant.DISTANT_PAST) <= clock.now()
        ) {
            conflict("Lease is missing, expired, or owned by another worker", "LEASE_CONFLICT")
        }
        return execution
    }

    private fun SchedulerUnitOfWork.recoverExpired(now: Instant) {
        executions.findExpiredLeases().forEach { stored ->
            val execution = stored.execution
            val job = visibleJob(execution.targetService, execution.jobId, forUpdate = true)
            val expiredAttempt = attempts.listByExecution(execution.id).last().copy(
                finishedAt = now,
                error = "Lease expired",
            )
            attempts.update(execution.id, expiredAttempt)
            val retry = execution.attemptCount < job.retryPolicy.maxAttempts
            val updated = if (retry) execution.copy(
                status = ExecutionStatus.PENDING, leaseUntil = null,
                scheduledAt = now + retryDelay(job.retryPolicy, execution.attemptCount).seconds,
                error = "Lease expired", updatedAt = now,
            ) else execution.copy(
                status = ExecutionStatus.FAILED, leaseUntil = null, error = "Lease expired", updatedAt = now,
            )
            executions.update(updated, null)
            if (!retry) jobs.update(finishJob(job, false, now))
        }
    }

    private fun finishJob(job: Job, success: Boolean, now: Instant): Job = when {
        job.status == JobStatus.CANCELLING -> job.copy(status = JobStatus.CANCELLED, updatedAt = now)
        job.nextRunAt == null -> job.copy(
            status = if (success) JobStatus.COMPLETED else JobStatus.FAILED,
            updatedAt = now,
        )
        else -> job
    }

    private fun validateRequest(request: CreateJobCommand) {
        val both = when (val schedule = request.schedule) {
            is Schedule.Interval -> schedule.maxExecutions != null && schedule.endAt != null
            is Schedule.Cron -> schedule.maxExecutions != null && schedule.endAt != null
            else -> false
        }
        if (both) throw DomainException(
            DomainErrorKind.BAD_REQUEST,
            "INVALID_SCHEDULE",
            "Only one of maxExecutions and endAt may be set",
        )
    }

    private fun normalizedRetry(value: RetryPolicy?) = RetryPolicy(
        maxAttempts = value?.maxAttempts ?: 5,
        initialDelaySeconds = value?.initialDelaySeconds ?: 5,
        multiplier = value?.multiplier ?: 2.0,
        maxDelaySeconds = value?.maxDelaySeconds ?: 300,
        jitter = value?.jitter ?: .2,
    )

    private fun retryDelay(policy: RetryPolicy, attempt: Int): Long = (min(
        policy.initialDelaySeconds * policy.multiplier.pow((attempt - 1).coerceAtLeast(0)),
        policy.maxDelaySeconds.toDouble(),
    ) * randomFactor(1.0 - policy.jitter, 1.0 + policy.jitter)).toLong().coerceAtLeast(0)

    private fun CreateJobCommand.fingerprint(): String = listOf(
        targetService,
        taskName,
        idempotencyKey,
        parameters.entries.sortedBy { it.key }.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "$key:${value.fingerprint()}"
        },
        schedule.toString(),
        delivery.toString(),
        retryPolicy.toString(),
        executionPolicy.toString(),
    ).joinToString("|")

    private fun JsonValue.fingerprint(): String = when (this) {
        JsonValue.Null -> "null"
        is JsonValue.BooleanValue -> value.toString()
        is JsonValue.NumberValue -> value
        is JsonValue.StringValue -> "\"$value\""
        is JsonValue.ArrayValue -> values.joinToString(prefix = "[", postfix = "]") { it.fingerprint() }
        is JsonValue.ObjectValue -> values.entries.sortedBy { it.key }
            .joinToString(prefix = "{", postfix = "}") { (key, value) -> "$key:${value.fingerprint()}" }
    }

    private fun notFound(kind: String, id: String): Nothing =
        throw DomainException(DomainErrorKind.NOT_FOUND, "NOT_FOUND", "$kind could not be found: id=$id")

    private fun forbidden(message: String): Nothing =
        throw DomainException(DomainErrorKind.FORBIDDEN, "FORBIDDEN", message)

    private fun conflict(message: String, code: String = "IDEMPOTENCY_CONFLICT"): Nothing =
        throw DomainException(DomainErrorKind.CONFLICT, code, message)

    private fun newId(): String = idGenerator()
}

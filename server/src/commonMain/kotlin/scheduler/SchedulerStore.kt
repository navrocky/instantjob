package scheduler

import instantjob.api.generated.models.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import instantjob.api.generated.models.Job as ApiJob

class ApiException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
) : RuntimeException(message)

data class CreateResult(val created: Boolean, val job: ApiJob)

interface SchedulerRepository {
    suspend fun create(producer: String, request: CreateJobRequest): CreateResult
    suspend fun getJob(service: String, id: String): ApiJob
    suspend fun listJobs(
        service: String,
        target: String?,
        task: String?,
        status: JobStatus?,
        cursor: String?,
        limit: Int
    ): Pair<List<ApiJob>, String?>

    suspend fun cancel(service: String, id: String): ApiJob
    suspend fun getExecution(service: String, id: String): Pair<Execution, List<Attempt>>
    suspend fun listExecutions(
        service: String,
        jobId: String,
        cursor: String?,
        limit: Int
    ): Pair<List<Execution>, String?>

    suspend fun claim(
        targetService: String,
        taskNames: List<String>?,
        maxItems: Int,
        waitSeconds: Int
    ): List<ClaimedExecution>

    suspend fun claimCallbacks(maxItems: Int): List<ClaimedExecution>
    suspend fun heartbeat(targetService: String, executionId: String, token: String): Instant
    suspend fun complete(
        targetService: String,
        executionId: String,
        request: CompleteExecutionRequest
    ): Pair<Execution, List<Attempt>>

    suspend fun tick()
    fun isReady(): Boolean
}

private data class StoredJob(
    var value: ApiJob,
    val requestFingerprint: String,
)

private data class StoredExecution(
    var value: Execution,
    var leaseToken: String? = null,
    val attempts: MutableList<Attempt> = mutableListOf(),
)

class SchedulerStore(
    private val clock: Clock = Clock.System,
    private val leaseSeconds: Long = 60,
) : SchedulerRepository {
    private val mutex = Mutex()
    private val jobs = linkedMapOf<String, StoredJob>()
    private val executions = linkedMapOf<String, StoredExecution>()
    private val idempotency = mutableMapOf<Pair<String, String>, String>()
    private val json = Json { encodeDefaults = true; classDiscriminator = "type" }

    override suspend fun create(producer: String, request: CreateJobRequest): CreateResult = mutex.withLock {
        validateRequest(request)
        val key = producer to request.idempotencyKey
        val fingerprint = json.encodeToString(request)
        idempotency[key]?.let { existingId ->
            val existing = jobs.getValue(existingId)
            if (existing.requestFingerprint != fingerprint) {
                throw ApiException(
                    HttpStatusCode.Conflict,
                    "IDEMPOTENCY_CONFLICT",
                    "Idempotency key is already used with another request"
                )
            }
            return@withLock CreateResult(false, existing.value)
        }

        val now = clock.now()
        val firstRunAt = ScheduleCalculator.firstRunAt(request.schedule, now)
        val job = ApiJob(
            id = newId(),
            producerService = producer,
            targetService = request.targetService,
            taskName = request.taskName,
            idempotencyKey = request.idempotencyKey,
            parameters = request.parameters,
            schedule = request.schedule,
            delivery = request.delivery,
            retryPolicy = normalizedRetry(request.retryPolicy),
            executionPolicy = request.executionPolicy ?: ExecutionPolicy(),
            status = JobStatus.ACTIVE,
            createdAt = now,
            updatedAt = now,
            nextRunAt = firstRunAt,
            executionCount = 0,
        )
        jobs[job.id] = StoredJob(job, fingerprint)
        idempotency[key] = job.id
        materializeDueLocked(now)
        CreateResult(true, jobs.getValue(job.id).value)
    }

    override suspend fun getJob(service: String, id: String): ApiJob = mutex.withLock {
        visibleJob(service, id).value
    }

    override suspend fun listJobs(
        service: String,
        target: String?,
        task: String?,
        status: JobStatus?,
        cursor: String?,
        limit: Int
    ): Pair<List<ApiJob>, String?> = mutex.withLock {
        val offset = cursor?.toIntOrNull() ?: 0
        val filtered = jobs.values.asSequence().map { it.value }
            .filter { it.producerService == service || it.targetService == service }
            .filter { target == null || it.targetService == target }
            .filter { task == null || it.taskName == task }
            .filter { status == null || it.status == status }
            .toList()
        val page = filtered.drop(offset).take(limit)
        page to if (offset + page.size < filtered.size) (offset + page.size).toString() else null
    }

    override suspend fun cancel(service: String, id: String): ApiJob = mutex.withLock {
        val stored = visibleJob(service, id)
        if (stored.value.producerService != service) {
            throw ApiException(HttpStatusCode.Forbidden, "FORBIDDEN", "Only the producer may cancel a job")
        }
        if (stored.value.status in setOf(
                JobStatus.CANCELLED,
                JobStatus.COMPLETED,
                JobStatus.FAILED
            )
        ) return@withLock stored.value
        val active = executions.values.any { it.value.jobId == id && it.value.status == ExecutionStatus.LEASED }
        stored.value = stored.value.copy(
            status = if (active) JobStatus.CANCELLING else JobStatus.CANCELLED,
            nextRunAt = null,
            updatedAt = clock.now(),
        )
        stored.value
    }

    override suspend fun getExecution(service: String, id: String): Pair<Execution, List<Attempt>> = mutex.withLock {
        val stored = executions[id] ?: notFound("Execution", id)
        ensureVisible(service, jobs.getValue(stored.value.jobId).value)
        stored.value to stored.attempts.toList()
    }

    override suspend fun listExecutions(
        service: String,
        jobId: String,
        cursor: String?,
        limit: Int
    ): Pair<List<Execution>, String?> = mutex.withLock {
        visibleJob(service, jobId)
        val offset = cursor?.toIntOrNull() ?: 0
        val all = executions.values.map { it.value }.filter { it.jobId == jobId }
        val page = all.drop(offset).take(limit)
        page to if (offset + page.size < all.size) (offset + page.size).toString() else null
    }

    override suspend fun claim(
        targetService: String,
        taskNames: List<String>?,
        maxItems: Int,
        waitSeconds: Int
    ): List<ClaimedExecution> {
        val deadline = clock.now() + waitSeconds.seconds
        do {
            val claimed = mutex.withLock {
                val now = clock.now()
                materializeDueLocked(now)
                recoverExpiredLeasesLocked(now)
                executions.values.asSequence()
                    .filter { it.value.targetService == targetService }
                    .filter { it.value.status == ExecutionStatus.PENDING && it.value.scheduledAt <= now }
                    .filter { jobs.getValue(it.value.jobId).value.delivery is PullDelivery }
                    .filter { taskNames.isNullOrEmpty() || it.value.taskName in taskNames }
                    .take(maxItems)
                    .map { leaseLocked(it, now) }
                    .toList()
            }
            if (claimed.isNotEmpty() || clock.now() >= deadline) return claimed
            delay(100.milliseconds)
        } while (true)
    }

    override suspend fun claimCallbacks(maxItems: Int): List<ClaimedExecution> = mutex.withLock {
        val now = clock.now()
        materializeDueLocked(now)
        recoverExpiredLeasesLocked(now)
        executions.values.asSequence()
            .filter { it.value.status == ExecutionStatus.PENDING && it.value.scheduledAt <= now }
            .filter { jobs.getValue(it.value.jobId).value.delivery is HttpCallbackDelivery }
            .take(maxItems)
            .map { leaseLocked(it, now) }
            .toList()
    }

    override suspend fun heartbeat(targetService: String, executionId: String, token: String): Instant =
        mutex.withLock {
            val stored = requireLease(targetService, executionId, token)
            val until = clock.now() + leaseSeconds.seconds
            stored.value = stored.value.copy(leaseUntil = until, updatedAt = clock.now())
            until
        }

    override suspend fun complete(
        targetService: String,
        executionId: String,
        request: CompleteExecutionRequest
    ): Pair<Execution, List<Attempt>> = mutex.withLock {
        val stored = requireLease(targetService, executionId, request.leaseToken)
        val now = clock.now()
        val attempt = stored.attempts.last()
        stored.attempts[stored.attempts.lastIndex] = attempt.copy(finishedAt = now, error = request.error)
        val job = jobs.getValue(stored.value.jobId)
        if (request.outcome == CompleteExecutionRequestOutcome.SUCCEEDED) {
            stored.value = stored.value.copy(
                status = ExecutionStatus.SUCCEEDED,
                leaseUntil = null,
                result = request.result,
                error = null,
                updatedAt = now
            )
            finishJobAfterExecutionLocked(job, successful = true, now = now)
        } else {
            val retry =
                request.retryable != false && stored.value.attemptCount < (job.value.retryPolicy.maxAttempts ?: 5)
            if (retry) {
                val delaySeconds = retryDelay(job.value.retryPolicy, stored.value.attemptCount)
                stored.value = stored.value.copy(
                    status = ExecutionStatus.PENDING,
                    leaseUntil = null,
                    error = request.error,
                    scheduledAt = now + delaySeconds.seconds,
                    updatedAt = now
                )
            } else {
                stored.value = stored.value.copy(
                    status = ExecutionStatus.FAILED,
                    leaseUntil = null,
                    error = request.error,
                    updatedAt = now
                )
                finishJobAfterExecutionLocked(job, successful = false, now = now)
            }
        }
        stored.leaseToken = null
        stored.value to stored.attempts.toList()
    }

    override suspend fun tick() = mutex.withLock {
        val now = clock.now()
        recoverExpiredLeasesLocked(now)
        materializeDueLocked(now)
    }

    override fun isReady(): Boolean = true

    private fun materializeDueLocked(now: Instant) {
        jobs.values.forEach { stored ->
            var job = stored.value
            val due = job.nextRunAt ?: return@forEach
            if (job.status != JobStatus.ACTIVE || due > now) return@forEach
            val hasActive = executions.values.any {
                it.value.jobId == job.id && it.value.status in setOf(
                    ExecutionStatus.PENDING,
                    ExecutionStatus.LEASED
                )
            }
            if (hasActive && job.executionPolicy.allowOverlap != true) return@forEach
            val execution = Execution(
                id = newId(), jobId = job.id, producerService = job.producerService,
                targetService = job.targetService, taskName = job.taskName, parameters = job.parameters,
                scheduledAt = due, status = ExecutionStatus.PENDING, attemptCount = 0,
                createdAt = now, updatedAt = now,
            )
            executions[execution.id] = StoredExecution(execution)
            val count = job.executionCount + 1
            val next = ScheduleCalculator.nextRunAt(job.schedule, due, now, count)
            job = job.copy(executionCount = count, nextRunAt = next, updatedAt = now)
            stored.value = job
        }
    }

    private fun recoverExpiredLeasesLocked(now: Instant) {
        executions.values.filter {
            it.value.status == ExecutionStatus.LEASED && (it.value.leaseUntil ?: Instant.DISTANT_FUTURE) <= now
        }.forEach { stored ->
            val attempt = stored.attempts.last()
            stored.attempts[stored.attempts.lastIndex] = attempt.copy(finishedAt = now, error = "Lease expired")
            val job = jobs.getValue(stored.value.jobId)
            if (stored.value.attemptCount < (job.value.retryPolicy.maxAttempts ?: 5)) {
                stored.value = stored.value.copy(
                    status = ExecutionStatus.PENDING,
                    leaseUntil = null,
                    scheduledAt = now + retryDelay(job.value.retryPolicy, stored.value.attemptCount).seconds,
                    error = "Lease expired",
                    updatedAt = now
                )
            } else {
                stored.value = stored.value.copy(
                    status = ExecutionStatus.FAILED,
                    leaseUntil = null,
                    error = "Lease expired",
                    updatedAt = now
                )
                finishJobAfterExecutionLocked(job, false, now)
            }
            stored.leaseToken = null
        }
    }

    private fun leaseLocked(stored: StoredExecution, now: Instant): ClaimedExecution {
        val token = newId()
        val until = now + leaseSeconds.seconds
        val attemptNumber = stored.value.attemptCount + 1
        stored.leaseToken = token
        stored.attempts += Attempt(number = attemptNumber, startedAt = now)
        stored.value = stored.value.copy(
            status = ExecutionStatus.LEASED,
            attemptCount = attemptNumber,
            leaseUntil = until,
            updatedAt = now
        )
        return ClaimedExecution(stored.value, token, until)
    }

    private fun requireLease(service: String, id: String, token: String): StoredExecution {
        val stored = executions[id] ?: notFound("Execution", id)
        if (stored.value.targetService != service || stored.value.status != ExecutionStatus.LEASED || stored.leaseToken != token || (stored.value.leaseUntil
                ?: Instant.DISTANT_PAST) <= clock.now()
        ) {
            throw ApiException(
                HttpStatusCode.Conflict,
                "LEASE_CONFLICT",
                "Lease is missing, expired, or owned by another worker"
            )
        }
        return stored
    }

    private fun finishJobAfterExecutionLocked(stored: StoredJob, successful: Boolean, now: Instant) {
        if (stored.value.status == JobStatus.CANCELLING) {
            stored.value = stored.value.copy(status = JobStatus.CANCELLED, updatedAt = now)
        } else if (stored.value.nextRunAt == null) {
            stored.value =
                stored.value.copy(status = if (successful) JobStatus.COMPLETED else JobStatus.FAILED, updatedAt = now)
        }
    }

    private fun visibleJob(service: String, id: String): StoredJob =
        (jobs[id] ?: notFound("Job", id)).also { ensureVisible(service, it.value) }

    private fun ensureVisible(service: String, job: ApiJob) {
        if (job.producerService != service && job.targetService != service) {
            throw ApiException(HttpStatusCode.Forbidden, "FORBIDDEN", "Resource is not visible to this service")
        }
    }

    private fun validateRequest(request: CreateJobRequest) {
        val bounded = when (val schedule = request.schedule) {
            is IntervalSchedule -> schedule.maxExecutions != null && schedule.endAt != null
            is CronSchedule -> schedule.maxExecutions != null && schedule.endAt != null
            else -> false
        }
        if (bounded) throw ApiException(
            HttpStatusCode.BadRequest,
            "INVALID_SCHEDULE",
            "Only one of maxExecutions and endAt may be set"
        )
        val retry = normalizedRetry(request.retryPolicy)
        if ((retry.maxDelaySeconds ?: 0) < (retry.initialDelaySeconds ?: 0)) {
            throw ApiException(
                HttpStatusCode.BadRequest,
                "INVALID_RETRY_POLICY",
                "maxDelaySeconds must not be less than initialDelaySeconds"
            )
        }
    }

    private fun normalizedRetry(value: RetryPolicy?) = RetryPolicy(
        maxAttempts = value?.maxAttempts ?: 5,
        initialDelaySeconds = value?.initialDelaySeconds ?: 5,
        multiplier = value?.multiplier ?: 2.0,
        maxDelaySeconds = value?.maxDelaySeconds ?: 300,
        jitter = value?.jitter ?: 0.2,
    )

    private fun retryDelay(policy: RetryPolicy, attempt: Int): Long {
        val base = (policy.initialDelaySeconds ?: 5).toDouble() * (policy.multiplier
            ?: 2.0).pow((attempt - 1).coerceAtLeast(0))
        val capped = min(base, (policy.maxDelaySeconds ?: 300).toDouble())
        val jitter = policy.jitter ?: 0.2
        return (capped * Random.nextDouble(1.0 - jitter, 1.0 + jitter)).toLong().coerceAtLeast(0)
    }

    private fun notFound(kind: String, id: String): Nothing =
        throw ApiException(HttpStatusCode.NotFound, "NOT_FOUND", "$kind could not be found: id=$id")

    @OptIn(ExperimentalUuidApi::class)
    private fun newId(): String = Uuid.random().toString()
}

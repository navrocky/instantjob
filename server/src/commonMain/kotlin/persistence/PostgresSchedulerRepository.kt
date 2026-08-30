package persistence

import instantjob.api.generated.models.*
import instantjob.api.generated.models.Job as ApiJob
import io.github.kormium.DelicateKormiumApi
import io.github.kormium.Scope
import io.github.kormium.transaction
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import scheduler.*
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(DelicateKormiumApi::class)
class PostgresSchedulerRepository(
    private val database: PostgresDatabase,
    private val clock: Clock = Clock.System,
    private val leaseSeconds: Long = 60,
) : SchedulerRepository {
    private val json = Json { encodeDefaults = true; classDiscriminator = "type"; ignoreUnknownKeys = true }

    override suspend fun create(producer: String, request: CreateJobRequest): CreateResult = database.handle.transaction {
        validateRequest(request)
        execute(
            "SELECT pg_advisory_xact_lock(hashtext(:key))",
            mapOf("key" to "$producer:${request.idempotencyKey}"), emptyList(),
        ) { Unit }
        val fingerprint = json.encodeToString(request)
        val existing = execute(
            "SELECT request_fingerprint, body::text FROM jobs WHERE producer_service=:producer AND idempotency_key=:key",
            mapOf("producer" to producer, "key" to request.idempotencyKey), emptyList(),
        ) { it.getString(0)!! to decodeJob(it.getString(1)!!) }.firstOrNull()
        if (existing != null) {
            if (existing.first != fingerprint) conflict("Idempotency key is already used with another request")
            return@transaction CreateResult(false, existing.second)
        }
        val now = clock.now()
        var job = ApiJob(
            id = newId(), producerService = producer, targetService = request.targetService,
            taskName = request.taskName, idempotencyKey = request.idempotencyKey,
            parameters = request.parameters, schedule = request.schedule, delivery = request.delivery,
            retryPolicy = normalizedRetry(request.retryPolicy),
            executionPolicy = request.executionPolicy ?: ExecutionPolicy(), status = JobStatus.ACTIVE,
            createdAt = now, updatedAt = now, nextRunAt = ScheduleCalculator.firstRunAt(request.schedule, now),
            executionCount = 0,
        )
        insertJob(job, fingerprint)
        if (job.nextRunAt!! <= now) job = materialize(job, now)
        CreateResult(true, job)
    }

    override suspend fun getJob(service: String, id: String): ApiJob = database.handle.transaction(readOnly = true) {
        getVisibleJob(service, id)
    }

    override suspend fun listJobs(service: String, target: String?, task: String?, status: JobStatus?, cursor: String?, limit: Int): Pair<List<ApiJob>, String?> = database.handle.transaction(readOnly = true) {
        val offset = cursor?.toIntOrNull() ?: 0
        val clauses = mutableListOf("(producer_service=:service OR target_service=:service)")
        val params = mutableMapOf<String, Any?>("service" to service, "limit" to limit + 1, "offset" to offset)
        target?.let { clauses += "target_service=:target"; params["target"] = it }
        task?.let { clauses += "task_name=:task"; params["task"] = it }
        status?.let { clauses += "status=:status"; params["status"] = it.wireValue }
        val rows = execute(
            "SELECT body::text FROM jobs WHERE ${clauses.joinToString(" AND ")} ORDER BY created_at,id LIMIT :limit OFFSET :offset",
            params, emptyList(),
        ) { decodeJob(it.getString(0)!!) }
        rows.take(limit) to if (rows.size > limit) (offset + limit).toString() else null
    }

    override suspend fun cancel(service: String, id: String): ApiJob = database.handle.transaction {
        var job = getVisibleJob(service, id, lock = true)
        if (job.producerService != service) forbidden("Only the producer may cancel a job")
        if (job.status in setOf(JobStatus.CANCELLED, JobStatus.COMPLETED, JobStatus.FAILED)) return@transaction job
        val active = execute(
            "SELECT EXISTS(SELECT 1 FROM executions WHERE job_id=CAST(:id AS uuid) AND status='LEASED')",
            mapOf("id" to id), emptyList(),
        ) { it.getBoolean(0)!! }.single()
        job = job.copy(status = if (active) JobStatus.CANCELLING else JobStatus.CANCELLED, nextRunAt = null, updatedAt = clock.now())
        updateJob(job)
        job
    }

    override suspend fun getExecution(service: String, id: String): Pair<Execution, List<Attempt>> = database.handle.transaction(readOnly = true) {
        val execution = getExecutionRow(id)
        getVisibleJob(service, execution.jobId)
        execution to attempts(id)
    }

    override suspend fun listExecutions(service: String, jobId: String, cursor: String?, limit: Int): Pair<List<Execution>, String?> = database.handle.transaction(readOnly = true) {
        getVisibleJob(service, jobId)
        val offset = cursor?.toIntOrNull() ?: 0
        val rows = execute(
            "SELECT body::text FROM executions WHERE job_id=CAST(:id AS uuid) ORDER BY created_at,id LIMIT :limit OFFSET :offset",
            mapOf("id" to jobId, "limit" to limit + 1, "offset" to offset), emptyList(),
        ) { decodeExecution(it.getString(0)!!) }
        rows.take(limit) to if (rows.size > limit) (offset + limit).toString() else null
    }

    override suspend fun claim(targetService: String, taskNames: List<String>?, maxItems: Int, waitSeconds: Int): List<ClaimedExecution> {
        val deadline = clock.now() + waitSeconds.seconds
        do {
            tick()
            val result = claimRows(targetService, taskNames, maxItems, "PULL")
            if (result.isNotEmpty() || clock.now() >= deadline) return result
            delay(100)
        } while (true)
    }

    override suspend fun claimCallbacks(maxItems: Int): List<ClaimedExecution> {
        tick()
        return claimRows(null, null, maxItems, "HTTP_CALLBACK")
    }

    override suspend fun heartbeat(targetService: String, executionId: String, token: String): Instant = database.handle.transaction {
        val execution = requireLease(targetService, executionId, token)
        val until = clock.now() + leaseSeconds.seconds
        updateExecution(execution.copy(leaseUntil = until, updatedAt = clock.now()), token)
        until
    }

    override suspend fun complete(targetService: String, executionId: String, request: CompleteExecutionRequest): Pair<Execution, List<Attempt>> = database.handle.transaction {
        var execution = requireLease(targetService, executionId, request.leaseToken)
        var job = getVisibleJob(targetService, execution.jobId, lock = true)
        val now = clock.now()
        val lastAttempt = attempts(executionId).last()
        val finished = lastAttempt.copy(finishedAt = now, error = request.error)
        executeUpdate(
            "UPDATE attempts SET body=CAST(:body AS jsonb),finished_at=CAST(:finished AS timestamptz) WHERE execution_id=CAST(:id AS uuid) AND attempt_number=:number",
            mapOf("body" to json.encodeToString(finished), "finished" to now.toString(), "id" to executionId, "number" to finished.number), emptyList(),
        )
        if (request.outcome == CompleteExecutionRequestOutcome.SUCCEEDED) {
            execution = execution.copy(status = ExecutionStatus.SUCCEEDED, leaseUntil = null, result = request.result, error = null, updatedAt = now)
            job = finishJob(job, true, now)
        } else {
            val retry = request.retryable != false && execution.attemptCount < (job.retryPolicy.maxAttempts ?: 5)
            execution = if (retry) execution.copy(
                status = ExecutionStatus.PENDING, leaseUntil = null, error = request.error,
                scheduledAt = now + retryDelay(job.retryPolicy, execution.attemptCount).seconds, updatedAt = now,
            ) else execution.copy(status = ExecutionStatus.FAILED, leaseUntil = null, error = request.error, updatedAt = now)
            if (!retry) job = finishJob(job, false, now)
        }
        updateExecution(execution, null)
        updateJob(job)
        execution to attempts(executionId)
    }

    override suspend fun tick() {
        database.handle.transaction {
            recoverExpired(clock.now())
            val due = execute(
                "SELECT body::text FROM jobs WHERE status='ACTIVE' AND next_run_at<=now() ORDER BY next_run_at FOR UPDATE SKIP LOCKED LIMIT 100",
                emptyMap(), emptyList(),
            ) { decodeJob(it.getString(0)!!) }
            due.forEach { job ->
                val active = execute(
                    "SELECT EXISTS(SELECT 1 FROM executions WHERE job_id=CAST(:id AS uuid) AND status IN ('PENDING','LEASED'))",
                    mapOf("id" to job.id), emptyList(),
                ) { it.getBoolean(0)!! }.single()
                if (!active || job.executionPolicy.allowOverlap == true) materialize(job, clock.now())
            }
        }
    }

    override fun isReady(): Boolean = database.ping()

    private fun claimRows(target: String?, tasks: List<String>?, max: Int, delivery: String): List<ClaimedExecution> = database.handle.transaction {
        val params = mutableMapOf<String, Any?>("max" to max, "delivery" to delivery)
        val where = mutableListOf("status='PENDING'", "scheduled_at<=now()", "delivery_type=:delivery")
        target?.let { where += "target_service=:target"; params["target"] = it }
        if (!tasks.isNullOrEmpty()) {
            val names = tasks.mapIndexed { index, name -> "task$index".also { params[it] = name } }
            where += "task_name IN (${names.joinToString(",") { ":$it" }})"
        }
        val rows = execute(
            "SELECT body::text FROM executions WHERE ${where.joinToString(" AND ")} ORDER BY scheduled_at,id FOR UPDATE SKIP LOCKED LIMIT :max",
            params, emptyList(),
        ) { decodeExecution(it.getString(0)!!) }
        val now = clock.now()
        rows.map { old ->
            val token = newId()
            val until = now + leaseSeconds.seconds
            val execution = old.copy(status = ExecutionStatus.LEASED, attemptCount = old.attemptCount + 1, leaseUntil = until, updatedAt = now)
            updateExecution(execution, token)
            val attempt = Attempt(number = execution.attemptCount, startedAt = now)
            executeUpdate(
                "INSERT INTO attempts(execution_id,attempt_number,body,started_at) VALUES(CAST(:id AS uuid),:number,CAST(:body AS jsonb),CAST(:started AS timestamptz))",
                mapOf("id" to execution.id, "number" to attempt.number, "body" to json.encodeToString(attempt), "started" to now.toString()), emptyList(),
            )
            ClaimedExecution(execution, token, until)
        }
    }

    private fun Scope<InstantJobCatalog>.materialize(source: ApiJob, now: Instant): ApiJob {
        val due = source.nextRunAt ?: return source
        val execution = Execution(
            id = newId(), jobId = source.id, producerService = source.producerService, targetService = source.targetService,
            taskName = source.taskName, parameters = source.parameters, scheduledAt = due, status = ExecutionStatus.PENDING,
            attemptCount = 0, createdAt = now, updatedAt = now,
        )
        val delivery = if (source.delivery is PullDelivery) "PULL" else "HTTP_CALLBACK"
        executeUpdate(
            "INSERT INTO executions(id,job_id,target_service,task_name,delivery_type,status,scheduled_at,body,created_at,updated_at) VALUES(CAST(:id AS uuid),CAST(:job AS uuid),:target,:task,:delivery,'PENDING',CAST(:scheduled AS timestamptz),CAST(:body AS jsonb),CAST(:created AS timestamptz),CAST(:updated AS timestamptz))",
            mapOf("id" to execution.id, "job" to execution.jobId, "target" to execution.targetService, "task" to execution.taskName, "delivery" to delivery, "scheduled" to due.toString(), "body" to json.encodeToString(execution), "created" to now.toString(), "updated" to now.toString()), emptyList(),
        )
        val count = source.executionCount + 1
        val updated = source.copy(executionCount = count, nextRunAt = ScheduleCalculator.nextRunAt(source.schedule, due, now, count), updatedAt = now)
        updateJob(updated)
        return updated
    }

    private fun Scope<InstantJobCatalog>.insertJob(job: ApiJob, fingerprint: String) {
        val delivery = if (job.delivery is PullDelivery) "PULL" else "HTTP_CALLBACK"
        executeUpdate(
            "INSERT INTO jobs(id,producer_service,target_service,task_name,idempotency_key,request_fingerprint,status,delivery_type,next_run_at,execution_count,body,created_at,updated_at) VALUES(CAST(:id AS uuid),:producer,:target,:task,:key,:fingerprint,:status,:delivery,CAST(:next AS timestamptz),:count,CAST(:body AS jsonb),CAST(:created AS timestamptz),CAST(:updated AS timestamptz))",
            mapOf("id" to job.id, "producer" to job.producerService, "target" to job.targetService, "task" to job.taskName, "key" to job.idempotencyKey, "fingerprint" to fingerprint, "status" to job.status.wireValue, "delivery" to delivery, "next" to job.nextRunAt?.toString(), "count" to job.executionCount, "body" to json.encodeToString(job), "created" to job.createdAt.toString(), "updated" to job.updatedAt.toString()), emptyList(),
        )
    }

    private fun Scope<InstantJobCatalog>.updateJob(job: ApiJob) {
        executeUpdate(
            "UPDATE jobs SET status=:status,next_run_at=CAST(:next AS timestamptz),execution_count=:count,body=CAST(:body AS jsonb),updated_at=CAST(:updated AS timestamptz) WHERE id=CAST(:id AS uuid)",
            mapOf("status" to job.status.wireValue, "next" to job.nextRunAt?.toString(), "count" to job.executionCount, "body" to json.encodeToString(job), "updated" to job.updatedAt.toString(), "id" to job.id), emptyList(),
        )
    }

    private fun Scope<InstantJobCatalog>.updateExecution(execution: Execution, token: String?) {
        executeUpdate(
            "UPDATE executions SET status=:status,scheduled_at=CAST(:scheduled AS timestamptz),lease_token=CAST(:token AS uuid),lease_until=CAST(:until AS timestamptz),body=CAST(:body AS jsonb),updated_at=CAST(:updated AS timestamptz) WHERE id=CAST(:id AS uuid)",
            mapOf("status" to execution.status.wireValue, "scheduled" to execution.scheduledAt.toString(), "token" to token, "until" to execution.leaseUntil?.toString(), "body" to json.encodeToString(execution), "updated" to execution.updatedAt.toString(), "id" to execution.id), emptyList(),
        )
    }

    private fun Scope<InstantJobCatalog>.getVisibleJob(service: String, id: String, lock: Boolean = false): ApiJob {
        val suffix = if (lock) " FOR UPDATE" else ""
        return execute("SELECT body::text FROM jobs WHERE id=CAST(:id AS uuid)$suffix", mapOf("id" to id), emptyList()) { decodeJob(it.getString(0)!!) }.firstOrNull()
            ?.also { if (it.producerService != service && it.targetService != service) forbidden("Resource is not visible to this service") }
            ?: notFound("Job", id)
    }

    private fun Scope<InstantJobCatalog>.getExecutionRow(id: String): Execution =
        execute("SELECT body::text FROM executions WHERE id=CAST(:id AS uuid)", mapOf("id" to id), emptyList()) { decodeExecution(it.getString(0)!!) }.firstOrNull() ?: notFound("Execution", id)

    private fun Scope<InstantJobCatalog>.attempts(id: String): List<Attempt> =
        execute("SELECT body::text FROM attempts WHERE execution_id=CAST(:id AS uuid) ORDER BY attempt_number", mapOf("id" to id), emptyList()) { json.decodeFromString(it.getString(0)!!) }

    private fun Scope<InstantJobCatalog>.requireLease(service: String, id: String, token: String): Execution {
        val row = execute("SELECT target_service,lease_token::text,lease_until,body::text FROM executions WHERE id=CAST(:id AS uuid) FOR UPDATE", mapOf("id" to id), emptyList()) {
            listOf(it.getString(0), it.getString(1), it.getInstant(2)?.toString(), it.getString(3))
        }.firstOrNull() ?: notFound("Execution", id)
        if (row[0] != service || row[1] != token || row[2]?.let(Instant::parse)!! <= clock.now()) conflict("Lease is missing, expired, or owned by another worker", "LEASE_CONFLICT")
        return decodeExecution(row[3]!!)
    }

    private fun Scope<InstantJobCatalog>.recoverExpired(now: Instant) {
        val rows = execute("SELECT body::text,lease_token::text FROM executions WHERE status='LEASED' AND lease_until<=now() FOR UPDATE SKIP LOCKED", emptyMap(), emptyList()) { decodeExecution(it.getString(0)!!) }
        rows.forEach { execution ->
            val job = getVisibleJob(execution.targetService, execution.jobId, lock = true)
            val previousAttempt = attempts(execution.id).last()
            val expiredAttempt = previousAttempt.copy(finishedAt = now, error = "Lease expired")
            executeUpdate(
                "UPDATE attempts SET body=CAST(:body AS jsonb),finished_at=CAST(:finished AS timestamptz) WHERE execution_id=CAST(:id AS uuid) AND attempt_number=:number",
                mapOf("body" to json.encodeToString(expiredAttempt), "finished" to now.toString(), "id" to execution.id, "number" to expiredAttempt.number), emptyList(),
            )
            val retry = execution.attemptCount < (job.retryPolicy.maxAttempts ?: 5)
            val updated = if (retry) execution.copy(status = ExecutionStatus.PENDING, leaseUntil = null, scheduledAt = now + retryDelay(job.retryPolicy, execution.attemptCount).seconds, error = "Lease expired", updatedAt = now)
            else execution.copy(status = ExecutionStatus.FAILED, leaseUntil = null, error = "Lease expired", updatedAt = now)
            updateExecution(updated, null)
            if (!retry) updateJob(finishJob(job, false, now))
        }
    }

    private fun finishJob(job: ApiJob, success: Boolean, now: Instant): ApiJob = when {
        job.status == JobStatus.CANCELLING -> job.copy(status = JobStatus.CANCELLED, updatedAt = now)
        job.nextRunAt == null -> job.copy(status = if (success) JobStatus.COMPLETED else JobStatus.FAILED, updatedAt = now)
        else -> job
    }

    private fun validateRequest(request: CreateJobRequest) {
        val both = when (val s = request.schedule) { is IntervalSchedule -> s.maxExecutions != null && s.endAt != null; is CronSchedule -> s.maxExecutions != null && s.endAt != null; else -> false }
        if (both) throw ApiException(HttpStatusCode.BadRequest, "INVALID_SCHEDULE", "Only one of maxExecutions and endAt may be set")
    }

    private fun normalizedRetry(v: RetryPolicy?) = RetryPolicy(v?.initialDelaySeconds ?: 5, v?.jitter ?: .2, v?.maxAttempts ?: 5, v?.maxDelaySeconds ?: 300, v?.multiplier ?: 2.0)
    private fun retryDelay(p: RetryPolicy, attempt: Int): Long = (min((p.initialDelaySeconds ?: 5) * (p.multiplier ?: 2.0).pow((attempt - 1).coerceAtLeast(0)), (p.maxDelaySeconds ?: 300).toDouble()) * Random.nextDouble(1.0 - (p.jitter ?: .2), 1.0 + (p.jitter ?: .2))).toLong().coerceAtLeast(0)
    private fun decodeJob(value: String): ApiJob = json.decodeFromString(value)
    private fun decodeExecution(value: String): Execution = json.decodeFromString(value)
    private fun notFound(kind: String, id: String): Nothing = throw ApiException(HttpStatusCode.NotFound, "NOT_FOUND", "$kind could not be found: id=$id")
    private fun forbidden(message: String): Nothing = throw ApiException(HttpStatusCode.Forbidden, "FORBIDDEN", message)
    private fun conflict(message: String, code: String = "IDEMPOTENCY_CONFLICT"): Nothing = throw ApiException(HttpStatusCode.Conflict, code, message)
    @OptIn(ExperimentalUuidApi::class) private fun newId() = Uuid.random().toString()
}

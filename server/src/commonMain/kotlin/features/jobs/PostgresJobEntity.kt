package features.jobs

import infrastructure.database.InstantJobCatalog
import infrastructure.database.databaseJson
import infrastructure.database.toJsonContainer
import infrastructure.database.toJsonObject
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Table
import io.github.kormium.jsonColumnType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal object JobsTable : Table<InstantJobCatalog, JobEntity>("jobs", ::JobEntity) {
    val id by Column.UUID().primaryKey()
    val producerService by Column.Text("producer_service")
    val targetService by Column.Text("target_service")
    val taskName by Column.Text("task_name")
    val idempotencyKey by Column.Text("idempotency_key")
    val requestFingerprint by Column.Text("request_fingerprint")
    val status by Column.enum<JobStatus>()
    val deliveryType by Column.Text("delivery_type")
    val nextRunAt by Column.Instant("next_run_at").nullable()
    val executionCount by Column.Long("execution_count")
    val body by Column.of(jsonColumnType<PersistedJob>(databaseJson))
    val createdAt by Column.Instant("created_at")
    val updatedAt by Column.Instant("updated_at")
}

internal class JobEntity : Entity() {
    var id by JobsTable.id
    var producerService by JobsTable.producerService
    var targetService by JobsTable.targetService
    var taskName by JobsTable.taskName
    var idempotencyKey by JobsTable.idempotencyKey
    var requestFingerprint by JobsTable.requestFingerprint
    var status by JobsTable.status
    var deliveryType by JobsTable.deliveryType
    var nextRunAt by JobsTable.nextRunAt
    var executionCount by JobsTable.executionCount
    var body by JobsTable.body
    var createdAt by JobsTable.createdAt
    var updatedAt by JobsTable.updatedAt
}

/** Serializable job snapshot stored in the job JSON column. */
@Serializable
internal data class PersistedJob(
    /** Job identifier. */
    val id: String,
    /** Service that registered the job. */
    val producerService: String,
    /** Service responsible for executing the task. */
    val targetService: String,
    /** Task identifier understood by the target service. */
    val taskName: String,
    /** Producer-scoped idempotency key. */
    val idempotencyKey: String,
    /** JSON task parameters. */
    val parameters: JsonObject,
    /** Persisted scheduling configuration. */
    val schedule: PersistedSchedule,
    /** Persisted delivery configuration. */
    val delivery: PersistedDelivery,
    /** Persisted retry configuration. */
    val retryPolicy: PersistedRetryPolicy,
    /** Persisted execution concurrency configuration. */
    val executionPolicy: PersistedExecutionPolicy,
    /** Persisted job status name. */
    val status: String,
    /** ISO-8601 creation time. */
    val createdAt: String,
    /** ISO-8601 time of the latest state change. */
    val updatedAt: String,
    /** ISO-8601 next execution time, if another execution is planned. */
    val nextRunAt: String? = null,
    /** Number of executions materialized for the job. */
    val executionCount: Long,
)

/** Serializable representation of a job schedule. */
@Serializable
internal data class PersistedSchedule(
    /** Schedule discriminator. */
    val type: String,
    /** ISO-8601 time used by one-time schedules. */
    val runAt: String? = null,
    /** Interval between executions in seconds. */
    val everySeconds: Long? = null,
    /** ISO-8601 first execution time for interval schedules. */
    val startAt: String? = null,
    /** Six-field cron expression. */
    val expression: String? = null,
    /** IANA time-zone name used to evaluate a cron expression. */
    val timezone: String? = null,
    /** Maximum number of executions, if limited. */
    val maxExecutions: Long? = null,
    /** ISO-8601 inclusive schedule end time, if limited. */
    val endAt: String? = null,
)

/** Serializable representation of execution delivery settings. */
@Serializable
internal data class PersistedDelivery(
    /** Delivery discriminator. */
    val type: String,
    /** HTTP method name used for callback delivery. */
    val method: String? = null,
    /** Absolute callback URL. */
    val url: String? = null,
    /** Parameter encoding name used for callback delivery. */
    val parametersType: String? = null,
    /** Reference to a configured callback authorization value. */
    val secretRef: String? = null,
    /** Additional callback response statuses eligible for retries. */
    val retryableStatuses: List<Int>? = null,
)

/** Serializable representation of retry settings. */
@Serializable
internal data class PersistedRetryPolicy(
    /** Maximum total number of attempts. */
    val maxAttempts: Int,
    /** Delay before the first retry in seconds. */
    val initialDelaySeconds: Long,
    /** Exponential retry-delay multiplier. */
    val multiplier: Double,
    /** Maximum retry delay in seconds. */
    val maxDelaySeconds: Long,
    /** Fractional random variation applied to retry delays. */
    val jitter: Double,
)

/** Serializable representation of execution concurrency settings. */
@Serializable
internal data class PersistedExecutionPolicy(
    /** Whether executions of the same job may overlap. */
    val allowOverlap: Boolean,
)

internal fun Job.toEntity(requestFingerprint: String): JobEntity = JobEntity().also {
    it.id = Uuid.parse(id)
    it.producerService = producerService
    it.targetService = targetService
    it.taskName = taskName
    it.idempotencyKey = idempotencyKey
    it.requestFingerprint = requestFingerprint
    it.status = status
    it.deliveryType = delivery.databaseType()
    it.nextRunAt = nextRunAt
    it.executionCount = executionCount
    it.body = toPersisted()
    it.createdAt = createdAt
    it.updatedAt = updatedAt
}

internal fun Job.toUpdateEntity(): JobEntity = JobEntity().also {
    it.status = status
    it.nextRunAt = nextRunAt
    it.executionCount = executionCount
    it.body = toPersisted()
    it.updatedAt = updatedAt
}

internal fun JobEntity.toStoredJob(): StoredJob = StoredJob(
    job = Job(
        id = id.toString(),
        producerService = producerService,
        targetService = targetService,
        taskName = taskName,
        idempotencyKey = idempotencyKey,
        parameters = body.parameters.toJsonContainer(),
        schedule = body.schedule.toDomain(),
        delivery = body.delivery.toDomain(),
        retryPolicy = body.retryPolicy.toDomain(),
        executionPolicy = ExecutionPolicy(body.executionPolicy.allowOverlap),
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt,
        nextRunAt = nextRunAt,
        executionCount = executionCount,
    ),
    requestFingerprint = requestFingerprint,
)

private fun Job.toPersisted() = PersistedJob(
    id, producerService, targetService, taskName, idempotencyKey, parameters.toJsonObject(),
    schedule.toPersisted(), delivery.toPersisted(), retryPolicy.toPersisted(),
    PersistedExecutionPolicy(executionPolicy.allowOverlap), status.name, createdAt.toString(),
    updatedAt.toString(), nextRunAt?.toString(), executionCount,
)

private fun Schedule.toPersisted(): PersistedSchedule = when (this) {
    Schedule.Immediate -> PersistedSchedule(type = "IMMEDIATE")
    is Schedule.At -> PersistedSchedule(type = "AT", runAt = runAt.toString())
    is Schedule.Interval -> PersistedSchedule(
        type = "INTERVAL", everySeconds = everySeconds, startAt = startAt?.toString(),
        maxExecutions = maxExecutions, endAt = endAt?.toString(),
    )
    is Schedule.Cron -> PersistedSchedule(
        type = "CRON", expression = expression, timezone = timezone,
        maxExecutions = maxExecutions, endAt = endAt?.toString(),
    )
}

private fun PersistedSchedule.toDomain(): Schedule = when (type) {
    "IMMEDIATE" -> Schedule.Immediate
    "AT" -> Schedule.At(Instant.parse(requireNotNull(runAt)))
    "INTERVAL" -> Schedule.Interval(
        requireNotNull(everySeconds), startAt?.let(Instant::parse), maxExecutions, endAt?.let(Instant::parse),
    )
    "CRON" -> Schedule.Cron(
        requireNotNull(expression), requireNotNull(timezone), maxExecutions, endAt?.let(Instant::parse),
    )
    else -> error("Unknown persisted schedule type: $type")
}

private fun Delivery.toPersisted(): PersistedDelivery = when (this) {
    Delivery.Pull -> PersistedDelivery(type = "PULL")
    is Delivery.HttpCallback -> PersistedDelivery(
        type = "HTTP_CALLBACK", method = method.name, url = url, parametersType = parametersType.name,
        secretRef = secretRef, retryableStatuses = retryableStatuses,
    )
}

private fun PersistedDelivery.toDomain(): Delivery = when (type) {
    "PULL" -> Delivery.Pull
    "HTTP_CALLBACK" -> Delivery.HttpCallback(
        Delivery.HttpCallback.Method.valueOf(requireNotNull(method)), requireNotNull(url),
        Delivery.HttpCallback.ParametersType.valueOf(requireNotNull(parametersType)), secretRef, retryableStatuses,
    )
    else -> error("Unknown persisted delivery type: $type")
}

private fun RetryPolicy.toPersisted() = PersistedRetryPolicy(
    maxAttempts, initialDelaySeconds, multiplier, maxDelaySeconds, jitter,
)

private fun PersistedRetryPolicy.toDomain() = RetryPolicy(
    maxAttempts, initialDelaySeconds, multiplier, maxDelaySeconds, jitter,
)

internal fun Delivery.databaseType(): String = if (this is Delivery.Pull) "PULL" else "HTTP_CALLBACK"

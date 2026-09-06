package presentation.rest

import common.JsonContainer
import common.JsonValue
import features.executions.Attempt as DomainAttempt
import features.executions.ClaimedExecution as DomainClaimedExecution
import features.executions.CompleteExecutionCommand
import features.executions.CompletionOutcome
import features.executions.Execution as DomainExecution
import features.jobs.CreateJobCommand
import features.jobs.Delivery as DomainDelivery
import features.jobs.ExecutionPolicy as DomainExecutionPolicy
import features.jobs.Job as DomainJob
import features.jobs.JobStatus as DomainJobStatus
import features.jobs.RetryPolicy as DomainRetryPolicy
import features.jobs.Schedule as DomainSchedule
import kotlinx.serialization.json.*
import kotlinx.serialization.ExperimentalSerializationApi
import instantjob.api.generated.models.*
import instantjob.api.generated.models.Attempt as AttemptDto
import instantjob.api.generated.models.Execution as ExecutionDto
import instantjob.api.generated.models.ExecutionPolicy as ExecutionPolicyDto
import instantjob.api.generated.models.ExecutionStatus as ExecutionStatusDto
import instantjob.api.generated.models.Job as JobDto
import instantjob.api.generated.models.JobStatus as JobStatusDto
import instantjob.api.generated.models.RetryPolicy as RetryPolicyDto

@OptIn(ExperimentalSerializationApi::class)
fun CreateJobRequest.toDomain() = CreateJobCommand(
    targetService = targetService,
    taskName = taskName,
    idempotencyKey = idempotencyKey,
    parameters = parameters.toDomain(),
    schedule = schedule.toDomain(),
    delivery = delivery.toDomain(),
    retryPolicy = retryPolicy?.toDomain(),
    executionPolicy = executionPolicy?.let { DomainExecutionPolicy(it.allowOverlap ?: false) },
)

fun CompleteExecutionRequest.toDomain() = CompleteExecutionCommand(
    leaseToken = leaseToken,
    outcome = CompletionOutcome.valueOf(outcome.name),
    retryable = retryable ?: true,
    result = result?.toDomain(),
    error = error,
)

fun DomainJob.toDto() = JobDto(
    id = id, producerService = producerService, targetService = targetService, taskName = taskName,
    idempotencyKey = idempotencyKey, parameters = parameters.toDto(), schedule = schedule.toDto(),
    delivery = delivery.toDto(), retryPolicy = retryPolicy.toDto(), executionPolicy = executionPolicy.toDto(),
    status = JobStatusDto.valueOf(status.name), createdAt = createdAt, updatedAt = updatedAt,
    nextRunAt = nextRunAt, executionCount = executionCount,
)

fun DomainExecution.toDto() = ExecutionDto(
    id = id, jobId = jobId, producerService = producerService, targetService = targetService,
    taskName = taskName, parameters = parameters.toDto(), scheduledAt = scheduledAt,
    status = ExecutionStatusDto.valueOf(status.name), attemptCount = attemptCount,
    leaseUntil = leaseUntil, result = result?.toDto(), error = error, createdAt = createdAt, updatedAt = updatedAt,
)

fun DomainAttempt.toDto() = AttemptDto(
    number = number, startedAt = startedAt, finishedAt = finishedAt, httpStatus = httpStatus, error = error,
)

fun DomainClaimedExecution.toDto() = instantjob.api.generated.models.ClaimedExecution(
    execution = execution.toDto(), leaseToken = leaseToken, leaseUntil = leaseUntil,
)

fun JobStatusDto.toDomain() = DomainJobStatus.valueOf(name)

private fun instantjob.api.generated.models.Schedule.toDomain(): DomainSchedule = when (this) {
    is ImmediateSchedule -> DomainSchedule.Immediate
    is AtSchedule -> DomainSchedule.At(runAt)
    is IntervalSchedule -> DomainSchedule.Interval(everySeconds, startAt, maxExecutions, endAt)
    is CronSchedule -> DomainSchedule.Cron(expression, timezone, maxExecutions, endAt)
}

private fun DomainSchedule.toDto(): instantjob.api.generated.models.Schedule = when (this) {
    DomainSchedule.Immediate -> ImmediateSchedule()
    is DomainSchedule.At -> AtSchedule(runAt)
    is DomainSchedule.Interval -> IntervalSchedule(
        everySeconds = everySeconds, startAt = startAt, maxExecutions = maxExecutions, endAt = endAt,
    )
    is DomainSchedule.Cron -> CronSchedule(
        expression = expression, timezone = timezone, maxExecutions = maxExecutions, endAt = endAt,
    )
}

private fun instantjob.api.generated.models.Delivery.toDomain(): DomainDelivery = when (this) {
    is PullDelivery -> DomainDelivery.Pull
    is HttpCallbackDelivery -> DomainDelivery.HttpCallback(
        method = DomainDelivery.HttpCallback.Method.valueOf(method.name), url = url,
        parametersType = DomainDelivery.HttpCallback.ParametersType.valueOf(parametersType.name),
        secretRef = secretRef, retryableStatuses = retryableStatuses,
    )
}

private fun DomainDelivery.toDto(): instantjob.api.generated.models.Delivery = when (this) {
    DomainDelivery.Pull -> PullDelivery()
    is DomainDelivery.HttpCallback -> HttpCallbackDelivery(
        method = HttpCallbackDeliveryMethod.valueOf(method.name),
        parametersType = HttpCallbackDeliveryParametersType.valueOf(parametersType.name),
        retryableStatuses = retryableStatuses, secretRef = secretRef, url = url,
    )
}

private fun RetryPolicyDto.toDomain() = DomainRetryPolicy(
    maxAttempts ?: 5, initialDelaySeconds ?: 5, multiplier ?: 2.0, maxDelaySeconds ?: 300, jitter ?: .2,
)

private fun DomainRetryPolicy.toDto() = RetryPolicyDto(
    initialDelaySeconds = initialDelaySeconds, jitter = jitter, maxAttempts = maxAttempts,
    maxDelaySeconds = maxDelaySeconds, multiplier = multiplier,
)

private fun DomainExecutionPolicy.toDto() = ExecutionPolicyDto(allowOverlap)

@OptIn(ExperimentalSerializationApi::class)
fun JsonContainer.toDto(): Map<String, JsonElement> = mapValues { it.value.toDto() }
fun Map<String, JsonElement>.toDomain(): JsonContainer = mapValues { it.value.toDomain() }

@OptIn(ExperimentalSerializationApi::class)
private fun JsonValue.toDto(): JsonElement = when (this) {
    JsonValue.Null -> JsonNull
    is JsonValue.BooleanValue -> JsonPrimitive(value)
    is JsonValue.NumberValue -> JsonUnquotedLiteral(value)
    is JsonValue.StringValue -> JsonPrimitive(value)
    is JsonValue.ArrayValue -> JsonArray(values.map { it.toDto() })
    is JsonValue.ObjectValue -> JsonObject(values.mapValues { it.value.toDto() })
}

private fun JsonElement.toDomain(): JsonValue = when (this) {
    JsonNull -> JsonValue.Null
    is JsonObject -> JsonValue.ObjectValue(mapValues { it.value.toDomain() })
    is JsonArray -> JsonValue.ArrayValue(map { it.toDomain() })
    is JsonPrimitive -> when {
        isString -> JsonValue.StringValue(content)
        booleanOrNull != null -> JsonValue.BooleanValue(boolean)
        else -> JsonValue.NumberValue(content)
    }
}

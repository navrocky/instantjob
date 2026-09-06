package infrastructure.database

import common.JsonContainer
import common.JsonValue
import features.executions.Attempt
import features.executions.Execution
import features.executions.ExecutionStatus
import features.jobs.*
import kotlinx.serialization.json.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.time.Instant

@OptIn(ExperimentalSerializationApi::class)
internal object PersistenceJson {
    private val json = Json

    fun encode(job: Job): String = json.encodeToString(JsonObject.serializer(), job.toJson())
    fun decodeJob(value: String): Job = json.parseToJsonElement(value).jsonObject.toJob()
    fun encode(execution: Execution): String = json.encodeToString(JsonObject.serializer(), execution.toJson())
    fun decodeExecution(value: String): Execution = json.parseToJsonElement(value).jsonObject.toExecution()
    fun encode(attempt: Attempt): String = json.encodeToString(JsonObject.serializer(), attempt.toJson())
    fun decodeAttempt(value: String): Attempt = json.parseToJsonElement(value).jsonObject.toAttempt()

    private fun Job.toJson() = buildJsonObject {
        put("id", id); put("producerService", producerService); put("targetService", targetService)
        put("taskName", taskName); put("idempotencyKey", idempotencyKey)
        put("parameters", parameters.toJson()); put("schedule", schedule.toJson()); put("delivery", delivery.toJson())
        put("retryPolicy", retryPolicy.toJson()); put("executionPolicy", executionPolicy.toJson())
        put("status", status.name); put("createdAt", createdAt.toString()); put("updatedAt", updatedAt.toString())
        nextRunAt?.let { put("nextRunAt", it.toString()) }; put("executionCount", executionCount)
    }

    private fun JsonObject.toJob() = Job(
        id = string("id"), producerService = string("producerService"), targetService = string("targetService"),
        taskName = string("taskName"), idempotencyKey = string("idempotencyKey"),
        parameters = objectValue("parameters").toDomain(), schedule = objectValue("schedule").toSchedule(),
        delivery = objectValue("delivery").toDelivery(), retryPolicy = objectValue("retryPolicy").toRetryPolicy(),
        executionPolicy = objectValue("executionPolicy").toExecutionPolicy(), status = JobStatus.valueOf(string("status")),
        createdAt = instant("createdAt"), updatedAt = instant("updatedAt"), nextRunAt = optionalInstant("nextRunAt"),
        executionCount = long("executionCount"),
    )

    private fun Execution.toJson() = buildJsonObject {
        put("id", id); put("jobId", jobId); put("producerService", producerService); put("targetService", targetService)
        put("taskName", taskName); put("parameters", parameters.toJson()); put("scheduledAt", scheduledAt.toString())
        put("status", status.name); put("attemptCount", attemptCount); leaseUntil?.let { put("leaseUntil", it.toString()) }
        result?.let { put("result", it.toJson()) }; error?.let { put("error", it) }
        put("createdAt", createdAt.toString()); put("updatedAt", updatedAt.toString())
    }

    private fun JsonObject.toExecution() = Execution(
        id = string("id"), jobId = string("jobId"), producerService = string("producerService"),
        targetService = string("targetService"), taskName = string("taskName"),
        parameters = objectValue("parameters").toDomain(), scheduledAt = instant("scheduledAt"),
        status = ExecutionStatus.valueOf(string("status")), attemptCount = int("attemptCount"),
        leaseUntil = optionalInstant("leaseUntil"), result = optionalObject("result")?.toDomain(),
        error = optionalString("error"), createdAt = instant("createdAt"), updatedAt = instant("updatedAt"),
    )

    private fun Attempt.toJson() = buildJsonObject {
        put("number", number); put("startedAt", startedAt.toString())
        finishedAt?.let { put("finishedAt", it.toString()) }; httpStatus?.let { put("httpStatus", it) }
        error?.let { put("error", it) }
    }

    private fun JsonObject.toAttempt() = Attempt(
        number = int("number"), startedAt = instant("startedAt"), finishedAt = optionalInstant("finishedAt"),
        httpStatus = this["httpStatus"]?.jsonPrimitive?.intOrNull, error = optionalString("error"),
    )

    private fun Schedule.toJson(): JsonObject = buildJsonObject {
        when (this@toJson) {
            Schedule.Immediate -> put("type", "IMMEDIATE")
            is Schedule.At -> { put("type", "AT"); put("runAt", runAt.toString()) }
            is Schedule.Interval -> {
                put("type", "INTERVAL"); put("everySeconds", everySeconds)
                startAt?.let { put("startAt", it.toString()) }; maxExecutions?.let { put("maxExecutions", it) }
                endAt?.let { put("endAt", it.toString()) }
            }
            is Schedule.Cron -> {
                put("type", "CRON"); put("expression", expression); put("timezone", timezone)
                maxExecutions?.let { put("maxExecutions", it) }; endAt?.let { put("endAt", it.toString()) }
            }
        }
    }

    private fun JsonObject.toSchedule(): Schedule = when (string("type")) {
        "IMMEDIATE" -> Schedule.Immediate
        "AT" -> Schedule.At(instant("runAt"))
        "INTERVAL" -> Schedule.Interval(long("everySeconds"), optionalInstant("startAt"), optionalLong("maxExecutions"), optionalInstant("endAt"))
        "CRON" -> Schedule.Cron(string("expression"), string("timezone"), optionalLong("maxExecutions"), optionalInstant("endAt"))
        else -> error("Unknown persisted schedule type: ${string("type")}")
    }

    private fun Delivery.toJson(): JsonObject = buildJsonObject {
        when (this@toJson) {
            Delivery.Pull -> put("type", "PULL")
            is Delivery.HttpCallback -> {
                put("type", "HTTP_CALLBACK"); put("method", method.name); put("url", url)
                put("parametersType", parametersType.name); secretRef?.let { put("secretRef", it) }
                retryableStatuses?.let { put("retryableStatuses", JsonArray(it.map(::JsonPrimitive))) }
            }
        }
    }

    private fun JsonObject.toDelivery(): Delivery = when (string("type")) {
        "PULL" -> Delivery.Pull
        "HTTP_CALLBACK" -> Delivery.HttpCallback(
            method = Delivery.HttpCallback.Method.valueOf(string("method")), url = string("url"),
            parametersType = Delivery.HttpCallback.ParametersType.valueOf(string("parametersType")),
            secretRef = optionalString("secretRef"),
            retryableStatuses = this["retryableStatuses"]?.jsonArray?.map { it.jsonPrimitive.int },
        )
        else -> error("Unknown persisted delivery type: ${string("type")}")
    }

    private fun RetryPolicy.toJson() = buildJsonObject {
        put("maxAttempts", maxAttempts); put("initialDelaySeconds", initialDelaySeconds); put("multiplier", multiplier)
        put("maxDelaySeconds", maxDelaySeconds); put("jitter", jitter)
    }

    private fun JsonObject.toRetryPolicy() = RetryPolicy(
        maxAttempts = int("maxAttempts"), initialDelaySeconds = long("initialDelaySeconds"),
        multiplier = double("multiplier"), maxDelaySeconds = long("maxDelaySeconds"), jitter = double("jitter"),
    )

    private fun ExecutionPolicy.toJson() = buildJsonObject { put("allowOverlap", allowOverlap) }
    private fun JsonObject.toExecutionPolicy() = ExecutionPolicy(this["allowOverlap"]!!.jsonPrimitive.boolean)

    private fun JsonContainer.toJson() = JsonObject(mapValues { it.value.toJson() })
    private fun JsonValue.toJson(): JsonElement = when (this) {
        JsonValue.Null -> JsonNull
        is JsonValue.BooleanValue -> JsonPrimitive(value)
        is JsonValue.NumberValue -> JsonUnquotedLiteral(value)
        is JsonValue.StringValue -> JsonPrimitive(value)
        is JsonValue.ArrayValue -> JsonArray(values.map { it.toJson() })
        is JsonValue.ObjectValue -> JsonObject(values.mapValues { it.value.toJson() })
    }

    private fun JsonObject.toDomain(): JsonContainer = mapValues { it.value.toDomain() }
    private fun JsonElement.toDomain(): JsonValue = when (this) {
        JsonNull -> JsonValue.Null
        is JsonObject -> JsonValue.ObjectValue(toDomain())
        is JsonArray -> JsonValue.ArrayValue(map { it.toDomain() })
        is JsonPrimitive -> when {
            isString -> JsonValue.StringValue(content)
            booleanOrNull != null -> JsonValue.BooleanValue(boolean)
            else -> JsonValue.NumberValue(content)
        }
    }

    private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content
    private fun JsonObject.optionalString(name: String) = get(name)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
    private fun JsonObject.int(name: String) = getValue(name).jsonPrimitive.int
    private fun JsonObject.long(name: String) = getValue(name).jsonPrimitive.long
    private fun JsonObject.double(name: String) = getValue(name).jsonPrimitive.double
    private fun JsonObject.optionalLong(name: String) = get(name)?.jsonPrimitive?.longOrNull
    private fun JsonObject.instant(name: String) = Instant.parse(string(name))
    private fun JsonObject.optionalInstant(name: String) = optionalString(name)?.let(Instant::parse)
    private fun JsonObject.objectValue(name: String) = getValue(name).jsonObject
    private fun JsonObject.optionalObject(name: String) = get(name)?.takeUnless { it is JsonNull }?.jsonObject
}

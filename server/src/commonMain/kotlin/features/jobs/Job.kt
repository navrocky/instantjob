package features.jobs

import common.JsonContainer
import kotlin.time.Instant

sealed interface Schedule {
    data object Immediate : Schedule
    data class At(val runAt: Instant) : Schedule
    data class Interval(
        val everySeconds: Long,
        val startAt: Instant? = null,
        val maxExecutions: Long? = null,
        val endAt: Instant? = null,
    ) : Schedule
    data class Cron(
        val expression: String,
        val timezone: String,
        val maxExecutions: Long? = null,
        val endAt: Instant? = null,
    ) : Schedule
}

sealed interface Delivery {
    data object Pull : Delivery
    data class HttpCallback(
        val method: Method,
        val url: String,
        val parametersType: ParametersType,
        val secretRef: String? = null,
        val retryableStatuses: List<Int>? = null,
    ) : Delivery {
        enum class Method { GET, POST, PUT }
        enum class ParametersType { QUERY, FORM_URLENCODED, JSON }
    }
}

data class RetryPolicy(
    val maxAttempts: Int = 5,
    val initialDelaySeconds: Long = 5,
    val multiplier: Double = 2.0,
    val maxDelaySeconds: Long = 300,
    val jitter: Double = .2,
)

data class ExecutionPolicy(val allowOverlap: Boolean = false)

enum class JobStatus { ACTIVE, CANCELLING, CANCELLED, COMPLETED, FAILED }

data class Job(
    val id: String,
    val producerService: String,
    val targetService: String,
    val taskName: String,
    val idempotencyKey: String,
    val parameters: JsonContainer,
    val schedule: Schedule,
    val delivery: Delivery,
    val retryPolicy: RetryPolicy,
    val executionPolicy: ExecutionPolicy,
    val status: JobStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val nextRunAt: Instant?,
    val executionCount: Long,
)

data class CreateJobCommand(
    val targetService: String,
    val taskName: String,
    val idempotencyKey: String,
    val parameters: JsonContainer,
    val schedule: Schedule,
    val delivery: Delivery,
    val retryPolicy: RetryPolicy? = null,
    val executionPolicy: ExecutionPolicy? = null,
)

data class CreateJobResult(val created: Boolean, val job: Job)

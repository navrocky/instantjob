package features.jobs

import common.JsonContainer
import kotlin.time.Instant

/** Defines when executions of a job are created. */
sealed interface Schedule {
    /** Creates the first execution as soon as the job is registered. */
    data object Immediate : Schedule

    /** Creates one execution at [runAt], or immediately when that instant is in the past. */
    data class At(val runAt: Instant) : Schedule

    /**
     * Creates executions at a fixed interval.
     *
     * @property everySeconds Number of seconds between execution times.
     * @property startAt Optional first execution time; registration time is used when absent.
     * @property maxExecutions Optional maximum number of executions.
     * @property endAt Optional inclusive upper bound for execution times.
     */
    data class Interval(
        val everySeconds: Long,
        val startAt: Instant? = null,
        val maxExecutions: Long? = null,
        val endAt: Instant? = null,
    ) : Schedule

    /**
     * Creates executions matching a six-field cron expression.
     *
     * @property expression Cron fields in second, minute, hour, day-of-month, month, day-of-week order.
     * @property timezone IANA time-zone name in which the expression is evaluated.
     * @property maxExecutions Optional maximum number of executions.
     * @property endAt Optional inclusive upper bound for execution times.
     */
    data class Cron(
        val expression: String,
        val timezone: String,
        val maxExecutions: Long? = null,
        val endAt: Instant? = null,
    ) : Schedule
}

/** Defines how a target service receives an execution. */
sealed interface Delivery {
    /** Makes executions available through the worker lease API. */
    data object Pull : Delivery

    /**
     * Sends executions to an HTTP endpoint.
     *
     * @property method HTTP method used for the request.
     * @property url Absolute callback URL.
     * @property parametersType Encoding used to pass execution parameters.
     * @property secretRef Optional reference to a configured Authorization header value.
     * @property retryableStatuses Additional response status codes that should be retried.
     */
    data class HttpCallback(
        val method: Method,
        val url: String,
        val parametersType: ParametersType,
        val secretRef: String? = null,
        val retryableStatuses: List<Int>? = null,
    ) : Delivery {
        /** HTTP methods supported by callback delivery. */
        enum class Method { GET, POST, PUT }

        /** Supported encodings for callback execution parameters. */
        enum class ParametersType { QUERY, FORM_URLENCODED, JSON }
    }
}

/**
 * Controls retry timing after a failed execution attempt.
 *
 * @property maxAttempts Maximum total number of attempts, including the initial attempt.
 * @property initialDelaySeconds Delay before the first retry.
 * @property multiplier Exponential multiplier applied for each subsequent retry.
 * @property maxDelaySeconds Upper bound for a calculated retry delay.
 * @property jitter Fractional random variation applied to a retry delay.
 */
data class RetryPolicy(
    val maxAttempts: Int = 5,
    val initialDelaySeconds: Long = 5,
    val multiplier: Double = 2.0,
    val maxDelaySeconds: Long = 300,
    val jitter: Double = .2,
)

/** @property allowOverlap Whether multiple executions of the same job may run concurrently. */
data class ExecutionPolicy(val allowOverlap: Boolean = false)

/** Lifecycle state of a registered job. */
enum class JobStatus { ACTIVE, CANCELLING, CANCELLED, COMPLETED, FAILED }

/**
 * Registered background job and its current scheduling state.
 *
 * @property id Unique job identifier.
 * @property producerService Service that registered the job.
 * @property targetService Service responsible for executing the task.
 * @property taskName Task identifier understood by the target service.
 * @property idempotencyKey Producer-scoped registration idempotency key.
 * @property parameters Arbitrary JSON-compatible task parameters.
 * @property schedule Execution schedule supplied at registration.
 * @property delivery Mechanism used to deliver executions.
 * @property retryPolicy Retry behavior for failed attempts.
 * @property executionPolicy Concurrency behavior for recurring executions.
 * @property status Current job lifecycle state.
 * @property createdAt Time at which the job was registered.
 * @property updatedAt Time of the latest state change.
 * @property nextRunAt Next planned execution time, or null when no further execution is planned.
 * @property executionCount Number of executions materialized for this job.
 */
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

/**
 * Domain command for registering a job.
 *
 * @property targetService Service responsible for executing the task.
 * @property taskName Task identifier understood by the target service.
 * @property idempotencyKey Producer-scoped idempotency key.
 * @property parameters Arbitrary JSON-compatible task parameters.
 * @property schedule Requested execution schedule.
 * @property delivery Requested execution delivery mechanism.
 * @property retryPolicy Optional retry policy; service defaults are used when absent.
 * @property executionPolicy Optional execution policy; service defaults are used when absent.
 */
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

/**
 * Result of idempotent job registration.
 *
 * @property created True when a new job was stored; false when an identical job already existed.
 * @property job Newly created or previously existing job.
 */
data class CreateJobResult(val created: Boolean, val job: Job)

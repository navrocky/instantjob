package features.jobs

import common.JsonContainer
import kotlin.time.Instant

/** Defines when executions of a job are created. */
sealed interface Schedule {
    /** Creates the first execution as soon as the job is registered. */
    data object Immediate : Schedule

    /** Creates one execution at a specified instant. */
    data class At(
        /** Planned execution time; past instants are executed immediately. */
        val runAt: Instant,
    ) : Schedule

    /** Creates executions at a fixed interval. */
    data class Interval(
        /** Number of seconds between execution times. */
        val everySeconds: Long,
        /** Optional first execution time; registration time is used when absent. */
        val startAt: Instant? = null,
        /** Optional maximum number of executions. */
        val maxExecutions: Long? = null,
        /** Optional inclusive upper bound for execution times. */
        val endAt: Instant? = null,
    ) : Schedule

    /** Creates executions matching a six-field cron expression. */
    data class Cron(
        /** Cron fields in second, minute, hour, day-of-month, month, day-of-week order. */
        val expression: String,
        /** IANA time-zone name in which the expression is evaluated. */
        val timezone: String,
        /** Optional maximum number of executions. */
        val maxExecutions: Long? = null,
        /** Optional inclusive upper bound for execution times. */
        val endAt: Instant? = null,
    ) : Schedule
}

/** Defines how a target service receives an execution. */
sealed interface Delivery {
    /** Makes executions available through the worker lease API. */
    data object Pull : Delivery

    /** Sends executions to an HTTP endpoint. */
    data class HttpCallback(
        /** HTTP method used for the request. */
        val method: Method,
        /** Absolute callback URL. */
        val url: String,
        /** Encoding used to pass execution parameters. */
        val parametersType: ParametersType,
        /** Optional reference to a configured Authorization header value. */
        val secretRef: String? = null,
        /** Additional response status codes that should be retried. */
        val retryableStatuses: List<Int>? = null,
    ) : Delivery {
        /** HTTP methods supported by callback delivery. */
        enum class Method { GET, POST, PUT }

        /** Supported encodings for callback execution parameters. */
        enum class ParametersType { QUERY, FORM_URLENCODED, JSON }
    }
}

/** Controls retry timing after a failed execution attempt. */
data class RetryPolicy(
    /** Maximum total number of attempts, including the initial attempt. */
    val maxAttempts: Int = 5,
    /** Delay before the first retry. */
    val initialDelaySeconds: Long = 5,
    /** Exponential multiplier applied for each subsequent retry. */
    val multiplier: Double = 2.0,
    /** Upper bound for a calculated retry delay. */
    val maxDelaySeconds: Long = 300,
    /** Fractional random variation applied to a retry delay. */
    val jitter: Double = .2,
)

/** Controls concurrency between executions of the same job. */
data class ExecutionPolicy(
    /** Whether multiple executions of the same job may run concurrently. */
    val allowOverlap: Boolean = false,
)

/** Lifecycle state of a registered job. */
enum class JobStatus { ACTIVE, CANCELLING, CANCELLED, COMPLETED, FAILED }

/** Registered background job and its current scheduling state. */
data class Job(
    /** Unique job identifier. */
    val id: String,
    /** Service that registered the job. */
    val producerService: String,
    /** Service responsible for executing the task. */
    val targetService: String,
    /** Task identifier understood by the target service. */
    val taskName: String,
    /** Producer-scoped registration idempotency key. */
    val idempotencyKey: String,
    /** Arbitrary JSON-compatible task parameters. */
    val parameters: JsonContainer,
    /** Execution schedule supplied at registration. */
    val schedule: Schedule,
    /** Mechanism used to deliver executions. */
    val delivery: Delivery,
    /** Retry behavior for failed attempts. */
    val retryPolicy: RetryPolicy,
    /** Concurrency behavior for recurring executions. */
    val executionPolicy: ExecutionPolicy,
    /** Current job lifecycle state. */
    val status: JobStatus,
    /** Time at which the job was registered. */
    val createdAt: Instant,
    /** Time of the latest state change. */
    val updatedAt: Instant,
    /** Next planned execution time, or null when no further execution is planned. */
    val nextRunAt: Instant?,
    /** Number of executions materialized for this job. */
    val executionCount: Long,
)

/** Domain command for registering a job. */
data class CreateJobCommand(
    /** Service responsible for executing the task. */
    val targetService: String,
    /** Task identifier understood by the target service. */
    val taskName: String,
    /** Producer-scoped idempotency key. */
    val idempotencyKey: String,
    /** Arbitrary JSON-compatible task parameters. */
    val parameters: JsonContainer,
    /** Requested execution schedule. */
    val schedule: Schedule,
    /** Requested execution delivery mechanism. */
    val delivery: Delivery,
    /** Optional retry policy; service defaults are used when absent. */
    val retryPolicy: RetryPolicy? = null,
    /** Optional execution policy; service defaults are used when absent. */
    val executionPolicy: ExecutionPolicy? = null,
)

/** Result of idempotent job registration. */
data class CreateJobResult(
    /** True when a new job was stored; false when an identical job already existed. */
    val created: Boolean,
    /** Newly created or previously existing job. */
    val job: Job,
)

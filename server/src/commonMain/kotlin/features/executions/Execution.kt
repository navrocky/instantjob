package features.executions

import common.JsonContainer
import kotlin.time.Instant

/** Lifecycle state of a concrete job execution. */
enum class ExecutionStatus { PENDING, LEASED, SUCCEEDED, FAILED }

/** Concrete execution materialized from a job schedule. */
data class Execution(
    /** Unique execution identifier. */
    val id: String,
    /** Identifier of the source job. */
    val jobId: String,
    /** Service that registered the source job. */
    val producerService: String,
    /** Service responsible for running the task. */
    val targetService: String,
    /** Task identifier understood by the target service. */
    val taskName: String,
    /** Snapshot of the job parameters for this execution. */
    val parameters: JsonContainer,
    /** Planned execution time. */
    val scheduledAt: Instant,
    /** Current execution lifecycle state. */
    val status: ExecutionStatus,
    /** Number of attempts started for this execution. */
    val attemptCount: Int,
    /** Expiration time of the active worker lease, if any. */
    val leaseUntil: Instant? = null,
    /** JSON-compatible result supplied after successful completion. */
    val result: JsonContainer? = null,
    /** Error details recorded for the latest failed attempt. */
    val error: String? = null,
    /** Time at which the execution was materialized. */
    val createdAt: Instant,
    /** Time of the latest execution state change. */
    val updatedAt: Instant,
)

/** A single attempt to process an execution. */
data class Attempt(
    /** One-based attempt number within the execution. */
    val number: Int,
    /** Time at which the lease and attempt started. */
    val startedAt: Instant,
    /** Completion time, or null while the attempt is active. */
    val finishedAt: Instant? = null,
    /** Callback response status associated with the attempt, when available. */
    val httpStatus: Int? = null,
    /** Error details recorded for the attempt. */
    val error: String? = null,
)

/** Execution returned to a worker together with its lease credentials. */
data class ClaimedExecution(
    /** Leased execution snapshot. */
    val execution: Execution,
    /** Secret token required for heartbeat and completion operations. */
    val leaseToken: String,
    /** Time at which the lease expires without a heartbeat. */
    val leaseUntil: Instant,
)

/** Outcome reported when a worker completes an execution attempt. */
enum class CompletionOutcome { SUCCEEDED, FAILED }

/** Domain command for completing a leased execution. */
data class CompleteExecutionCommand(
    /** Token proving ownership of the active lease. */
    val leaseToken: String,
    /** Reported attempt outcome. */
    val outcome: CompletionOutcome,
    /** Whether a failure may be retried according to the job policy. */
    val retryable: Boolean = true,
    /** Optional JSON-compatible result for successful completion. */
    val result: JsonContainer? = null,
    /** Optional failure details. */
    val error: String? = null,
)

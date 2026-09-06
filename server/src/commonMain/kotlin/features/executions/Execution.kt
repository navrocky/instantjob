package features.executions

import common.JsonContainer
import kotlin.time.Instant

/** Lifecycle state of a concrete job execution. */
enum class ExecutionStatus { PENDING, LEASED, SUCCEEDED, FAILED }

/**
 * Concrete execution materialized from a job schedule.
 *
 * @property id Unique execution identifier.
 * @property jobId Identifier of the source job.
 * @property producerService Service that registered the source job.
 * @property targetService Service responsible for running the task.
 * @property taskName Task identifier understood by the target service.
 * @property parameters Snapshot of the job parameters for this execution.
 * @property scheduledAt Planned execution time.
 * @property status Current execution lifecycle state.
 * @property attemptCount Number of attempts started for this execution.
 * @property leaseUntil Expiration time of the active worker lease, if any.
 * @property result JSON-compatible result supplied after successful completion.
 * @property error Error details recorded for the latest failed attempt.
 * @property createdAt Time at which the execution was materialized.
 * @property updatedAt Time of the latest execution state change.
 */
data class Execution(
    val id: String,
    val jobId: String,
    val producerService: String,
    val targetService: String,
    val taskName: String,
    val parameters: JsonContainer,
    val scheduledAt: Instant,
    val status: ExecutionStatus,
    val attemptCount: Int,
    val leaseUntil: Instant? = null,
    val result: JsonContainer? = null,
    val error: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * A single attempt to process an execution.
 *
 * @property number One-based attempt number within the execution.
 * @property startedAt Time at which the lease and attempt started.
 * @property finishedAt Completion time, or null while the attempt is active.
 * @property httpStatus Callback response status associated with the attempt, when available.
 * @property error Error details recorded for the attempt.
 */
data class Attempt(
    val number: Int,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
    val httpStatus: Int? = null,
    val error: String? = null,
)

/**
 * Execution returned to a worker together with its lease credentials.
 *
 * @property execution Leased execution snapshot.
 * @property leaseToken Secret token required for heartbeat and completion operations.
 * @property leaseUntil Time at which the lease expires without a heartbeat.
 */
data class ClaimedExecution(
    val execution: Execution,
    val leaseToken: String,
    val leaseUntil: Instant,
)

/** Outcome reported when a worker completes an execution attempt. */
enum class CompletionOutcome { SUCCEEDED, FAILED }

/**
 * Domain command for completing a leased execution.
 *
 * @property leaseToken Token proving ownership of the active lease.
 * @property outcome Reported attempt outcome.
 * @property retryable Whether a failure may be retried according to the job policy.
 * @property result Optional JSON-compatible result for successful completion.
 * @property error Optional failure details.
 */
data class CompleteExecutionCommand(
    val leaseToken: String,
    val outcome: CompletionOutcome,
    val retryable: Boolean = true,
    val result: JsonContainer? = null,
    val error: String? = null,
)

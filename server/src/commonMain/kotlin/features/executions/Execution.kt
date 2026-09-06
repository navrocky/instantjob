package features.executions

import common.JsonContainer
import kotlin.time.Instant

enum class ExecutionStatus { PENDING, LEASED, SUCCEEDED, FAILED }

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

data class Attempt(
    val number: Int,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
    val httpStatus: Int? = null,
    val error: String? = null,
)

data class ClaimedExecution(
    val execution: Execution,
    val leaseToken: String,
    val leaseUntil: Instant,
)

enum class CompletionOutcome { SUCCEEDED, FAILED }

data class CompleteExecutionCommand(
    val leaseToken: String,
    val outcome: CompletionOutcome,
    val retryable: Boolean = true,
    val result: JsonContainer? = null,
    val error: String? = null,
)

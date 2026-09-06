package features.callbacks

import common.DelayProvider
import features.executions.CompleteExecutionCommand
import features.executions.ClaimedExecution
import features.executions.CompletionOutcome
import features.jobs.Delivery
import features.scheduling.SchedulerService
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Dispatches scheduled callbacks through the HTTP adapter. */
class CallbackDispatcher(
    private val repository: SchedulerService,
    private val client: CallbackClient,
    private val delayProvider: DelayProvider,
    private val isCancellation: (Throwable) -> Boolean,
) : AutoCloseable {
    suspend fun runForever() {
        while (true) {
            try {
                val claimed = repository.claimCallbacks(20)
                if (claimed.isEmpty()) delayProvider.delay(250.milliseconds)
                claimed.forEach { dispatch(it) }
            } catch (error: Throwable) {
                if (isCancellation(error)) throw error
                delayProvider.delay(1.seconds)
            }
        }
    }

    private suspend fun dispatch(claimed: ClaimedExecution) {
        val execution = claimed.execution
        val job = repository.getJob(execution.targetService, execution.jobId)
        val delivery = job.delivery as Delivery.HttpCallback
        try {
            val response = client.execute(
                CallbackRequest(
                    executionId = execution.id,
                    jobId = execution.jobId,
                    producerService = execution.producerService,
                    targetService = execution.targetService,
                    taskName = execution.taskName,
                    parameters = execution.parameters,
                    scheduledAt = execution.scheduledAt.toString(),
                    attemptCount = execution.attemptCount,
                    delivery = delivery,
                )
            )
            completeFromResponse(execution, claimed.leaseToken, delivery, response)
        } catch (error: Throwable) {
            if (isCancellation(error)) throw error
            repository.complete(
                execution.targetService,
                execution.id,
                CompleteExecutionCommand(
                    leaseToken = claimed.leaseToken,
                    outcome = CompletionOutcome.FAILED,
                    retryable = true,
                    error = error.message?.take(8192) ?: "Callback failed",
                ),
            )
        }
    }

    private suspend fun completeFromResponse(
        execution: features.executions.Execution,
        token: String,
        delivery: Delivery.HttpCallback,
        response: CallbackResponse,
    ) {
        val successful = response.statusCode in 200..299
        val retryable = response.statusCode in (delivery.retryableStatuses ?: emptyList()) ||
                response.statusCode == 408 || response.statusCode == 429 || response.statusCode >= 500
        val responseText = response.body?.take(8192)
        repository.complete(
            execution.targetService,
            execution.id,
            CompleteExecutionCommand(
                leaseToken = token,
                outcome = if (successful) CompletionOutcome.SUCCEEDED else CompletionOutcome.FAILED,
                retryable = retryable,
                error = if (successful) null else "HTTP ${response.statusCode}: ${responseText.orEmpty()}".take(8192),
            ),
        )
    }

    override fun close() = client.close()
}

package features.callbacks

import common.JsonContainer
import features.jobs.Delivery

data class CallbackRequest(
    val executionId: String,
    val jobId: String,
    val producerService: String,
    val targetService: String,
    val taskName: String,
    val parameters: JsonContainer,
    val scheduledAt: String,
    val attemptCount: Int,
    val delivery: Delivery.HttpCallback,
)

data class CallbackResponse(
    val statusCode: Int,
    val body: String?,
)

/** Infrastructure port used by the callback application service. */
interface CallbackClient : AutoCloseable {
    suspend fun execute(request: CallbackRequest): CallbackResponse
}

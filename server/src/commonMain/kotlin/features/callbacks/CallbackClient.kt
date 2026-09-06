package features.callbacks

import common.JsonContainer
import features.jobs.Delivery

/**
 * Transport-independent callback request produced by the callback workflow.
 *
 * @property executionId Unique execution identifier.
 * @property jobId Identifier of the source job.
 * @property producerService Service that registered the job.
 * @property targetService Service receiving the callback.
 * @property taskName Task identifier understood by the target service.
 * @property parameters JSON-compatible task parameters.
 * @property scheduledAt ISO-8601 representation of the planned execution time.
 * @property attemptCount Number of the current attempt.
 * @property delivery HTTP delivery configuration from the source job.
 */
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

/**
 * Transport-independent result returned by a callback client.
 *
 * @property statusCode HTTP response status code.
 * @property body Optional response body, truncated by the client when necessary.
 */
data class CallbackResponse(
    val statusCode: Int,
    val body: String?,
)

/** Infrastructure port used by the callback application service. */
interface CallbackClient : AutoCloseable {
    suspend fun execute(request: CallbackRequest): CallbackResponse
}

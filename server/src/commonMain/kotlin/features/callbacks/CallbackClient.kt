package features.callbacks

import common.JsonContainer
import features.jobs.Delivery

/** Transport-independent callback request produced by the callback workflow. */
data class CallbackRequest(
    /** Unique execution identifier. */
    val executionId: String,
    /** Identifier of the source job. */
    val jobId: String,
    /** Service that registered the job. */
    val producerService: String,
    /** Service receiving the callback. */
    val targetService: String,
    /** Task identifier understood by the target service. */
    val taskName: String,
    /** JSON-compatible task parameters. */
    val parameters: JsonContainer,
    /** ISO-8601 representation of the planned execution time. */
    val scheduledAt: String,
    /** Number of the current attempt. */
    val attemptCount: Int,
    /** HTTP delivery configuration from the source job. */
    val delivery: Delivery.HttpCallback,
)

/** Transport-independent result returned by a callback client. */
data class CallbackResponse(
    /** HTTP response status code. */
    val statusCode: Int,
    /** Optional response body, truncated by the client when necessary. */
    val body: String?,
)

/** Infrastructure port used by the callback application service. */
interface CallbackClient : AutoCloseable {
    /** Delivers [request] and returns the transport-independent callback response. */
    suspend fun execute(request: CallbackRequest): CallbackResponse
}

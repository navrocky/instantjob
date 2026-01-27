package common

import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

//class KtorTools
//
//private val logger = KtorTools::class.logger

fun RoutingCall.getRequestId(): RequestId? = this.request.headers["X-Request-Id"]?.let { RequestId(it.trim()) }

fun RoutingContext.getContext(): Context = RootContext(call.getRequestId() ?: RequestId(generateId()))

interface JsonBaseResponse {
    val code: String
}

@Serializable
data class JsonErrorResponse(
    override val code: String,
    val message: String,
    val causedBy: List<String>?
) : JsonBaseResponse

@Serializable
open class JsonSuccessResponse(
    override val code: String = "SUCCESS"
) : JsonBaseResponse
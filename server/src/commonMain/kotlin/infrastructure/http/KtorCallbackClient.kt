package infrastructure.http

import common.DomainErrorKind
import common.DomainException
import common.JsonValue
import features.callbacks.CallbackClient
import features.callbacks.CallbackRequest
import features.callbacks.CallbackResponse
import features.jobs.Delivery
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlin.time.Duration.Companion.seconds

class CallbackSecretRegistry(private val authorizationValues: Map<String, String>) {
    fun authorization(secretRef: String?): String? = secretRef?.let {
        authorizationValues[it] ?: error("Callback secret is not configured: $it")
    }

    companion object {
        fun parse(value: String): CallbackSecretRegistry = CallbackSecretRegistry(
            value.split(',').filter { it.isNotBlank() }.associate { entry ->
                val parts = entry.trim().split('=', limit = 2)
                require(parts.size == 2 && parts.all { it.isNotBlank() }) {
                    "<6ddde93a> Callback secrets must use ref=value format"
                }
                parts[0] to parts[1]
            }
        )
    }
}

/** Delivers callback requests with the Ktor HTTP client. */
@OptIn(ExperimentalSerializationApi::class)
class KtorCallbackClient(
    private val secrets: CallbackSecretRegistry,
    private val allowHttp: Boolean,
    private val client: HttpClient = defaultHttpClient(),
) : CallbackClient {
    override suspend fun execute(request: CallbackRequest): CallbackResponse {
        val delivery = request.delivery
        validateUrl(delivery.url)
        val response = client.request(delivery.url) {
            method = when (delivery.method) {
                Delivery.HttpCallback.Method.GET -> HttpMethod.Get
                Delivery.HttpCallback.Method.POST -> HttpMethod.Post
                Delivery.HttpCallback.Method.PUT -> HttpMethod.Put
            }
            header("X-InstantJob-Execution-Id", request.executionId)
            header("X-InstantJob-Attempt", request.attemptCount)
            header("X-InstantJob-Job-Id", request.jobId)
            secrets.authorization(delivery.secretRef)?.let { header(HttpHeaders.Authorization, it) }
            when (delivery.parametersType) {
                Delivery.HttpCallback.ParametersType.JSON -> {
                    contentType(ContentType.Application.Json)
                    setBody(request.callbackBody())
                }
                Delivery.HttpCallback.ParametersType.FORM_URLENCODED -> setBody(request.formParameters())
                Delivery.HttpCallback.ParametersType.QUERY -> url {
                    request.formParameters().forEach { key, values -> values.forEach { parameters.append(key, it) } }
                }
            }
        }
        return CallbackResponse(response.status.value, runCatching { response.body<String>() }.getOrNull()?.take(8192))
    }

    override fun close() = client.close()

    private fun validateUrl(value: String) {
        val url = Url(value)
        if (url.protocol != URLProtocol.HTTPS && !(allowHttp && url.protocol == URLProtocol.HTTP)) {
            throw DomainException(DomainErrorKind.BAD_REQUEST, "CALLBACK_URL_FORBIDDEN", "Callback URL must use HTTPS")
        }
        if (url.host.isBlank() || url.host == "localhost" || url.host.startsWith("127.") || url.host == "::1") {
            throw DomainException(DomainErrorKind.BAD_REQUEST, "CALLBACK_URL_FORBIDDEN", "Callback host is not allowed")
        }
    }

    private fun CallbackRequest.formParameters(): Parameters = Parameters.build {
        append("executionId", executionId)
        append("jobId", jobId)
        append("parameters", json.encodeToString(JsonObject(parameters.mapValues { it.value.toJson() })))
    }

    private fun CallbackRequest.callbackBody() = JsonObject(
        mapOf(
            "id" to JsonPrimitive(executionId),
            "jobId" to JsonPrimitive(jobId),
            "producerService" to JsonPrimitive(producerService),
            "targetService" to JsonPrimitive(targetService),
            "taskName" to JsonPrimitive(taskName),
            "parameters" to JsonObject(parameters.mapValues { it.value.toJson() }),
            "scheduledAt" to JsonPrimitive(scheduledAt),
            "attemptCount" to JsonPrimitive(attemptCount),
        )
    )

    private fun JsonValue.toJson(): JsonElement = when (this) {
        JsonValue.Null -> JsonNull
        is JsonValue.BooleanValue -> JsonPrimitive(value)
        is JsonValue.NumberValue -> JsonUnquotedLiteral(value)
        is JsonValue.StringValue -> JsonPrimitive(value)
        is JsonValue.ArrayValue -> JsonArray(values.map { it.toJson() })
        is JsonValue.ObjectValue -> JsonObject(values.mapValues { it.value.toJson() })
    }

    companion object {
        private val json = Json { encodeDefaults = true; classDiscriminator = "type"; ignoreUnknownKeys = true }

        private fun defaultHttpClient() = HttpClient(CIO) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) { requestTimeoutMillis = 30.seconds.inWholeMilliseconds }
        }
    }
}

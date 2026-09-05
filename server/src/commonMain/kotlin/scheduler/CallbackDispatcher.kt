package scheduler

import instantjob.api.generated.models.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class CallbackSecretRegistry(private val authorizationValues: Map<String, String>) {
    fun authorization(secretRef: String?): String? = secretRef?.let {
        authorizationValues[it] ?: throw ApiException(
            HttpStatusCode.InternalServerError,
            "SECRET_NOT_FOUND",
            "Callback secret is not configured: $it"
        )
    }

    companion object {
        fun parse(value: String): CallbackSecretRegistry = CallbackSecretRegistry(
            value.split(',').filter { it.isNotBlank() }.associate { entry ->
                val parts = entry.trim().split('=', limit = 2)
                require(parts.size == 2 && parts.all { it.isNotBlank() }) { "<6ddde93a> Callback secrets must use ref=value format" }
                parts[0] to parts[1]
            }
        )
    }
}

class CallbackDispatcher(
    private val repository: SchedulerRepository,
    private val secrets: CallbackSecretRegistry,
    private val allowHttp: Boolean = false,
) : AutoCloseable {
    private val json = Json { encodeDefaults = true; classDiscriminator = "type"; ignoreUnknownKeys = true }
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 30.seconds.inWholeMilliseconds }
    }

    suspend fun runForever() {
        while (true) {
            try {
                val claimed = repository.claimCallbacks(20)
                if (claimed.isEmpty()) delay(250.milliseconds)
                claimed.forEach { dispatch(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                delay(1.seconds)
            }
        }
    }

    private suspend fun dispatch(claimed: ClaimedExecution) {
        val execution = claimed.execution
        val job = repository.getJob(execution.targetService, execution.jobId)
        val delivery = job.delivery as HttpCallbackDelivery
        try {
            validateUrl(delivery.url)
            val response = client.request(delivery.url) {
                method = when (delivery.method) {
                    HttpCallbackDeliveryMethod.GET -> HttpMethod.Get
                    HttpCallbackDeliveryMethod.POST -> HttpMethod.Post
                    HttpCallbackDeliveryMethod.PUT -> HttpMethod.Put
                }
                header("X-InstantJob-Execution-Id", execution.id)
                header("X-InstantJob-Attempt", execution.attemptCount)
                header("X-InstantJob-Job-Id", execution.jobId)
                secrets.authorization(delivery.secretRef)?.let { header(HttpHeaders.Authorization, it) }
                when (delivery.parametersType) {
                    HttpCallbackDeliveryParametersType.JSON -> {
                        contentType(ContentType.Application.Json)
                        setBody(execution)
                    }

                    HttpCallbackDeliveryParametersType.FORM_URLENCODED -> setBody(
                        Parameters.build {
                            append("executionId", execution.id)
                            append("jobId", execution.jobId)
                            append("parameters", json.encodeToString(execution.parameters))
                        }.formUrlEncode()
                    )

                    HttpCallbackDeliveryParametersType.QUERY -> url {
                        parameters.append("executionId", execution.id)
                        parameters.append("jobId", execution.jobId)
                        parameters.append("parameters", json.encodeToString(execution.parameters))
                    }
                }
            }
            completeFromResponse(execution, claimed.leaseToken, delivery, response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            repository.complete(
                execution.targetService,
                execution.id,
                CompleteExecutionRequest(
                    leaseToken = claimed.leaseToken,
                    outcome = CompleteExecutionRequestOutcome.FAILED,
                    retryable = true,
                    error = e.message?.take(8192) ?: "Callback failed",
                ),
            )
        }
    }

    private suspend fun completeFromResponse(
        execution: Execution,
        token: String,
        delivery: HttpCallbackDelivery,
        response: HttpResponse
    ) {
        val successful = response.status.value in 200..299
        val retryable = response.status.value in (delivery.retryableStatuses ?: emptyList()) ||
                response.status == HttpStatusCode.RequestTimeout || response.status == HttpStatusCode.TooManyRequests || response.status.value >= 500
        val responseText = runCatching { response.body<String>() }.getOrNull()?.take(8192)
        repository.complete(
            execution.targetService,
            execution.id,
            CompleteExecutionRequest(
                leaseToken = token,
                outcome = if (successful) CompleteExecutionRequestOutcome.SUCCEEDED else CompleteExecutionRequestOutcome.FAILED,
                retryable = retryable,
                error = if (successful) null else "HTTP ${response.status.value}: ${responseText.orEmpty()}".take(8192),
            ),
        )
    }

    private fun validateUrl(value: String) {
        val url = Url(value)
        if (url.protocol != URLProtocol.HTTPS && !(allowHttp && url.protocol == URLProtocol.HTTP)) {
            throw ApiException(HttpStatusCode.BadRequest, "CALLBACK_URL_FORBIDDEN", "Callback URL must use HTTPS")
        }
        if (url.host.isBlank() || url.host == "localhost" || url.host.startsWith("127.") || url.host == "::1") {
            throw ApiException(HttpStatusCode.BadRequest, "CALLBACK_URL_FORBIDDEN", "Callback host is not allowed")
        }
    }

    override fun close() = client.close()
}

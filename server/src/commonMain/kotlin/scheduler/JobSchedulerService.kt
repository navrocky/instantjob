package scheduler

import common.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

@Serializable
data class RetryConfig(
    val intervalsInSeconds: List<Int>,
    val max: Long? = null,
)

@Serializable
sealed interface ScheduleConfig {

    @Serializable
    @SerialName("SINGLE_SHOT")
    data class SingleShotScheduleConfig(
        val scheduleAt: Instant,
    ) : ScheduleConfig

    @Serializable
    @SerialName("PERIODICAL")
    data class Periodical(
        val intervalInSeconds: Long,
    ) : ScheduleConfig

}

@Serializable
sealed interface CallbackConfig {

    /**
     * Call HTTP Get with parameters in query
     */
    @Serializable
    @SerialName("HTTP")
    data class Http(
        val method: Method, val url: String, val parametersType: ParametersType
    ) : CallbackConfig {

        enum class Method {
            GET, POST, PUT
        }

        enum class ParametersType {
            /**
             * In query
             */
            QUERY,

            /**
             * In body using form url encoded format
             */
            FORM_URLENCODED,

            /**
             * In body using JSON encoding
             */
            JSON
        }
    }

}

@Serializable
data class JobRequest(
    val idempotencyKey: String? = null,
    val parameters: JsonObject? = null,
    val callback: CallbackConfig? = null,
    val retry: RetryConfig? = null,
    val schedule: List<ScheduleConfig>? = null,
)

typealias JobId = String

@Serializable
data class Job(
    val id: JobId,
    val status: Status,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
    val idempotencyKey: String? = null,
    val parameters: JsonObject? = null,
    val callback: CallbackConfig? = null,
    val retry: RetryConfig? = null,
    val schedule: List<ScheduleConfig>? = null,
) {
    enum class Status {
        ENQUEUED, CANCELLED, SUCCEEDED, FAILED,
    }
}

interface JobRepository {
    fun getById(ctx: Context, id: JobId): Job
    fun findByIdempotencyKey(ctx: Context, key: String): Job?
    fun save(ctx: Context, job: Job)
}

interface JobSchedulerService {
    suspend fun enqueueJob(ctx: Context, jobRequest: JobRequest): JobId
    suspend fun getJobById(ctx: Context, id: JobId): Job
    suspend fun cancelJob(ctx: Context, id: JobId)
}
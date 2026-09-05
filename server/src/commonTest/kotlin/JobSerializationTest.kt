import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import scheduler.CallbackConfig
import scheduler.Job
import scheduler.RetryConfig
import scheduler.ScheduleConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

class JobSerializationTest {
    @Test
    fun testJobsSerialization() {
        val job = Job(
            id = "1",
            parameters = buildJsonObject {
                put("key", "value")
            },
            callback = CallbackConfig.Http(
                method = CallbackConfig.Http.Method.GET,
                url = "https://example.com",
                parametersType = CallbackConfig.Http.ParametersType.QUERY,
            ),
            retry = RetryConfig(intervalsInSeconds = listOf(1, 2, 5, 10, 30, 60)),
            schedule = listOf(ScheduleConfig.Periodical(intervalInSeconds = 3600)),
            status = Job.Status.ENQUEUED,
            createdAt = Clock.System.now(),
            updatedAt = null,
            idempotencyKey = "job1"
        )
        val json = Json.encodeToString(job)
        val decodedJob = Json.decodeFromString<Job>(json)
        assertEquals(job, decodedJob)
    }
}

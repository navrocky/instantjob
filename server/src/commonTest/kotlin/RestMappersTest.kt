import common.JsonValue
import features.jobs.Delivery
import features.jobs.Schedule
import instantjob.api.generated.models.CreateJobRequest
import instantjob.api.generated.models.CronSchedule
import instantjob.api.generated.models.HttpCallbackDelivery
import instantjob.api.generated.models.HttpCallbackDeliveryMethod
import instantjob.api.generated.models.HttpCallbackDeliveryParametersType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import presentation.rest.toDomain
import presentation.rest.toDto
import kotlin.test.Test
import kotlin.test.assertEquals

class RestMappersTest {
    @Test
    fun generatedRequestMapsToIndependentDomainTypes() {
        val request = CreateJobRequest(
            targetService = "worker",
            taskName = "report",
            idempotencyKey = "report-1",
            parameters = mapOf(
                "values" to JsonArray(listOf(JsonPrimitive(42), JsonPrimitive("text"), JsonNull)),
            ),
            schedule = CronSchedule(expression = "0 */5 * * * *", timezone = "Europe/Moscow"),
            delivery = HttpCallbackDelivery(
                method = HttpCallbackDeliveryMethod.POST,
                parametersType = HttpCallbackDeliveryParametersType.JSON,
                url = "https://worker.example/jobs",
            ),
        )

        val domain = request.toDomain()

        assertEquals(Schedule.Cron("0 */5 * * * *", "Europe/Moscow"), domain.schedule)
        assertEquals(Delivery.HttpCallback.Method.POST, (domain.delivery as Delivery.HttpCallback).method)
        assertEquals(
            JsonValue.ArrayValue(
                listOf(JsonValue.NumberValue("42"), JsonValue.StringValue("text"), JsonValue.Null)
            ),
            domain.parameters["values"],
        )
        assertEquals(request.parameters, domain.parameters.toDto())
    }
}

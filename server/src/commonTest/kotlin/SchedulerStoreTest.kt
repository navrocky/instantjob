import instantjob.api.generated.models.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import scheduler.ApiException
import scheduler.SchedulerStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchedulerStoreTest {
    @Test
    fun immediatePullJobIsIdempotentAndCompletes() = runBlocking {
        val store = SchedulerStore()
        val request = CreateJobRequest(
            targetService = "worker",
            taskName = "send-report",
            idempotencyKey = "report-42",
            parameters = mapOf("reportId" to JsonPrimitive(42)),
            schedule = ImmediateSchedule(),
            delivery = PullDelivery(),
        )

        val created = store.create("producer", request)
        val replay = store.create("producer", request)
        assertTrue(created.created)
        assertFalse(replay.created)
        assertEquals(created.job.id, replay.job.id)

        val claimed = store.claim("worker", null, 1, 0).single()
        assertEquals(created.job.id, claimed.execution.jobId)
        val (completed, attempts) = store.complete(
            "worker",
            claimed.execution.id,
            CompleteExecutionRequest(
                leaseToken = claimed.leaseToken,
                outcome = CompleteExecutionRequestOutcome.SUCCEEDED,
                result = mapOf("accepted" to JsonPrimitive(true)),
            ),
        )
        assertEquals(ExecutionStatus.SUCCEEDED, completed.status)
        assertEquals(1, attempts.size)
        assertEquals(JobStatus.COMPLETED, store.getJob("producer", created.job.id).status)
    }

    @Test
    fun conflictingIdempotencyPayloadIsRejected() = runBlocking {
        val store = SchedulerStore()
        val base = CreateJobRequest(
            targetService = "worker",
            taskName = "task",
            idempotencyKey = "same-key",
            parameters = emptyMap(),
            schedule = ImmediateSchedule(),
            delivery = PullDelivery(),
        )
        store.create("producer", base)

        val error = runCatching { store.create("producer", base.copy(taskName = "other")) }.exceptionOrNull()
        assertTrue(error is ApiException)
        assertEquals("IDEMPOTENCY_CONFLICT", error.code)
    }

    @Test
    fun intervalHonoursExecutionLimit() = runBlocking {
        val store = SchedulerStore()
        val created = store.create(
            "producer",
            CreateJobRequest(
                targetService = "worker",
                taskName = "twice",
                idempotencyKey = "twice",
                parameters = emptyMap(),
                schedule = IntervalSchedule(everySeconds = 1, maxExecutions = 2),
                delivery = PullDelivery(),
            ),
        )
        val first = store.claim("worker", null, 1, 0).single()
        store.complete(
            "worker",
            first.execution.id,
            CompleteExecutionRequest(
                leaseToken = first.leaseToken,
                outcome = CompleteExecutionRequestOutcome.SUCCEEDED,
            ),
        )
        assertEquals(JobStatus.ACTIVE, store.getJob("producer", created.job.id).status)
    }
}

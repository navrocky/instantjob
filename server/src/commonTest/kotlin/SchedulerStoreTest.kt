import common.DomainException
import common.JsonValue
import features.executions.CompleteExecutionCommand
import features.executions.CompletionOutcome
import features.executions.ExecutionStatus
import features.jobs.CreateJobCommand
import features.jobs.Delivery
import features.jobs.JobStatus
import features.jobs.Schedule
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchedulerServiceTest {
    @Test
    fun immediatePullJobIsIdempotentAndCompletes() = runBlocking {
        val store = schedulerService()
        val request = CreateJobCommand(
            targetService = "worker",
            taskName = "send-report",
            idempotencyKey = "report-42",
            parameters = mapOf("reportId" to JsonValue.NumberValue("42")),
            schedule = Schedule.Immediate,
            delivery = Delivery.Pull,
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
            CompleteExecutionCommand(
                leaseToken = claimed.leaseToken,
                outcome = CompletionOutcome.SUCCEEDED,
                result = mapOf("accepted" to JsonValue.BooleanValue(true)),
            ),
        )
        assertEquals(ExecutionStatus.SUCCEEDED, completed.status)
        assertEquals(1, attempts.size)
        assertEquals(JobStatus.COMPLETED, store.getJob("producer", created.job.id).status)
    }

    @Test
    fun conflictingIdempotencyPayloadIsRejected() = runBlocking {
        val store = schedulerService()
        val base = CreateJobCommand(
            targetService = "worker",
            taskName = "task",
            idempotencyKey = "same-key",
            parameters = emptyMap(),
            schedule = Schedule.Immediate,
            delivery = Delivery.Pull,
        )
        store.create("producer", base)

        val error = runCatching { store.create("producer", base.copy(taskName = "other")) }.exceptionOrNull()
        assertTrue(error is DomainException)
        assertEquals("IDEMPOTENCY_CONFLICT", error.code)
    }

    @Test
    fun intervalHonoursExecutionLimit() = runBlocking {
        val store = schedulerService()
        val created = store.create(
            "producer",
            CreateJobCommand(
                targetService = "worker",
                taskName = "twice",
                idempotencyKey = "twice",
                parameters = emptyMap(),
                schedule = Schedule.Interval(everySeconds = 1, maxExecutions = 2),
                delivery = Delivery.Pull,
            ),
        )
        val first = store.claim("worker", null, 1, 0).single()
        store.complete(
            "worker",
            first.execution.id,
            CompleteExecutionCommand(
                leaseToken = first.leaseToken,
                outcome = CompletionOutcome.SUCCEEDED,
            ),
        )
        assertEquals(JobStatus.ACTIVE, store.getJob("producer", created.job.id).status)
    }
}

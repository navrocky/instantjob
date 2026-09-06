import features.executions.CompleteExecutionCommand
import features.executions.CompletionOutcome
import features.executions.ExecutionStatus
import features.jobs.CreateJobCommand
import features.jobs.Delivery
import features.jobs.Schedule
import features.jobs.RetryPolicy
import common.JsonValue
import infrastructure.config.getEnvVal
import io.github.kormium.DelicateKormiumApi
import io.github.kormium.autocommit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import infrastructure.database.DatabaseConfig
import infrastructure.database.PostgresDatabase
import features.scheduling.PostgresSchedulerTransactionManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

class PostgresSchedulerServiceTest {
    private val enabled = getEnvVal("INSTANTJOB_TEST_POSTGRES") == "true"

    @OptIn(DelicateKormiumApi::class)
    @Test
    fun appliesVersionedKormiumMigrations() {
        if (!enabled) return
        database().use { database ->
            val appliedIds = database.handle.autocommit {
                execute(
                    "SELECT id FROM kormium_migrations ORDER BY idx",
                    emptyMap(),
                    emptyList(),
                ) { it.getString(0)!! }
            }

            assertEquals(
                listOf("001-create-jobs", "002-create-executions", "003-create-attempts"),
                appliedIds,
            )
        }
    }

    @Test
    fun persistsAcrossRepositoryRestart() = runBlocking {
        if (!enabled) return@runBlocking
        database().use { firstDb ->
            clean(firstDb)
            val first = service(firstDb)
            val created = first.create("producer", request("persist"))
            database().use { secondDb ->
                val restored = service(secondDb).getJob("producer", created.job.id)
                assertEquals(created.job.id, restored.id)
            }
        }
    }

    @Test
    fun concurrentCreateAndClaimRemainUnique() = runBlocking {
        if (!enabled) return@runBlocking
        database().use { db ->
            clean(db)
            val repositories = List(4) { service(db) }
            val creates = coroutineScope {
                repositories.map { repository ->
                    async {
                        repository.create(
                            "producer",
                            request("shared")
                        )
                    }
                }.awaitAll()
            }
            assertEquals(1, creates.count { it.created })
            assertEquals(1, creates.map { it.job.id }.distinct().size)

            val claims = coroutineScope {
                repositories.map { repository -> async { repository.claim("worker", null, 1, 0) } }.awaitAll().flatten()
            }
            assertEquals(1, claims.size)
            assertEquals(ExecutionStatus.LEASED, claims.single().execution.status)
        }
    }

    @Test
    fun ormRoundTripsComplexJobBody() = runBlocking {
        if (!enabled) return@runBlocking
        database().use { db ->
            clean(db)
            val service = service(db)
            val request = CreateJobCommand(
                targetService = "callback-worker",
                taskName = "complex-task",
                idempotencyKey = "complex-body",
                parameters = mapOf(
                    "nested" to JsonValue.ObjectValue(
                        mapOf("number" to JsonValue.NumberValue("42.50"), "enabled" to JsonValue.BooleanValue(true))
                    )
                ),
                schedule = Schedule.At(Clock.System.now() + 1.hours),
                delivery = Delivery.HttpCallback(
                    method = Delivery.HttpCallback.Method.POST,
                    url = "https://worker.example/tasks",
                    parametersType = Delivery.HttpCallback.ParametersType.JSON,
                    secretRef = "worker-secret",
                    retryableStatuses = listOf(409, 429),
                ),
                retryPolicy = RetryPolicy(maxAttempts = 7, jitter = 0.1),
            )

            val created = service.create("producer", request).job
            val restored = service.getJob("producer", created.id)

            assertEquals(created.id, restored.id)
            assertEquals(request.parameters, restored.parameters)
            assertEquals(request.schedule, restored.schedule)
            assertEquals(request.delivery, restored.delivery)
            assertEquals(request.retryPolicy, restored.retryPolicy)
        }
    }

    @Test
    fun heartbeatAndCompletionValidateLease() = runBlocking {
        if (!enabled) return@runBlocking
        database().use { db ->
            clean(db)
            val repository = service(db)
            repository.create("producer", request("lease"))
            val claimed = repository.claim("worker", null, 1, 0).single()
            val extended = repository.heartbeat("worker", claimed.execution.id, claimed.leaseToken)
            assertTrue(extended > claimed.leaseUntil)
            val completed = repository.complete(
                "worker", claimed.execution.id,
                CompleteExecutionCommand(
                    leaseToken = claimed.leaseToken,
                    outcome = CompletionOutcome.SUCCEEDED
                ),
            ).first
            assertEquals(ExecutionStatus.SUCCEEDED, completed.status)
            assertFalse(repository.claim("worker", null, 1, 0).isNotEmpty())
        }
    }

    @Test
    fun claimSurvivesRepositoryRestartAndExpiredLeaseIsRecovered() = runBlocking {
        if (!enabled) return@runBlocking
        database().use { firstDb ->
            clean(firstDb)
            val first = service(firstDb, leaseSeconds = 0)
            first.create("producer", request("restart-claim"))
            val claimed = first.claim("worker", null, 1, 0).single()

            database().use { secondDb ->
                val second = service(secondDb)
                second.tick()
                val recovered = second.getExecution("worker", claimed.execution.id)
                assertEquals(ExecutionStatus.PENDING, recovered.first.status)
                assertEquals("Lease expired", recovered.first.error)
                assertEquals("Lease expired", recovered.second.single().error)
            }
        }
    }

    private fun request(key: String) = CreateJobCommand(
        targetService = "worker", taskName = "task", idempotencyKey = key,
        parameters = emptyMap(), schedule = Schedule.Immediate, delivery = Delivery.Pull,
    )

    private fun database() =
        PostgresDatabase(DatabaseConfig("127.0.0.1", 5432, "instantjob", "instantjob", "test-password", 10))

    private fun service(database: PostgresDatabase, leaseSeconds: Long = 60) =
        schedulerService(PostgresSchedulerTransactionManager(database), leaseSeconds)

    @OptIn(DelicateKormiumApi::class)
    private fun clean(database: PostgresDatabase) {
        database.handle.autocommit {
            executeUpdate("DELETE FROM attempts", emptyMap(), emptyList())
            executeUpdate("DELETE FROM executions", emptyMap(), emptyList())
            executeUpdate("DELETE FROM jobs", emptyMap(), emptyList())
        }
    }
}

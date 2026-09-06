import common.DelayProvider
import features.scheduling.DefaultSchedulerService
import features.scheduling.MockedSchedulerTransactionManager
import features.scheduling.ScheduleCalculator
import features.scheduling.SchedulerTransactionManager
import kotlinx.coroutines.delay
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
fun schedulerService(
    transactions: SchedulerTransactionManager = MockedSchedulerTransactionManager(),
    leaseSeconds: Long = 60,
) = DefaultSchedulerService(
    transactions = transactions,
    clock = Clock.System,
    schedulePlanner = ScheduleCalculator,
    delayProvider = DelayProvider { delay(it) },
    idGenerator = { Uuid.random().toString() },
    randomFactor = Random::nextDouble,
    leaseSeconds = leaseSeconds,
)

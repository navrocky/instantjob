import instantjob.api.generated.models.CronSchedule
import scheduler.ApiException
import scheduler.ScheduleCalculator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class ScheduleCalculatorTest {
    @Test
    fun sixFieldCronUsesSeconds() {
        val next = ScheduleCalculator.firstRunAt(
            CronSchedule(expression = "15 30 10 * * *", timezone = "UTC"),
            Instant.parse("2026-08-30T10:30:14Z"),
        )

        assertEquals(Instant.parse("2026-08-30T10:30:15Z"), next)
    }

    @Test
    fun cronUsesConfiguredIanaTimezone() {
        val next = ScheduleCalculator.firstRunAt(
            CronSchedule(expression = "0 0 12 * * *", timezone = "Europe/Moscow"),
            Instant.parse("2026-08-30T08:59:59Z"),
        )

        assertEquals(Instant.parse("2026-08-30T09:00:00Z"), next)
    }

    @Test
    fun cronSupportsListsRangesAndSteps() {
        val next = ScheduleCalculator.firstRunAt(
            CronSchedule(expression = "*/10 0,30 9-10 * * *", timezone = "UTC"),
            Instant.parse("2026-08-30T09:00:00Z"),
        )

        assertEquals(Instant.parse("2026-08-30T09:00:10Z"), next)
    }

    @Test
    fun cronRequiresExactlySixFields() {
        val error = assertFailsWith<ApiException> {
            ScheduleCalculator.firstRunAt(
                CronSchedule(expression = "0 0 12 * *", timezone = "UTC"),
                Instant.parse("2026-08-30T00:00:00Z"),
            )
        }

        assertEquals("INVALID_CRON", error.code)
    }
}

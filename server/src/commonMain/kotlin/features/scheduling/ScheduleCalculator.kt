package features.scheduling

import common.DomainErrorKind
import common.DomainException
import features.jobs.Schedule
import kotlinx.datetime.*
import kotlin.math.floor
import kotlin.time.Instant

/** Calculates execution instants from domain schedules. */
object ScheduleCalculator : SchedulePlanner {
    override fun firstRunAt(schedule: Schedule, now: Instant): Instant = when (schedule) {
        Schedule.Immediate -> now
        is Schedule.At -> maxOf(schedule.runAt, now)
        is Schedule.Interval -> maxOf(schedule.startAt ?: now, now)
        is Schedule.Cron -> CronExpression(schedule.expression).nextAfter(now, schedule.timezone)
    }

    override fun nextRunAt(schedule: Schedule, scheduledAt: Instant, now: Instant, executionCount: Long): Instant? {
        val max = when (schedule) {
            is Schedule.Interval -> schedule.maxExecutions
            is Schedule.Cron -> schedule.maxExecutions
            else -> null
        }
        if (max != null && executionCount >= max) return null
        return when (schedule) {
            Schedule.Immediate, is Schedule.At -> null
            is Schedule.Interval -> {
                val interval = schedule.everySeconds
                val elapsedSeconds = ((now - scheduledAt).inWholeSeconds).coerceAtLeast(0)
                val intervalsToSkip = floor(elapsedSeconds.toDouble() / interval).toLong() + 1
                val candidate = Instant.fromEpochSeconds(scheduledAt.epochSeconds + intervalsToSkip * interval)
                val bounded = schedule.endAt
                if (bounded != null && candidate > bounded) null else maxOf(candidate, now)
            }

            is Schedule.Cron -> {
                val candidate =
                    CronExpression(schedule.expression).nextAfter(maxOf(scheduledAt, now), schedule.timezone)
                if (schedule.endAt != null && candidate > schedule.endAt) null else candidate
            }
        }
    }
}

private class CronExpression(expression: String) {
    private data class Field(val values: Set<Int>, val wildcard: Boolean)

    private val parts = expression.trim().split(Regex("\\s+"))
    private val seconds: Field
    private val minutes: Field
    private val hours: Field
    private val days: Field
    private val months: Field
    private val weekdays: Field

    init {
        if (parts.size != 6) invalid("Cron expression must contain 6 fields")
        seconds = parse(parts[0], 0, 59)
        minutes = parse(parts[1], 0, 59)
        hours = parse(parts[2], 0, 23)
        days = parse(parts[3], 1, 31)
        months = parse(parts[4], 1, 12)
        weekdays = parse(parts[5], 0, 7).let { field ->
            field.copy(values = field.values.map { if (it == 7) 0 else it }.toSet())
        }
    }

    fun nextAfter(after: Instant, timezoneName: String): Instant {
        val timezone = try {
            TimeZone.of(timezoneName)
        } catch (_: IllegalArgumentException) {
            invalid("Unknown IANA timezone: $timezoneName")
        }
        val startDate = after.toLocalDateTime(timezone).date
        for (dayOffset in 0..(366 * 5)) {
            val date = startDate.plus(dayOffset, DateTimeUnit.DAY)
            if (date.month.ordinal + 1 !in months.values) continue
            val dayMatches = date.day in days.values
            val cronWeekday = date.dayOfWeek.isoDayNumber % 7
            val weekdayMatches = cronWeekday in weekdays.values
            val calendarMatches = when {
                days.wildcard -> weekdayMatches
                weekdays.wildcard -> dayMatches
                else -> dayMatches || weekdayMatches
            }
            if (!calendarMatches) continue
            for (hour in hours.values.sorted()) for (minute in minutes.values.sorted()) for (second in seconds.values.sorted()) {
                val local = LocalDateTime(date, LocalTime(hour, minute, second))
                val candidate = local.toInstant(timezone)
                if (candidate > after && candidate.toLocalDateTime(timezone) == local) return candidate
            }
        }
        invalid("Cron expression has no occurrence in the next five years")
    }

    private fun parse(text: String, min: Int, max: Int): Field {
        val wildcard = text == "*"
        val values = mutableSetOf<Int>()
        text.split(',').forEach { segment ->
            val stepParts = segment.split('/')
            if (stepParts.size > 2) invalid("Invalid cron field: $text")
            val step = stepParts.getOrNull(1)?.toIntOrNull() ?: 1
            if (step <= 0) invalid("Cron step must be positive")
            val rangeText = stepParts[0]
            val range = when {
                rangeText == "*" -> min..max
                '-' in rangeText -> {
                    val bounds =
                        rangeText.split('-', limit = 2).map { it.toIntOrNull() ?: invalid("Invalid cron field: $text") }
                    bounds[0]..bounds[1]
                }

                else -> {
                    val value = rangeText.toIntOrNull() ?: invalid("Invalid cron field: $text")
                    value..value
                }
            }
            if (range.first < min || range.last > max || range.first > range.last) invalid("Cron value is out of range: $text")
            range.step(step).forEach(values::add)
        }
        return Field(values, wildcard)
    }

    private fun invalid(message: String): Nothing =
        throw DomainException(DomainErrorKind.BAD_REQUEST, "INVALID_CRON", message)
}

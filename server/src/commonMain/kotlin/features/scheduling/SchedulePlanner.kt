package features.scheduling

import features.jobs.Schedule
import kotlin.time.Instant

/** Calculates execution instants for supported job schedules. */
interface SchedulePlanner {
    /** Returns the first execution time for [schedule] relative to [now]. */
    fun firstRunAt(schedule: Schedule, now: Instant): Instant

    /** Returns the next execution time after [scheduledAt], or null when the schedule is exhausted. */
    fun nextRunAt(schedule: Schedule, scheduledAt: Instant, now: Instant, executionCount: Long): Instant?
}

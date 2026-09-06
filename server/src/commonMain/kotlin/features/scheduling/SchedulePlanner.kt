package features.scheduling

import features.jobs.Schedule
import kotlin.time.Instant

interface SchedulePlanner {
    fun firstRunAt(schedule: Schedule, now: Instant): Instant
    fun nextRunAt(schedule: Schedule, scheduledAt: Instant, now: Instant, executionCount: Long): Instant?
}

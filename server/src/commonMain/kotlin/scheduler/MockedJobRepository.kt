package scheduler

import common.Context

class MockedJobRepository : JobRepository {
    private val jobs = mutableMapOf<JobId, Job>()

    override fun getById(ctx: Context, id: JobId): Job =
        jobs[id] ?: throw NoSuchElementException("<fc5227f5> Job could not be found: id=$id")

    override fun findByIdempotencyKey(ctx: Context, key: String): Job? =
        jobs.asSequence().find { it.value.idempotencyKey == key }?.value

    override fun save(ctx: Context, job: Job) {
        jobs[job.id] = job
    }
}
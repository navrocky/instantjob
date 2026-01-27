package scheduler

import common.Context
import common.generateId
import log.logger
import kotlin.time.Clock

private val logger = JobSchedulerServiceImpl::class.logger

class JobSchedulerServiceImpl(
    private val jobRepository: JobRepository
) : JobSchedulerService {

    override suspend fun enqueueJob(ctx: Context, jobRequest: JobRequest): JobId {
        if (jobRequest.idempotencyKey != null) {
            val existingJob = jobRepository.findByIdempotencyKey(ctx, jobRequest.idempotencyKey)
            if (existingJob != null)
                return existingJob.id
        }

        val now = Clock.System.now()

        val job = Job(
            id = generateId(),
            status = Job.Status.ENQUEUED,
            idempotencyKey = jobRequest.idempotencyKey,
            parameters = jobRequest.parameters,
            callback = jobRequest.callback,
            retry = jobRequest.retry,
            schedule = jobRequest.schedule,
            createdAt = now,
            updatedAt = null,
        )

        logger.debug { "$ctx <07e63b5e> Enqueueing job: job=$job" }

        jobRepository.save(ctx, job)

        return job.id
    }

    override suspend fun getJobById(ctx: Context, id: JobId): Job {
        logger.debug { "$ctx <710c0871> Get job by id: id=$id" }
        return jobRepository.getById(ctx, id)
    }

    override suspend fun cancelJob(ctx: Context, id: JobId) {
        logger.debug { "$ctx <2ac34937> Cancel job: id=$id" }
        val job = jobRepository.getById(ctx, id)
        if (job.status != Job.Status.ENQUEUED)
            throw IllegalStateException("<18a9858a> Job cannot be cancelled: id=$id, status=${job.status}")
        jobRepository.save(ctx, job.copy(status = Job.Status.CANCELLED))
    }
}
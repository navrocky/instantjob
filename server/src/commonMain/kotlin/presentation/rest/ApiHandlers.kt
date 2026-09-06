package presentation.rest

// REST presentation adapters generated from the OpenAPI handler contracts.

import features.auth.AuthenticationService
import instantjob.api.generated.apis.ExecutionsApiHandler
import instantjob.api.generated.apis.JobsApiHandler
import instantjob.api.generated.apis.SystemApiHandler
import instantjob.api.generated.apis.WorkersApiHandler
import instantjob.api.generated.models.*
import features.scheduling.ExecutionService
import features.scheduling.JobService
import features.scheduling.SchedulerMaintenanceService

class JobsHandler(
    private val auth: AuthenticationService,
    private val store: JobService,
) : JobsApiHandler {
    override suspend fun createJob(apiKey: String, body: CreateJobRequest): CreateJobResponse {
        val principal = auth.authenticate(apiKey)
        val result = store.create(principal.serviceName, body.toDomain())
        return CreateJobResponse(code = "SUCCESS", created = result.created, job = result.job.toDto())
    }

    override suspend fun listJobs(
        apiKey: String,
        targetService: String?,
        taskName: String?,
        status: JobStatus?,
        cursor: String?,
        limit: Int?,
    ): ListJobsResponse {
        val principal = auth.authenticate(apiKey)
        val (items, next) = store.listJobs(
            principal.serviceName, targetService, taskName, status?.toDomain(), cursor, limit ?: 50,
        )
        return ListJobsResponse(code = "SUCCESS", items = items.map { it.toDto() }, nextCursor = next)
    }

    override suspend fun getJob(jobId: String, apiKey: String): GetJobResponse {
        val principal = auth.authenticate(apiKey)
        return GetJobResponse(code = "SUCCESS", job = store.getJob(principal.serviceName, jobId).toDto())
    }

    override suspend fun cancelJob(jobId: String, apiKey: String): GetJobResponse {
        val principal = auth.authenticate(apiKey)
        return GetJobResponse(code = "SUCCESS", job = store.cancel(principal.serviceName, jobId).toDto())
    }
}

class ExecutionsHandler(
    private val auth: AuthenticationService,
    private val store: ExecutionService,
) : ExecutionsApiHandler {
    override suspend fun getExecution(executionId: String, apiKey: String): GetExecutionResponse {
        val principal = auth.authenticate(apiKey)
        val (execution, attempts) = store.getExecution(principal.serviceName, executionId)
        return GetExecutionResponse(
            code = "SUCCESS", execution = execution.toDto(), attempts = attempts.map { it.toDto() },
        )
    }

    override suspend fun listJobExecutions(
        jobId: String,
        apiKey: String,
        cursor: String?,
        limit: Int?
    ): ListExecutionsResponse {
        val principal = auth.authenticate(apiKey)
        val (items, next) = store.listExecutions(principal.serviceName, jobId, cursor, limit ?: 50)
        return ListExecutionsResponse(code = "SUCCESS", items = items.map { it.toDto() }, nextCursor = next)
    }
}

class WorkersHandler(
    private val auth: AuthenticationService,
    private val store: ExecutionService,
) : WorkersApiHandler {
    override suspend fun claimExecutions(apiKey: String, body: ClaimExecutionsRequest): ClaimExecutionsResponse {
        val principal = auth.authenticate(apiKey)
        val items = store.claim(
            targetService = principal.serviceName,
            taskNames = body.taskNames,
            maxItems = body.maxItems ?: 1,
            waitSeconds = body.waitSeconds ?: 30,
        )
        return ClaimExecutionsResponse(code = "SUCCESS", items = items.map { it.toDto() })
    }

    override suspend fun heartbeatExecution(executionId: String, apiKey: String, body: LeaseRequest): LeaseResponse {
        val principal = auth.authenticate(apiKey)
        return LeaseResponse(
            code = "SUCCESS",
            leaseUntil = store.heartbeat(principal.serviceName, executionId, body.leaseToken)
        )
    }

    override suspend fun completeExecution(
        executionId: String,
        apiKey: String,
        body: CompleteExecutionRequest
    ): GetExecutionResponse {
        val principal = auth.authenticate(apiKey)
        val (execution, attempts) = store.complete(principal.serviceName, executionId, body.toDomain())
        return GetExecutionResponse(
            code = "SUCCESS", execution = execution.toDto(), attempts = attempts.map { it.toDto() },
        )
    }
}

class SystemHandler(private val store: SchedulerMaintenanceService) : SystemApiHandler {
    override suspend fun liveness(): HealthResponse = HealthResponse(HealthResponseStatus.UP)

    override suspend fun readiness(): HealthResponse =
        if (store.isReady()) HealthResponse(HealthResponseStatus.UP) else error("Store is not ready")
}

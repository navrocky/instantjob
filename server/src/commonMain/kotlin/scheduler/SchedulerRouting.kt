package scheduler

import common.JsonSuccessResponse
import common.getContext
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class EnqueueJobResponse(
    val id: JobId
) : JsonSuccessResponse()

@Serializable
data class GetJobResponse(
    val job: Job
) : JsonSuccessResponse()

fun Routing.scheduler(jobSchedulerService: JobSchedulerService) {
    get("/jobs/{id}") {
        val id = call.parameters["id"] ?: throw BadRequestException("<eb6482d8> ID is required")
        val job = jobSchedulerService.getJobById(getContext(), id)
        call.respond(GetJobResponse(job))
    }
    post("/jobs") {
        val job = call.receive<JobRequest>()
        val id = jobSchedulerService.enqueueJob(getContext(), job)
        call.respond(EnqueueJobResponse(id))
    }
    delete("/jobs/{id}") {
        val id = call.parameters["id"] ?: throw BadRequestException("<69601341> ID is required")
        jobSchedulerService.cancelJob(getContext(), id)
        call.respond(JsonSuccessResponse())
    }
}
import common.JsonErrorResponse
import common.KtorLogger
import config.Config
import config.EnvConfigBackend
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import log.LogLevel
import log.LoggerFactory
import log.logger
import scheduler.JobSchedulerServiceImpl
import scheduler.MockedJobRepository
import scheduler.scheduler

class Main

val logger = Main::class.logger

class ErrorClass(val status: HttpStatusCode, val code: String)

fun Throwable.collectCauseMessages(): List<String>? =
    cause?.let { listOf(it.message ?: "<No message>") + (it.collectCauseMessages() ?: emptyList()) }

fun commonMain() {
    try {
        val envConfigBackend = EnvConfigBackend(prefix = "INSTANTJOB_")
        val config = Config(backends = listOf(envConfigBackend))

        LoggerFactory.logLevel = config.getOrDefault("logLevel", "DEBUG").let { LogLevel.valueOf(it) }

        val host = config.getOrDefault("host", "0.0.0.0")
        val port = config.getOrDefault("port", "8080").toInt()

        val jobRepository = MockedJobRepository()
        val jobSchedulerService = JobSchedulerServiceImpl(
            jobRepository = jobRepository
        )

        embeddedServer(CIO, serverConfig(applicationEnvironment {
            log = KtorLogger("Ktor")

        }) {
            module {
                install(ContentNegotiation) {
                    json()
                }
                install(StatusPages) {
                    exception<Throwable> { call, cause ->
                        val errorClass = when (cause) {
                            is BadRequestException -> ErrorClass(HttpStatusCode.BadRequest, "BAD_REQUEST")
                            is NoSuchElementException -> ErrorClass(HttpStatusCode.NotFound, "NOT_FOUND")
                            else -> ErrorClass(HttpStatusCode.InternalServerError, "INTERNAL_SERVER_ERROR")
                        }
                        call.respond(
                            errorClass.status,
                            JsonErrorResponse(
                                code = errorClass.code,
                                message = cause.message ?: "Unknown error",
                                causedBy = cause.collectCauseMessages()
                            )
                        )
                    }
                }
                routing {
                    scheduler(
                        jobSchedulerService = jobSchedulerService
                    )
                }.apply {
                    logger.info {
                        "<00d2d4f8> Configured routes:\n" +
                                getAllRoutes()
                                    .mapIndexed { index, node -> "  ${index + 1}) ${node.selector} ${node.path}" }
                                    .joinToString("\n")
                    }
                }
            }
        }) {
            reuseAddress = true
            connectors.addAll(
                arrayOf(
                    EngineConnectorBuilder().apply {
                        this.port = port
                        this.host = host
                    }
                ))
        }.apply {
            this.application
        }.start(wait = true)
    } catch (e: Throwable) {
        logger.error(e) { "<f785a854> Application start failed" }
    }
}

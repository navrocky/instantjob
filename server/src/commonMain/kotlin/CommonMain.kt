import api.ExecutionsHandler
import api.JobsHandler
import api.SystemHandler
import api.WorkersHandler
import auth.AuthRegistry
import common.KtorLogger
import config.Config
import config.EnvConfigBackend
import instantjob.api.generated.MissingAuthenticationException
import instantjob.api.generated.apis.ExecutionsApiRoutes
import instantjob.api.generated.apis.JobsApiRoutes
import instantjob.api.generated.apis.SystemApiRoutes
import instantjob.api.generated.apis.WorkersApiRoutes
import instantjob.api.generated.models.ErrorResponse
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
import kotlinx.coroutines.launch
import log.LogLevel
import log.LoggerFactory
import log.logger
import persistence.DatabaseConfig
import persistence.PostgresDatabase
import persistence.PostgresSchedulerRepository
import scheduler.ApiException
import scheduler.CallbackDispatcher
import scheduler.CallbackSecretRegistry

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

        val authRegistry = AuthRegistry.parse(config.getOrDefault("apiKeys", "dev-key:dev"))
        val postgresDatabase = PostgresDatabase(
            DatabaseConfig(
                host = config.getOrDefault("database.host", "127.0.0.1"),
                port = config.getOrDefault("database.port", "5432").toInt(),
                name = config.getOrDefault("database.name", "instantjob"),
                user = config.getOrDefault("database.user", "instantjob"),
                password = config.getOrDefault("database.password", "test-password"),
                poolSize = config.getOrDefault("database.poolSize", "10").toInt(),
            )
        )
        postgresDatabase.migrate()
        val schedulerStore = PostgresSchedulerRepository(postgresDatabase)
        val callbackSecrets = CallbackSecretRegistry.parse(config.getOrDefault("callbackSecrets", ""))
        val jobsHandler = JobsHandler(authRegistry, schedulerStore)
        val executionsHandler = ExecutionsHandler(authRegistry, schedulerStore)
        val workersHandler = WorkersHandler(authRegistry, schedulerStore)
        val systemHandler = SystemHandler(schedulerStore)

        embeddedServer(CIO, serverConfig(applicationEnvironment {
            log = KtorLogger("Ktor")

        }) {
            module {
                val callbackDispatcher = CallbackDispatcher(
                    repository = schedulerStore,
                    secrets = callbackSecrets,
                    allowHttp = config.getOrDefault("callbackAllowHttp", "false").toBooleanStrict(),
                )
                val callbackJob = launch { callbackDispatcher.runForever() }
                monitor.subscribe(ApplicationStopped) {
                    callbackJob.cancel()
                    callbackDispatcher.close()
                    postgresDatabase.close()
                }
                install(ContentNegotiation) {
                    json()
                }
                install(StatusPages) {
                    exception<Throwable> { call, cause ->
                        val errorClass = when (cause) {
                            is ApiException -> ErrorClass(cause.status, cause.code)
                            is MissingAuthenticationException -> ErrorClass(HttpStatusCode.Unauthorized, "UNAUTHORIZED")
                            is BadRequestException -> ErrorClass(HttpStatusCode.BadRequest, "BAD_REQUEST")
                            is IllegalArgumentException -> ErrorClass(HttpStatusCode.BadRequest, "BAD_REQUEST")
                            is NoSuchElementException -> ErrorClass(HttpStatusCode.NotFound, "NOT_FOUND")
                            else -> ErrorClass(HttpStatusCode.InternalServerError, "INTERNAL_SERVER_ERROR")
                        }
                        call.respond(
                            errorClass.status,
                            ErrorResponse(
                                code = errorClass.code,
                                message = cause.message ?: "Unknown error",
                                requestId = call.request.headers["X-Request-Id"] ?: "unknown",
                            )
                        )
                    }
                }
                routing {
                    JobsApiRoutes(this, jobsHandler)
                    ExecutionsApiRoutes(this, executionsHandler)
                    WorkersApiRoutes(this, workersHandler)
                    SystemApiRoutes(this, systemHandler)
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

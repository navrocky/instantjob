import presentation.rest.ExecutionsHandler
import presentation.rest.JobsHandler
import presentation.rest.SystemHandler
import presentation.rest.WorkersHandler
import features.auth.DefaultAuthenticationService
import infrastructure.config.Config
import infrastructure.config.EnvConfigBackend
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
import infrastructure.log.LogLevel
import infrastructure.log.LoggerFactory
import infrastructure.log.logger
import infrastructure.database.DatabaseConfig
import infrastructure.database.PostgresDatabase
import features.scheduling.PostgresSchedulerTransactionManager
import features.auth.ConfiguredApiKeyRepository
import common.DomainErrorKind
import common.DomainException
import common.DelayProvider
import features.callbacks.CallbackDispatcher
import features.scheduling.DefaultSchedulerService
import features.scheduling.ScheduleCalculator
import infrastructure.http.CallbackSecretRegistry
import infrastructure.http.KtorLogger
import infrastructure.http.KtorCallbackClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class Main

val logger = Main::class.logger

class ErrorClass(val status: HttpStatusCode, val code: String)

fun Throwable.collectCauseMessages(): List<String>? =
    cause?.let { listOf(it.message ?: "<No message>") + (it.collectCauseMessages() ?: emptyList()) }

@OptIn(ExperimentalUuidApi::class)
fun commonMain() {
    try {
        val envConfigBackend = EnvConfigBackend(prefix = "INSTANTJOB_")
        val config = Config(backends = listOf(envConfigBackend))

        LoggerFactory.logLevel = config.getOrDefault("logLevel", "DEBUG").let { LogLevel.valueOf(it) }

        val host = config.getOrDefault("host", "0.0.0.0")
        val port = config.getOrDefault("port", "8080").toInt()

        val authenticationService = DefaultAuthenticationService(
            ConfiguredApiKeyRepository.parse(config.getOrDefault("apiKeys", "dev-key:dev"))
        )
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
        val schedulerStore = DefaultSchedulerService(
            transactions = PostgresSchedulerTransactionManager(postgresDatabase),
            clock = Clock.System,
            schedulePlanner = ScheduleCalculator,
            delayProvider = DelayProvider { delay(it) },
            idGenerator = { Uuid.random().toString() },
            randomFactor = Random::nextDouble,
        )
        val callbackSecrets = CallbackSecretRegistry.parse(config.getOrDefault("callbackSecrets", ""))
        val jobsHandler = JobsHandler(authenticationService, schedulerStore)
        val executionsHandler = ExecutionsHandler(authenticationService, schedulerStore)
        val workersHandler = WorkersHandler(authenticationService, schedulerStore)
        val systemHandler = SystemHandler(schedulerStore)

        embeddedServer(CIO, serverConfig(applicationEnvironment {
            log = KtorLogger("Ktor")

        }) {
            module {
                val callbackDispatcher = CallbackDispatcher(
                    repository = schedulerStore,
                    client = KtorCallbackClient(
                        secrets = callbackSecrets,
                        allowHttp = config.getOrDefault("callbackAllowHttp", "false").toBooleanStrict(),
                    ),
                    delayProvider = DelayProvider { delay(it) },
                    isCancellation = { it is CancellationException },
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
                            is DomainException -> ErrorClass(
                                when (cause.kind) {
                                    DomainErrorKind.BAD_REQUEST -> HttpStatusCode.BadRequest
                                    DomainErrorKind.UNAUTHORIZED -> HttpStatusCode.Unauthorized
                                    DomainErrorKind.NOT_FOUND -> HttpStatusCode.NotFound
                                    DomainErrorKind.FORBIDDEN -> HttpStatusCode.Forbidden
                                    DomainErrorKind.CONFLICT -> HttpStatusCode.Conflict
                                },
                                cause.code,
                            )
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

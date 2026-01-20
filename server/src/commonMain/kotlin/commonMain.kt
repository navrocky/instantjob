import common.KtorLogger
import config.Config
import config.EnvConfigBackend
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import log.LogLevel
import log.LoggerFactory
import log.logger

class Main

val logger = Main::class.logger

fun commonMain() {
    try {
        val envConfigBackend = EnvConfigBackend(prefix = "INSTAJOB_")
        val config = Config(backends = listOf(envConfigBackend))

        LoggerFactory.logLevel = config.getOrDefault("logLevel", "INFO").let { LogLevel.valueOf(it) }

        val host = config.getOrDefault("host", "0.0.0.0")
        val port = config.getOrDefault("port", "8080").toInt()

        embeddedServer(CIO, serverConfig(applicationEnvironment {
            log = KtorLogger("Ktor")
        }) {
            module {

//                install(CallLogging)

                routing {
                    get("/") {
                        logger.info { "<0871e92e> Get /" }
                        call.respondText("Hello World!", ContentType.Text.Plain)
                    }
                }
            }
        }) {
            connectors.addAll(
                arrayOf(
                    EngineConnectorBuilder().apply {
                        this.port = port
                        this.host = host
                    }
                ))
        }.start(wait = true)
    } catch (e: Throwable) {
        logger.error(e) { "<f785a854> Application start failed" }
    }
}

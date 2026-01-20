package common

import io.ktor.util.logging.*
import log.LogLevel
import log.LoggerFactory

class KtorLogger(name: String = "Ktor") : Logger {
    private val logger = LoggerFactory.create(name)

    override val level: io.ktor.util.logging.LogLevel
        get() = when (LoggerFactory.logLevel) {
            LogLevel.TRACE -> io.ktor.util.logging.LogLevel.TRACE
            LogLevel.DEBUG -> io.ktor.util.logging.LogLevel.DEBUG
            LogLevel.INFO -> io.ktor.util.logging.LogLevel.INFO
            LogLevel.WARN -> io.ktor.util.logging.LogLevel.WARN
            LogLevel.ERROR -> io.ktor.util.logging.LogLevel.ERROR
        }

    override fun error(message: String) {
        logger.error { message }
    }

    override fun error(message: String, cause: Throwable) {
        logger.error(cause) { message }
    }

    override fun warn(message: String) {
        logger.warn { message }
    }

    override fun warn(message: String, cause: Throwable) {
        logger.warn(cause) { message }
    }

    override fun info(message: String) {
        logger.info { message }
    }

    override fun info(message: String, cause: Throwable) {
        logger.info(cause) { message }
    }

    override fun debug(message: String) {
        logger.debug { message }
    }

    override fun debug(message: String, cause: Throwable) {
        logger.debug(cause) { message }
    }

    override fun trace(message: String) {
        logger.trace { message }
    }

    override fun trace(message: String, cause: Throwable) {
        logger.trace(cause) { message }
    }
}

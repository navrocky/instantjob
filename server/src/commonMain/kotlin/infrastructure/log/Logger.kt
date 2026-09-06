package infrastructure.log

/** Severity threshold and classification of a log record. */
enum class LogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

interface LogHandler {
    fun isLogLevelEnabled(level: LogLevel): Boolean
    fun write(level: LogLevel, name: String, message: String, cause: Throwable?)
}

class Logger(
    private val name: String,
    private val handler: LogHandler,
) {
    fun trace(cause: Throwable? = null, msg: () -> String) {
        write(LogLevel.TRACE, cause, msg)
    }

    fun debug(cause: Throwable? = null, msg: () -> String) {
        write(LogLevel.DEBUG, cause, msg)
    }

    fun info(cause: Throwable? = null, msg: () -> String) {
        write(LogLevel.INFO, cause, msg)
    }

    fun warn(cause: Throwable? = null, msg: () -> String) {
        write(LogLevel.WARN, cause, msg)
    }

    fun error(cause: Throwable? = null, msg: () -> String) {
        write(LogLevel.ERROR, cause, msg)
    }

    fun write(level: LogLevel, cause: Throwable? = null, msg: () -> String) {
        if (handler.isLogLevelEnabled(level)) {
            handler.write(level, name, msg(), cause)
        }
    }
}

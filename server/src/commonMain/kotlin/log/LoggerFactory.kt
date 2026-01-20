package log

import kotlin.reflect.KClass

object LoggerFactory {

    private class LogHandlerImpl : LogHandler {
        var currentLogLevel: LogLevel = LogLevel.INFO
        val backends = mutableListOf<LoggerBackend>()

        override fun isLogLevelEnabled(level: LogLevel): Boolean = when (currentLogLevel) {
            LogLevel.TRACE -> level == LogLevel.TRACE || level == LogLevel.DEBUG || level == LogLevel.INFO || level == LogLevel.WARN || level == LogLevel.ERROR
            LogLevel.DEBUG -> level == LogLevel.DEBUG || level == LogLevel.INFO || level == LogLevel.WARN || level == LogLevel.ERROR
            LogLevel.INFO -> level == LogLevel.INFO || level == LogLevel.WARN || level == LogLevel.ERROR
            LogLevel.WARN -> level == LogLevel.WARN || level == LogLevel.ERROR
            LogLevel.ERROR -> level == LogLevel.ERROR
        }

        override fun write(level: LogLevel, name: String, message: String, cause: Throwable?) {
            val message = Message(
                level = level,
                name = name,
                message = if (cause != null) "$message\n${exceptionToString(cause)}" else message,
                threadName = getCurrentThreadId().toString(),
            )
            backends.forEach { backend -> backend.write(message) }
        }
    }

    var logStackTraces: Boolean = true

    var logLevel: LogLevel
        get() = logHandler.currentLogLevel
        set(v) {
            logHandler.currentLogLevel = v
        }

    private val logHandler = LogHandlerImpl()

    private fun exceptionToString(e: Throwable): String =
        buildString {
            append("Caused by: ${e::class.qualifiedName}: ${e.message}\n")
            if (logStackTraces) {
                try {
                    append(e.stackTraceToString())
                } catch (e: Throwable) {
                    append("<Cannot get stacktrace: ${e.message}>")
                }
            }
            e.cause?.also { append(exceptionToString(it)) }
        }

    fun create(name: String): Logger = Logger(name, logHandler)

    fun registerBackend(backend: LoggerBackend) {
        logHandler.backends.add(backend)
    }
}

val KClass<*>.logger get() = LoggerFactory.create(this.qualifiedName!!)
import log.ConsoleLoggerBackend
import log.LoggerFactory

fun main(args: Array<String>) {
    LoggerFactory.registerBackend(ConsoleLoggerBackend())
    commonMain()
}

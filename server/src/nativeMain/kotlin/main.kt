import infrastructure.log.ConsoleLoggerBackend
import infrastructure.log.LoggerFactory


fun main(args: Array<String>) {
    LoggerFactory.registerBackend(ConsoleLoggerBackend())
    commonMain()
}

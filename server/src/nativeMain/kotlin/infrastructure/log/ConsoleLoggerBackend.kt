package infrastructure.log

import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyles.bold
import infrastructure.log.LogLevel
import infrastructure.log.LoggerBackend
import infrastructure.log.Message
import kotlin.time.Clock
import kotlin.time.Instant

class ConsoleLoggerBackend : LoggerBackend {
    override fun write(message: Message) {
        val levelColor = when (message.level) {
            LogLevel.TRACE -> gray
            LogLevel.DEBUG -> gray + bold
            LogLevel.INFO -> blue + bold
            LogLevel.WARN -> yellow + bold
            LogLevel.ERROR -> red + bold
        }
        val milliseconds = Clock.System.now().toEpochMilliseconds()

        println(
            "${green(Instant.fromEpochMilliseconds(milliseconds).toString())} [${message.threadName}] ${
                levelColor(
                    message.level.name.padEnd(5)
                )
            } " +
                    "${brightWhite(message.name)} - ${message.message}"
        )
    }
}
package infrastructure.log

class Message(
    val level: LogLevel,
    val name: String,
    val message: String,
    val threadName: String
)

interface LoggerBackend {
    fun write(message: Message)
}
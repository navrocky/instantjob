package config

expect fun getEnvVal(name: String): String?

class EnvConfigBackend(private val prefix: String) : ConfigBackend {
    override fun findValue(path: ConfigPath): String? {
        val name = prefix + path.joinToString("_") { it.uppercase() }
        return getEnvVal(name)
    }
}
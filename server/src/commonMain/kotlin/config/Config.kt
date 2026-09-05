package config

import log.logger

typealias ConfigPath = List<String>

fun ConfigPath.asString() = joinToString(".")

interface ConfigBackend {
    fun findValue(path: ConfigPath): String?
}

private val logger = Config::class.logger

class Config(
    private val backends: List<ConfigBackend>,
    val path: ConfigPath? = null
) {
    fun get(path: String): String {
        val fullPath = getFullPath(path)
        return tryGet(fullPath).also {
            logger.debug { "<a8b97a9c> Get variable: ${fullPath.asString()}=${displayValue(fullPath, it)}" }
        } ?: throw NoSuchElementException("<0514695e> Value required: ${fullPath.asString()}")
    }

    fun getOrDefault(path: String, default: String): String {
        val fullPath = getFullPath(path)
        return (tryGet(getFullPath(path)) ?: default).also {
            logger.debug { "<55193d62> Get variable: ${fullPath.asString()}=${displayValue(fullPath, it)}" }
        }
    }

    fun getGroup(path: String): Config =
        Config(backends, (this.path ?: emptyList()) + parsePath(path))

    private fun getFullPath(path: String): ConfigPath =
        (this.path ?: emptyList()) + parsePath(path)

    private fun tryGet(fullPath: ConfigPath): String? {
        backends.forEach { backend ->
            val v = backend.findValue(fullPath)
            if (v != null) return v
        }

        return null
    }

    private fun parsePath(p: String): ConfigPath =
        p.split(".").toList()

    private fun displayValue(path: ConfigPath, value: String?): String? =
        if (path.lastOrNull()?.lowercase()
                ?.let { it.contains("password") || it.contains("secret") || it.contains("key") } == true
        ) {
            "<redacted>"
        } else {
            value
        }
}

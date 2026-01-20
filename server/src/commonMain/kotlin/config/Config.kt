package config

typealias ConfigPath = List<String>

fun ConfigPath.asString() = joinToString(".")

interface ConfigBackend {
    fun findValue(path: ConfigPath): String?
}

class Config(
    private val backends: List<ConfigBackend>,
    val path: ConfigPath? = null
) {
    fun get(path: String): String {
        val fullPath = getFullPath(path)
        return tryGet(fullPath) ?: throw NoSuchElementException("<0514695e> Value required: ${fullPath.asString()}")
    }

    fun getOrDefault(path: String, default: String): String =
        tryGet(getFullPath(path)) ?: default

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
}
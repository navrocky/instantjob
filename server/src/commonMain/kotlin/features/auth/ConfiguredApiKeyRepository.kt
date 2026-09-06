package features.auth

/** Reads API-key principals from application configuration. */
class ConfiguredApiKeyRepository private constructor(
    private val keys: Map<String, ServicePrincipal>,
) : ApiKeyRepository {
    override fun findByApiKey(apiKey: String): ServicePrincipal? = keys[apiKey]

    companion object {
        /** Parses comma-separated `key:service` entries into a configured repository. */
        fun parse(value: String): ConfiguredApiKeyRepository {
            val entries = value.split(',').filter { it.isNotBlank() }.associate { entry ->
                val parts = entry.trim().split(':', limit = 2)
                require(parts.size == 2 && parts.all { it.isNotBlank() }) {
                    "<d379a0bf> API key registry entries must use key:service format"
                }
                parts[0] to ServicePrincipal(parts[1])
            }
            require(entries.isNotEmpty()) { "<1a7018a6> At least one API key must be configured" }
            return ConfiguredApiKeyRepository(entries)
        }
    }
}

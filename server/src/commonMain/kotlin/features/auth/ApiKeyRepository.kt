package features.auth

interface ApiKeyRepository {
    fun findByApiKey(apiKey: String): ServicePrincipal?
}

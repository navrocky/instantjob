package features.auth

/** In-memory API-key repository used by unit tests. */
class MockedApiKeyRepository(
    private val principals: Map<String, ServicePrincipal> = emptyMap(),
) : ApiKeyRepository {
    override fun findByApiKey(apiKey: String): ServicePrincipal? = principals[apiKey]
}

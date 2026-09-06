package features.auth

class MockedApiKeyRepository(
    private val principals: Map<String, ServicePrincipal> = emptyMap(),
) : ApiKeyRepository {
    override fun findByApiKey(apiKey: String): ServicePrincipal? = principals[apiKey]
}

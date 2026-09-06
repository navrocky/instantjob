package features.auth

import common.DomainErrorKind
import common.DomainException

data class ServicePrincipal(val serviceName: String)

interface AuthenticationService {
    fun authenticate(apiKey: String): ServicePrincipal
}

class DefaultAuthenticationService(
    private val apiKeys: ApiKeyRepository,
) : AuthenticationService {
    override fun authenticate(apiKey: String): ServicePrincipal =
        apiKeys.findByApiKey(apiKey)
            ?: throw DomainException(DomainErrorKind.UNAUTHORIZED, "UNAUTHORIZED", "Invalid API key")
}

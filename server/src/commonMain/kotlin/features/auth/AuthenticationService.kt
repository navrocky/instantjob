package features.auth

import common.DomainErrorKind
import common.DomainException

/** Represents an authenticated service identity. */
data class ServicePrincipal(
    /** Stable name of the authenticated service. */
    val serviceName: String,
)

/** Authenticates services that call the application. */
interface AuthenticationService {
    /** Returns the service principal authenticated by [apiKey] or throws when the key is invalid. */
    fun authenticate(apiKey: String): ServicePrincipal
}

/** Authenticates services against API keys supplied by an [ApiKeyRepository]. */
class DefaultAuthenticationService(
    private val apiKeys: ApiKeyRepository,
) : AuthenticationService {
    override fun authenticate(apiKey: String): ServicePrincipal =
        apiKeys.findByApiKey(apiKey)
            ?: throw DomainException(DomainErrorKind.UNAUTHORIZED, "UNAUTHORIZED", "Invalid API key")
}

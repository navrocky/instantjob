package features.auth

/** Provides service identities indexed by API credentials. */
interface ApiKeyRepository {
    /** Returns the principal associated with [apiKey], or null when the key is unknown. */
    fun findByApiKey(apiKey: String): ServicePrincipal?
}

package auth

import io.ktor.http.*
import scheduler.ApiException

data class ServicePrincipal(val serviceName: String)

class AuthRegistry(private val keys: Map<String, ServicePrincipal>) {
    fun authenticate(apiKey: String): ServicePrincipal =
        keys[apiKey] ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Invalid API key")

    companion object {
        fun parse(value: String): AuthRegistry {
            val entries = value.split(',').filter { it.isNotBlank() }.associate { entry ->
                val parts = entry.trim().split(':', limit = 2)
                require(parts.size == 2 && parts.all { it.isNotBlank() }) {
                    "<d379a0bf> API key registry entries must use key:service format"
                }
                parts[0] to ServicePrincipal(parts[1])
            }
            require(entries.isNotEmpty()) { "<1a7018a6> At least one API key must be configured" }
            return AuthRegistry(entries)
        }
    }
}

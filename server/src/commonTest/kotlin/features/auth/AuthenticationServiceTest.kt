package features.auth

import common.DomainErrorKind
import common.DomainException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthenticationServiceTest {
    @Test
    fun validApiKeyReturnsPrincipal() {
        val service = DefaultAuthenticationService(
            MockedApiKeyRepository(mapOf("secret" to ServicePrincipal("producer")))
        )

        assertEquals(ServicePrincipal("producer"), service.authenticate("secret"))
    }

    @Test
    fun unknownApiKeyIsUnauthorized() {
        val service = DefaultAuthenticationService(MockedApiKeyRepository())

        val error = assertFailsWith<DomainException> { service.authenticate("unknown") }

        assertEquals(DomainErrorKind.UNAUTHORIZED, error.kind)
        assertEquals("UNAUTHORIZED", error.code)
        assertEquals("Invalid API key", error.message)
    }

    @Test
    fun configuredRepositoryParsesMultipleEntriesAndWhitespace() {
        val repository = ConfiguredApiKeyRepository.parse(" first:producer,second:worker ")

        assertEquals(ServicePrincipal("producer"), repository.findByApiKey("first"))
        assertEquals(ServicePrincipal("worker"), repository.findByApiKey("second"))
    }

    @Test
    fun configuredRepositoryRejectsEmptyConfiguration() {
        val error = assertFailsWith<IllegalArgumentException> { ConfiguredApiKeyRepository.parse(" , ") }

        assertEquals("<1a7018a6> At least one API key must be configured", error.message)
    }

    @Test
    fun configuredRepositoryRejectsMalformedEntry() {
        val error = assertFailsWith<IllegalArgumentException> { ConfiguredApiKeyRepository.parse("invalid") }

        assertEquals("<d379a0bf> API key registry entries must use key:service format", error.message)
    }
}

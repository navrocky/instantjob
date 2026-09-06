package common

enum class DomainErrorKind { BAD_REQUEST, UNAUTHORIZED, NOT_FOUND, FORBIDDEN, CONFLICT }

class DomainException(
    val kind: DomainErrorKind,
    val code: String,
    override val message: String,
) : RuntimeException(message)

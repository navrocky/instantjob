package common

/** Transport-neutral category used to map a domain failure to a presentation error. */
enum class DomainErrorKind { BAD_REQUEST, UNAUTHORIZED, NOT_FOUND, FORBIDDEN, CONFLICT }

/**
 * Expected domain failure that may be presented to an API consumer.
 *
 * @property kind Transport-neutral error category.
 * @property code Stable machine-readable error code.
 * @property message Human-readable failure description.
 */
class DomainException(
    val kind: DomainErrorKind,
    val code: String,
    override val message: String,
) : RuntimeException(message)

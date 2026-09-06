package common

/** Identifies a request across service boundaries and log records. */
data class RequestId(
    /** Correlation identifier propagated through a request context. */
    val id: String,
) {
    override fun toString(): String {
        return "{$id}"
    }
}

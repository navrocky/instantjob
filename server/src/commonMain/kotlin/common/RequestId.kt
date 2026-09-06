package common

/** @property id Correlation identifier propagated through a request context. */
data class RequestId(val id: String) {
    override fun toString(): String {
        return "{$id}"
    }
}

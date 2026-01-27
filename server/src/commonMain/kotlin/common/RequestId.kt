package common

data class RequestId(val id: String) {
    override fun toString(): String {
        return "{$id}"
    }
}
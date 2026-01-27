package common

interface Context {
    val requestId: RequestId
}

class RootContext(override val requestId: RequestId) : Context {
    override fun toString(): String {
        return requestId.toString()
    }
}
package infrastructure.log

import kotlinx.cinterop.*
import pthread.pthread_getname_np
import pthread.pthread_self

@OptIn(ExperimentalForeignApi::class)
actual fun getCurrentThreadId(): ULong = pthread_self()

@OptIn(ExperimentalForeignApi::class)
actual fun getCurrentThreadName(): String? {
    val buffer = ByteArray(16)
    return buffer.usePinned { pinned ->
        val result = pthread_getname_np(
            pthread_self(),
            pinned.addressOf(0),
            buffer.size.convert()
        )
        if (result == 0) pinned.get().toKString() else null
    }
}
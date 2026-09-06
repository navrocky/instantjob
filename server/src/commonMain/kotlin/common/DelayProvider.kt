package common

import kotlin.time.Duration

fun interface DelayProvider {
    suspend fun delay(duration: Duration)
}

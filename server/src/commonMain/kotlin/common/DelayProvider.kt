package common

import kotlin.time.Duration

/** Suspends application workflows for a requested duration. */
fun interface DelayProvider {
    /** Suspends for [duration]. */
    suspend fun delay(duration: Duration)
}

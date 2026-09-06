package infrastructure.config

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
actual fun getEnvVal(name: String): String? =
    getenv(name)?.toKString()

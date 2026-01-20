@file:Suppress("UNUSED_VARIABLE")

val ktorVersion = "3.3.3"
val mordantVersion = "3.0.2"

plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
}

kotlin {
    val hostOs = System.getProperty("os.name")
    val isArm64 = System.getProperty("os.arch") == "aarch64"
    val isMingwX64 = hostOs.startsWith("Windows")
    val nativeTarget = when {
//        hostOs == "Mac OS X" && isArm64 -> macosArm64()
//        hostOs == "Mac OS X" && !isArm64 -> macosX64()
        hostOs == "Linux" && isArm64 -> linuxArm64()
        hostOs == "Linux" && !isArm64 -> linuxX64()
        isMingwX64 -> mingwX64()
        else -> throw GradleException("Host OS is not supported in Kotlin/Native.")
    }

    nativeTarget.apply {
        binaries {
            executable("instajob", listOf(RELEASE)) {
//            linkerOpts = mutableListOf("-static", /*"-static-libgcc", "-static-libstdc++",*/ "-s", "-lpthread", "-lglibc")
//            linkerOpts = mutableListOf("-static", "-lpthread")
            }
            executable("instajob", listOf(DEBUG)) {
            }
        }
        compilations.getByName("main") {
            cinterops {
                val pthread by creating
            }
        }
    }

    sourceSets {
//        val okioVersion = "3.10.2"
//        val kotlinLoggingVersion = "7.0.3"
        val commonMain by getting {
            dependencies {
                implementation("io.ktor:ktor-server-core:${ktorVersion}")
                implementation("io.ktor:ktor-server-cio:${ktorVersion}")
//                implementation("com.squareup.okio:okio:$okioVersion")
//                implementation("com.github.ajalt.clikt:clikt:4.2.1")
//                implementation("com.github.ajalt.mordant:mordant:2.1.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
//                implementation("io.github.oshai:kotlin-logging:$kotlinLoggingVersion")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val linuxX64Main by getting {
            dependencies {
                implementation("io.ktor:ktor-server-core:${ktorVersion}")
                implementation("com.github.ajalt.mordant:mordant:${mordantVersion}")
//                implementation("io.github.oshai:kotlin-logging-linuxx64:$kotlinLoggingVersion")
            }
        }
//        val nativeTest by getting
    }
}

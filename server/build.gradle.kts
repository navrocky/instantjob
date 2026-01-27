@file:Suppress("UNUSED_VARIABLE")


val ktorVersion = "3.3.3"
val mordantVersion = "3.0.2"

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("io.kotest")
    id("com.google.devtools.ksp")
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
        commonMain {
            dependencies {
                implementation("io.ktor:ktor-server-core:${ktorVersion}")
                implementation("io.ktor:ktor-server-cio:${ktorVersion}")
                implementation("io.ktor:ktor-server-content-negotiation:${ktorVersion}")
                implementation("io.ktor:ktor-serialization-kotlinx-json:${ktorVersion}")
                implementation("io.ktor:ktor-server-status-pages:${ktorVersion}")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
            }
        }

        commonTest {
            dependencies {
//                implementation(kotlin("test"))
                implementation("io.kotest:kotest-framework-engine:6.1.1")
                implementation("io.kotest:kotest-assertions-core:6.1.1")
            }
        }

        linuxX64Main {
            dependencies {
                implementation("io.ktor:ktor-server-core:${ktorVersion}")
                implementation("com.github.ajalt.mordant:mordant:${mordantVersion}")
            }
        }
    }
}

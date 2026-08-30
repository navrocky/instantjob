@file:Suppress("UNUSED_VARIABLE")

val ktorVersion = "3.3.3"
val mordantVersion = "3.0.2"
val openApiSpec = rootProject.layout.projectDirectory.file("api/openapi.yaml")
val generatedOpenApiDir = layout.buildDirectory.dir("generated/openapi/kotlin")

val verifyOpenApiYagen by tasks.registering(Exec::class) {
    inputs.property("requiredOpenApiVersion", "3.2")
    commandLine(
        "sh", "-c",
        "openapi-yagen info builtin:kotlin_ktor_server | grep -F 'OpenAPI version: 3.2'",
    )
}

val generateOpenApiServer by tasks.registering(Exec::class) {
    dependsOn(verifyOpenApiYagen)
    inputs.file(openApiSpec)
    outputs.dir(generatedOpenApiDir)
    commandLine(
        "openapi-yagen", "generate",
        openApiSpec.asFile.absolutePath,
        "--clear",
        "--out-dir", generatedOpenApiDir.get().asFile.absolutePath,
        "--generator", "builtin:kotlin_ktor_server",
        "--var", "packageName=instantjob.api.generated",
        "--var", "strict=true",
        "--var", "dateTimeType=kotlin.time.Instant",
        "--var", "generate=all",
    )
}

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.ensody.nativebuilds") version "0.8.0" apply false
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

//    cinterops(libs.nativebuilds.zstd.headers) {
//        definitionFile.set(file("src/nativeMain/cinterop/lib.def"))
//    }

    nativeTarget.apply {
        binaries {
            val libpqDirectory = listOf(
                "/usr/lib64",
                "/usr/lib/x86_64-linux-gnu",
                "/usr/lib/aarch64-linux-gnu",
                "/usr/lib/postgresql18/lib64",
            ).map(::file).firstOrNull { directory ->
                directory.resolve("libpq.so").exists()
            }
            if (libpqDirectory != null) {
                all { linkerOpts("-L${libpqDirectory.absolutePath}") }
            }
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
            cinterops
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generatedOpenApiDir)
            dependencies {
                implementation("io.ktor:ktor-server-core:${ktorVersion}")
                implementation("io.ktor:ktor-server-cio:${ktorVersion}")
                implementation("io.ktor:ktor-server-content-negotiation:${ktorVersion}")
                implementation("io.ktor:ktor-serialization-kotlinx-json:${ktorVersion}")
                implementation("io.ktor:ktor-server-status-pages:${ktorVersion}")
                implementation("io.ktor:ktor-client-core:${ktorVersion}")
                implementation("io.ktor:ktor-client-cio:${ktorVersion}")
                implementation("io.ktor:ktor-client-content-negotiation:${ktorVersion}")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
                implementation("io.github.kormium:kormium-postgres:0.12.0")
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        linuxX64Main {
            dependencies {
                implementation("io.ktor:ktor-server-core:${ktorVersion}")
                implementation("com.github.ajalt.mordant:mordant:${mordantVersion}")
                api("com.ensody.nativebuilds:nghttp2-libnghttp2:1.68.0.8")
                implementation("com.ensody.nativebuilds:nghttp2-headers:1.68.0.8")
                implementation("com.ensody.nativebuilds:nghttp2-libnghttp2:1.68.0.8")
            }
        }
    }
}

tasks.matching {
    it.name.startsWith("compileKotlin") || it.name.startsWith("compileTestKotlin")
}.configureEach {
    dependsOn(generateOpenApiServer)
}

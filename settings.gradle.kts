pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        kotlin("multiplatform") version "2.3.0"
        kotlin("plugin.serialization") version "2.3.0"
        id("io.kotest") version "6.1.1"
        id("com.google.devtools.ksp") version "2.3.3"
    }
}

rootProject.name = "instantjob"
include("server")
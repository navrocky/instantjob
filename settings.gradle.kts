pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        kotlin("multiplatform") version "2.4.10"
        kotlin("plugin.serialization") version "2.4.10"
    }
}

rootProject.name = "instantjob"
include("server")
include("dashboard")

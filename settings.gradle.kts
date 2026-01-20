pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        kotlin("multiplatform") version "2.3.0"
    }
}

rootProject.name = "instantjob"
include("server")
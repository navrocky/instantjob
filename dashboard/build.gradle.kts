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
    js {
        browser {
            commonWebpackConfig {
                outputFileName = "instantjob-dashboard.js"
                cssSupport {
                    enabled.set(true)
                }
            }
        }
        binaries.executable()
    }

    sourceSets {
        jsMain {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-browser:0.5.0")
//                implementation(npm("react", "> 14.0.0 <=16.9.0"))
            }
        }

        jsTest {

        }
    }
}

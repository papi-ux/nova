plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

group = "com.papi.nova.shared"
version = "1.0-SNAPSHOT"

kotlin {
    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

tasks.register("test") {
    group = "verification"
    description = "Runs the JVM tests for the shared Polaris model."
    dependsOn("jvmTest")
}

import org.gradle.api.tasks.testing.Test

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        val commonTest by getting {
            dependencies { implementation(kotlin("test")) }
        }
    }
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

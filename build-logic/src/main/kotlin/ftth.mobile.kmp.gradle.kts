import org.gradle.api.tasks.testing.Test
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

val buildAndroid = providers.gradleProperty("ftth.android").map(String::toBoolean).getOrElse(false)
if (buildAndroid) pluginManager.apply("com.android.kotlin.multiplatform.library")

kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()
    if (buildAndroid) {
        targets.withType<KotlinMultiplatformAndroidLibraryTarget>().configureEach {
            namespace = "com.duluin.ftth." + project.path.removePrefix(":").replace(":", ".")
            compileSdk = 36
            minSdk = 26
            androidResources { enable = true }
        }
    }
    sourceSets {
        val commonTest by getting {
            dependencies { implementation(kotlin("test")) }
        }
    }
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

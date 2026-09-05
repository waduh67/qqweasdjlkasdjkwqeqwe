plugins {
    id("ftth.mobile.kmp")
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":mobile:domain"))
            implementation(project(":mobile:core:mvi"))
            implementation(project(":mobile:core:ui"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.compose.runtime:runtime:1.9.3")
            implementation("org.jetbrains.compose.foundation:foundation:1.9.3")
            implementation("org.jetbrains.compose.ui:ui:1.9.3")
        }
        jvmTest.dependencies {
            implementation("org.jetbrains.compose.ui:ui-test:1.9.3")
            implementation(compose.desktop.currentOs)
        }
    }
}

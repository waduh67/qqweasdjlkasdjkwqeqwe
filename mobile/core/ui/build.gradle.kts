plugins {
    id("ftth.mobile.kmp")
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin { sourceSets { commonMain.dependencies {
    implementation("org.jetbrains.compose.runtime:runtime:1.9.3")
    implementation("org.jetbrains.compose.foundation:foundation:1.9.3")
    implementation("org.jetbrains.compose.ui:ui:1.9.3")
    implementation("io.github.compose-fluent:fluent:v0.1.0")
} } }

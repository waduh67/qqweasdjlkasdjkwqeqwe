plugins {
    id("ftth.mobile.kmp")
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin { sourceSets { commonMain.dependencies {
    implementation(project(":mobile:feature:workorders"))
    implementation(project(":mobile:feature:attendance"))
    implementation(project(":mobile:feature:payroll"))
    implementation(project(":mobile:core:ui"))
    implementation(project(":mobile:core:mvi"))
    implementation(project(":mobile:domain"))
    implementation("org.jetbrains.compose.runtime:runtime:1.9.3")
    implementation("org.jetbrains.compose.foundation:foundation:1.9.3")
} } }

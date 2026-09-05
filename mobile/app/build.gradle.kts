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
    implementation(project(":mobile:core:storage"))
    implementation(project(":mobile:domain"))
    implementation(libs.koin.core)
    implementation(libs.koin.compose)
    implementation(libs.koin.compose.viewmodel)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation("org.jetbrains.compose.runtime:runtime:1.9.3")
    implementation("org.jetbrains.compose.foundation:foundation:1.9.3")
}
    commonTest.dependencies {
        implementation(kotlin("test"))
        implementation(libs.koin.test)
    }
} }

plugins {
    id("ftth.mobile.android")
    alias(libs.plugins.kotlin.compose.compiler)
}

android {
    namespace = "com.duluin.ftth.technician"
    defaultConfig {
        applicationId = "com.duluin.ftth.technician"
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies {
    implementation(project(":mobile:app"))
    implementation(project(":mobile:data"))
    implementation(project(":mobile:domain"))
    implementation(project(":mobile:core:storage"))
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("org.jetbrains.compose.runtime:runtime:1.9.3")
}

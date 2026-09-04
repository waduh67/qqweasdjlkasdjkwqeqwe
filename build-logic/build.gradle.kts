plugins { `kotlin-dsl` }

repositories { google(); gradlePluginPortal(); mavenCentral() }

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
    implementation("org.jetbrains.kotlin.plugin.compose:org.jetbrains.kotlin.plugin.compose.gradle.plugin:2.3.21")
    implementation("org.jetbrains.compose:compose-gradle-plugin:1.9.3")
    implementation("com.android.tools.build:gradle:8.5.2")
    implementation("com.android.library:com.android.library.gradle.plugin:8.5.2")
    implementation("com.android.kotlin.multiplatform.library:com.android.kotlin.multiplatform.library.gradle.plugin:8.5.2")
}

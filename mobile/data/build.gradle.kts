plugins { id("ftth.mobile.kmp") }

kotlin { sourceSets {
    commonMain.dependencies {
        implementation(project(":mobile:domain"))
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        implementation(libs.cryptography.core)
        implementation("dev.whyoleg.cryptography:cryptography-provider-optimal:0.6.0")
    }
    commonTest.dependencies {
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        implementation(project(":mobile:core:storage"))
    }
} }

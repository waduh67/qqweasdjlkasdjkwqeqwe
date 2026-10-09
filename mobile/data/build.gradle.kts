plugins { id("ftth.mobile.kmp") }

kotlin { sourceSets {
    commonMain.dependencies {
        implementation(project(":mobile:domain"))
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        implementation(libs.cryptography.core)
        implementation("dev.whyoleg.cryptography:cryptography-provider-optimal:0.6.0")
        implementation("io.ktor:ktor-client-core:3.4.3")
    }
    jvmMain.dependencies { implementation("io.ktor:ktor-client-cio:3.4.3") }
    iosMain.dependencies { implementation("io.ktor:ktor-client-darwin:3.4.3") }
    findByName("androidMain")?.dependencies { implementation("io.ktor:ktor-client-okhttp:3.4.3") }
    commonTest.dependencies {
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        implementation(project(":mobile:core:storage"))
    }
} }

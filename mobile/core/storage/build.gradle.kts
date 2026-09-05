plugins { id("ftth.mobile.kmp") }

kotlin {
    sourceSets {
    commonMain.dependencies {
        implementation(project(":mobile:domain"))
        implementation(libs.cryptography.core)
    }
    iosMain.dependencies { implementation(libs.cryptography.provider.cryptokit) }
    commonTest.dependencies { implementation(kotlin("test")) }
    }
}

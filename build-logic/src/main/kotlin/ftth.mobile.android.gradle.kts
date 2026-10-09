plugins { id("com.android.application") }

android {
    compileSdk = 36
    defaultConfig { minSdk = 26; targetSdk = 36 }
    buildFeatures { compose = true }
}

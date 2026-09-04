plugins { id("ftth.mobile.kmp") }

kotlin { sourceSets { commonMain.dependencies {
    implementation(project(":mobile:domain"))
    implementation(project(":mobile:data"))
    implementation(project(":mobile:core:common"))
    implementation(project(":mobile:core:network"))
    implementation(project(":mobile:core:storage"))
    implementation(project(":mobile:core:location"))
    implementation(project(":mobile:core:evidence"))
} } }

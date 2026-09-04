plugins { id("ftth.mobile.kmp") }

kotlin { sourceSets {
    commonMain.dependencies { implementation(project(":mobile:domain")) }
    commonTest.dependencies { implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2") }
} }

plugins { id("ftth.mobile.kmp") }

kotlin { sourceSets { commonMain.dependencies { implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2") } } }

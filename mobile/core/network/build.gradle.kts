plugins { id("ftth.mobile.kmp") }

kotlin { sourceSets { commonMain.dependencies { implementation(project(":mobile:domain")) } } }

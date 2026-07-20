// Deklarasi plugin di root (apply false) supaya Kotlin plugin dimuat sekali di
// classpath root, lalu subproject tinggal meng-apply tanpa memuat ulang.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.jpa) apply false
    alias(libs.plugins.spring.boot) apply false
}

allprojects {
    group = "com.duluin.ftth"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

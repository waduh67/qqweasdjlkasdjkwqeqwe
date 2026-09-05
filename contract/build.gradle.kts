// Kontrak wire antara ftth-server dan ftth-collector.
//
// Sengaja Kotlin murni tanpa Spring, Jackson, atau anotasi apa pun: kedua sisi
// di-deploy terpisah (server di cloud, collector di jaringan ISP) sehingga
// keduanya harus bisa memilih pustaka serialisasinya sendiri. Yang dibagikan
// hanya BENTUK datanya.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.withType<Test> {
    useJUnitPlatform()
}

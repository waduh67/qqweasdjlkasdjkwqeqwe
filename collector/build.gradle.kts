// ftth-collector — agent yang dipasang di jaringan ISP.
//
// Sengaja TIDAK memakai Spring: agent ini di-deploy operator di mesin apa adanya
// (kadang VM kecil, kadang mini-PC di POP), jadi waktu start dan jejak memorinya
// harus kecil. HTTP client bawaan JDK sudah cukup; yang benar-benar perlu hanya
// SNMP, JSON, dan logging.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

application {
    mainClass.set("com.duluin.ftth.collector.MainKt")
}

dependencies {
    implementation(project(":contract"))
    implementation(libs.snmp4j)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.kotlin)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
}

tasks.withType<Test> {
    useJUnitPlatform()
}

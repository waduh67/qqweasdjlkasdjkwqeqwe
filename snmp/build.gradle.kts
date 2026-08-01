// ftth-snmp — pustaka adapter SNMP OLT, dipakai BERSAMA oleh :collector (agent
// on-prem) dan :server (polling langsung dari server).
//
// Sengaja TIDAK memakai Spring dan hanya bergantung pada :contract + snmp4j.
// Dengan begitu logika penafsiran MIB per-vendor (yang paling rawan salah dan
// paling perlu diuji) hidup di satu tempat, dan kedua sisi menariknya tanpa
// memaksa salah satunya menanggung dependensi milik yang lain.
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
    implementation(project(":contract"))
    implementation(libs.snmp4j)
    implementation(libs.slf4j.api)

    testImplementation(kotlin("test"))
}

tasks.withType<Test> {
    useJUnitPlatform()
}

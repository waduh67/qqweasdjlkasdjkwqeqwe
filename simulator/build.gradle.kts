// ftth-simulator — lab perangkat tiruan untuk mencoba app tanpa hardware nyata.
//
// Satu app Spring Boot yang mewadahi beberapa peniru protokol yang aplikasi baca:
//   - OLT: agen SNMPv2c (profil HSGQ) → memberi makan discovery ONU + metrik.
//   - (menyusul) BRAS/RADIUS: mesin sesi radacct + responder DAE + stub RouterOS.
//
// Sengaja me-reuse modul yang sudah ada agar OID/enkode tak disalin-tempel:
//   :snmp     → konstanta OID & adapter (satu sumber kebenaran),
//   :contract → tipe OltTarget/OnuReading dsb.
//
// DEV-ONLY. Tak pernah di-deploy ke produksi.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    // Distribusi non-fat (bin/simulator + lib/*.jar) untuk image lab — cermin :collector.
    // Spring Boot jalan mulus dari classpath datar, tak butuh layout bootJar.
    application
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

application {
    // File-level main() SimulatorApplication.kt → kelas sintetis ...Kt.
    mainClass.set("com.duluin.ftth.simulator.SimulatorApplicationKt")
}

dependencies {
    implementation(platform(libs.spring.boot.bom))

    implementation(project(":contract"))
    // Agen OLT memakai konstanta OID & profil dari :snmp — bukan menyalinnya.
    implementation(project(":snmp"))
    implementation(libs.snmp4j)

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Virtual-NAS (BRAS/RADIUS): koneksi samping ke radius-db untuk memateri sesi radacct.
    // Hikari + driver saja (bukan starter-jdbc) agar tak memicu auto-config datasource utama.
    implementation("com.zaxxer:HikariCP")
    runtimeOnly("org.postgresql:postgresql")

    // Uji kebenaran agen: adapter :snmp produksi diarahkan ke agen ini.
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

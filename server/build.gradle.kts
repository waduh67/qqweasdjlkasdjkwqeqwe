plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.spring.boot)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.modulith.bom))
    implementation(platform(libs.awssdk.bom))

    // Kontrak wire collector↔server; dipakai bersama modul :collector.
    implementation(project(":contract"))
    // Adapter SNMP OLT: server memoll OLT langsung (server-side SNMP), memakai
    // adapter yang sama dengan agent :collector on-prem.
    implementation(project(":snmp"))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Kanal email platform (SMTP) — dipakai pemulihan password portal pelanggan. Tanpa
    // `spring.mail.host`, autokonfigurasi Boot tak membuat bean pengirim dan adapter jatuh
    // ke mode catat-ke-log; jadi kehadiran dependensi ini tak mewajibkan server SMTP.
    implementation("org.springframework.boot:spring-boot-starter-mail")

    implementation("org.springframework.modulith:spring-modulith-starter-core")

    // Boot 4 memecah autoconfigure per teknologi; FlywayAutoConfiguration ada di modul ini.
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Geometri PostGIS. Kehadiran modul ini membuat PostgreSQLDialect otomatis
    // mengaktifkan dukungan spasial; JTS ikut sebagai dependensi transitif.
    implementation("org.hibernate.orm:hibernate-spatial")

    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("com.github.ben-manes.caffeine:caffeine")

    implementation(libs.springdoc.webmvc.ui)

    // Object storage bukti pengerjaan work order (MinIO/S3-compatible).
    implementation(libs.awssdk.s3)
    // PKI OpenVPN: module vpn menerbitkan CA + sertifikat server sendiri.
    implementation(libs.bouncycastle.pkix)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Boot 4 memecah autoconfigure test per teknologi; @AutoConfigureMockMvc ada di sini.
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

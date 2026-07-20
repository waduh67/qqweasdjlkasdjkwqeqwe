// Placeholder — ftth-collector diisi di Phase 2 (monitoring).
// Agent slim yang di-deploy di jaringan ISP: polling OLT via SNMP/Telnet,
// push hasil outbound ke ftth-server.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

rootProject.name = "ftth"

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

include("contract", "server", "collector", "snmp", "simulator")
include(
    ":mobile:app",
    ":mobile:domain",
    ":mobile:data",
    ":mobile:core:common",
    ":mobile:core:mvi",
    ":mobile:core:ui",
    ":mobile:core:network",
    ":mobile:core:storage",
    ":mobile:core:location",
    ":mobile:core:evidence",
    ":mobile:feature:workorders",
    ":mobile:feature:attendance",
    ":mobile:feature:payroll",
)

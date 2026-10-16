# Karuhun package namespace

Application code uses `com.karuhun.ftth`. Source and test folders, imports, Gradle coordinates, collector and simulator launchers, mobile namespace configuration, CI selectors and documentation follow this prefix.

The Android application and iOS bundle identifier are `com.karuhun.ftth.technician`. An installation with a previous identifier is a separate mobile application; the namespace change does not transfer its local outbox. No device installation or local-data deletion is part of the server release. JVM/mobile shared-code checks can run on Linux; building and signing the iOS application requires Xcode.

The HTTP routes, DTO fields, database schema, migration files and network account identifiers are unchanged. Spring component/entity discovery starts from `com.karuhun.ftth.FtthApplication`. The collector/server contracts continue to serialize the same field names.

Historical upgrade tests keep their pinned application code. `scripts/warehouse/historical_namespace.py` reads the entry-point package in each checkout and translates only the copied Kotlin test harness. Test selectors and the draft seed launcher use the resulting historical class names. SQL overlays still verify immutable migration bytes. Historical product sources are never renamed.

Before release, build protocol tests, collector/simulator distributions, the server boot JAR, shared mobile tests and module graph checks. Run integration tests against the isolated warehouse fixture, inspect packaged class paths and launcher configuration, and deploy through GitHub Actions. Private reports live in `.omo/runtime/karuhun-namespace/`.

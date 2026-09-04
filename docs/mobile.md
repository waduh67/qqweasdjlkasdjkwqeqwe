# Technician Mobile Foundation

The `mobile/` tree is a Kotlin Multiplatform foundation for the technician
work-order application. `domain` owns platform-independent state, reducers,
use cases, and ports. `data` and `core/*` contain adapter boundaries. The
`feature:workorders` module composes those contracts; `app` owns the shared
Compose Fluent UI entry point and the iOS `ComposeUIViewController` bridge.

## Module Graph

```text
:mobile:app -> :mobile:feature:workorders
:mobile:feature:workorders -> :mobile:domain, :mobile:data, :mobile:core:*
:mobile:data -> :mobile:domain
:mobile:core:network|storage|location|evidence -> :mobile:domain
:mobile:core:common -> (no feature/data dependencies)
```

The graph is acyclic. UI does not call HTTP, auth, location, persistence, or
evidence directly. `Outbox` is an encrypted-storage contract; the current
in-memory implementation deliberately reports `encryptedAtRest = false` until
platform secure-storage adapters are supplied.

## UI And Platform Boundaries

The shared root uses `FluentTheme` and Fluent `Text` only. The Fluent dependency
is `io.github.compose-fluent:fluent:v0.1.0`; this release is experimental and
was published against an older Compose generation, so upgrades require Android
and iOS validation. Location adapters are `expect`/`actual` boundaries and do
not claim native permission or secure-storage behavior yet.

The repository currently cannot configure the Android target: Kotlin 2.3.21
fails to infer the Android Gradle Plugin version when `androidTarget()` is used
through the included convention plugin. iOS targets (`iosArm64` and
`iosSimulatorArm64`) and JVM test targets remain configured. Resolve this by
moving to the repository-supported Kotlin/AGP Android KMP plugin matrix before
enabling the Android launcher.

## Extension Points

- Implement `HttpClientPort` and `WorkOrderGateway` for authenticated API DTOs.
- Implement `SecureOutboxPort` with platform encryption and durable replay.
- Implement `PlatformLocationAdapter` with runtime permission and GPS fixes.
- Add evidence hashing/upload adapters behind `EvidencePort`.
- Add Compose tests for loading, empty, error, offline, conflict, and permission states.

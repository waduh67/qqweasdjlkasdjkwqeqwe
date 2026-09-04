# Technician Mobile Foundation

The `mobile/` tree is a Kotlin Multiplatform foundation for the technician
work-order application. `domain` owns platform-independent state, reducers,
use cases, and ports. `data` and `core/*` contain adapter boundaries. The
`feature:workorders` module composes those contracts; `app` owns the shared
Compose Fluent UI entry point and the iOS `ComposeUIViewController` bridge.

## Module Graph

```text
:mobile:app -> :mobile:feature:workorders, :mobile:feature:attendance, :mobile:feature:payroll, :mobile:core:ui
:mobile:feature:workorders -> :mobile:domain, :mobile:core:mvi, :mobile:core:ui
:mobile:feature:attendance -> :mobile:domain, :mobile:core:mvi, :mobile:core:ui
:mobile:feature:payroll -> :mobile:domain, :mobile:core:mvi, :mobile:core:ui
:mobile:data -> :mobile:domain
:mobile:core:network|storage|location|evidence -> :mobile:domain
:mobile:core:common -> (no feature/data dependencies)
:mobile:core:mvi -> (coroutines only)
:mobile:core:ui -> (Compose Fluent primitives only)
```

The graph is acyclic and `./gradlew verifyMobileModuleGraph` rejects feature-to-feature,
feature-to-data, and core UI/MVI coupling. UI does not call HTTP, auth, location,
persistence, or evidence directly. `SecureOutboxPort` persists encrypted operation metadata and
attachment bytes through injected record/cipher ports. It binds user, device, session, namespace,
key, payload hash, and revision; replay/conflict survive recreation. Android/iOS wrappers require
KeyStore/Keychain-backed implementations of those ports. JVM persistence is test-only.

## UI And Platform Boundaries

`mobile:core:mvi` provides the only commonMain MVI contract: typed
`Intent -> reducer -> immutable State`, ordered actions, non-replayed effects, explicit
store ownership/cancellation, and an optional state saver. Feature stores own their scope
through composition rather than Android ViewModel inheritance.

`mobile:core:ui` provides FluentTheme-backed atoms (`FluentAction`, `FluentMessage`,
`FluentPanel`), molecules (`FluentStatePanel`), and responsive scaffolding. Atoms contain
no domain or network logic; feature organisms bind immutable states and emit intents.
The shared states cover loading, empty, error, offline, conflict, permission-denied,
list/form slots, and responsive surfaces. The Fluent dependency is
`io.github.compose-fluent:fluent:v0.1.0`; this release is experimental and
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
- Implement `SecureOutboxRecords` and `OutboxCipher` with Android KeyStore or iOS Keychain.
- Implement `PlatformLocationAdapter` with runtime permission and GPS fixes.
- Add evidence hashing/upload adapters behind `EvidencePort`.
- Reuse `MviStore` and the core UI atoms for attendance/payroll features; do not add a
  competing feature-local store or shared visual state layer.
- Attendance uses self-service permission state, operation keys/revisions, `SecureOutboxPort`, and explicit offline/conflict states; its queued payload is metadata only.
- Payroll reads only `SecurePayslipPort.personalPayslip()`, has no peer identifier or mutation intent, and renders locked periods as read-only.

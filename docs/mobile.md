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
store ownership/cancellation, and an optional state saver. Feature ViewModels own their
stores under AndroidX Lifecycle `viewModelScope`; Compose obtains them through Koin rather
than constructing feature stores directly.

`mobile:core:ui` provides FluentTheme-backed atoms (`FluentAction`, `FluentMessage`,
`FluentPanel`), molecules (`FluentStatePanel`), and responsive scaffolding. Atoms contain
no domain or network logic; feature organisms bind immutable states and emit intents.
The shared states cover loading, empty, error, offline, conflict, permission-denied,
list/form slots, and responsive surfaces. The Fluent dependency is
`io.github.compose-fluent:fluent:v0.1.0`; this release is experimental and
was published against an older Compose generation, so upgrades require Android
and iOS validation. Location adapters are `expect`/`actual` boundaries and do
not claim native runtime permission behavior yet.

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

## Lifecycle And Dependency Injection

The shared MVI engine remains a pure `MviReducer` plus `MviStore`. Lifecycle-facing
owners are feature `ViewModel`s (`WorkOrderViewModel`, `AttendanceViewModel`, and
`PayrollViewModel`) built on the single `core:mvi` `MviViewModel` abstraction. That
base owns `MviStore` under AndroidX Lifecycle `viewModelScope`, exposes immutable
`StateFlow`/`SharedFlow`, and closes the store from `onCleared`.

Compose obtains those owners with Koin's `koinViewModel()` rather than constructing
stores in `remember` or closing them from `DisposableEffect`. `commonAppModule`
declares feature/domain port bindings and ViewModels; Android and iOS platform
modules bind the platform port bundle and their concrete secure outbox. Android
uses `applicationContext`; iOS uses CryptoKit, Keychain, and Application Support.
Koin is initialized by the platform entrypoint through `KoinApplication`, once for
the Compose app lifecycle. Hilt is intentionally not used because it is Android-
centric and does not provide the shared iOS composition required by this KMP app.

## Permissions And Runtime Limits

Location is purpose-bound to technician check-in. The common state machine represents
only `Unknown`, `Granted`, and `Denied`; denied permission routes to permission help and
does not claim background or continuous tracking. JVM and iOS location adapters currently
return `Unknown` and reject coordinate retrieval, so neither is native runtime proof.
Android runtime execution is also environment-gated because this repository does not
configure an Android KMP target. A production Android adapter must declare/request location
permission, disclose the onsite purpose, and keep exact coordinates out of portal and
payslip projections before it can be claimed as implemented.

Secure outbox behavior is concrete on the available platform bindings: Koin injects a
user-scoped `SecureOutboxPort`; Android uses application context and iOS uses CryptoKit,
Keychain, and Application Support. The outbox binds user/device/session/operation identity,
encrypts bytes at rest, rejects foreign-user retry/enqueue/purge, and purges the signed-out
user. Native device execution, Keychain fault injection, and Android permission prompts
remain platform-runtime acceptance checks, not evidence supplied by JVM/common tests.

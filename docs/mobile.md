# Technician Mobile Application

The `mobile/` tree contains the shared technician application and its Android
and iOS hosts. `domain` owns platform-independent state, reducers,
use cases, and ports. `data` and `core/*` contain adapter boundaries. The
`feature:workorders` module composes those contracts; `app` owns the shared
Compose Fluent UI entry point and the iOS `ComposeUIViewController` bridge.

The material flow shares the warehouse custody, revision, source and exact-unit
contracts. See the [operator guide](warehouse.md) and [release review](warehouse-review.md)
for the server-backed journey and verification commands. JVM/shared tests and actual
iOS target compilation are separate required checks; neither is a native runtime,
store release, hardware-permission or secure-platform-storage certification.

## Module Graph

```text
:mobile:app -> :mobile:feature:workorders, :mobile:feature:materials, :mobile:feature:attendance, :mobile:feature:payroll, :mobile:data, :mobile:core:ui
:mobile:feature:materials -> :mobile:domain, :mobile:core:mvi, :mobile:core:ui
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

Android is enabled explicitly with `-Pftth.android=true`. The convention plugin
uses `com.android.kotlin.multiplatform.library` and configures its Android target;
the launcher uses `com.android.application`. The local build uses Kotlin 2.3.21,
Compose 1.9.3, AGP 9.0.0 and the checksum-pinned Gradle 9.3.0 wrapper. iOS targets
(`iosArm64` and `iosSimulatorArm64`) and JVM tests remain available without an
Android SDK.

## Run the technician host

The reference entry point has Ringkasan, Pekerjaan, Material, Permintaan and Retur
destinations. Sign in with the API server URL and the tenant technician account;
enter an authenticator code when the account has two-factor authentication.
The host persists encrypted credentials, refreshes tokens, and clears the account
queue on logout. Requests, returns, named photos, progress and completion use
the real reference API and an encrypted queue with exact retry bytes and keys.
Stale snapshots are labeled; a destination that has not loaded does not report
an invented zero count.

With JDK 21 and Android SDK 36 configured, build and install the debug host:

```bash
./gradlew :mobile:android:assembleDebug -Pftth.android=true \
  --no-daemon --no-parallel --max-workers=2 \
  -Pkotlin.compiler.execution.strategy=in-process
adb install -r mobile/android/build/outputs/apk/debug/android-debug.apk
```

Application ID: `com.duluin.ftth.technician`; minimum Android API 26, target API
36. Use HTTPS for the server. Debug development HTTP is restricted to localhost,
127.0.0.1 and the Android emulator host alias 10.0.2.2. Photos are selected or
captured from the named slot, normalized to JPEG, bounded to 2048 pixels and 5 MB,
and previewed before review. The merged manifest removes obsolete phone/storage
permissions inherited from the Fluent dependency. App backups are disabled.

The iOS host lives in `mobile/ios`. On macOS with Xcode, JDK 21 and XcodeGen:

```bash
xcodegen generate --spec mobile/ios/project.yml
open mobile/ios/FTTHTechnician.xcodeproj
```

Select the FTTHTechnician scheme and an iOS 16 or newer simulator/device. The
pre-build script embeds the static `TechnicianApp` framework. The SwiftUI host
uses the shared screen, Keychain credentials/outbox, and named camera/library
photos. Configure a signing team for a physical device. Generated Xcode files
and Info.plist are ignored; the XcodeGen specification is the source of truth.

## Extension Points

- Extend the legacy `HttpClientPort` and `WorkOrderGateway` adapters when a
  separate feature host needs them; reference operations use `KtorFieldTransport`
  and `ReferenceTechnicianRepository`.
- Keep platform storage behind `SecureOutboxRecords` and `OutboxCipher`; the
  reference host already binds Android KeyStore and iOS Keychain implementations.
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
The Android adapter also returns Unknown and rejects coordinate retrieval.
A production attendance adapter must declare/request location
permission, disclose the onsite purpose, and keep exact coordinates out of portal and
payslip projections before it can be claimed as implemented.

Secure outbox behavior is concrete on the available platform bindings: Koin injects a
user-scoped `SecureOutboxPort`; Android uses application context and iOS uses CryptoKit,
Keychain, and Application Support. The outbox binds user/device/session/operation identity,
encrypts bytes at rest, rejects foreign-user retry/enqueue/purge, and purges the signed-out
user. Native device execution, Keychain fault injection, and Android permission prompts
remain platform-runtime acceptance checks, not evidence supplied by JVM/common tests.

## Material Saya

The shared `feature:materials` uses the same MVI engine, lifecycle ViewModel and Fluent
primitives. Koin builds `MaterialRepository` from the host's authenticated
`MaterialHttpPort`, observable `MaterialSessionPort`, operation-key generator and the
existing native secure outbox. The host must bind each HTTP request to the captured
tenant/user/device/session and send the supplied key as `Idempotency-Key`; it must not
switch credentials underneath an in-flight request. Update session and connectivity
StateFlows on login, logout, permission changes and connection changes. Recreate the
platform bundle and its user-scoped outbox for a different logged-in user.

Material Saya reads bounded own WO, issue and custody pages. Receipt forms retain
actual issue/WO revisions, measured accepted/missing/rejected quantities and serial
matching. Usage selects acknowledged non-serialized sources and supports initial use,
additional use with the current history reference, and explicit no-material declarations.
Device installation remains the customer-asset workflow; the shared material module
exposes no offline reservation, assignment or serialized-consumption command.

Quantities use checked integer strings: millimetres for cable and whole EA units.
Forms accept at most three decimal places for metres and never use floating point.
Submitting while offline persists an encrypted pending intent; it does not claim server
stock or a successful receipt. The encrypted envelope freezes canonical request bytes,
key, source/document revisions and tenant/user/device/session. Reconnect checks current
access, assignment and source before the first send. An ATTEMPTED state is persisted
before HTTP, so restart or a lost response retries the same bytes/key even when the
original command already consumed the source. The server reauthorizes canonical replay.
Only a parsed server success removes that one queue entry and reloads displayed stock.
Conflicts/rejections remain visible; uncertain commands cannot be discarded or edited.
Account changes clear the ViewModel and purge the prior user's queue. Late results from
an old scope cannot repopulate the screen. Offline startup restores the current session's
pending commands without fetching or inventing stock.

`scripts/warehouse/qa.sh kmp` includes domain, repository, secure storage, MVI,
workorders, material screen and app DI tests plus the module graph. Tests include exact
quantity boundaries, partial receipt, source revocation, restart/response-loss replay,
session changes and actual Compose text input. The reusable `mobile-materials` workflow
compiles both configured iOS application targets on macOS and rejects skipped/no-source
compilation. Linux common/JVM checks do not establish iOS compilation. Neither check
claims native device execution, a release-signed app binary or an app-store release.

## Current verification evidence

On 2026-10-09 the Android debug APK assembled successfully on the Windows ARM64
SDK host. The final manifest rebuild passed in 46 seconds; `aapt2 dump badging`
verified the application ID, API 26/36 and the removal of phone/storage permissions.
Local artifact: `.omo/runtime/artifacts/ftth-technician-debug.apk`. SHA256:
`09fb576e602e321c06a21d24c357803f010054076167b5dfd2395e571844b8b3`.
Build log: `.omo/runtime/reference-android-final.log`.

The domain, data and app JVM checks plus `verifyMobileModuleGraph` passed in
28 seconds, with 26 tests and no failures, errors or skips (unchanged domain/data
results were reused by Gradle). Log: `.omo/runtime/reference-mobile-final-shared.log`.
Tests cover authentication refresh/session fences, account purge, exact quantity
and source validation, encrypted photo/completion retry, and late controller results.

The remaining MVI, secure storage, workorders and materials consumer JVM checks
plus the module graph passed in 32 seconds with 28 tests and no failures, errors
or skips. Log: `.omo/runtime/reference-mobile-consumer-check.log`. Across both
commands the reviewed XML reports contain 54 tests: domain 5, data 19, app 2,
MVI 6, storage 7, workorders 7 and materials 8.

The Android device list is empty and this host has no supported emulator or macOS
toolchain. Android launch, native visual/accessibility checks, camera/KeyStore
runtime and iOS compile/link/runtime have not been observed. The
`mobile-materials` workflow now builds the APK, compiles both iOS targets, rejects
skipped native compilation, generates the Xcode host and links device/simulator
builds without signing. Its YAML and shell syntax passed locally; the workflow
has not been run for these changes. APK assembly and JVM checks do not establish
those remaining native acceptance checks.

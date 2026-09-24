## Task42 shared Material Saya verified; iOS CI proof pending, task43 NEXT

KMP gate41 tests PASS / BUILD SUCCESSFUL21s; module graph PASS. Suites: domain5,
data8,mvi6,storage7,workorders7,materials7(5common+2actualComposeUI),appDI1.
Proof task42/material-feature-verification.json. Shared UI/ViewModel/core implementation
is complete;42 remains OPEN for configured iOS compilation proof. Native runtime/release
is not in scope. Tasks43-48/F1-F4 remain; goal ACTIVE, continue independently while CI runs.

New feature/materials has actual own WO/issue/custody selection, measured partial receipt
with serial matching, multi-source initial use, single-source correction and no-material
form; offline is encrypted QUEUED, response loss ATTEMPTED retry samekey, server success
reloads counts. MaterialSessionPort now exposes session/connectivity StateFlows. VM
immediately clears/purges changed accounts, ignores old asynchronous results and restores
pending records even offline. Koin creates MaterialRepository from host MaterialHttpPort,
MaterialSessionPort and existing native SecureOutboxPort. Host transport must bind captured
session credentials and Idempotency-Key; shared foundation does not claim native launcher.
App embeds MaterialScreen in scrollable existing field app. FluentTextInput adds editable
48dp field; tests exercise actual input and uncertain retry UI. qa.sh kmp now includes
new domain/repository tests. docs/mobile.md describes actual boundaries and workflow.

.github/workflows/mobile-materials.yml: push on feat/warehouse-workorder mobile paths,
reusable workflow_call for later48. Read-only permissions, macos-latest, Java21, two
app iOS compile tasks plus graph; rejects SKIPPED/NO-SOURCE and uploads log/commit-bound
native JSON. gh is authenticated and Actions enabled. After pushing this checkpoint,
find newest workflow run with gh run list --workflow mobile-materials.yml --branch
feat/warehouse-workorder; inspect result/log and fix real native compiler issues if any.
Download safe native JSON evidence after success. Then42 can be checked complete.
No deploy job is triggered by this feature branch; existing deploy onlymain.

Local .omo/runtime/material-kmp-gate.sh runs owned test env + qa.sh kmp, then Linux
compileKotlinIosArm64/SimulatorArm64 + common metadata attempt. Latest output:
.omo/runtime/material-kmp-gate-verified.log, exec session47180; may still be running.
Check completion/owned cleanup. Earlier UI label duplicate and nullable-when compile
failures are fixed; no failed test assertion removed. Initials .omo/runtime/material-
kmp-gate.log and -final.log retained locally. Earlier foundation ate09ab382 remote pushed.

NEXT43: M05/M06 reserved177/178,149 next free incremental patch. Read C10/task43 carefully.
InventoryTenantPolicyService already supports exclusive LEGACY->VALIDATING fence/watermark
but finalization throws INDEPENDENT_APPROVAL_NOT_INSTALLED; WarehouseOpeningBalanceService
has no real migration baseline yet. Existing claims/candidates/legacy admission staged
by173/174/176. Need provenance cases +migration batch, dryrun real quantities/units/
collisions/effects, independent approval, atomic original-ID admission/opening/claims and
per-tenant ENFORCED finalization without boot requiring every tenant clean. Full packaged
migration upgrade fixture colliding legacy identities must boot. UI /warehouse/provenance
already linked from customer asset legacy badge but not implemented yet. No43 edits yet.

Branch work/warehouse-completion -> origin/feat/warehouse-workorder; original branch
preserved. Commit/push coherent changes. No deployment/main merge, no resets/volume deletion.

## Task42 encrypted material contracts/repository checkpoint; feature UI NEXT

Foundation20 JVM tests PASS (domain5,data8,storage7). Evidence:
task42/material-outbox-foundation.json. Final domain/data gate BUILD SUCCESSFUL17s;
storage7 executed earlier18s and remained up-to-date. Native code not compiled yet.
40/41 done atdd95ad65; full web320/66 PASS and backend RMA10 PASS; remote pushed.

NEW MaterialContracts in mobile/domain: exact checked MM/EA strings, own job/context/
issue/custody models, typed ReportUse/Acknowledge drafts and explicit delivery states.
MaterialJson/MaterialCommands/MaterialRepository in mobile/data use actual canonical
my-materials GETs and report-use/correct-use/acknowledge POSTs. Encrypted envelope binds
tenant/user/device/session, actual document revisions, source guards, exact body and key.
SHA256 uses existing crypto0.6 provider-optimal; data adds serialization-json1.10.0.
SecureOutboxPort now has scoped decrypted entries, mark and per-key complete. Native
records append delivery state (old10field records default QUEUED); failed persistence
and corrupt records fail closed. ATTEMPTED is persisted before send; source preflight
runs for unsent commands, uncertain retries use same request after current context read
and rely on canonical server replay authorization even if own consumption removed source.
No offline reservation/assignment/serial-use API. Terminal conflicts persist; attempted
commands cannot be discarded. Session change purges prior user.7newrepositorytests cover
restart/offlineconflict/response-loss/revocation/malformedresponse/partialACK/sourcegates.

Next42: add feature/materials Gradle module, MviViewModel/reducer, Fluent form actions,
app DI and navigation. Existing MaterialSessionPort has current()/online() only; add
observable session/connectivity (StateFlow) so ViewModel immediately clears/purges on
logout/switch and ignores stale asynchronous responses. Host provides MaterialHttpPort
with captured authenticated session; app Koin should construct MaterialRepository using
its existing platform SecureOutboxPort. Existing TechnicianPlatformPorts needs required
material HTTP/session fields; update common DI test. Shared MaterialScreen should select
actual own jobs/issues/custody, edit measured receipt/use with serial matching, distinguish
local draft/queued/uncertain/accepted/conflict, retain exact queued retry. Use existing
core:mvi and core:ui; no production fake transport or in-memory outbox. Then full qa.sh kmp
with nonzero tests, graph and existing iOS compilation attempt (Linux may skip). Remove
minor unnecessary-safe-call warning in MaterialRepository.sessionChanged when editing.
No module42/settings/app changes yet. Native release/hardware claims remain out of scope.

Branch work/warehouse-completion -> origin/feat/warehouse-workorder. Goal ACTIVE.
149 next migration,177/178 reserved43. Commit/push coherent chunks; no main merge/deploy.


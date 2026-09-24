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


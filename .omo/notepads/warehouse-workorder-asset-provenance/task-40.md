## Task40 handover recovery checkpoint — final backend verification PENDING

Three-party handover UI implemented: current named scoped dispatcher sources/targets on
WO Material, captured authorization with expected sender/receiver IDs, own sender pending
grants with fresh grant/context/source checks and exact command replay. Recipient ACK
uses existing own residual UI. Shared MaterialCustodyQuerySql preserves field-only own
reads and adds authorized dispatcher candidates; no selector to impersonate another actor.
Dispatcher authorization now checks current source/target warehouse scopes; new snapshots
remember sourceLocationId for scope checks on exact replay. Older snapshots keep optional
source location compatibility. Inventory still owns all posting and authorization.

New migration V175_148 validates optional reviewed parties and recorded source location
on authorization INSERT. New MaterialResidualRequest expectedSenderId/expectedReceiverId
are NON_NULL-serialized, preserving prior canonical request hashes when absent. Authorize
checks both expected people, rejects half-bound pairs; RETURN rejects these handover-only
fields. Current target scope is checked before reading/revealing its custodian.

Validation so far: handover web37tests/10files PASS14.52s; named-party final6/3 PASS6.04s,
TypeScript and new-product oxlint PASS before last active-WO display gate. Earlier backend
run8 had1 syntax error from reserved SQL alias authorization; fixed to handover. Next run
8 had1 expectation error (hidden location is404, not403); fixed. Shared custody/read and
existing handover regressions passed in those runs. Two subsequent queued runs stopped
at compileTestKotlin because new PartiesIT imported WarehouseCanonicalPayload from the
wrong package; now corrected to inventory.application.service. Do NOT use copied old XML
as new proof: require fresh BUILD SUCCESSFUL and ten selected nonzero tests.

CURRENT QA: .omo/runtime/material-handover-parties-final-server.sh running; log same base
.log; archive task40/material-handover-parties-final/xml. Selected MaterialHandoverWorkbenchIT2,
MaterialHandoverPartiesIT2, MaterialWorkbenchIT2, MyMaterialsIT3, existing LifecycleHandover1
=10 tests. Wait/inspect failures, fix and rerun as needed. Final new PartiesIT covers target
custodian change with unchanged WO revision, actual expected people, optional legacy hash/
exact replay, changed same-key payload and DB forged party/source metadata.

After backend: run full warehouse+WO web including src/pages/MyMaterialsPage.test.tsx,
src/pages/myMaterialDraft.test.ts, src/pages/MyMaterialHandoverGrants.test.tsx, then build/lint,
write safe verification evidence and commit/push. Task40/39 remain OPEN for final acceptance
and task41 customer-asset navigation; actual mobile touch/keyboard browser is45. Then41–48
and F1–F4 remain; goal ACTIVE.149 next free migration;177/178 reserved43. Original
warehouse-task29 preserved; working branch work/warehouse-completion tracks
origin/feat/warehouse-workorder. This checkpoint explicitly includes unverified final tests
for VPS recovery, not a completion claim. No deployment.

## Task40 unused serialized return verified — three-party handover next

Canonical residual return also handles acknowledged unused SERIAL stock; no backend
change required. MyMaterialsSerialReturnIT1 PASS1m58s proves former-assignee single ONU
return, exact replay, receiver quarantine and zero customer assignment. MyMaterialsPage
now exposes return for held serials; form requires a matching observed/manual/keyboard
serial, exact1EA, actual source/current revision/target refresh before review. Installed
assets continue through physical customer removal, not this own-ISSUED selector.

Targeted31web/7files PASS10.11s, TypeScript/lint PASS. Previous core full283/55 and build
PASS at79381bbe. Proof task40/my-materials-serial-return-verification.json. QA cleaned,
volumes retained. Current next40: add named scoped source/receiver read workbench for
independent dispatcher authorization plus current sender's immutable pending grants and
canonical dispatch. Recipient ACK UI already exists in own residuals. Keep40 checkbox
OPEN until this flow and41 cross-links are handled; browser actual touch/keyboard45.
39 also OPEN for41 links;41–48/F1–F4 remain. Goal ACTIVE, commit/push checkpoints.
No migration;148 next free;177/178 reserved43. Branch work/warehouse-completion tracks
origin/feat/warehouse-workorder; original warehouse-task29 preserved.

## Task40 Material Saya core verified — continue serialized return and handover

Current branch work/warehouse-completion, remote feat/warehouse-workorder. Original
warehouse-task29 preserved. New /my-materials route under Lapangan and WO cross-link,
self-only paged jobs/issues/custody/residuals, partial measured receipt, current measured
use with fresh source checks, own residual return after reassignment. Offline edits are
in-tab drafts, not stock or queued confirmations; reconnect rechecks current references
and assignment before review. User/tenant change unmounts local drafts. Keyboard/manual
serial selection and optional camera with permission/error fallback and track cleanup.
Captured body/key retained after ambiguous response. Scoped direct GET references hide
moved/revoked sources; historical context requires visible inventory when not assigned.

WarehouseReturns now has on-demand pending residual inbox and real-revision independent
ACK into quarantine, then existing intake/inspection flow. Warehouse receiver with only
return.view/manage + current area/location grants can ACK without broad WO view. Existing
WO lockForCustody internal permission includes return.manage; inventory owner still checks
RETURN purpose/independence/current target grant. All seven backend tests PASS4m15s after
fixture Jackson map correction; no authority weakening. Targeted web29/7 PASS8.71s, full
web283/55 PASS63.65s, TypeScript/build/new-product oxlint PASS. Proof task40/my-materials-
core-verification.json. Owned cleanup completed, volumes retained. No migration.

Next: new uncommitted MyMaterialsSerialReturnIT is queued/running via local ignored
.omo/runtime/my-materials-serial-return-server.sh/log. Canonical residual source appears
to support unused acknowledged SERIAL too; prove real return+ACK before enabling it in
myReturnInput/MyMaterialReturn/MyMaterialsPage (currently serial return UI blocked).
Then finish three-party technician handover authorization/sender dispatch UI using real
immutable authorizations, evaluate remaining40 acceptance, task41 customer-asset panel/
cross-links. Task39 remains OPEN for final41 links;40 OPEN pending above. Browser mobile
touch/keyboard is task45, not proven by jsdom.41–48/F1–F4 remain; goal ACTIVE.
Migration148 next free,177/178 reserved43. Continue committing/pushing coherent checkpoints.

## Task40 own material read checkpoint — 9 real backend tests PASS

Added current principal-only `/api/v1/warehouse/my-materials` job/context/custody/issues/
residuals/return-location reads, named records and actual revisions; scope and area
filters precede paging. Former assignee can inspect own physical remainder and current
WO revision for canonical return, while current team plan stays hidden. Current assignee
field context remains available. GETs never post stock. No migration.

MyMaterialsIT3 plus existing LifecycleReturns6 PASS3m41s. Proof task40/my-materials-read-
verification.json. Tests include partial60+40m exact replay, serial ownership, old-token
location revocation, foreign actor/tenant, reassigned82.5m use+17.5m return and quarantine.
Owned QA cleaned with volumes retained. Task40 remains OPEN. Web files in progress are
not included in this backend checkpoint: myMaterials API, scanner, receipt/return forms.
Next add direct scoped source/location/issue refresh (web API already anticipates routes),
finish own page/navigation, offline-use revalidation, warehouse pending residual ACK,
meaningful web tests and actual mobile browser later45.39 remains OPEN for40/41 links.
Then41–48/F1–F4; long-run goal ACTIVE.148 next free;177/178 reserved43.


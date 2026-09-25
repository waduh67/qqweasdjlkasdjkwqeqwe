## Verified checkpoint:241focused cases pass; current historical and full gates pending

2c1d8e08171a1914e99987f5b930c572630b0d4f is pushed on validation branch and integrated
in original. Originalsession91758/compatibility-focused-r4.sh is STILL RUNNING its
seven historical tests. Its modern phase FINISHED PASS:241tests/23suites, zero failures,
errors or skipped,17m40s. All3DeleteTenantHTTP cases pass, including empty204 with a
protected bystander and protected-history409/no effects; the178.8→178.9 control-cascade
upgrade test,178.8scope test,58-guardcatalog and provenance controls all pass. Reports
are preserved, hashes checked, safe proof task46/local-focused-r4-verification.json.
Original source remains FROZEN until historical wrapper exits. Edit only validation
checkout as needed; then fast-forward original after archive. No oldpatch reapplication.

CI36131581036 at2c1d8e08 passed focused stage and is in fullserver/historical stage.
All nonserver jobs PASS:22browser+6legacybrowser+576web+44shared,2actual iOS compilations,
real image smoke/restart. All12encrypted archives downloaded/hash-verified/decrypted
privately and actual reports revalidated; native task log checked separately. Legacy
V172→178.9 preserves2customers/2ONUs/2UI-createdcatalog SKUs after restart, with no
available stock;2positive and6negative preflight output hashes all match. Safe proof:
 task46/ci-2c1d8e08-nonserver-verification.json. This is NOT aggregate/fullserverPASS.

New handover screenshot was visually reviewed: installed loan ONU, ISP ownership,
actual receiving/issue references and accepted customer handover are visible; no
upload toasts obscure it. Task45/numeric-1789-handover-verification.json and reviewed
numeric-1789-accepted-loan-mobile.png save this. No product behavior changed for capture.
Oldb360 featureCI completed with149focusedPASS/projection1PASS/historical3PASS1FAIL;
its same cached-plan title failure is fixed by the pool eviction, currenthistorical
runtime pending. Safe classification task46/ci-b360-historical-failure.json.

NEXT: preserve currenthistorical outcome; fix any actual failure in validation; once
all7pass, fast-forward original and run PREPAREDfull-server-r6.sh (not launched).
It requires23focused+7historical PASS and unchanged source, then unfiltered server
with14400s modern deadline, and requires>3700cases plus Modularity/Compatibility.
Olderfull-server-r4/r5 are obsolete. Runtime helpers are local only; if lost, recreate
from documented qa.sh commands and these proof requirements. Use umask077 BEFORE
outer-shell log redirects. Original runtime directory is0700, focused rawlogs0600.

No complete corrected unfiltered modern regression yet. GoalACTIVE;1–45DONE;
46IN PROGRESS,47–48/F1–F4OPEN. No independentreviewer agents authorized or spawned;
optional user questions unanswered, no approval inferred. No mainmerge/publish/deploy.
Commit/push this evidence checkpoint and KEEP WORKING; neverfinal at a checkpoint.

## Empty-tenant control correction prepared after229-case focused verification

GoalACTIVE;1–45DONE,46IN PROGRESS,47–48/F1–F4OPEN. Continue aftercommit/push.
Original64e50573/session6617 has FINISHED. Complete local and CI36129278994 focused
results match:229tests/1failure/0errors/0skips across21classes. All scope178.8,
provenance,58-validator catalog, manual conflict and protected-history deletion checks
PASS. The only failure is empty tenant expected204/got409. CI full/historical were
not reached. Local session returned143 despite complete BUILD FAILED/XML and successful
owned cleanup; no signal cause established. Reports are preserved in focused-r3-reports.
Safe proof: task46/focused-r3-empty-tenant-failure.json. No unfiltered PASS exists.

A read-only app-role query of the failed tenant found only ordinary onboarding rows
plus two protected controls:iam_authorization_epoch and inventory_tenant_cutover.
Prepared ADDITIVE178.9 changes only those two FKs to tenant-delete cascades and adds
an INVOKER delete guard requiring matching row scope and an already absent parent.
Direct control deletion while the tenant exists still rejects; no epoch reset, grants,
RLS disable, security-definer bypass or business-history deletion is introduced.
TenantEraser locks the root before checking history, skips those two controls until
the final parent delete, and still rejects protected business history before deletion.
New old178.8→178.9 test checks preserved epoch bytes, direct-delete denial, wrong-scope
rollback, nonempty FK rejection, scoped empty cascade, and untouched other tenant.
Existing actual HTTP204/409/400 tests remain. Runtime of these changes is PENDING.
CI focused selection is now23classes, followed by mandatory7historical and fullserver.
Highestmigration178.9, next178.10. Never edit applied178.8 or older migration bytes.

All nonserver jobs for64e50573 PASSED:22browser,6legacybrowser,576web,44shared,
2actual iOS compilation targets and real image smoke/restart. All12 encrypted archives
were downloaded/hash-verified/decrypted privately, executed reports revalidated; native
log checked separately. Legacy178.8 kept2customers/2ONUs,2UIcatalog SKUs afterrestart,
no available stock, and passed2positive+6negative SQLpreflights. Safe aggregate:
 task46/ci-64e50573-nonserver-verification.json. Raw secrets remain ignored/private.
Reviewed numeric mobile screenshot shows917,500m cable/9ONUs, saved in task45.
Its earlier handover image missed the episode and had transient upload notifications;
new screenshot-only helper waits for normal toast expiry and scrolls to the history.
E2E TypeScript, workflowguard5, actionlint/shellsyntax passed; new browsercapture PENDING.

NEXT: commit/push current correction, fast-forward original from validation only after
checking clean state, then a NEWfocused-r4 runner archives/hash-checks R3 before
running23classes+7historical. Full-server-r5.sh is now OUTDATED(21classes); prepare a
NEWfull runner once focused/historical pass. Do not launch obsolete runners or reapply
oldpatches. Original currentlyfree; use one checkout for runtime and another for fixes.
FeatureCI36127543781/b360 was still active at lastcheck; preserve its eventual outcome.
Notes-onlyCI36129663422 was cancelled BEFORE start; activeCIwas never cancelled.
User's optional reviewer-agent and deletion-policy questions remain unanswered.
No subagents, independent verdict, main merge, publication or deployment authorized.
Keepworking afterpush; neverfinal at a checkpoint.

## Running the corrected21-suite gate; mobile label visually inspected

64e5057378df0b370c42dbe8d0d53b589a535126 is committed and pushed to the validation
branch. Original was safely fast-forwarded to64e50573; compatibility-focused-r3.sh
is RUNNING there/session6617 with21selected classes followed by7historical upgrades.
Compilation passed and actual tests have begun. Original sources/wrapper are FROZEN;
make necessary fixes only in this validation checkout until that runner exits.
Previous149focused XML was hash checked and preserved before clearing active XML.
Prepared full-server-r5.sh is NOT launched and requires21focused+7historical PASS,
unchanged source inputs, no tracked edits, then unfiltered fullserver with14400s limit.
Olderfull-server-r4.sh is obsolete and must not be used.

CI36129278994 validates64e50573 on the validation branch. Feature36127543781/b360
continues its complete full attempt, with allotherjobsPASS; preserve its final outcome.
Do not cancel active CI. Migration178.8 and new tenant-deletion behavior still await
actual focused/full results; do not describe this checkpoint as verified runtime.

Downloaded/hash-checked/decrypted CI36126213391 returns artifact10860537665 and
revalidated actual4Playwright cases (desktop2/mobile2, no skipped/flaky/failures).
Visually inspected the mobile screenshot: Status teknis:Dikerjakan, no inherited
shipment/custody on the reassigned technician. Complete web Git tree matches64e50573.
Safe proof and reviewed synthetic screenshot are in task45/localized-status-browser-
verification.json and reassigned-technician-localized-mobile.png. This does not
certify the new server178.8; CI above exercises those current inputs separately.

Continue core work after commit/push. GoalACTIVE;46–48/F1–F4 pending. User preference
about immutable-history deletion and reviewer-agent authorization remain unanswered;
no approval inferred, no subagents spawned. Do not final at a checkpoint.

## Active checkpoint: seven late regressions and a historical JDBC fixture correction

Goal ACTIVE. Tasks1–45 DONE;46 IN PROGRESS;47–48/F1–F4 OPEN. Continue after commits.
Primary EDIT checkout is again .omo/runtime/warehouse-regression-validation on
work/warehouse-regression-fixtures. Original b3609065 focusedR2/session51192 has
FINISHED exit1: all149 modern tests PASS, fulfillment2 historical PASS, deployment1
PASS, title1 FAIL (PostgreSQL0A000 stale prepared result shape after in-place DDL).
Episode was not reached. Original executing inputs were never edited. Reports/log
remain private in compatibility-focused-r2* and historical-upgrades latest directory.
Safe proofs: task46/local-focused-r2-verification.json and local-historical-r2-failure.json.

CI36116539885 finally completed its FULL modern suite:3787tests/48failures/0errors/
0skips across614suites. Artifact10860197914 was downloaded, hash verified and privately
decrypted. Safe complete proof task46/ci-r2-complete-server-failure.json. Seven later
failures were beyond the previously known41; this is a complete FAILED run.

Current corrections, runtime still PENDING: additive migration178.8 checks return-title
scope BEFORE any RLS-filtered query; exact58-function deferred entry catalog and an
old178.7/new178.8 scope upgrade test; current opening source denial; two additional
unchanged historical HTTP tests moved to version-correct pins (seven total); manual
conflict fixture creates its own episodes; tenant erasure skips empty protected tables
and rejects existing immutable history before any deletion. The latter assumes the
existing permanent-history contract, with Suspend as the available alternative; an
optional user preference question is pending, no answer/approval claimed. Historical
title test now evicts pooled sessions immediately after DDL to match application
restart. All corruption, scope, replay, byte-preservation and positive controls remain.
Workflow guard5/actionlint/shellsyntax PASS; new Kotlin/database runtime NOT yet run.

NEXT: commit/push this checkpoint, archive current149 reports before any new run,
fast-forward original safely from this branch, run21focused classes+7historical checks,
fix actual failures and then run unfiltered full14400s server. The old full-server-r4
wrapper expects16classes/5historical and is OUTDATED: prepare a NEW runner. No old
pending patches should be reapplied. No full successful regression exists yet.

CI36127543781 atb360 is RUNNING fullserver; allother jobsPASS including latestlabels.
CI36126213391 at6853 completed: nonserverPASS, focused monitoring10oldfailures (fixed
byd8a, now149locallyPASS). Validationd8a CI36126725025 was cancelled BEFORE execution.
Download6853orcurrentb360returns artifact to visually verify new Indonesian badge.
No active CI has been cancelled; no subagents authorized; no main merge/publish/deploy.
Commit, push, update notes frequently and KEEP WORKING; no final at a checkpoint.

## Primary checkout resumed after the old full regression was archived

Continue in the ORIGINAL checkout on `work/warehouse-completion`. It was safely
fast-forwarded from0a14cc2f to d8a3660e after the old runner exited and preflight
finished; d8a3660e is now pushed to feat/warehouse-workorder as well. All fixture,
browser, label and CI fixes are already applied. Do not reapply pending patches.
The validation worktree remains at d8a3660e as a reference; do not make competing
changes there now.

Local full R3/session11018 exited124 at its original7200-second deadline. All
available log/XML/binary are preserved in full-server-r3-complete-archive. Status
INCOMPLETE: observed2319 passed/41 failed includes7initial environment checks and1
historical projection check. Modern XML was never finalized; the only XML is the
old7-case environment report. Safe archive hashes/counts/case names are in
 task46/full-server-r3-incomplete-verification.json. Product server sources match
between initial environment commit38e068e3 and0a14, but Docker/environment-test
files differ; do not claim all earlier test inputs identical. No successful full
regression is established. No new failure beyond the known41 was observed.

The old preflight waiter47709 terminated143 before any output or SQL report; cause
unknown. Fresh runbook-preflight-r3.sh/session70814 completed0: app-role read-only
SQL passed with2customers/3ONUs/39positions/31movements; wrong migration/unit/cutover
each failed for the intended reason. Owned containers/network stopped; volumes
retained. Task47/local-preflight-r3-verification.json records this positive-stock
probe; the separate CI upgraded-fixture proof remains valid.

NEXT: commit/push recovery notes, then launch compatibility-focused-r2.sh with a
private log. It validates the R3 archive before replacing active XML, executes16
modern suites, archives reports and runs5historical upgrades. Do not edit executing
inputs. Fix failures and rerun affected checks. When that passes, prepared
full-server-r4.sh runs unfiltered regression with the updated14400-second deadline.
Both wrappers hold the outer host lock and retain volumes. If runtime wrappers
are lost, reproduce these steps using documented qa.sh commands, archiving first.

CI36126213391 at6853 is active (label/encrypted-binary changes, old monitoring
fixtures). CI36126725025 atd8a3660e is pending with the monitoring correction. Older
CI36116539885 still runs its original full attempt. No active run was cancelled.
Tasks46-48/F1-F4 remain open; independent reviewers have not been authorized.
GoalACTIVE. Continue after commits; no final response at a checkpoint.

## Focused CI result:139/149 passed; monitoring fixture correction pending

CI36125103447 at53e9 compiled all current tests and executed all16selected suites:
149tests/10failures/0errors/0skips. Raw encrypted server artifact10859408443 was
ZIP-hash checked, decrypted privately, and its XML preserved. Safe per-suite report
and source inventory: task46/ci-r4-monitoring-fixture-correction.json.

The remaining failures are Incident5, Notification2, NetworkEndToEnd2, Predictive1.
Nine cases report OLT-X against a generated attached OLT, so PATH_MISMATCH correctly
excludes their metrics. Fix uses actual per-tenant OLT code. Predictive's samples
predate the staged ONU and exceed collector72hour admission; explicit legacycreatedAt
now predates all samples, and7samples at6hourintervals preserve the original-1dB/day
trend plus7flatcontrol samples. All4fixtures additionally assert every metric accepted
and no unknownserials. Every original test method and behavioral expectation remains.
No product guard or migration changes. Corrected runtime/compile are stillPENDING.

Checkpoint6853f15a is pushed; its CI36126213391 was pending and may be superseded
by this newer fixture push. The 53e9run's nonserver jobs have mostly passed; preserve
its completed result. OriginalR3/session11018 remains frozen at0a14; preflight47709
stillqueued. Continue in validation checkout, archive originalR3 afterexit, allow
preflight to finish, FAST-FORWARD original, then16focused+5historical/fullserver.
Do not reapply pending patches. GoalACTIVE; continue after eachcommit/push.

## Interrupted Gradle diagnostics retained in encrypted CI artifacts

Checkpoint b17474d0 is pushed with the label correction and safe returns/legacy
proof. CI36125103447 at53e9 is still running16focused fixtures after successful
compilation; b174's CI36125963923 is pending and may be replaced by the next push.
OriginalR3/session11018 and queuedpreflight47709 remain active; original0a14frozen.

Source review found ci-artifacts.py omitted Gradle .bin files. A real age roundtrip
regression first reproduced binary-only evidence being rejected; adding .bin to the
encrypted attachment allowlist preserves partial diagnostics when XML has not been
written. All4artifact tests pass, including exact binary recovery, no public payload
output, env/symlink rejection, and the result parser still emitting FAILED without
final JUnit. This change does not accept partial results or weaken release gates.
Safe proof: task48/interrupted-gradle-evidence-verification.json. CI runtime pending.

Keep working in validation checkout; preserve/archive originalR3 after its exit,
allow preflight to finish, then fast-forward original and run focused/historical/full
checks. Do not reapply old patches. GoalACTIVE; no final checkpoint response.

## Verified reassignment/return and upgraded-database preflight supplement

CI36124045412 at c227f6bc completed: all nonserver jobs PASS. Server compile failed
on the removed onboarding field, already corrected in7013 and compiled successfully
in CI36125103447 at53e9. The latter is now running16focused fixture suites; full
server is still pending. No aggregate PASS is claimed.

Downloaded returns and legacy artifacts, verified GitHub ZIP/encrypted hashes,
decrypted privately, and revalidated actual Playwright reports against safe proofs:
returns4 (desktop2/mobile2), legacy6 (before/after/restart2each). Reassignment retains
old custody and gives the new technician zero inherited stock; cancel does not
restock; physical return/inspection restores the exact ONU. Upgraded V172 legacy
keeps2customers/2ONUs, persists2UI-created MM SKUs after restart, and still creates
no available stock. Read-only SQL in that same upgraded DB passes2positive probes
and rejects6wrong migration/unit/cutover probes; all8raw output hashes match.
Safe proof is in task45/ci-r3-return-reassignment-verification.json and
 task47/ci-r3-upgraded-preflight-verification.json. Authenticated raw artifacts stay
ignored/private. Two mobile screenshots were visually reviewed and safely saved.

The new-technician screenshot exposed a raw IN_PROGRESS badge. WarehouseStatus now
labels ASSIGNED/IN_PROGRESS/DONE in Indonesian. MyMaterialsPage7tests and full web
lint pass (existing warnings remain). Browser proof for this label change is still
pending; do not attribute old c227 screenshots to the new label source.

Originalcheckout remains0a14and frozen with R3/session11018 running; preflight47709
waits for its lock. Continue in work/warehouse-regression-fixtures. After R3 exits,
run archive-full-server-r3.py, let preflight finish, then FAST-FORWARD original to
this branch. Do not reapply patches. Run16focused+5historical and fixed full server;
continue final browser/CI/audits. No reviewer agents authorized yet. GoalACTIVE;
tasks46-48/F1-F4 remain open. Commit/push checkpoints, then keep working.

## Additional full-suite wire contract correction

OriginalR3 isstillRUNNING with41observedfailures. The new failure is
WarehouseContractTest's exact enum inventory: WarehouseReturnState already exposes
LOST from the implemented loss workflow, but the old expected list ends atSCRAP.
Only that explicit expected string is extended withLOST; exact ordered equality and
serialization/deserialization of every member remain required. All other enumerated
contracts match current source. Include WarehouseContractTest in focused gates
(now16classes). Runtime verification is stillpending.

Validation branch7013b28e is pushed; it fixes the unused multiline onboarding field
and adds the early15-classcompatibility stage beforemandatoryfullserver. CI for
c227f6bc isstillrunning its otherjobs afterservercompilefailed. Its web/shared/native
andprovenancebrowser passed sofar. The next queuedCI must use thelatest correction.
A mistyped extra remote refs/refs/heads/work/warehouse-regression-fixtures was created
by the push, then explicitly removed; the real work/warehouse-regression-fixtures
branch was updated normally. No existing branch/ref/data was removed or rewritten.

Originalcheckout remains0a14andfrozen untilR3exit/archive; preflight47709stillqueued.
Continue fixes HERE, thenfast-forwardoriginal afterarchive, run16focused+historical,
fullserver/browser/legacypreflight, finalCI/audits. GoalACTIVE; no checkpointfinal.

## CI compilation correction and early fixture verification

CI36124045412 at c227f6bc started on work/warehouse-regression-fixtures. Web/shared
passed; server compile failed on a leftover multiline onboarding field in
NetworkEndToEndIT (removed import, still-unused field). The field is now removed.
The remaining browser/legacy/native/images jobs from that run continue; do not
cancel them before their evidence is captured. No server runtimePASS from thisrun.

The server CI job now runs all15repaired modernfixtureclasses immediately after
compilation, before the unfiltered full gate. Each selectedsuite must appear; every
actualcase must pass. FocusedXML/binary/logs are preserved separately and successful
activeXML is cleared before the full gate, preventing stale focusedPASS on a later
interruption. Both focused and full stages remain required before acceptance.
Five workflow guards andactionlintpass after this change. Full current browser
TypeScript and24guardtests passed at c227; Kotlincompile must rerun after thisfix.

Corrected pending-network-fixtures.patch.gz can still restore the full nine-class
change against original0a14; current validation-branch source is authoritative and
alreadyincludes allninepatches. Do not reapply those patches here. Originalcheckout
stillfrozen at0a14, localR3session11018 andqueuedpreflight47709remainactive.
Follow the isolated-checkout instructions below, fast-forward only after archiving
originalR3, andcontinue until allrealgates/audits are finished. GoalACTIVE.

## Isolated regression validation checkout: fixes applied, runtime results pending

This branch is `work/warehouse-regression-fixtures`, created from0a14cc2f in
`.omo/runtime/warehouse-regression-validation`. The original checkout remains at
0a14cc2f with FULLR3/session11018 running against its unchanged compiled inputs.
PreflightR2/session47709 still waits on its hostlock. DO NOTedit the original
executing sources/wrappers or overwrite its results. No subagents were started.

All nine current pending patches are now applied HERE: explicit-area network/real
receipt-issue-install fixture, predictive legacy history, genuine RMA source controls,
handover source denial, orphan vs duplicate revision probes, five version-correct
historical upgrades, return/reassign/cancel browser supplement, template malformed
return negative, and actual upgraded-DB preflight. Old episode-only patch was NOTused.
Runbook/CI docs match these final source changes. Source-set moves retain every
historical assertion and remove only mocks of components absent in the historicalapp.

Static validation HERE: full warehouse browser TypeScript passes,24guardtests pass
(11results+3encryptedartifacts+5workflow+5publication), bashsyntax andactionlint pass.
Kotlin compilation and runtime tests have NOTyet passed. No completed-task claim.
CI now runs on work/warehouse-* branches as well as feat/warehouse-workorder; this
allows isolated validation while the original red full run finishes. Current test
sources compile first, then all historical/server/browser/legacy/web/shared/native/
image gates remain mandatory. This does not publish images or deploy production.

Next: push this branch for CI; inspect early Kotlin/historical/browser failures and
fix HERE. Keep originalR3 running to discover remaining failures. Once R3 exits,
archive its log/XML/binary with an honest complete/incomplete status, inspect queued
preflight, and fast-forward the original checkout to the verified fixes (no reset,
rebase, stash, or discarded edits). Then run15focusedmodernclasses plus5historical,
newbrowser/legacy supplements, full local regression and final featureCI. Update
remote feat/warehouse-workorder with normal fast-forward checkpoints. All46–48/F1–F4
remainopen; goalACTIVE, continue after commit/push, no final response at checkpoints.

## Further full-regression finding and upgraded-preflight supplement prepared

Pushed checkpoint21c832bf follows1201b6c2. FullR3/session11018 STILLRUNNING:
latest observed1868passed/40failed, notfinalcounts. Preflight47709 stillqueued.
CI36116539885 stillactive; queued docs-only36122515683 was cancelled beforeexecution.
Keep source/QA freeze until R3 exits; archivebeforeanyfocusedrerun.

New pending-template-action.patch.gz fixes the40th observedfailure:
WorkOrderMaterialsITTemplateActions line20 expected409 for empty returnbody, actual400.
The real MaterialSettlementController now decodes MaterialResidualRequest. Template
persistence/replay, non-selection on ordinary REPAIR and missing-demand denial remain;
additional real DB assertions require zero movements/balances/assignments. No product
validation changed. Patch is NOTapplied/compiled/executed.

New pending-legacy-preflight.patch.gz extends the same6legacybrowsertests: after
independent V172zero-openingcutover, create an actual MM catalog SKU viaUI; read it
afterrestart; stillrequirezeroavailable stock andunchangedoldcustomer/ONUidentity.
Run read-only preflight.sql in that same upgradedDB forboth tenants:2positive+6
wrongversion/unit/cutovernegatives. BrowsercapturedSKU IDs bind the SQLprobe; no
businessSQLwrites. This covers the final review's upgraded-fixture preflight requirement.
DraftTypeScriptcompile,bashsyntaxandapplycheckPASS; noactualbrowser/SQLPASSyet.

Private recovery scripts prepared, NOTlaunched:
- archive-full-server-r3.py: run ONLYafter originalsessionexits; preserveslog,all
  XML/in-progressbinary andwrappercounts in full-server-r3-complete-archive;
  classifiesFAILED_COMPLETEorINCOMPLETE, nevercountsoldfocusedXMLasfullPASS.
- compatibility-focused-r2.sh supersedesR1: validates that archive and its hashes,
  requiresall5historicaltests moved, then15modernclasses (old14plusTemplateActions),
  preservesfocusedreports beforethefivehistoricalupgradegroups. Handles honest
  incompleteR3 iftheoriginal7200sdeadlinecutsitshort. Do noteditoncequeued/running.
The archive script and source review observations are runtime-only; source fixes are
saved in pending patches. Ifthese runtimefilesareunavailable, reconstruct thesame
archive-before-rerunprocedure from thisnote; neverinferanoldPASS.

Corrected the prior note:12customercreation sites receive explicitMAINarea across
9networkclasses, with37originaltestmethods retained (JSONinventory authoritative).
GoalACTIVE; tasks46–48/F1–F4remainopen. Continueaftercommit/push; nofinalcheckpoint.

## Pending network fixture corrected before execution

Latest source/evidence checkpoint1201b6c2 is pushed. LocalR3/session11018 still
RUNNING (>1666passed/39failed observed); preflight47709 stillqueued; CI36116539885
serverstillrunning. Queued docs-only CI36122060200 was cancelled before execution.

The prepared network patch now explicitly assigns MAIN area on every customer
creation request (12sites across9classes). The previous draft only created area and
admin scope; CustomerService preserves a null request area, so a restricted installer
would not be able to use those customers. Existing AutoProvisioningIT confirms the
required explicit-area setup. Corrected pending-network-fixtures.patch.gz, its JSON
inventory and pending-patch-manifest.json are authoritative. This is still NOT applied,
compiled or runtime-tested. All37original methods and assertions remain unchanged.

Continue after checkpoint; archive R3 then apply fixes/focused/historical/full runs.

## Runtime resilience checkpoint: temporary Btrfs swap added

Full R3/session11018 and CI36116539885 are still running; no final PASS. Local
observation is1513passed/39failed, with existing fixes still unapplied. Preflight
R2/session47709 remains queued. Do not edit active or queued QA/source inputs.

At10:04UTC the15GiB host had only~400MiB available and its512MiB swap was full.
Created and activated a4GiB Btrfs-compatible swap file at
`/swap/warehouse-development.swap` using `btrfs filesystem mkswapfile` and `swapon`.
No process was killed, no existing swap changed, and no fstab/persistent boot change.
If the VPS reboots, the file persists but needs `sudo swapon /swap/warehouse-development.swap`
to reactivate. Do not `swapoff` while RAM is constrained. Once development stops
and sufficient free RAM exists, the task-owned file can be deactivated and removed.
Root Codex itself uses~9.6GiB RSS; continue serial QA and avoid additional JVMs.

Read-only source review confirms all pinned migrations match byte-for-byte at the
three proposed historical app heads (222/265/275files). Preliminary posting/unit/
CI/publication observations are saved privately in release-source-review-preliminary.json;
they are NOT final independent F1–F4 approval. Core next step remains complete R3,
preserve all available results, fix fixtures, focused/historical/full retest.

## Historical regression supplement: five version-correct application fixtures prepared

Follows pushed0ee64efd. Full R3/session11018 STILL RUNNING, now over1386passed and
39failed observed; no final result. Preflight R2/session47709 remains queued. Freeze
server/test/QA sources until R3 exits and preserve all available reports before fixes.
CI36116539885 at c622a85b is still active; queued checkpoint-only CI36120239630 was
cancelled before execution. No active full report has been cancelled.

The latest pending-historical-upgrades.patch.gz now covers FIVE tests and supersedes
its old3-test version plus obsolete pending-episode-upgrade.patch.gz. Confirmed R3
failures include both WarehouseFulfillmentITOwnerUpgrade and BngLineageUpgrade:
modern ONU mapping requires retired_at absent in their175.29/175.34 schemas.
Their historical app is pinned3bfe12331428eb43740c8c0399b61a956c8820a7 (schema175.36).
The only test behavior change is removing the mocked later MaterialSettlementService;
all original source, tenant timing, replay and preservation assertions remain.
Customer title/deployment use fd2cf7c5; episode uses dfa25e79. All groups add exact
current migrations, current isolated-DB adapter and migration inventory helper;
the customer deployment group also overlays the reviewed orphan-revision fixture.

Future qa.sh full regression deadline becomes14400s; CI server job360minutes,
including bounded historical groups. R3 remains on its original7200s deadline and
has not been modified. If it times out, preserve log and in-progress binary results,
mark the attempt incomplete, and run the fixed full suite again. Never count old
focused XML as a complete server result. The private prepared compatibility-focused-r1.sh
assumes a normally finished R3 with matching WAREHOUSE_COUNTS/XML; adapt a NEW runner
if timeout prevents complete XML. It has NOT been launched. It selects14 modern
suites, requires every selected suite, archives results before historical groups,
and validates the old full report hashes before clearing stale root XML.

Additional pending patches remain as listed in the preceding checkpoint. Latest
historical draft passes bash syntax, actionlint and git apply --check, but has not
compiled/executed. No product or migration changes made at this checkpoint.
The next core step is still complete R3 -> fix all failures -> focused/full reruns.

An optional asynchronous question was sent asking the user to authorize separate
reviewer agents for the plan's independent F1–F4 audit. NO ANSWER YET; do not spawn
agents without explicit authorization. Core work continues while awaiting a reply.
Goal ACTIVE; tasks46–48/F1–F4 remain open. Continue after commit/push; do not final.

## Latest recovery checkpoint: expanded regression fixture corrections prepared

Goal ACTIVE. Tasks 1–45 DONE;46 IN PROGRESS;47–48/F1–F4 OPEN. Continue after push.
Remote prior checkpoint362f321c; local work/warehouse-completion targets
origin/feat/warehouse-workorder. No main merge, image publication or deployment.

Full server R3/session11018 is STILL RUNNING; at least1208 passed/37failed observed,
not final counts. Exact safe observation: task46/full-server-r3-progress.json.
Preflight R2/session47709 remains queued on the host lock. Sources/wrappers are
FROZEN. Archive complete R3 XML/counts before applying any patch or focused rerun.
CI36116539885/c622a85b full server also continues. All other jobs passed. Duplicate
queued CI36119073644 for docs checkpoint was cancelled before it began; bb0a's
older pending run was replaced by concurrency. Do not cancel the active full run.

Prepared patches in task46 (all gzip source-only, never runtime credentials):
1. pending-network-fixtures.patch.gz —9legacy network classes, real receipt/issue/ACK/install.
2. pending-predictive-history.patch.gz —existing historical LEGACY_UNRESOLVED fixture.
3. pending-rma-source-control.patch.gz —fake positive becomes negative; actual RMA positive strengthened.
4. pending-handover-source-denial.patch.gz —CLOSED/INACTIVE historical corruption returns
   SOURCE_NOT_VERIFIED before ordinary state checks; all no-effect assertions stay.
5. pending-deployment-orphan.patch.gz —deferred orphan probes use next unused physical
   revision; a separate test retains immediate duplicate revision23505/no-effects proof.
6. pending-historical-upgrades.patch.gz —SUPERSEDES pending-episode-upgrade.patch.gz.
   Move three unchanged historical tests into mandatory qa.sh historical-upgrades;
   pinned fd2cf7c5 for title175.69/deployment175.63 and dfa25e79 for episode175.86,
   each applying the entire current migration chain in a separate database. Overlays
   the updated orphan helper after patch5. No product sources or old migrations altered.
7. pending-browser-reassignment.patch.gz —additional real UI review of reassignment,
   no inherited custody, cancellation and same-unit return in returns.spec.ts. Draft
   TypeScript compiles in private browser-draft; NOT APPLIED or executed in browser.
Apply check passes for every patch; backend compilation/runtime has NOT run yet.
Do not apply obsolete episode-only patch alongside expanded historical patch.
Actual result binary confirms missing inventory_repair_replacement_request in old
schemas, RMA_HANDOVER_EXECUTION_REQUIRED, SOURCE_NOT_VERIFIED vs STALE_REVISION,
and23505 masking the five deferred orphan checks. Final XML remains authoritative.

Two other historical HTTP fixtures may need the same treatment if full R3 proves
failures: WarehouseFulfillmentITOwnerUpgrade at175.29 and BngLineageUpgrade at175.34.
They currently mock the later MaterialSettlementService. Inspect actual failures;
prefer the correct historical application without disabling current guards. Do not
weaken expected tenant-scope, authorization or immutable-history assertions.

All declared G/W/B QA sources for48planrows exist; safe inventory is source mapping
only, not evidence that all tests passed. Runbooks are committed but SQL preflight
still must execute. Next: complete R3 -> inspect every failure -> apply/fix focused
fixtures and historical gates -> returns browser supplement -> full server/final CI
-> preflight/runbook proof -> final audits and concrete review. No subagents or
independent reviewer approval claimed; no native release from compile evidence.

## Current checkpoint: CI evidence retained; full server regression still running

Branch work/warehouse-completion -> origin/feat/warehouse-workorder. Tasks 1–45
DONE; 46 IN PROGRESS; 47–48/F1–F4 OPEN. Goal stays ACTIVE. Continue after push.
The previous remote checkpoint is bb0a4603. No merge, publish or deployment.

CI run 36116539885 at c622a85b has passed all eight browser jobs (22 tests), legacy
upgrade/restart (6), web (576), shared (44), both iOS compile targets and actual
Docker smoke. All 11 encrypted archives were downloaded, hash-checked, decrypted
privately and matched against successful executed cases. Safe aggregate proofs:
task46/ci-browser-web-shared-r2.json and ci-native-legacy-r2.json. Full server and
aggregate acceptance are still pending; do not call the workflow green.

Local full-server-r3.sh/log remains ACTIVE (session 11018), over 990 passed and 30
failed cases observed so far, not final counts. Preflight R2 (session 47709) waits
for the same host lock. Preserve complete R3 XML/counts before any focused rerun.
Do not edit executing/queued wrappers, server/test/QA sources or applied migrations.
Network, predictive-history and RMA source patches remain unapplied in task46
pending-*.patch.gz. A fourth pending-episode-upgrade.patch.gz moves the unchanged
V175.86 test into a mandatory historical-application gate, applies all current
migrations, and preserves every original assertion. Syntax/actionlint/apply-check
pass only; no compile or runtime PASS. Confirm the actual XML cause before applying.
Handover CLOSED/INACTIVE also fail an expected-code assertion; inspect final XML
and current source validation order before changing expectations.

Runbook and review drafts are now saved with this checkpoint. UI labels/relative
links match source; existing browser runs execute the documented flows. SQL preflight
is NOT yet verified: queued runbook-preflight-r2.sh must pass the actual app-role
probe plus wrong migration/unit/cutover negatives before task47 can close. The Arch
Chromium install note and cleanup preserve real failures. Draft historical runner
is not enabled until the full suite finishes. Next: full report -> fixture fixes ->
focused verification -> full regression/CI -> runbook proof -> final audits/review.
No subagents authorized; no independent-review approval or native release claimed.
Raw reports, decrypted archives, private env and traces remain in ignored runtime.

## Current checkpoint: image smoke PASS; full server running; repair patches saved

Goal ACTIVE. Tasks1–45DONE;46inprogress;47–48/F1–F4open. Continue afterpush.
No subagents. Current source c622a85b + documentation/evidence changes only.
CI36116539885: imagesPASS (14replays/16snapshots/4stockkinds before+restart); all8
browsersPASS; legacy6PASS; web576PASS/shared44PASS/native2COMPILED. Serverrunning.
Image tar/config hashes reverified locally; safe task48/docker-image-smoke-verification.json.
Private imagearchives in.omo/runtime/ci-remote-r2/tested-images; raw reports encrypted.

LIVE LOCAL full-serverR3 session11018, wrapper/log.omo/runtime/full-server-r3.sh/log.
Env7PASS, historicalPASS; modernfull currently27failures mostlyoldserial-onlyfixtures.
Do NOTedit runningbackend/QA/testsource oroverwritefullJUnit. Waitforcompletionand
archive. PreflightR2 session47709 queued withread-onlypositive+3negativechecks.
Prepared,NOTAPPLIED patches saved safely in task46 evidence: pending-network-fixtures.patch.gz
(nineclasses+actualwarehouse-registrationhelper),pending-predictive-history.patch.gz
(explicithistoricallegacydevice),pending-rma-source-control.patch.gz (genuineRMApositive
andfakesource-negative). All apply--checkPASS; no compile/executionclaim. Afterfull
reportarchive, applythenfocusedtest/fix, preserveoldreports, rerunfullsuite.

Warehouse concurrency now cancel-in-progress:false, so checkpoint pushes preserve
runningfullreport and queue thelatestfollowup. Uncommittedrunbookdocs47+preflight.sql
need actualprobeverification. Finishregressions/docs/gates/audits; no prematurefinal.

## Previous checkpoints (superseded)

## Current checkpoint: tasks1–45 done; full regression and Docker image CI active

Goal ACTIVE. Continue until46–48 and final audits are handled; no checkpoint final.
Branch work/warehouse-completion -> origin/feat/warehouse-workorder. No subagents.
Task45 completed in38e068e3:22real browser cases/eight specs; legacy6separatePASS.
QA dependency fix9ce591d5: MinIO built from checksum-pinned upstreamrelease source;
Timescale pinned to the existing tested digest. Environment7/7PASS including S3.
RemoteCI36114803368 had web576PASS/native2COMPILED/shared44PASS; overallFAILED
because formerMinIO registrydenial and parser[jvm] (fixed2907bc7f). Preserve raw
encrypted artifacts in .omo/runtime/ci-remote-r1; publicsafeproof task46.

LIVE full-serverR3 session11018 (.omo/runtime/full-server-r3.sh/log), underfd8.
Env7PASS, historicalprojectionPASS, complete modernserver suite RUNNING. Archive
.omo/runtime/full-server-r3-reports whenfinished. NEVER editactivewrapper/QA/server
inputs. PreflightR2 session47709 (.omo/runtime/runbook-preflight-r2.sh/log) queued.
OldR2 cancelledwhilequeued, zeroexecuted; MinIOsourcebuild91077completed successfully.

Current exact-image CI checkpoint adds mandatoryimages prerequisite, actualHTTP
smoke/restart, exportedtestedimages andcheckedpublication+immutabledeployreferences.
24guardtests andactionlintPASS; realDockerimagejob has NOTexecuted yet. Featurepush
startsCI; no imagepublish/mainmerge/deploy authorized orperformed. Draftdocs47 and
preflight.sql await actualpositive+3negativerun. Review CI thenfix failures, archive
fullserverreports, finishrunbooks/audits andkeepcommitting+pushingsafecheckpoints.

## Earlier checkpoints (superseded)

## Current checkpoint: task45 complete; continue final regression and release gates

Tasks1–45 DONE;46 IN PROGRESS;47–48/F1–F4 OPEN. Goal ACTIVE. No subagents authorized.
Browser R5 finished exit0:22 real tests/eight specs/both projects, no skipped/flaky.
Safe proof task45/browser-matrix-r5-verification.json and four inspected history PNGs.
LegacyR6 separately6PASS. Current product/source identity recorded in both proofs.
Remote CI36114803368 FAILED as expected: old pinned MinIO registry denies access.
Web576/115PASS; native2targetsCOMPILED; shared44/7modulesPASS, parser[jvm]bug now
fixed in2907bc7f and tested11PASS plus revalidated identical encrypted CI reports.
Full-serverR2 was safely cancelled WHILE QUEUED, zero tests, to use final dependency.
LIVE session91077: .omo/runtime/minio-source-build-r1.sh/log under hostfd8, building
QA-only MinIO from upstreamrelease source with pinned tar/base digests. No byte
equivalence claim to oldvendorimage. Do not edit executing Dockerfile/wrapper.
Next: wire verified source image + pinned Timescale in QA Compose, run environment
S3 tests and full server, run preflightpositive/3negative, finish exact Docker smoke
and CI. Uncommitted image/workflow/publish drafts have not run real containers yet.
Keep preserving private traces/env, retaining volumes, committing and pushing.

## Previous checkpoints (superseded)

## Current checkpoint: isolated fixtures, portal and legacy cutover verified; final matrix running

Goal ACTIVE; tasks 1–44 DONE, 45 IN PROGRESS, 46–48/F1–F4 OPEN. Keep working after push.
Branch work/warehouse-completion -> origin/feat/warehouse-workorder. No subagents authorized.
Latest committed fixes: ddf494b3 separate DATABASE/public for every migration/restart
fixture (isolation 10/10); fcdd987a AFTER_COMMIT portal contact index transaction
(60/60 portal tests); f49250b0 historical V172 UI -> current cutover -> restart (6/6).
QA shared warehouse_e2e functions recovered against a fresh migrated reference:
three deterministic functions restored, activation timestamp preserved, 322 tables /
31,173 business rows and all Flyway checksums unchanged. warehouse_test unchanged.
Never use sibling schemas for historical migrations that explicitly reference public.
V178.6 and V178.7 immutable in BOTH retained environments; next available V178.8.

Current browser matrix R5 uses unchanged product with corrected approval workbench
assertion and new scrolled asset-history screenshots. Five specs already pass:
setup 4, receiving 2, provenance 2, issue 2, returns 4. Exceptions is running;
numeric and customer-assets follow. Private reports/source hashes/artifacts:
.omo/runtime/task45-matrix-r5-<spec>-{report.json,source.json,artifacts/}.
Session 42129 / task45-browser-matrix-r5.sh/log. DO NOT edit ANY E2E files while active.
Full server R2 queued session 51803 / full-server-r2.sh/log under host fd8 lock.
It requires all eight R5 reports green first, then runs historical projection gate
and ALL modern server tests. Full gate deadline 7200s; focused remains 1800s.
Archives complete JUnit in .omo/runtime/full-server-r2-reports before any rerun.
Do not edit executing/queued wrappers or kill unrelated Java process 1045933.

CI foundation being committed: mandatory server/web/KMP/eight browser/legacy/native
jobs before publish/deploy; safe result summaries + encrypted raw reports. 17 Python
guard tests pass (including failing/missing/empty/skipped/flaky/cancelled cases),
actionlint passes. age and actionlint installed via pacman on Arch. Repository
variable WAREHOUSE_EVIDENCE_RECIPIENT is the existing user's SSH Ed25519 PUBLIC key;
private key never uploaded. Real five-report encryption roundtrip byte-identical.
Task48 NOT DONE: actual remote CI, exact Docker image smoke and publication identity
must still be completed. Docker context/bootJar fixes, docs47 and preflight remain
drafts. Preflight runner .omo/runtime/runbook-preflight-r1.sh exists but is NOT queued
or verified. Old active-session lists below are obsolete. Continue through 45–48
and final audits, preserving private credentials/traces and retaining QA volumes.

## Earlier checkpoints (superseded by the status above)

## Current checkpoint: issue, returns and count browser gates pass

Follows71d5cb9f. Goal ACTIVE; tasks1–44 DONE,45 IN PROGRESS,46–48/F1–F4 OPEN.
New safe proof task45/issue-return-count-browser-verification.json:8 realPASS,
2issue(R2,76.1s),4returns(R3,173.2s),2exceptions(R3,142.6s), both projects no skips/retries.
Restrictedtechnician ACK andunusedmixedcaseunitreturn->independentwarehouseACK,
intake/resetinspection10availableONU/900000MM; separate60/40transfer+independent
resolution; blindcounter,approvalonlycheckerREWORK_REQUIRED(noadjustment), physical
transitACKstales open count, appendedrecount0preserves100observation. Tablet768/theme
coverage also passes. No SQLstockseed/mockAPI. Sourcefiles andsafeproof committed.

LIVE fd8 queue, inspect logs, no editing running scripts or any E2E files:
-39842 task45-browser-matrix-r3: nowcustomer-assets4cases toverify drawer screenshots.
-24817 projection-upgrade-r1: queued; newhistoricalrunner/movedtest NOT VERIFIED YET.
-74710 portal-contact-green: queued; AFTER_COMMIT fix/test NOT VERIFIED YET.
-35108 legacy-browser-r4: queued; area pickerfix nowunit4PASS, realcutoverstillpending.
-21703 runbook-preflight-r1: queued; new read-only SQL with wrongversion/unit/cutover
  negative cases. Draftdocs47 andpreflight.sql NOT VERIFIED YET.
-76343 full-regression-r1: queued; full historical+current server --rerun-tasks.
  Its archive runs on failure too: if historical phase fails before current suite,
  do NOT treat copied prior XML as full-suite evidence; inspect log andsource identity.

Task48 preparatoryUNCOMMITTED Docker changes: .dockerignore excludes.omo/.env
(runtimecredentials were previously eligible for buildercontext);web .env/reports
excluded. ServerDockerfile uses exact GradlebootJar metadata and boundedbuildworkers,
no ambiguouswildcardorcompile retries. No Dockerimagebuilt/published/deployed yet.
CIworkflowimplementation stillpending. Docswarehouse.md/review +mobile/work-order/deploy
links drafted;preflight requiresrealSKU andknownexpectedunit/cutover,read-only approle.
Need executebefore marking47done. Earliernotesdescribe otheropenwork andsafety.
Continue longrun; commit/push and continue, no prematurefinal.

## Previous checkpoint (superseded)

## Current checkpoint: legacy customer area selector repaired; keep running

Follows a3c83602. Goal ACTIVE; tasks1–44 DONE,45 IN PROGRESS,46–48/F1–F4 OPEN.
Product CustomerAreaField now matches existing customer-admin empty-area-unrestricted
semantics. Scoped operators still see only their own descendants, absent permission
loads no areas, stored inaccessible assignment is preserved. Warehouse CurrentAuthority
and source/install guards unchanged. Red3tests1fail; green4tests2files PASS3.07s.
Targeted lint exit0 (existing effect-state warning), safe task45/customer-area-verification.json.

LegacyR3 oldUI2PASS, after-upgrade2FAIL at first area choice. Real bootstrap deadlock:
assigning admin area first hides unassigned legacy customer; assigning customer first
had no choices despite existing raw customer-admin unrestricted permission. R3 fixture
now creates area -> edits customer -> grants admin area -> login -> warehouse review.
Product fix above resolves picker. R4 queued, no cutover-after/restart PASS yet.

LIVE host fd8 queue (inspect actual logs):
- session39842 task45-browser-matrix-r3.sh/log RUNNING returns -> exceptions -> assets.
  Freeze all E2E source while matrix runs. R2 issue2PASS76.1s, returns2PASS2FAIL only
  wrong quantity location in response. R3 assertions now request.quantityBase and
  transit.stockIdentityId; actual return command was committed correctly.
- session24817 projection-upgrade-r1.sh/log queued. New test-only design moves
  ProjectionUpgrade to server/src/historicalTest and runs it over pinned historical
  app/fixturesc164c4eb4224fb51ebffd669c87caf4fde2af1df. All existing migration bytes
  compared with current before running. No mocks/SQL stock seeds/disabled checks.
  qa.sh full server gate now runs historical1test before current full suite;
  new qa.sh projection-upgrade mode for focused run. Scripts and move uncommitted,
  ShellCheck/bash PASS but execution PENDING. Newworktree warehouse-v175-fixture.
- session74710 portal-contact-green.sh/log queued. Real red1FAIL: new committedemail
  login401expected200. XML privately archived. Added syncCommittedContact with
  REQUIRES_NEW; ordinary sync keeps REQUIRED so sees uncommitted credentials.
  Green allportalpackage + PortalCustomerAssetsIT PENDING. No server fix committed yet.
- session35108 legacy-browser-r4.sh/log queued after those checks.

New docs warehouse.md/warehouse-review.md and links in work-order/mobile are draft47,
not complete: still need executed preflight/negative gates and deploy instructions.
R3 retained legacy artifacts .omo/runtime/warehouse-legacy-987d7d3a3fa372f68b3d2fdeb5dc048e.
All raw traces/env/XML private (public repo). Do not edit running scripts, delete
volumes, or kill unrelatedJava. Nextmigration178.8. Continue through all gates.

## Previous checkpoint (superseded)

## Current checkpoint: RMA migration verified in both retained environments

Follows aa22c32e; locate containing commit with git log -1. Goal ACTIVE.
Tasks1–44 DONE,45 IN PROGRESS,46–48/F1–F4 OPEN. CONTINUE after commit/push.
No subagents authorized. Branch work/warehouse-completion -> origin/feat/warehouse-workorder.

V178.7 is APPLIED/IMMUTABLE in BOTH default and retained OLD environments.
SHA726b103ef37215b2af736cde9b6cedb336b02b7726b042f4707b673ee02fc081.
RMA14/14 tests in4suites PASS; OLD populated upgrade9/9 in2suites PASS.
All2142 prior tenants and23 compared tables have unchanged counts/fingerprints;
all old migration checksums unchanged, only178.7 applied. Nextfree178.8.
Default marker98b38fbf54518f766065f31955d52574 restored; OLDabc63ab0045a7096566cf763c8f477fc retained.
Proof task45/rma-category-verification.json; private XMLs archived separately.
Current numeric browser2/2 also PASS122.5s with actual categoryONU and realonuId,
917500MM/9available/1installed. customer-assets4/4 PASS365.5s as previous checkpoint.
Safe numeric-onu-browser-verification.json supersedes old assignment-only limitation.
Native macOS run36097475699 compiled both actual iOS targets successfully at636209cf;
mobile/build inputs identical throughaa22c32e. task46/native-source-equivalence.json.
Native compile is not runtime/hardware/release proof.

qa.sh now removes prior Playwright JSON before build/readiness so a failed preflight
cannot accidentally reuse the preceding spec's result. Shared readiness loop cleanup;
ShellCheck -P SCRIPTDIR -x and bash -n PASS. Product/browser inputs unchanged by checkpoint.

RUNNING/QUEUED (read logs before new work):
- session1401 .omo/runtime/task45-browser-matrix-r2.sh/log: issue2PASS76.1s;
  returns4 -> exceptions2 -> customer-assets4 (improved drawer history screenshots).
  Freeze ALL E2E source while matrix runs: per-spec TypeScript checks scan all fixtures.
- session39156 .omo/runtime/portal-contact-red.sh/log queued after browsermatrix;
  new PortalContactIdentityIT compiles, not executed yet. Archive XML before focused rerun.
LegacyR2 session36699 completed: oldV172 UI2/2PASS creates2customers+2ONUs, after-upgrade
2failed because setupOwnArea restricts admin before assigning oldNULL-area customer,
so raw customer list becomes empty. Source selector itself matches actual UI.
Nextfix: create area viaUI without changing access, assign customer area while admin's
legacy raw list is unrestricted, then grant that area/relogin for warehouse review.
Do not change production authorization. Remaining cutover phases not yet proven.
Private R2 schema/artifacts: .omo/runtime/warehouse-legacy-a0f7db143b5ed570c54008f2c3e1c9ab
(use .omo/runtime/warehouse-legacy-latest.txt for exact path; no deletes).

Uncommitted legacy harness + fixture drafts must be finished and committed; no PASS
claimed. Existing historicalV172 checkout remains clean. New detached task-owned
.omo/runtime/warehouse-v175-fixture atc164c4eb was just created for task46 historical
fixture repair; no edits/build/test yet. Current ProjectionUpgrade test still fails
before intended assertion because newservices require post175.21 columns. Need real
version-correct historical fixture; no production fallbacks or unrelated409 shortcut.
Portal AFTER_COMMIT bug remains unfixed pending red test.

MatrixR1 issue NEVER EXECUTED: a transient unused E2E import stopped TypeScript before
launch; its copied numeric JSON was quarantined as issue-NOT_EXECUTED. Do not count it.
R2 source/report archives are per-spec and stop on first failure. Remaining45 legacy
cutover and currentmatrix; then46fullregressions,47docs/preflight,48CI,F1–F4audits.
Preserve private env/logs/traces, no auth artifact upload in PUBLIC repo. Retain volumes,
never kill unrelatedJava1045933. Commit/push checkpoints and CONTINUE.

## Previous checkpoint (superseded)

## Current checkpoint: real customer asset journeys pass; task45 continues

Follows e3e9708b; locate containing commit with git log -1. Goal ACTIVE.
Tasks1–44 DONE,45 IN PROGRESS,46–48/F1–F4 OPEN. CONTINUE after commit/push.
No subagents authorized. Branch work/warehouse-completion -> origin/feat/warehouse-workorder.

customer-assets.spec.ts passes4/4 real tests (365.5s), desktop1280/mobile375, no retry,
skips or mocks. Actual UI tenant/catalog/receipts/roles/scopes/WO, restricted technician
and QA, signatures,17.5m residual intake and exact917.5m/9available. Fixture now sets
category ONU and requires real onuId. SALE: remove, vendor repair, reset, RMA ACK and
reinstall to original customer, stillCUSTOMER title. LOAN: swap, recover/reset oldunit,
reuse sameasset atB preserving closedA history, final917.5m/8available/2installed.
Safe proof task45/customer-assets-browser-verification.json; full private artifacts:
.omo/runtime/task45-matrix-r1-customer-assets-{report.json,artifacts,source.json}.

V178.7 NEW/APPLIED/IMMUTABLE in default browser warehouse_e2e public schema.
SHA726b103ef37215b2af736cde9b6cedb336b02b7726b042f4707b673ee02fc081.
Optional RMA SKU category NULL previously compared createsOnu=false to SQLNULL;
coalesce predicate now matches application without weakening other bindings. Added
real catalog-change regression: red3tests/1fail(NULL); ONU andROUTER passed. Focused
post-fix green and retainedOLD-volume upgrade PENDING. Nextfree178.8; do not edit7.
Currentqa.sh shares PID/readiness helpers in qa-processes.sh and gates E2E TypeScript.
Browser artifact includes7; focused server result must be recorded before release.

ACTIVE/QUEUED under existing fd8 host lock (inspect logs before new work):
- session22133 .omo/runtime/task45-browser-matrix-r1.sh/log: customer-assets4PASS,
  now numeric -> issue -> returns -> exceptions. Archives each spec separately;
  stops on first failure. Extended issue/returns and exceptions are still drafts.
- session14743 rma-category-green.sh/log: queued4suites; archives XML privately.
- session22932 rma-category-populated-upgrade.sh/log: queued; swaps to preservedOLD
  markerabc63ab0045a7096566cf763c8f477fc, fingerprints23tables, appliesonly178.7,
  runs RMA deployment+guards, restoresdefaultmarker98b38fbf54518f766065f31955d52574.
- session36699 legacy-browser-r2.sh/log: queued; newowned schema eachrun.
Finished: rma-category-red30247 (3tests1fail), exceptionsR2 74347 (2testsfailedonly
expectedREJECTED vs actualcorrectREWORK_REQUIRED; expectation fixed), legacyR1
41652 (historical V172 app boots;2beforeUItests createcustomers but wrong rowselector;
no ONU seed/upgrade yet). Private legacyR1 schema/artifacts retained at
.omo/runtime/warehouse-legacy-933016b17c7d5f1cb02fee66da1699c0.
Legacy R2 fixes button selection/Aksi sel/Edit and expects oldONU canonicalization.

UNCOMMITTED legacy runner and web/e2e/warehouse-legacy fixtures/config are NOT PASS.
They build clean historicalV172 abaecd9e129cdfe12058d777fad3da2a9320c3c2, create
customer+ONU through oldUI, forward-upgrade same isolatedschema, resolvehistoryonly,
independentzeroopening/finalize, restart and compareoldIDs/checksums. No stockSQLseed.
QA oldcheckout .omo/runtime/warehouse-legacy-ui-base built successfully. Never edit
running scripts; preserveprivateenv/traces, retainvolumes; do not kill unrelatedJava.

Remaining45: complete current browsermatrix and actuallegacy cutover; add screenshots
that scroll into drawer history cards (current viewport images omit lower history).
Then46 fullregressions and version-correct175.21 ProjectionUpgrade fixture; new real
portal AFTER_COMMIT contact-sync transaction failure is recorded in task-46.md (not
fixed yet). Then47runbooks/preflight,48requiredCI,F1–F4currentartifactaudits. No native
or hardware claim, no prematurefinal. Prior checkpoint history preserved below.

## Prior checkpoint context (superseded by current status above)

## Current checkpoint: web serial identity fixed; finish task45 browser journeys

Follows cf8bc93c; locate containing commit with git log -1. Goal ACTIVE.
Tasks1–44 DONE,45 IN PROGRESS,46–48/F1–F4 OPEN. CONTINUE after commit/push.
No subagents authorized. Branch work/warehouse-completion -> origin/feat/warehouse-workorder.

Product fixes: sameSerialIdentity helper compares observed versus stored raw serial
with trim/uppercase, blank false. Applied to customer install/discovery, MaterialSaya
receipt/unused return/handover, return inspection/repair and RMA. Raw history, source
snapshot equality, frozen command bytes and exact retries preserved. Existing wrong
serial/quantity/role/session/reset checks remain. Reproduced3RMA failures and1install
failure before fixes; combined9files/45tests PASS8.40s. Actual web production build,
TypeScript and targeted lint PASS. Safe proof task45/serial-identity-web-verification.json.

Original complete numeric LOAN browser gate remains2/2green at cf8bc93c; safe proof
numeric-browser-verification.json. New asset browser r1 failed2 tests because the
fixture tried canonical option names while the actual source list preserves raw
serial. That exposed real customer installation comparisons now fixed. R2 failed
before execution due a closing-parenthesis typo in loan-reuse-scenario.ts; fixed and
added warehouse E2E TypeScript config/script (draft). No zero-test PASS claimed.

CURRENT runner session37432: .omo/runtime/customer-assets-browser-r3.sh/log,4cases
(2scenarios x desktop/mobile). Firstcase reached QA successfully then failed only
QA text expectation: frozen review shows canonical serial, not raw. After runner
finishes, revert completeNumericJourney review assertion to fixture.serial, leaving
installation source option on fixture.recordedSerial. All four currently share that
wrong QA expectation. Wait for completion/archive reports before next run.
No product fix required for canonical QA witness. R1/r2 raw reports/traces privately
archived with customer-assets-browser-r1/r2-report.json and -artifacts. r3 must archive.
Never print/commit authenticated traces or env. Volumes retained by all wrappers.

UNCOMMITTED browser drafts (compile+lint checked; not yet passing real gates):
asset-journey.ts actual createWO/signature/remove/intake/inspect/service/RMA;
loan-reuse-journey.ts serial-only plan/explicit reservation/ACK/swap/return/reset/reuse;
loan-reuse-scenario.ts checks preserved A history and same-unit B, final8available/2installed;
customer-assets.spec.ts 2cases; numeric-journey raw/canonical names and signature helper;
fulfillment optional mixed receipt serial prefix and cable cost; approvals optional
COUNT_VARIANCE; count-journey.ts blind counter/create/start/observe/submit/recount;
exceptions.spec.ts rejection and transit count stale, tablet768/themes, final900m/9available.
Exceptions never executed yet. Added tsconfig.warehouse-e2e.json and package command
npm --prefix web run typecheck:warehouse-e2e. All drafts compiled+targeted lint passed.
Product-only checkpoint intentionally leaves these drafts to finish next. User asked
frequent recoverable commits: keep working until browser gates are coherent, then
commit/push them and the actual evidence. Do not stop/final at this checkpoint.

Remaining45: finish customer-assets andexceptions, actuallegacycutover browser, extend
issue/returns realMaterialSaya coverage if needed. Legacy newtenant ENFORCED report is
covered by provenance.spec but is not an oldtenant cutover. Need legitimate populated
pre-upgrade fixture, no fabricated stock/admission bypass. Then46 full regressions
(including known175.21 ProjectionUpgrade fixture),47 runbooks/preflight,48requiredCI,
F1–F4 currentartifact audits. V178.6 immutable bothQAenvs; next178.7. No native/hardware
claim. All QA serialized under existing host fd8 lock; never kill unrelated processes.

Earlier checkpoints below are historical.

## Current checkpoint: numeric browser journey verified; continue task45

This checkpoint follows 7a3e62e3; locate its containing commit with git log -1.
Branch work/warehouse-completion -> origin/feat/warehouse-workorder. Goal ACTIVE.
Tasks1–44 DONE,45 IN PROGRESS,46–48/F1–F4 OPEN. CONTINUE after commit/push.
No subagents authorized. Feature push does not deploy. No main merge/native release.

Actual numeric empty-tenant UI journey PASS in BOTH desktop1280x900/mobile375x812,
2 tests, no retries/skips/failures (2.1m). UI-only customer/area/role/scope/receipt,
1000m+10ONU -> issue100m+1 -> restricted tech ACK/use82.500m/install LOAN/signature
and customer handover -> actual remnant17.500m return/warehouse ACK/intake/inspection
-> independent restricted QA -> persisted/visible917.500m and9 availableONU.
Same remnant identity asserted. TypeScript,targeted lint,16existing web tests PASS.
Product adds CustomerAreaField, actual signature multipart upload and proof refresh;
evidence mutations hidden once QA approved. Helper defaults preserve old scenarios.
R1 only failed2 test button matches in use-confirm dialog; r2 scopes confirmation to
its dialog. Safe proof task45/numeric-browser-verification.json with source hashes.
Private reports/traces: .omo/runtime/numeric-browser-r2-report.json and matching
-artifacts folder. Session59284 finished0; all owned services STOPPED, volumes kept.

NEXT: finish customer-assets.spec.ts sale/swap/dismantle/reset/same-asset reuse and
repaired sold RMA back to originalcustomer; exceptions.spec.ts countstale,approval
rejection,legacy cutover,768tablet,themes. Existing issue/returns coverage must include
real MaterialSaya ACK/return/handover (main numeric now has these actual paths).
Discovered pending frontend mixed-case identity bug: returnDraft.serial,rmaDraft,
myMaterialRma API and MyMaterialRma.tsx compare canonical scanner text to raw receipt
serial. Server178.6 fixes corresponding actual return/RMA guards; preserve raw history
and command replay bytes, fix only identity comparisons, add regressions and prove
in real browser sold-RMA journey. No changes for this frontend fix yet.

Then46 full server/web/KMP and fix historical175.21 projection fixture booting newer
MaterialSource query;47 runbooks/preflight;48 required CI/gate failures;F1–F4 current
artifact audits. Never final at checkpoint. V178.6 immutable in BOTH owned DBs;
next178.7. Env and archived reports contain secrets; never commit/print them.
NEW default marker98b38fbf54518f766065f31955d52574; OLD retainedabc63ab0045a7096566cf763c8f477fc.
Use existing host fd8 QA lock and owned scripts. Never remove volumes or unrelated PIDs.

Earlier checkpoints below are historical.

## Current checkpoint: task44 complete; continue real browser journeys

This checkpoint follows pushed5a2601ef; locate its containing commit with git log -1.
Branch work/warehouse-completion -> origin/feat/warehouse-workorder. Goal ACTIVE.
Tasks1–44 DONE,45 IN PROGRESS,46–48/F1–F4 OPEN. CONTINUE after commit and push.
No subagents authorized. No production deployment, main merge or native release.

Task44 final matrix58 tests/22 suites PASS7m20s, no failures/errors/skips. Actual
numeric fixture proves different-key concurrent cable use200+409, stale quarantine
count with unchanged partial-bin count, real same-asset ONU removal/reset/reissue
to customer B, unchanged frozen A QA review, delayed A metric isolation and concurrent
staleA/freshB ACS privacy. Full socket-loss/app-close/fresh-context replay/outbox
redelivery, reassignment,serial-only and count approval gates rerun successfully.
Final917500MM available+82500MM consumed,0technicianMM,9availableONU+1installed,
10physicalassets. Reuse keeps2historical handovers and one current installation.

Mixed-case raw receipt serial revealed a real return inspection bug. All return,
repair and RMA observed serial checks now use existing canonical identity semantics;
5 guarded SQL function changes preserve exact raw source/history, payload hashes,
original replies and every ownership/reset/quantity/authority/source guard. Wrong
serial rejects; same key with changed spelling still conflicts as changed bytes.
V178.6 APPLIED/IMMUTABLE in BOTH owned QA volume sets, SHA256 a1090f524423ed8623f561c6c26b18cade0aa440ec439634317754cf55659f7b.
Next178.7. Never alter178.6 or earlier. Actual populated upgrade4tests PASS5m39s;
only178.6 added;2138 existing tenants retain identical rows/digests in18 physical,
return/repair/RMA/history tables and all prior Flyway checksums. NEW env restored,
BOTH volume sets retained. Safe proof task44/numeric-temporal-verification.json.

Failure history: invalid first full-location count fixture corrected to supported
selected measured positions; ACS correctly rejected duplicate serials across old
fixture tenants, so numeric receipts now use unique mixed-case serials. That exposed
the raw-versus-canonical inspection defect, fixed forward-only. Focused4tests passed
2m43s before the final58-case matrix and populated upgrade. Earlier task44 proof
files cover previous defect/regression stages; full release regression is task46.

NEXT45: local client drafts add real customer area selection, signature upload,
proof refresh and the complete warehouse-empty-tenant.spec.ts numeric UI journey.
See task-45.md for exact files and remaining browser coverage. They are NOT verified
by this server checkpoint. Current session75763 runs numeric-browser-r1.sh: archives
upgrade results privately, TypeScript/targeted lint/5web unit files, then builds real
backend/web and runs the new spec desktop1280/mobile375. Inspect its actual log and
report before continuing; do not start duplicate services. Prior98513 and82693 are
finished0. Only owned processes may be stopped; never delete either volume set.

After the full numeric browser journey, finish customer-assets.spec.ts actual
sale/swap/remove/reset/reuse/original-customer sold RMA, exceptions.spec.ts count,
approvals/cutover/tablet/themes, then46 full regressions including the known175.21
historical projection fixture mismatch,47 runbooks/preflight,48 requiredCI gates,
F1–F4 current-source audits. Do not stop/final at a checkpoint. Never commit raw env,
authenticated traces, logs/XML or uploaded files. Feature pushes do not deploy.

Earlier checkpoint notes below are historical.

## Current checkpoint: reassignment QA and real envelope recovery verified

This checkpoint follows pushed0e530ae1; locate its containing commit with git log -1.
Branch work/warehouse-completion -> origin/feat/warehouse-workorder. Goal ACTIVE.
Tasks1–43 DONE,44 IN PROGRESS,45–48/F1–4 OPEN. CONTINUE after this checkpoint.

Both real mixed and serial-only jobs now complete and pass QA after reassignment.
The old technician is denied new use, then loses roles/areas; the new assigned tech
uploads completion proof while preserving the real customer handover signature.
Original physical source actors remain immutable and fully validated. Only today's
roster membership is removed from historical QA-source checks. No new consumption,
deployment, fake NONE or material review command. Mixed final917500MM available,
82500MM consumed,0techMM,9availableONU,1installed; serial-only cable stays1000000MM.
Forged original actor/asset, omitted deployment or bulk usage still fail real SQL.

Final current coverage108 tests/12 suites through broad+focused gates. Broadr1
ran108/7m5s,104passed4failed:1 test expected403 after role+area revoke but actual404;
3 old envelope fixtures attempted forbidden synthetic legacy checkpoints. Current
reassign suite2/2 PASS, and complete binding suite24/24 PASS1m48s replace those
results. Broad raw XML/counts preserved before reruns. Existing full numeric HTTP
loss/app restart/outbox redelivery gate also reran successfully on these changes.
Envelope tests now use real usage/completion/approval, actual persistent claim and
reconciliation; only delivery-copy bytes/type are corrupted. Original persisted
payload survives, no effects before reconciliation, valid retry applies1 settlement
and2 owner effects with no physical mutation. Test-only public port decorator.

V178.5 APPLIED/IMMUTABLE in BOTH owned QA environments, SHA256
85ae2a27ec50781cadc01871682aed0b26a17078fb64a2a0777df968d444ef81. Next178.6.
Actual populated upgrade2 tests PASS2m7s, only178.5 added;2136 existing tenants
retain identical rows/digests in13 physical/history tables and all earlier Flyway
checksums. All services stopped, NEW default restored, BOTH volume sets retained.
Safe proof: .omo/evidence/warehouse-workorder-asset-provenance/task44/reassignment-settlement-verification.json.
All runner sessions95084/96289/21790/91891 are finished; no active QA processes.

NEXT44: draft .omo/runtime/WarehouseNumericCountIT.kt (NOT compiled/executed) uses
full numeric fixture, full-bin count of900000MM+9ONUs before actual17500MM remnant
return; new position must invalidate submission withCOUNT_STALE and no adjustment,
then original917500MM/9ONU/1installed QA result. Copy into fulfillment tests and run.
Add different-key concurrent use/cut on full numeric fixture (one200,one409, one
usage/split, complete return+QA). Actual time-aware reuse still required: existing
WarehouseReturnITReuse/WarehouseReturnAssetFixture implement real loan remove,
inspection+reset, reissue and same-asset install for a second customer. Extend actual
history with delayed metric/staleACS checks; WarehouseDiscoveryITTemporal SQL-seeded
episodes remain supporting only. Decide whether full numeric case can force select
returned ONU among9others via actual reservation choices; do not seed stock/episodes.
Then45 full real browser,46 full regression incl known historical175.21 projection
fixture/schema mismatch,47 runbooks,48 requiredCI,F1–F4. No subagents authorized.
No production deploy/main merge/native release claimed. Keep immutable migrations.

Earlier checkpoints below are historical.

## Current checkpoint: full numeric restart/outbox recovery verified

This checkpoint follows pushed636209cf; locate its containing commit with git log -1.
Goal ACTIVE, tasks1–43 done,44 in progress,45–48/F1–F4 open. Continue working.
WarehouseRecoveryIT runs2 ordered tests with a full real warehouse/customer/QA flow.
Actual HTTP inspection response is held after commit and client socket reset before
any bytes arrive. Whole first Spring app closes; fresh app replays exact durable
inspection outcome. Actual outbox claim/reader/fulfillment delivery loses ACK; fresh
production dispatcher redelivers once, old lease fenced, attempt2DELIVERED,1 inbox
and1 observation. All numeric physical totals and counts survive unchanged:
917500MM available,82500MM consumed,0 techMM,9availableONU,1installed,10assets.
Only scheduling lease metadata is expired in SQL. No physical seed, fake delivery
algorithm or process kill. Historical usage current-scope access tested with oldJWT.
2 tests PASS1m17s; full reports privately archived before next run. Proof:
.omo/evidence/warehouse-workorder-asset-provenance/task44/numeric-recovery-verification.json.
R1 failed only a final404 expectation on terminal WO write; r2 explicitly proves
history200 before revoke/404 after, and correct terminal409 write with no effects.

NEXT: WarehouseReassignmentSettlementIT is currently an UNCOMMITTED regression.
First red attempt stopped at redundant signature upload requiring correctionReason;
fixture now reuses the real original customer handover signature while the new
technician uploads fresh completion proof. Redr2 running via
.omo/runtime/reassignment-settlement-red-r2.sh, log same basename.log; inspect
actual failure before product changes. If local drafts are lost, recreate2 boolean
cases(includeCable=true/false): full actual use/install/handover/return, reassign
before completion, new tech completes with original handover signature, QA should
verify those physical facts; old tech new-use403 and no new physical movement.
Suspect checks requiring original usage/deployment actor still in current roster.
No actor guards changed yet. Three current SQL definitions captured privately as
.omo/runtime/warehouse_{assert_fulfillment_snapshot,fulfillment_deployments_guard,assert_deployment_only_settlement}-1784.sql.
Do not edit applied migrations. V178.4 immutable in BOTH QA volumes; next178.5.
After reassignment proof/fix continue remaining44 races,45 browser,46 full regression
including historical175.21 fixture repair,47 runbooks,48 CI andF1–F4. No subagents.

## Current checkpoint: serial-only QA verified; recovery next

This checkpoint follows pushed aebe3912. Locate its containing commit with git log -1.
Branch work/warehouse-completion -> origin/feat/warehouse-workorder. Goal ACTIVE.
Tasks1–43 DONE;44 IN PROGRESS;45–48/F1–F4 OPEN. Continue after this checkpoint.

QA now accepts a fully installed all-SERIAL plan without fabricating measured usage.
Bulk source is nullable only with complete actual receipt-backed deployment witnesses;
NONE still needs its explicit snapshot. No extra material review command or movement.
Named frozen device review remains byte-identical after real signed removal; foreign
and revoked original receipt-location access return404. Mixed bulk-source omission and
missing/foreign deployment witnesses are rejected by real SQL with atomic rollback.
Web/KMP hide measured-use actions for serial-only plans and retain default-compatible
metadata and exact prior/defaulttrue KMP guard bytes. Source actor guards remain intact.

PASS:16 server tests/6 suites2m44s;31 web tests/6 files5.98s; full TypeScript and
targeted lint;44 KMP JVM reported tests/14 suites28s and module graph. Only core:mvi
JVM test cached; changed modules ran. No native runtime claim. Actual old populated
upgrade adds only178.4;2133 existing tenants retain identical physical/history rows
across13 tables and every prior Flyway checksum.2 more serial tests PASS2m12s.
Proof: .omo/evidence/warehouse-workorder-asset-provenance/task44/serial-settlement-verification.json.
V178.4 APPLIED/IMMUTABLE in BOTH QA environments, SHA256
b00cf665eaebae1959e5d75113674e98efba3f6c6838fda57604c1cda0545003. Next free178.5.

NEXT: copy prepared private drafts .omo/runtime/WarehouseRecoveryIT.kt and
WarehouseResponseLossProbe.kt into server test inventory package; make numeric
saveCommand protected open. They use actual HTTP, response held after commit,
socket reset, @DirtiesContext full shutdown/fresh app, exact original inspection replay,
real outbox claim/reader/delivery, lost ACK, expired lease metadata and one owner effect.
Drafts have NOT compiled or run. Also prepared WarehouseReassignmentSettlementIT.kt
for the suspected stranded-QA path after fully used materials then reassignment.
Run it red before changing current actor guards. Fix only observed contract violations.
Remaining combined races, task45 real browser,46 full regression/historical175.21
fixture repair,47 docs,48 CI and F1–F4 still required. No subagents authorized.

QA currently STOPPED; new default restored. Current marker
warehouse-f7c0d53b912f-98b38fbf54518f766065f31955d52574; retained old marker
warehouse-f7c0d53b912f-abc63ab0045a7096566cf763c8f477fc. ALL volumes retained.
Private env archives must never be printed/committed. Host fd8 QA lock unchanged.
R3 server and populated-upgrade reports archived privately before any next rerun.
All serial runner sessions29728/92986/20829 finished0. No unrelated processes killed.

Earlier checkpoints below are historical.

## Task44 checkpoint: install/use ordering verified; serial-only and recovery NEXT

This checkpoint follows pushed c92151f5. Locate its containing commit with git log -1.
Branch work/warehouse-completion -> origin/feat/warehouse-workorder. Goal ACTIVE.
Tasks1–43 DONE;44 IN PROGRESS;45–48/F1-F4 OPEN. Keep working after this checkpoint.

First measured use after ONU install now advances the shared physical revision and
remains a first usage. A positive delta after installation binds the actual latest
usage predecessor across deployment revisions. DB unique document revision and
owner sequence validation preserve historical reads; physical fact/snapshot/posting
revisions match. Web/KMP choose first/corrective use from latestUsageId, retaining
current revision/source/authority preflight and exact attempted-command replay.

Verification:51 server tests/6 suites PASS3m52s;14 web/3 files PASS3.87s; TypeScript
and targeted lint pass; KMP42 reported tests/14 suites green27s, module graph passes
(only core:mvi test task cached; changed modules executed). No native runtime claim.
Actual new numeric cases: install then82500MM use;80000MM use,install,2500MM delta.
Both complete return/QA with917500MM/9 available ONU/1 installed and1,000,000MM/10EA
conservation. Original3 numeric concurrency/rollback cases and old usage guards pass.

V178.3 APPLIED/IMMUTABLE in fresh AND retained populated QA databases. Next178.4.
Populated upgrade2 more numeric tests PASS2m11s. All previous tenant physical rows
in13 tables retain exact counts/digests; all previous migration checksums unchanged;
only178.3 added. Safe proof:task44/usage-order-verification.json. Prior178.1–.2 intact.

CURRENT default QA marker:warehouse-f7c0d53b912f-98b38fbf54518f766065f31955d52574.
OLD retained marker:warehouse-f7c0d53b912f-abc63ab0045a7096566cf763c8f477fc.
Private env archives under .omo/runtime/environment-archives/<marker>/warehouse-test.env;
never print/commit them. Both volume sets retained, all owned services stopped, new
default restored after upgrade. Host lock/ports/roles/JDK21 unchanged; no reset/kill.

NEXT: serial-only QA source model, then actual HTTP loss/full app restart/outbox
redelivery and remaining combined races. Avoid fake NONE/cable/stock or a second
DEPLOY. An optional bulk source alongside real deployment witnesses may be simpler
than adding a separate operator review command; investigate current nullable/FK and
snapshot/settlement guards first. task44-serial-review-design.md is an unimplemented
alternative, not a requirement. Also inspect QA after reassignment of fully used
material: original actor must not post NEW use after revoke, but historic physical
facts should not require fictitious extra usage. Add real regressions before changes.
Then45 full real browser,46 final regressions/historical175.21 fixture repair,47 docs,
48 CI gates,F1–F4. No subagents authorized. Preserve reports before reruns. Earlier
sections below are history; detailed task44.md has phase notes and remaining work.

## Task44 checkpoint: mixed material QA fixed; continue remaining acceptance

This checkpoint follows pushed ee60908b. Locate containing commit with git log -1.
Branch work/warehouse-completion -> origin/feat/warehouse-workorder. Goal ACTIVE.
Tasks1–43 DONE;44 IN PROGRESS;45–48/F1–F4 OPEN. Do not stop at this checkpoint.

Full real controller+PostgreSQL numeric fixture now completes with917500MM available,
82500MM consumed,0 technician MM,9 available ONUs,1 installed assignment/ONU and
10 original physical assets. QA binds actual serialized deployment witnesses beside
bulk usage; no second debit or fabricated material line. Exact same-key concurrency,
precommit rollback/retry, shared assignment lock and omitted/forged witness rejection
are tested. Internal owner corruption is tested as SQL exception, not an ordinary409.

V178.1 and178.2 APPLIED/IMMUTABLE. Next free178.3. All earlier migrations unchanged.
Final focused31 tests PASS:3 numeric2m21s and28 isolation2m54s. Broad181 tests had5
failures:4 fixed and rerun;1 remains the historical175.21 fixture running a newer
application that requires transfer_receiver_id. Full broad XML archived privately.
Safe source-matched proof:task44/mixed-material-verification.json. Task44 remains open.

NEXT: fix first cable use after ONU install and delta after install, preserving the
shared use revision, actual latest usage predecessor and all physical checks. Web/KMP
must distinguish first bulk use by latestUsageId, not by nonzero physical revision.
Then serial-only explicit source-backed review without fake NONE/cable/stock; actual
network response loss +whole app restart +outbox redelivery; remaining combined races.
Continue45 browser,46 full compatibility/historical fixture,47 runbooks,48 CI,F1–F4.
Detailed task44.md records known gaps, SQL research, tests and safe recovery patterns.

QA wrappers finished and owned containers stopped, volumes retained. Old environment
has many test tenants and slow app startup; a fresh separately owned environment can
be created after archiving its private env, retaining every old volume for recovery.
Never reset/delete old volumes or kill unrelated processes. Archive reports before
reruns. Never commit env/raw logs/XML/uploads/authenticated traces. No subagents are
authorized. Feature pushes do not deploy. Earlier sections below are history.

## Task43 complete: provenance management UI verified; task44 NEXT

Checkpoint follows pushed38d40e9c; locate this containing commit with git log -1.
Branch work/warehouse-completion -> origin/feat/warehouse-workorder. Goal ACTIVE.
Tasks1-43 DONE;44-48/F1-F4 OPEN. Keep going through the full plan; save and push
coherent source/proof/handoff commits. Do not stop at this checkpoint.

Full /warehouse/provenance management page is installed: bounded cases, raw serial/
MAC/quantities, original private evidence upload/download, evidence-bound resolution
with exact EA/MM/M conversion, original quantity retained, duplicate/cancellation,
read-only admitted history. Opening review has explicit zero acknowledgement,
active named review location, issues, paged saved proposal directory and independent
approval source links. Finalization reviews approved counts and persists its result.
Current permission loss clears page; command retry retains exact request/key.
Navigation uses inventory.provenance.manage. Docs include actual UI workflow.

Verification:272tests/52files PASS47.44s, full TS/Vite build PASS, full lint PASS with
pre-existing unrelated warnings; changed files clean. Real browser2tests PASS19.5s
at375/768/1280,light/dark, UI-created empty tenant and restricted IAM roles. All10
source counts0, ENFORCED0/no batch, no fake opening; manager-only allowed and
approval-only page/API403. Initial test listened on obsolete approvals endpoint;
correct /approvals/workbench rerun passed. Safe proof task43/ui-verification.json
and6screenshots; raw traces/logs private. Actual legacy HTTP+PG+MinIO full-app
restart is already proven by e5f70e52/finalization-verification.json45tests and
38d40e9c/opening-directory-verification.json9tests. UI mocks are not legacybrowser
proof; interactive legacy browser journey is still task45, along with numeric suite.
No server or SQL changes this checkpoint; V178 immutable, next178.1.

NEXT44: implement full numeric cross-module fixture (1,000,000MM+10ONU receipt,
100,000MM+1ONU issue/ACK,82,500MM use+real device install,17,500MM inspected return,
QA replay). Actual stock/identity/assignment commands only, no fake installations.
Need DB rollback, concurrent commands/cuts/counts, outbox redelivery, whole app
restart and conserved917500MM/9availableONU/1installed/0techMM. Existing generic
PostingIT and earlier cable-only fulfillment are support, not full acceptance.
Useful fixture chain: WarehouseFulfillmentFixture -> MaterialUsageFixture ->
MaterialReceiptFixture -> WarehouseIssueFixture -> MaterialWorkflowFixture ->
WarehouseReceiptHttpFixture. Extend with a combined numeric fixture, use real
receipt/issue/ACK/deployment/handover/return APIs. MaterialUsagePreparation rejects
SERIAL: report only cable, install ONU through deployment authorization/customer.
INSTALL requires PSB; mint after any cable usage/WO start so revision is current.
WarehouseReturnIT has real accepted17500MM inspection flow. Whole-app restart
pattern is ordered methods + @DirtiesContext(AFTER_METHOD), assert ContextClosedEvent
before fresh HTTP calls; existing WarehousePolicyITRestart also has owned child
SIGKILL/restart harness. Do not alter product replay/authorization to simplify tests.
Then45 browser complete numeric/customer/exceptions/legacy,46 regressions,47 docs,
48 required CI gates, F1-F4 final audits. No subagents authorized in this session.

OwnedQA fully stopped, retained volumes. Private runner provenance-browser.sh and
log provenance-browser-r2.log. Host QA lock/JDK21/test cache settings unchanged.
Never commit env/rawlogs/XML/uploads/authenticated traces. Older sections are history.

## Task43 scoped opening directory and browser clients verified; actual page NEXT

Checkpoint follows pushed e5f70e52 (M06 finalization45PASS). Locate containingcommit
with git log -1. Branch work/warehouse-completion -> origin/feat/warehouse-workorder.
Goal ACTIVE:1–42done,43inprogress,44–48/F1–F4open. Continue full scope; save/push
coherent source, proof and handoff checkpoints. Do not stop at this checkpoint.

GET /api/v1/warehouse/provenance/batches/{batch}/opening now returns paged summaries:
id,batchId,code,state(DRAFT/POSTED),reviewHash,requestedBy,createdAt,migrationReference,
reviewLocation{id,code,name}. WarehouseQuerySql applies current location/area/ancestor/
site visibility BEFORE count/page. Service checks current provenance +all source scope,
history lock. Private runner migration-opening-directory.sh/log9tests PASS1m47s:
OpeningApproval6,Modularity3. Scope denial/zero count/current grants, pagination and
unknownquery checks, fresh restart history retain original opening.

Web clients now tested: provenanceModels.ts,provenance.ts, reusable exports from
migrationReview.ts; fixture warehouseProvenanceFixture.ts +provenance.test.ts.
Strict summary/source/evidence/resolution/review/opening/finalization decoders.
Rawblankserial/invalidMAC/nullclaims retained, rawqty not silently assignedunit.
Captured multipart bytes/key/request through retry; private downloadchecksum.
9tests/2files PASS936ms; full TypeScript PASS; targeted lint PASS. Source proof:
task43/opening-directory-verification.json. No SQL changes; V178 immutable SHA
f6b8780d898577c16f2b63298e9d3fd43244d6e32a02a4c921ae04c7a1c9997d; next178.1.

NEXT actual /warehouse/provenance UI, route/navigation, case/evidence/resolution
forms, baseline review and named review-location picker, savedopening directory,
approval links (sourceDocumentId), finalization readiness/confirmed counts, original
file downloads. NewemptyENFORCED withno batch is alreadyactive state; oldempty needs
explicit reviewedzero+independent approval. Do not fake origin/cost/unit.
Use existing WarehouseState,WarehousePagination,WarehousePicker,WarehouseCommandDialog,
WarehouseQuantity,WarehouseTime,saveReceiptFile; read web/DESIGN.md section9 (already
read this session). Actual page not started; client infrastructure is not UI proof.
Need UI unit tests/current permission loss, then realbrowser task43/45. Plan43 still
unchecked. Then complete44–48/F1–F4. All ownedQA containers stopped, volumes retained.
Never commit env/rawlogs/XML/uploads. Older sections below are history.

## Task43 M06 finalization and full application restart verified; management UI NEXT

Branch work/warehouse-completion; remote origin/feat/warehouse-workorder. This
checkpoint follows pushed b462c622; find containing commit with git log -1.
Tasks1–42 done;43 in progress;44–48/F1–F4 remain. Goal ACTIVE. Continue full scope,
keep source/proofs/recovery handoff committed and pushed. Do not stop at checkpoint.

V178 APPLIED/IMMUTABLE, SHA256
f6b8780d898577c16f2b63298e9d3fd43244d6e32a02a4c921ae04c7a1c9997d.
Next free M06 version178.1; V177–177.10 unchanged/immutable.
M06 adds owner-only finalization receipts, replaces the closed cutover transition
with a current-XID finalization witness, validates VERIFIED references while
preserving legacy null/orphan rows. No global tenant admission is needed at boot.

GET/POST /api/v1/warehouse/provenance/batches/{batch}/finalization installed.
Input expectedEpoch,openingDocumentId,expectedReviewHash,reason +Idempotency-Key.
Owner checks actual independent approved posting/event/inbox, immutable review,
every pending cancellation receipt, current legacy identity reservations, exact
VERIFIED available baseline and admitted physical claims. API rechecks real file
bytes and current IAM/all source scopes. Exclusive tenant lock then history batch;
receipt/epoch ENFORCED2 atomically. Original source counts (zeros included), exact
unit totals, historical exclusions, cancellation/retained identity counts, actor,
authority epoch/reason/time recorded. Replay returns original response after current
authority even across successful epoch transition. Existing policy stub removed.
Concrete policy.lockCurrentForTransition fixes read-before-lock race for finalizer
and begin/report owner; ordinary API epoch checks unchanged. Opening review GET now
uses immutable history lock so reads survive finalization.

45tests/8suites PASS5m4s; zero failures/errors/skips. Source proof
task43/finalization-verification.json. OpeningApproval6,Policy11,Boot2,Capture3,
Query2,Posting12,ReceiptGuards6,Modularity3. Real HTTP+Postgres+MinIO includes
ordered preparation then full Spring app shutdown (ContextClosedEvent asserted)
then fresh application boot/HTTP replay: A ENFORCED/B VALIDATING, original raw
asset/ONU/customer IDs, current reserved serial rejects competing receipt, B new
writes denied. Realzero baseline finalizes with0SKU/stock/lot. Same-key concurrency,
rollback after owner function returns, altered evidence denial/restoration,
3 real legacy inventory/checkpoint/outbox cancellation receipts and current actor
revocation pass. Direct SQL cutover/witness writes and pre-approval finalization
reject. Initial r2 exposed stale epoch race fixed in Kotlin; V178 SQL unchanged.
R3 nested simultaneous application experiment caused one lease fixture interference;
latest test closes the original application before restart and all6tests pass.

Owned QA stopped, volumes retained. Private runner migration-finalization-regression.sh
and log migration-finalization-r4.log. JDK21, host lock, workers2,heap1536MiB/cache1.
Never commit raw env/logs/XML/uploads. docs/warehouse-provenance.md updated API guide.

NEXT full /warehouse/provenance management UI. Draft unverified web source already
started in working tree: provenanceModels.ts,provenance.ts, plus reusable exports
from migrationReview.ts. Not part of this backend checkpoint. Need bounded opening
list API to rediscover sealed requests after refresh, complete case/evidence/resolution
forms +review/opening approval links +finalization. Read finalization-ui-followup.md,
actual server response models and web/DESIGN.md section9. Reuse existing guards,
dialogs, scoped pagers/selectors, exact quantity/file verification. Keep task43
unchecked until UI +browser proof. Then44–48/F1–F4 all remain.

## Task43 legacy closure and admitted review seal verified; finalization/M06 NEXT

Branch work/warehouse-completion; remote origin/feat/warehouse-workorder. This
checkpoint follows pushed8e84c464 (current identity reservations). Locate containing
commit with git log -1. Tasks1–42 done;43 in progress;44–48/F1–F4 remain. Goal ACTIVE.
Continue full scope and keep verified source/proof/handoff commits pushed.

V177.10 APPLIED and IMMUTABLE, SHA256
7fb239b034ff5fae3469d857d055acdddeb9129c569a74f43eccea860dd3df08.
Next free M05 version177.11;178 reserved forM06. Only product change this phase is
SQL. Legacy WORK_ORDER checkpoint/outbox/progress creation is closed after cutoff;
business/hash/source/effect identity and terminal outcome are protected. Current
frozen fulfillment works; onboarding MIGRATION source retains its owner flow.
Existing terminal claimOrCreate replay and harmless legacy lease/ACK/reconciliation
remain usable. Tombstone app INSERT/UPDATE/DELETE revoked; history append-only.
New migration evidence/resolution after admission rejects under the same batch lock,
so a committed opening's review cannot become irrecoverably stale. Original replay
and read-only history retain their existing behavior.

31tests/6suites PASS3m38s, zero failures/skips. Safe proof task43/
legacy-closure-verification.json records source hashes. Real HTTP+PG+MinIO tests:
new ambiguous legacy work in ENFORCED creates no checkpoint; original V172 sources
still reconcile/replay/ACK; SQL creation/source/effect/hash changes, progress, terminal
reopen and tombstone writes denied; new case file/resolution after admission409 and
old history unchanged. Normal WO material verification, service owner effects,
concurrency and rollback pass. OpeningApprovalIT's requiredEffects drift now fails
earlier at the new DB guard rather than accepting a change then rejecting approval.

NEXT implement finalization/M06 under exclusive cutover with real independent
opening/cancellation/current-identity proof, actual mixed-tenant HTTP+restart gate,
and full /warehouse/provenance management UI. Then44–48/F1–F4. Existing sources and
original IDs/services remain; no finalization or ENFORCED transition exists yet.
Read cancellation-followup.md in .omo/notepads/warehouse-workorder-asset-provenance
for design audit (some closure items are now completed by177.10).

Read paths for MigrationEvidenceService and MigrationResolutionService already use
MIGRATION_REPORT plus caseHash, not VALIDATING-only batch lock; they can retain history
after ENFORCED. Mutating replay is still subject to current cutover operation gate.
Current policy.finalizeValidation and warehouse_cutover_guard retain their closed
stubs; replace/remove them precisely when owner finalization is implemented. Do not
reuse pre-admission review_issues after posting: verified assets leave legacy live
views intentionally. Validate immutable admission bindings and actual stock instead.

Owned QA stopped, volumes retained. Private migration-closure.sh/log. JDK21, host
QA lock, workers2,1536MiB test heap/context cache1. Never commit private env, raw logs,
XML or uploads. Older sections below are history; this leading status takes precedence.

## Task43 current identity reservations verified; finalization/legacy closure NEXT

Branch work/warehouse-completion, remote origin/feat/warehouse-workorder. This
checkpoint follows pushedb2d19dbb (permanent approved cancellation); use git log -1
for its commit. Tasks1–42 done;43 in progress;44–48/F1–F4 remain. Goal ACTIVE.
Continue full scope, preserving coherent committed/pushed progress and safe proofs.

V177.9 APPLIED and IMMUTABLE, SHA256
b66ace6e025d257171a9a5ceff5aa5d7e5deceaa94f1c9871fc64d1e105e6c69.
Next free M05 version177.10;178 remains reserved forM06. Candidate rows now retain
raw versions; BEGIN's exclusive owner function reserves current raw serial/MAC
from actual owner projections, retaining every old candidate/claim. Invalid MAC
stays explicit unclaimed/null. Current tombstone claims are retired. Raw asset/ONU
identity cannot change after cutoff, but ONU status continues. Existing ONU original
identity was already immutable; that guard stays unchanged.

13tests/4suites PASS2m13s, zero failures/skips. Proof task43/
current-identity-verification.json records source hashes and suites. Real HTTP+PG+
MinIO proves new/changed sources after boot, five new current claims, exact replay,
no stock minted by capture, direct SQL denial, status continuity and independent
baseline admission retaining obsolete candidate history of a currently changed
peer. Opening still preserves the original physical UUID/raw serial; changed peer
remains staged/reserved. Existing cutoff/query/cancellation/approval/rollback cases
pass. First run failed only an invalid test attempt to rename an existing ONU;
the corrected test preserves old ONU identity and adds actual new legacy sources.

NEXT read cancellation-followup.md in this notepad directory (contains source audit
and finalization design details). Close obsolete new legacy WO checkpoint/outbox/
progress paths after BEGIN, retain terminal replays and harmless ACK/retry metadata;
close old tombstone writes. Freeze new resolutions after an admission so the sealed
review cannot become permanently stale. Then implement finalization/M06 under
exclusive cutover, mixed-tenant restart, full /warehouse/provenance management UI.
No finalization or ENFORCED transition is implemented yet; do not mark43 done.

Owned QA stopped, volumes retained. Private wrapper migration-identities.sh and
log migration-identities-retry.log. Continue established JDK21/host-lock/owned-env
runner; never commit private env/raw logs/XML/uploaded evidence. Older sections
below are historical; this leading status takes precedence.

## Task43 approved legacy cancellation verified; all-current reservation/finalization NEXT

Branch work/warehouse-completion, remote origin/feat/warehouse-workorder. This
checkpoint follows pushed6d620e98 (opening admission + web review). Use git log -1
for the containing commit. Tasks1–42 complete;43 in progress;44–48/F1–F4 remain.
Continue full scope and push coherent verified phases; goal stays ACTIVE.

V177.8 APPLIED and IMMUTABLE, SHA256
3bd70b2ff029705331a4c453296ad6604524cd18b4c3aa08aea12ab8dc43b180.
Next free M05 version177.9;178 reserved forM06. Never edit applied migrations.
Owner-only append-only inventory/fulfillment cancellation receipts are bound to
current-XID independently approved opening admission. Original movement/checkpoint/
outbox/progress remain unchanged. Late ACK/reconciliation cannot reopen canceled
work; process/accept replay MANUAL_RESOLVED with CANCELED_BY_APPROVED_MIGRATION.
Raw reopen, identity rename, new progress, receipt deletion and owner-function replay
outside the original admission transaction are denied. Required cancellation set
is deferred-checked with the same atomic baseline posting and effect receipt.

Broad regression37tests/8suites PASS3m51s. Then final7tests/2suites PASS1m34s after
adding @Repository exception translation and business-drift HTTP409 verification.
Both gates have zero failures/skips. Safe source hashes/proofs are task43/
cancellation-regression-verification.json and cancellation-final-verification.json.
Real HTTP+PG+MinIO validates1 pending movement +2 fulfillment sources, actual lease
before final approval, late delivery, one competing baseline, exact replay, zero
baseline, current area change and three rollback stages. A changed current legacy
requiredEffects rejects409 without SQL leakage or partial decision/stock/receipts.
Normal WO material verification, service owner effects, concurrency and durable
unit replay passed the broad gate. Web is unchanged since6d620e98.

NEXT read .omo/notepads/warehouse-workorder-asset-provenance/cancellation-followup.md.
All-current serial/MAC reservation must retain history while checking current active
conflicts. Close obsolete legacy pending creation after BEGIN or safely account
same-checkpoint delivery churn before enabling finalization. Current cancellation
fails closed on changed checkpoint outboxIds; this is not an implemented recapture
flow. Then implement finalization/M06, mixed-tenant restart and full provenance UI.
Do not mark43 done yet. Existing cancellation receipts do not flip tenant ENFORCED.

Owned QA is stopped, retained volumes. Private runtime wrappers/logs:
migration-cancellation-final and migration-cancellation-conflict. JDK21, host fd8
QA lock, task-local Gradle cache, workers2,1536MiB test heap/context cache1.
Never commit private env, raw logs, XML or uploaded evidence files. Older sections are history;
this leading section takes precedence.

## Task43 opening approval, admission and web review verified

Branch `work/warehouse-completion`, remote `origin/feat/warehouse-workorder`.
This checkpoint builds on `7b02a825`; locate its commit with `git log -1`.
Tasks1–42 complete;43 in progress;44–48 and F1–F4 remain. Continue full scope.

Real HTTP + PostgreSQL + MinIO gate:47 tests in9 suites PASS, no failures/skips,
6m28s. Safe proof `task43/opening-admission-verification.json` records source hashes.
Approved opening preserves original physical IDs/raw serial, posts exact1EA and
82500MM unknown-cost stock, and supports an explicit zero baseline without fake SKU.
All tiers are independent; current owner areas, source manifest and original files
are checked. Competing finals admit once, exact response replay works, and three
failure stages roll back every physical/control effect. Ordinary posting/restart,
receipt approval/evidence/workbench, policy, schema and module regressions passed.
The earlier broad-run heap failure is resolved by1536MiB and context cache size1.

Web warehouse gate:257 tests/50 files PASS38.26s, lint PASS (existing unrelated
warnings), TypeScript and production build PASS. Safe proof
`task43/opening-web-verification.json`. Approval workbench now reviews opening
cutoff, physical lines or explicit zero, unknown valuation, unresolved history,
bounded sealed cases and actual private evidence with SHA256 validation. Current
permission denial removes download controls. Full provenance management page and
browser acceptance remain pending; these tests are not browser acceptance.

V177.7–177.7.3 APPLIED and IMMUTABLE; hashes in docs/warehouse-migrations.md.
Next free M05 version177.8;178 reserved forM06. Tenant stays VALIDATING after
opening. Narrow OPENING_POSTED approval-receipt inbox exception requires the
current owner admission; ordinary consumers remain closed. No legacy price,
supplier, receipt or identity is invented. Applied migrations must not be edited.

NEXT: approved permanent cancellation of captured pending legacy effects; reserve
all current identities, including sources changed/created since boot; finalization
and M06 mixed-tenant restart; actionable `/warehouse/provenance` management UI.
Then44–48/F1–F4. CANCEL_PENDING resolutions are currently proposals only.
Legacy fulfillment CONTROL_PLANE fencing does not freeze checkpoint/outbox state:
old dispatch can still mark reconciliation or change leases. Cancellation must
recheck current owner state, permanently prevent later dispatch, preserve history,
and join the approved opening effect atomically or use a bound durable receipt.
Do not falsely claim pending effects are already canceled.

QA Docker services stopped, volumes retained. Use host lock
`/home/fajar/ftth/warehouse-workorder-asset-provenance-resume/.omo/runtime/wave5-host-qa.lock`,
JDK21, task-local Gradle cache/workers2, owned QA marker/private environment.
Private runtime logs: migration-opening-admission-final-retry.log and
migration-opening-web-final.log. Never commit raw logs/XML/private environment.
Commit and push coherent verified phases with updated proof and these notes.
Older sections below are historical; this leading status takes precedence.

## Task43 unvalued opening policy evaluation verified; actual approval/posting NEXT

15tests PASS: Opening4,PolicyEvaluation6,DurableReceiptApproval2,Modularity3.
Safe proof task43/opening-policy-verification.json; final runtime log
migration-opening-policy-final.log. No new SQL migration in this phase.
V177.6 remains immutable; next free177.7;178reserved forM06. Goal ACTIVE.
Owned QA stopped, volumes retained. Task43 still OPEN;44–48/F1–F4 outstanding.

MigrationOpeningPolicyContext looks up the actual sealed opening, takes batch advisory
before owner scope locks, requires exact current review/no issues, and reads batch
requester. PolicySource has explicit OpeningPolicyContext for review location,
participants and extra current customer/WO areas. Empty physical lines are allowed
only with this actual source context. No artificial PolicySourceLine is inserted.
WarehousePolicyEvaluationService evaluates every configured tier for an opening;
value numerator/denominator/currency remain null. Source costs of ordinary receipts
still require a known basis under their existing policy rules. Public evaluation
keeps cost fields omitted and explains unknown historical cost/all-tier review.

Tests place FOUR distinct actors in policy candidates and prove exclusion of batch
creator, resolution reviewer, file uploader and opening requester. Policy candidates
and both sides of delegation must cover all current customer/WO areas in addition to
warehouse scopes. Current customer area changes deny old-area callers/candidates;
updated scopes permit evaluation. New resolution makes old opening evaluation stale.
An approval-view actor can inspect evaluation with full source scopes; no provenance
management permission is silently required of independent approvers.

NEXT implement real WarehouseApprovalOwner for OPENING_BALANCE and narrow batch-bound
DB admission/stock posting. DurableApprovalService request and non-ENFORCED decisions
are STILL CLOSED for this source; no approval record/stock created by the new preview.
Existing durable receipt flow and unknown receipt-cost rejection passed regression.
MigrationOpeningPolicyContext currently uses VALIDATING-only batch lock. Actual
approval get/replay after finalization must instead support immutable batch read locks.
WarehouseApprovalAuthority must recheck extra current source areas for delegates too,
not just rely on initial evaluation. Seal/source hash must bind current source-owner
scope snapshots as well as latest review so a changed customer/WO area invalidates
an existing approval. Use owner public reference contracts/views, never module internals.

Existing stock-origin constraints also need precise approved-opening support:
warehouse_assert_opening_approval is a stub inV174.2; warehouse_origin_guard rejects
all UPDATE origin changes, including legacy null->approved origin. Do not bypass these
broadly. Migration owner admission must be narrow, tenant/fence/approved batch-bound,
fixed search_path, original selected asset ID/raw identity preserved. Single posting
owner must admit and create the approval effect atomically; zero baseline needs a
real control posting with zero physical legs. Pending cancellation receipts,
reservation of postboot legacy identity candidates, final exclusive epoch flip,
provenance UI, mixed-tenant API+restart proof/M06 and remaining plan still required.

## Task43 opening review sealed and verified; independent approval/admission NEXT

V177.6 is APPLIED and IMMUTABLE, SHA-256
702c8a58dfdbecba78faa274bf1a7422ae9193e8fa97b58790714816a242f566.
Next free V177.7; V178 remains reserved for M06. Product gate10PASS2m1s
(Opening3,Resolution4,Modularity3); final strengthened Opening3PASS1m2s.
Safe proof task43/opening-review-verification.json. Runtime wrappers/logs
migration-opening-gate and migration-opening-final. Owned QA down, volumes retained.
Goal ACTIVE:1–42 DONE;43–48/F1–F4 OPEN. Continue to completion and push checkpoints.

Actual endpoints on WarehouseProvenanceController:
GET /batches/{batch}/review, POST /batches/{batch}/opening,
GET /batches/{batch}/opening/{id}. Prefix /api/v1/warehouse/provenance.
WarehouseOpeningBalanceService now implements the real batch-bound overload; the
old freeform multipart endpoint remains closed until its UI is replaced.
Request fields expectedEpoch,expectedReviewHash,reviewLocationId,
expectedReviewLocationRevision,migrationReference,reason plus Idempotency-Key.

warehouse_migration_review_manifest derives full captured sources + latest resolution
original JSON, ordered table/sourceId. Hash is DB jsonb text SHA256. Active AVAILABLE
asset/balance and pending movement/checkpoint/outbox require review; history may keep
null resolution, never auto-approved provenance. warehouse_migration_review_issues
rederives exact baseline/master snapshot and current source hash; requires matching
duplicate winner revision/identity. Selected asset/balance double counting is blocked.
Current implementation loops selected asset peers; optimize with set-based identity
groups if large-tenant profiling shows a bottleneck. Do not edit applied177.6.

Store creates an actual OPENING_BALANCE DRAFT + only selected BASELINE_STOCK lines,
stock identities still null until approved admission. No supplier, cost, purchase,
claim promotion or stock posting. Empty tenant has zero physical lines; real active
review warehouse/bin identifies policy scope without a fictitious SKU/quantity.
Opening response freezes manifest, review location, current owner-source refs,
requester/auth/cutover epochs and request metadata. Immutable DB request binds exact
command/response, manifest, original document and expected derived physical lines.
All sealed draft/line mutations currently fail closed; forward approval migration
must allow only its real approved admission/terminal transitions. Same actor/key/body
returns exact original even after later resolution, with current source scopes and
original files rechecked. Missing actual object fails replay. SQL999-unit forgery
fails seal and rolls back the cloned draft; direct edit/delete fails.

NEXT actual independent opening approval using existing DurableApprovalService,
WarehousePolicyEvaluationService and single WarehousePostingService. Do not introduce
a second approval or posting owner. All configured tiers must review unknown value;
valueNumerator/valueDenominator/currency remain NULL, never zero valuation. Existing
DB approval columns already allow null pairs; Kotlin store currently requireNotNull.
Include request actor + batch requester + every resolver/evidence uploader in excluded
participants. Require candidates/delegators/delegates to cover all current source
WO/customer areas, not only stock/review warehouse. Existing policy source rejects
empty lines; explicitly represent review metadata separately without fake line.

Current integration restrictions to change narrowly with real opening proof:
DurableApprovalService.request takes ORDINARY_STOCK, decision invalidates non-ENFORCED;
WarehousePostingService and assertReceiptApproval likewise requireENFORCED.
ApprovalPostingKind needs OPENING_BALANCE and an actual owner. Approval source content
must include sealed manifest AND current review/current source scope validation so
later resolutions make it stale. WarehouseApprovalSourceLock should take batch advisory
before owner WO/topology/customer locks; get/replay afterENFORCED must support frozen
batch reads without warehouse_lock_migration_batch's VALIDATING-only restriction.
Opening SQL seal currently blocks ALL document/line mutations, including rejection
approval_disposition. Request drafts may repeat review hash under new keys; actual
posting must enforce unique business identity per batch across all proposals.
The old freeform opening form needs replaced by provenance workbench.

Physical admission: reuse original selected asset IDs, preserve raw serial/MAC/legacy
SKU fields; set warehouse SKU/unit/verified fields only through narrowly checked
owner function. Existing warehouse_admission_guard and warehouse_claim_guard allow
migration owner, not application promotion; use approved batch-bound guard/function,
fixed search_path and tenant assertions, never a general bypass. Bulk/lot baseline
may create provenance OPENING origin lot with no supplier/cost. Historical duplicate
rows stay staged. Same transaction must admit, create single conserved posting and
approval effect, then future finalization validates and advances epoch ENFORCED.
Zero baseline uses approved control posting with zero legs, never fake physical leg.

Still required: approved pending legacy cancellation receipt and sealed terminal
checkpoint behavior; current MANUAL/APPLIED guards are preserved. Reserve every
current unresolved canonical identity before finalization, including postboot/new
or changed legacy rows; do not allow missing boot claims to be stolen by new receipt.
Keep old claims/candidates/history. C10 active selected conflict and units validation,
mixed tenant full packaged boot+HTTP reconciliation+restart, actual provenance UI,
M06 then remaining44–48/F1–F4 all still outstanding.

## Task43 legacy fulfillment cutoff verified; opening approval NEXT

V177.5 is APPLIED and IMMUTABLE. Next free version:177.6;178reserved. Final gate:
16tests PASS, BUILD SUCCESSFUL2m8s (FulfillmentMigration2,Coordinator5,Capture2,
Resolution4,Modularity3). Safe proof task43/legacy-fulfillment-verification.json.
Owned QA containers/network stopped; volumes retained. Goal ACTIVE:1–42 DONE;
43–48/F1–F4 OPEN. Continue the whole scope with commit/push recovery checkpoints.

Legacy WORK_ORDER checkpoints without fulfillment_approval_snapshot and their outbox
records now join current/frozen provenance source views (ten kinds). Fulfillment owns
capture through InventoryMigrationEffectsPort; no raw payload, error/outcome message
or worker identity is exposed. Source snapshots contain actual IDs/hashes/state/effect
progress. Pending checkpoint and outbox counts are separate. Pending sources require
CANCEL_PENDING proposals; APPLIED/manual history stays provenance-only. No cancellation
is executed yet: independently approved permanent cancellation receipts remain required.

InventoryProvenanceWorkOrderPort is implemented in workorder, checks current area and
returns safe code/customer references. Source access order is cutover/current authority,
optional batch advisory, WO shared locks, topology/location, customer shared locks.
Customer coverage includes the WO's current customer. Tenant-wide summary/cases take an
exclusive read fence: READ_COMMITTED live sources cannot appear between scope checks
and counts. GET still does not persist cases or mutate stock. Evidence/resolution reads
use the already frozen batch membership.

FulfillmentCoordinator now treats MANUAL_RESOLVED as terminal in accept/process and
preserves terminal outcomes during failed retries. Worker reconciliation cannot reopen
APPLIED/FAILED_PERMANENT/MANUAL_RESOLVED. Added explicit cutover fences to save, enqueue,
ACK and effect-progress persistence methods (claim methods already had them). Tests
prove real HTTP report waits on a writer changing WO area, then denies old-area access;
worker ACK waits on exclusive cutover; exact manual/APPLIED replay from fresh transactions;
old pending payload twice returns FULFILLMENT_SNAPSHOT_REQUIRED reconciliation with zero
new physical effects.17 current sources captured, including6 fulfillment sources.

NEXT: actual independent OPENING_BALANCE workflow using existing durable approval and
single posting owner, not a parallel approval/ledger. Seal actual resolution/file/master
manifest under batch lock; require proposals for active stock and pending effects, keep
historical/unknown-installed sources staged and excluded. Derive quantities from source;
no invented price, receipt or zero valuation. Explicit unvalued review must use every
configured tier; keep real costs null. Empty validated tenant needs independent control
approval without fake physical lines. Exclude batch/request/resolution/evidence actors.
Approvers need all current source areas, including WO/customer areas, not only baseline
warehouse areas; evaluate candidate eligibility accordingly. Opening evidence must be
available via existing approval source/evidence reads under current scope.

Existing integration points: WarehouseOpeningBalanceService is still a fail-closed stub;
DurableApprovalService.request currently requires ORDINARY_STOCK, decide marks non-ENFORCED
stale, and rework needs opening-specific new-request handling. WarehousePolicySource maps
OPENING but requires lines; Evaluation rejects unknown cost except title correction, and
WarehouseApprovalStore.insert requires nonnull value numerator/denominator. Add explicit
unvalued all-tier semantics, never reuse title's internal0/1 as an opening cost. Approval-
PostingKind lacks OPENING; SQL permit validation and posting stage guards need forward
changes. WarehousePostingService is ENFORCED-only. Empty baseline requires a narrow typed
control posting, not a fake stock leg. Finalization remains closed in app and DB.

After approval: atomic original-asset admission/claim promotion/opening posting and
approved permanent legacy-effect cancellation; exclusive count/unit/collision/effect
checks -> epoch+ENFORCED. Unknown installed history remains staged with IDs intact.
Then /warehouse/provenance UI (still absent), mixed-tenant realHTTP+restart and browser QA.
Branch work/warehouse-completion -> origin/feat/warehouse-workorder. No merge/deploy.

## Task43 current cutoff snapshots verified; legacy fulfillment effects NEXT

V177.4 APPLIED and IMMUTABLE; next free177.5,178reserved.13testsPASS/BUILD SUCCESSFUL
2m54s: Capture2/Boot2/Query2/Resolution4/Modularity3. Safeproof task43/current-cutoff-
verification.json. OwnedQA containers/network stopped;volumesretained. Goal ACTIVE;
1–42DONE;43–48/F1–F4OPEN. Continue whole scope, commit/push coherent checkpoints.

The stale Flyway snapshot gap is fixed. Read-only live owner views provide current
source data beforebatch; GET doesnotpersist. Begin exclusivecutover waitsoldwriters
then checks actualreviewhash. Changedsources get immutable newcaseversions; identical
sources reuse originalcaseID. Deterministic SHA-derived candidateIDs make previewID
matchcapture. inventory_provenance_case uniqueness nowtenant/table/sourceID/hash;
no oldrows deleted. InsertguardrequiresinitialVALIDATINGexclusivefence+actualowner
snapshot+stableID and refusesafterbatchseal. Inventorycapturesown7sourcekinds;
customer rootport captureSources obtainsactualONUwhitelist+currentareaauthority.
Databasechecks refer to RLS-invoking publicowner sourceviews. No directinventory
Kotlinqueryonprivatecustomertables. BatchtriggerrequiresALLactualcurrentsources
captured, derivesmanifest/hash. warehouse_report_provenance_case useslive data before
batch andexactfrozenmanifestafterwards; eligibility ignoresobsoletehistoryversions.

CaptureITrealHTTP holdsactualappDBsharedcutoverlock, changeslegacyquantity, starts
BEGIN, observespg_blocking_pids (no sleeprace), commitswriter: BEGIN409STALE_REVISION.
GETshows91000but old82500snapshotunchanged; refreshBEGIN201capturesnewversion and
exactreplay. Addedsourceincluded, removedprojectionexcludedfromcurrentmanifestbut
oldcase preserved. All previous query/resolution/boot regressionspass. No newdocs/
verifiedstock/admission. Currentreportcases are the activegeneration; rawhistorical
versions remain inbase table, notsilentlydeleted.

NEXT177.5: capture pendinglegacyfulfillmenteffects throughinventoryrootport implemented
infulfillment. Existing InventoryTenantPolicyPersistence.beginValidation onlyrecords
inventory_movementstate!=APPLIED; FulfillmentCheckpointPersistenceAdapter claim/claim-
Pending/claimOrCreate useCONTROL_PLANEfence. Actualownerwork istransactional, but old
pendingoutbox/checkpointIDs also need cutoffmanifest and terminalreconciliation so
oldreplay neverpostsafterbaseline. Use actualcheckpoint/outbox/effectprogress, not
fakeinventoryfacts or rawpayload disclosure. FulfillmentINVENTORY executor currently
approvals.verify (no newstockeffect); retain history and distinguish alreadycompleted
frompending. Cancel proposals still proposals, notexecutedmutations.

Then: seal actualresolutions/evidence/master revisions; reuseexistingdurableapproval
owner andsinglepostingauthority forOPENING_BALANCE with explicitunvaluedfulltiers,
no fake0cost or receipt. Emptyvalidated tenant needs independent controlapproval
withoutfakephysicallines. Admitwinner actualassetID+claims atomically; oldduplicate
rows remain staged. FinalizeexclusiveepochENFORCED aftercounts/units/conflicts/effects.
Unmatched/unknowninstalled provenanceonly remainsstaged,neverISPavailability. Add
/warehouse/provenance UI (stillabsent), mixedtenantrealHTTP+restart andbrowserproof.
Branchwork/warehouse-completion ->origin/feat/warehouse-workorder; no merge/deploy.

## Task43 resolution proposals verified; current cutoff capture NEXT

V177.3 APPLIED and IMMUTABLE; next free177.4,178reserved. Nine tests PASS1m59s
(Modularity3/Query2/Resolution4), then Resolution4 PASS1m with added directSQL
quantity-forgery/strictinput assertions; product unchanged. Safe proof task43/
migration-resolution-verification.json distinguishes runs. Owned QA stopped;
volumes retained. Goal ACTIVE:1–42DONE;43–48/F1–F4OPEN. Continue until full scope.

POST/GET /provenance/batches/{batch}/cases/{case}/resolutions now stores immutable
revision history: BASELINE_STOCK,PROVENANCE_ONLY,DUPLICATE,CANCEL_PENDING. Exact
batch/case/source-hash,1–10 real existing file IDs/readback, expectedrevision,
actor/key/body replay. Database independently derives stock from original data:
serial1EA, balance explicitEA/MM/M exactintegerconversion; original active tenant
location +activeSKU/revisions +ISP title required. Cannot inventquantity/price,
reinterpret knownunits, admit installed/customer-owned or count serialbalance
again. Duplicate binds actualsamephysicalasset's currentproposalrevision; old
rawrows remain. CANCEL_PENDING is a proposal only; actualmovementstillpending.
No verifiedstock/claims/docs/posting/finalization. Database derives/fences metadata;
resolutionhistoryUPDATE/DELETErevoked+appendonly. Allordinarywritesstillclosed.

Platform orphan report fix: currenttenant missinglocation reference is shown only
as preserveddata withoutforeignname; ordinaryoperator stillneedsfullsourcecoverage.
Orphan cannotbeBASELINE_STOCK. Realtests coverforeignlocation, competingrevisions,
revokedoldJWT/replay, unchangedrawIDs/status anddirectSQLquantityforgery rejection.

CRITICAL NEXT beforeapproval/admission:177 snapshots are currently captured at
Flywaytime, butLEGACYwriters maychange/add/delete sources beforeBEGIN. Implement
current source capture/versioning under exclusivecutover, retaining old snapshots,
withoutadmitting staleboot quantities. Report should showactualcurrentrawsource;
batchmustfreezeexactwatermarkmanifest afteroldwritersdrain. Customer snapshot work
stayswithcustomerowner via inventoryrootport, no directinventoryprivatecustomerSQL.
Capture pendinglegacyfulfillmentoutbox through ownerport aswell. Then independent
batch approval through existingdurableowner, explicitunvalued/allconfiguredtiers,
openingposting/claimpromotion/finalization (samepostingauthority), UIandHTTPmixed
stages/restart. Duplicatewinner/master revisions needrevalidation atseal.
/warehouse/provenance UI stillabsent; read web/DESIGN.md9 before work.

Branch work/warehouse-completion -> origin/feat/warehouse-workorder. Commit/push
coherentchunks withhandoff. No merge/deploy; originalwarehouse-task29preserved.

## Task43 private evidence checkpoint verified; case resolution NEXT

V177.2 is APPLIED and IMMUTABLE; next free177.3,178reserved. Real HTTP+MinIO
EvidenceIT3 PASS / BUILD SUCCESSFUL1m3s after final object-key/label/epoch guards.
Supporting Modularity3/Query2/Policy11 PASS in preceding19-test green run2m12s.
Safe proof task43/migration-evidence-verification.json distinguishes both runs.
Owned QA containers/network stopped; volumes retained. Goal ACTIVE,1–42DONE;
43–48/F1–F4 OPEN. Branch work/warehouse-completion -> origin/feat/warehouse-workorder.

POST/list/GET /provenance/batches/{batch}/cases/{case}/evidence now uses real private
ObjectStorage, exact multipart request+file, PDF/PNG/JPEG<=15MiB, SHA/readback checks,
immutable batch/case/source-hash metadata and actor/key/body-bound original replay.
Download/replay fail closed when object is missing/corrupt. Fresh-transaction cleanup
waits the same batch advisory lock, retains committed/unsettled objects, and rejects
keys outside the exact supplied tenant/batch/case/evidence ID. Tests lose actualHTTP
response, terminate only owned app DB backend after write, and verify rollback cleanup.
No receipt/intake/stock is fabricated. PROVENANCE_RESOLUTION enabled only VALIDATING;
MIGRATION_APPROVAL/BASELINE/FINALIZATION still hardclosed pending their actual owners.

Shared WarehouseProvenanceAccess factors current provenance.manage plus current full
source warehouse/customer-area scope. Batch command lock order: cutover -> current
authority -> batch advisory -> topology/customer -> case/document. Immutable batch/case/
evidence tables have UPDATE revoked: use warehouse_lock_migration_batch/advisory locks,
not SELECT FOR UPDATE on them. V177.2 function validates actual batch and epoch.

NEXT: append-only evidence-bound case resolutions with exact source-unit conversion,
no guessed price/title, preserved loser raw IDs, duplicate balance/asset checks; then
reuse actual durable approval/posting owner for independently approved batch-bound
OPENING_BALANCE. Explicit unvalued all-tier review must keep actual costs null, not0.
Audit pending legacy fulfillment effects through owner port, reconcile under exclusive
cutover fence, and implement finalization/mixedtenant HTTP+restart proof. Orphan source
locations currently block reports even for platform: add safe preserved-only handling.
/warehouse/provenance UI still absent; read web/DESIGN.md9 before implementing it.
No full43 acceptance yet. Continue; commit/push coherent phases, no merge/deploy.

## Task43 report + begin-batch checkpoint verified; resolution/approval NEXT

V177.1 is APPLIED and IMMUTABLE (see docs/warehouse-migrations.md for hash).
Authenticated Spring Boot/MockMvc API2 PASS / BUILD SUCCESSFUL51s after fixing
error-advice registration and a fixture permission typo (actual inventory.item.view).
Support15 PASS on same product source: Modularity3/full-packaged Boot2/inventory10.
Expanded17 run had only the fixture failure, then query2 alone reran green. Safe
proof task43/provenance-query-batch-verification.json. All ownedQAservices cleaned,
volumes retained. Branch work/warehouse-completion -> origin/feat/warehouse-workorder.

GET /api/v1/warehouse/provenance, /cases bounded page/size/sourceTable, /cases/{id}.
Require provenance.manage + every referenced current location/customer area before
counts. Root InventoryProvenanceCustomerPort implemented in customer checks current
area under authority fence; no inventory SQL on customer tables. Tests move customer
area while warehouse scope stays valid, revoke old-token access, foreigntenant,
malformed filters, original raw82500 unknown units and deterministic snapshot hash.

POST /provenance/batches uses Idempotency-Key and strict {expectedEpoch,
expectedPreservationHash}. Exclusive cutover fence before authority/topology/customer
locks; verifies actual immutable manifest hash. LEGACY -> existing policy.beginValidation
captures watermark +pending movementIDs and epoch++. Existing VALIDATING withoutbatch
captures its original batchid/epoch/watermark. DB derives manifest and binds immutable
inventory_migration_command actor/hash/expected+resultingepochs/originalresponse tobatch.
Replay rechecks current authority/actor/exacthash/currentepoch; same stored response201.
Onebatch/onecommand,0newdocuments/verifiedstock; ordinary writes still denied.

NEXT: 177.2 next unused,178reserved. Implement evidence-bound case resolution and
independent OPENING_BALANCE through existing durable approval/posting owners; full-tier
unvalued review must not fake0price. No admission/finalization guards opened yet. Audit
legacy fulfillment outbox effects through owner port, in addition to pending movement
IDs; current report covers pending legacy movements only. Finalization must reconcile
active units/conflicts/effects and exclusive tenant fence. Customer installed/unknown
sources remain staged unless proven, never ISP availability. See appended design notes
in task-43.md. Missing source locations currently master lookup fails even for platform;
add preserved-only orphan reporting safely as part of full reconciliation.

No frontend43 edits yet. Read web/DESIGN.md9 before UI. Route /warehouse/provenance
is still absent (customerlegacyCTA alreadypoints there); add typed API/read/begin workbench
then resolution/approval forms using existing Fluent command patterns. Current newdocs
warehouse-provenance.md accurately describes partialphase; update as workflows finish.
No full network-HTTP/restart/admission/browser proof yet;43 OPEN. 1–42complete,43–48/F1–F4
remain. Goal ACTIVE; continue. Originalbranchpreserved; no merge/deploy. Commit+push often.

## Task43 M05 preservation checkpoint verified; interactive API NEXT

V177 applied successfully and now IMMUTABLE. Fifteen tests PASS (full-packaged
WarehouseMigrationITBoot2, inventory10, old schema3), BUILD SUCCESSFUL1m9s.
Safe proof task43/preservation-migration-verification.json. Full clean and V172
colliding upgrade run every packaged migration. 3 original assets,2ONU,11cases,
3 identity conflicts; unknown82500 units preserved,0 new verified stock/documents.
Batch test derives all11manifest rows/hash under VALIDATING, rejects forged empty
manifest, immutable evidence, foreign tenant visibility and premature ENFORCED.
No actual application HTTP reconciliation/admission yet;43 stays OPEN.

M05 snapshots8 legacy source kinds in inventory_provenance_case, exact source IDs/
whitelisted JSON/DB-generated SHA256. No new units/prices/origins/stock. Immutable
inventory_migration_batch binds existing cutover batchid/epoch/watermark and derives
case manifest. App can only SELECT cases, INSERT/SELECT batch under validatingfence.
No prior VALIDATING row is forced to have a batch before operator bootstrap.
Next free177.1;178reserved finalconstraints. Initial V177 attempt rolled back
(nonimmutable convert_to in generated column); fixed before first successful apply.

NEXT: current-authority/scoped dryrun and bounded cases API, exclusive begin-batch
control command with original-response idempotency, then resolution/independent
approval/opening/posting/finalization andUI. Reuse existing durable approval owner.
For customer scope use inventory root port implemented in customer (current tenant/
area) rather than inventory SQL reading customer tables. Gate global tenant report
before counts if operator lacks affected location/area coverage. Old source snapshots
must remain immutable. Task42complete at01c051f6pushed,41JVM+bothmacOSiOScompilesPASS.
Tasks43–48/F1–F4remain; active goal. No deploy/mainmerge. Commit+push checkpoints.

## Task42 complete; task43 preservation/cutover in progress

At eef43d24, 41 JVM tests and module graph PASS; both actual iOS application
compile tasks PASS locally and on macOS CI run36074462296 (success). Commit-bound
safe proofs: task42/material-ios-local-verification.json and material-ios-macos-
verification.json, plus material-feature-verification.json. Native runtime/release
not claimed. Local QA services cleaned; volumes retained. 1–42 done,43–48/F1–F4
remain; goal ACTIVE. No merge/deploy.

Task43 research and next steps in task-43.md. V177/M05 and V178/M06 unused and
reserved. Max current175.148; actual M04 shipped175.48 onward (no V176 file).
Begin immutable legacy source snapshots + full packaged collision upgrade proof;
then batch-bound resolution, existing durable independent approval/posting owner,
per-tenant finalization and /warehouse/provenance. Do not synthesize origin/units/
prices or re-enable ordinary writes in VALIDATING. No43 product SQL/code yet.

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

## Tasks40/41 complete; task42 mobile material contracts NEXT

RMA technician flow verified: backend10 tests PASS / BUILD SUCCESSFUL4m13s;
full web320 tests/66files PASS53.77s, project TypeScript/build/changed-product lint PASS.
Proof: task41/my-materials-rma-verification.json. Owned services cleaned; volumes retained.
No migration.149 remains next free;177/178 reserved43.

MyMaterialQuery now includes own repair-only WO jobs and scoped RMA reads. All3 recorded
location grants and actual CUSTOMER SERVICEABLE1EA physical position checked before count;
after reinstall source disappears. New dispatch captures actual WO code; old rows use
readable document fallback. Material Saya has named own RMA receipt/reinstall, manual/
keyboard/camera scan, current context/source recheck, separate original-customer source
chain, exact canonical ACK then authorize/install commands and safe retries. Customer
blade completes signed acceptance; ownership remains CUSTOMER. Offline remains a draft.
Two-stage customer and RMA installs stop before a later request if account/session changes;
normal access-token refresh does not invalidate the captured session.

39-41 now implemented/verified. Full actual browser desktop/mobile and numeric acceptance
still task45, not claimed by web unit tests.42-48/F1-F4 OPEN. Goal ACTIVE; keep working.
Next42: existing mobile foundation has MviViewModel, Fluent primitives, Koin app and native
SecureOutboxPort, but secure records currently lack scoped decrypted listing and per-key
completion needed for durable material retry. Add typed domain MaterialContracts, actual
canonical request/decoder repository, shared feature/materials ViewModel/screens and app
DI/navigation. Only allowed material report/ACK intents may queue; accepted only from
server, reconnect checks current source/scope/revision, uncertain retries keep exact keys.
Bind tenant+user/device/session and purge on logout/switch. Reuse existing encrypted storage,
not a production in-memory queue. Unit test restart/response loss/offline conflict/DI.
Current Android target is pre-existing disabled; iOS targets configured but Linux may skip
native compilation. Record actual executed checks, no native runtime/release claim.

Current branch work/warehouse-completion tracks origin/feat/warehouse-workorder;
original warehouse-task29 preserved. Commit/push each coherent checkpoint. Never commit
runtime env/tokens/raw private XML. No deployment/main merge.

## Task41 core customer asset UI + safe portal verified; customer-owned RMA NEXT

All current changes validated: full web304tests/63files PASS69.01s, TypeScript/production
build/new-product oxlint PASS (existing chunk warning and old-page set-state-in-effect
warnings remain). PortalCustomerAssetsIT1 +ModularityTests3 +PortalSelfServiceTest12 =16
PASS / BUILD SUCCESSFUL2m51s. Initial portal fixture failed customer creation because it
omitted required location and code exceeded40chars; fixed fixture, finalall16green.
Read workbench52realtests already PASS at53c646ef. Safe proof task41/customer-asset-core-
verification.json. Owned services cleaned, volumes retained; no migration.

CustomerAssetPanel/Installation/Action in existing customer blade use eligible acknowledged
serials, current WO and assignment/title/episode/signature refs. Two-stage authorize then
consume keeps independent exact command keys/bytes after response loss. Actual canonical
install/replace/remove/handover/ODP relocation. Old free serial/create/delete/attach/detach
controls in OnuManager replaced by observation plus asset actions. Discovered suggestion
opens eligible source review; no direct raw serial provision. Portal /api/portal/me/assets
only uses logged-in principal and whitelisted presentation; no assignment IDs, warehouse
IDs, cost, evidence or actors. Old portal connection schema preserved. Cross-links use
/customers with state.openCustomerId, not nonexistent /customers/:id.39 marked COMPLETE.
40/41 still OPEN for RMA described below; actual mobile/full browser remains45.

NEXT required40/41: warehouse dispatch of repaired CUSTOMER-owned RMA exists in
WarehouseCustomerRma.tsx, but field UI has no own RMA ACK and no reinstall action yet.
Canonical APIs already implemented/proven task26: POST /api/v1/warehouse/rma-handovers/
{id}/acknowledge {expectedRevision,observedSerial,evidenceReference}; authorize on REPAIR
WO with DeploymentIntentRequest {expectedRevision=currentWO,assetId,issueLineId:null,
purpose:RETURN_CUSTOMER_RMA,ownershipMode:SALE,previousAssignmentId:originalAssignment,
repairCaseId}; then normal /api/customers/{id}/assets/install {authorizationId,expectedRevision:
actual permit revision,topology}. Final acceptance uses existing /assets/handover; customer
title stays CUSTOMER, never ISP available. CustomerAssetAction wording already handles
RMA CUSTOMER title without claiming a second transfer.

Suggested implementation next (no RMA edits yet): add bounded self-only RMA reads to
InventoryMyMaterialsApi/MyMaterialsController + direct current reference, expose pending
DISPATCHED and own held RECEIVED. MyMaterialQuery.jobs currently only issues/residuals;
add own RMA jobs so repair-only WO appears. New query joins inventory_rma_handover,
current inventory_document revision + inventory_operation.original_body (typed current
CustomerRmaHandover), filters current actor and all three visible source/transit/field
locations before count, plus actual1EA CUSTOMER SERVICEABLE IN_TRANSIT/ISSUED position.
After reinstall stock no longer ISSUED so source disappears. Reuse WarehouseCustomerRmaService
.details for named work order/people/locations after scoped IDs. It authorizes all three
locations and current WO area via InventoryRmaWorkOrderPort. Receiver ACK requires stored
WO revision still current; reinstallation authorize uses fresh current WO revision.
RmaHandoverStore.create currently leaves document.work_order_code_snapshot null; consider
capturing actual workOrders.read(...).code on new rows and readable legacy fallback rather
than guessing a WO ID. Material Saya context has no customerId except field.plan; RMA
may have no material plan, so add explicit scoped customerId if needed for navigation.
Field own RMA ACK only needs field permission; deployment needs field+customer.onu.assign;
customer read panel also customer.onu.view. Never make CUSTOMER stock selectable as ISP use.

Test fixtures: WarehouseCustomerRmaFixture.prepareRma(),dispatchRma(),receiveRma(),
case.authorization; existing WarehouseCustomerRmaDeploymentIT real replay/conservation;
AcceptanceIT,GuardsIT,HandoverIT cover title/scopes. Workbench query sources currently
only PSB/MIGRATION ISP serials (correct); add separate RMA path/panel, not ownership bypass.
Then finish41 and40, continue42-48/F1-F4. Goal ACTIVE. Migration149 next free,177/178reserved43.
Commit/push often to origin feat/warehouse-workorder; original warehouse-task29 preserved.
No deployment or main merge.

## Task41 customer asset read workbench verified —52 real tests PASS

New CustomerAssetWorkbenchIT4 and existing CustomerAssetReplacementIT48 PASS;
BUILD SUCCESSFUL6m26s, proof task41/customer-asset-read-verification.json. Own bounded
acknowledged SERIAL source picker, actual WO/job/signature/assignment/title/episode
revisions, named origin and old/new replacement chain. Current customer area, field
actor, assigned job and warehouse source location gates. Exact install replay and
sale ownership verified; GET never posts stock. No migration. Owned cleanup complete.

UNCOMMITTED frontend41: customerAssets typed API and two-stage captured authorize/
install-or-replace command; CustomerAssetPanel/Installation/Action/Topology. Existing
customer blade now keeps ONU observation and exposes canonical asset actions; free
serial create/old attach/detach/delete buttons removed there. Discovered inbox opens
eligible source review; no direct suggestion-only provision POST. Links from39/40 use
existing /customers router state openCustomerId (there is no /customers/:id route).
New7webtests PASS3.33s; related20/5 PASS5.20s; TS PASS. Own lint warnings still to fix:
CustomerAssetPanel unnecessary version dependency; Topology exports move to helper.
Existing old-page set-state-in-effect warnings also present. Build not run yet.

Next: fix own lint; add replacement/removal/relocation/readonly/observed-inbox coverage;
portal safe asset presentation and actual principal-only backend proof; build/fullweb;
checkpoint.41 remains OPEN. Browser45 will exercise actual desktop/mobile full customer
chain and portal redaction.43 provides legacy reconcile route currently linked by CTA.
39/40 OPEN pending complete41 verification.42-48/F1-F4 remain, goal ACTIVE.
Migration149 next free,177/178 reserved43. Commit/push often; no deployment or main merge.

## Task40 handover verified; task41 customer asset workbench IN PROGRESS

Handover production workflow at4e877146 validated. Last ten-test run had8 related
regressions PASS plus PartiesIT1PASS/1FAIL; failure was test accounting baseline taken
before a deliberate master-location update (which legitimately records an operation).
Baseline moved after that update; fresh PartiesIT2 PASS / BUILD SUCCESSFUL2m12s.
No production fix required. Full web291/58 PASS89.48s, TypeScript/build/new-product lint
PASS. Safe proof task40/material-handover-verification.json records both runs honestly.
Owned cleanup completed, volumes retained.39/40 stay OPEN for41 asset links; actual
mobile touch/keyboard browser45. No migration beyond148;149 next free,177/178 reserved43.

CURRENT task41 uncommitted backend read work: public CustomerAssetReadApi/customer
owner scope check and episode metadata; WorkOrderAssetWorkbenchApi own assigned jobs
with actual revisions and signature references; InventoryAssetWorkbenchApi named eligible
acknowledged SERIAL sources/current bounded customer assignment history. Coordinator
/api/customers/{id}/assets/workbench root,history,jobs,job,sources and direct references.
CustomerAssetWorkbenchIT4 and existing CustomerAssetReplacementIT queued/running via
.omo/runtime/customer-asset-workbench-server.sh/log; archive task41/customer-asset-workbench.
These new reads are NOT verified yet. Inspect compile/runtime failures before claiming.
Next41: typed frontend API, CustomerAssetPanel in existing customer blade, actual canonical
install/replace/remove/accept/relocate controls, discovered inbox eligible source, safe portal
presentation, tests and links from WO/Material Saya. Then42-48/F1-F4. Goal remains ACTIVE.
Continue coherent commits/pushes to origin feat/warehouse-workorder; no merge/deployment.

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

## Task39 review/rework verified — continue40, finish39 links with40/41

WO Material now integrates planning/templates and request/stock links, exact measured
use and immutable positive correction, explicit NONE declaration, scoped usage history,
name-resolved paged obligations and independent technical/QA/provisioning/material states.
The frozen QA review reads actual persisted fulfillment usage/version, gated by current
WO + actor/location authority; no raw source payload/session/cost exposure. Positive
rework form captures prior plan/use/evidence revisions, appends only extra quantities
and preserves inherited history. Field-only technicians can see the existing evidence
section using the controller's established field permission. Existing completion/QA
commands remain canonical, no invented revision or serial-consumption fallback.

Backend8/3suites PASS2m20s, targeted web29/6 PASS8.76s, full web269/50 PASS51.31s.
Initial review decoder TypeScript errors fixed with default path parameters; final7
review/API tests PASS, TypeScript/build PASS. Whole warehouse+WO oxlint exits0 with4
pre-existing set-state-in-effect warnings in old WO components (new material files clean).
Proof task39/material-review-verification.json; raw reports/logs local ignored runtime.
All QA services cleaned, volumes retained. No migration.148 next free,177/178 reserved43.

Task39 checkbox deliberately OPEN for final cross-navigation to Material Saya40 and
serialized customer-asset workflow41. Next40: actual own pending handovers, acknowledged
stock and returns even after reassignment, scanner/camera fallback and offline draft
semantics. Current workbench custody is per active authorized WO; do not bypass its
roster check to serve former-assignee returns. Add separate own-custody read service
using inventory records/current fences + WorkOrderMaterialContextApi.lockForCustody.
Canonical residual return/ack/handover commands already exist; reuse them. Current issue
ACK requires current WO revision equals dispatch snapshot revision; present mismatch
rather than inventing/replacing revisions. Tasks40–48/F1–F4 open; long-run goal ACTIVE.

## Task39 WO Material UI checkpoint — core flow verified; remaining review/rework ongoing

22web tests/4files PASS3.92s, TypeScript and targeted oxlint PASS. Integrated existing
WO detail with named plan, canonical planning/editor/request links, exact measured use,
positive immutable correction, explicit NONE declaration, paged named usage history,
independent technical/QA/provisioning/material states and actual-revision closure.
Captured body/key survives uncertain replay; stale409 reloads. Current roster/status
hides invalid use, and residual obligations remain visible after cancel/reassign.
No invented allocations or serial consumption bypass. Backend e139250a five tests PASS.

Current additional backend edits for named paged obligation rows and frozen QA review
are being verified by .omo/runtime/material-review-server.sh/log (8tests expected).
Next finish those UI reads and positive rework plan form, add targeted tests, then
full web/build gate before marking39 complete. Task39 remains OPEN;40–48/F1–F4 remain.
No migrations. Goal ACTIVE. Existing evidence anchor remains in WO proof/evidence flow.

## Task39 material workbench backend verified — frontend in progress

Added InventoryMaterialWorkbenchApi with current WO plan/use revisions, bounded own
custody choices and immutable named usage history. Custody matches actual accepted
receipt and use/return/handover descendants against current own ISSUED physical balance;
current location/area topology filters run before totals/pages. No cost/raw posting
DTOs, no broad IAM requests, no writes on GET. Context contains latest usage identity,
not another actor's raw source lines. Canonical report-use/correct-use remain unchanged.

5tests/3suites PASS3m22s: new MaterialWorkbenchIT2, existing UsageLifecycle2 and
LifecycleDelta1. Proof task39/material-workbench-verification.json. QA cleanup done.
Current uncommitted web work: materialExecution API, WO Material section with planning,
usage/positive correction form, history and independent close; tsc pending. Remaining39:
meaningful API/UI tests, named obligation references, frozen QA review, rework form,
all required rejection/reassign/cancel/no-material cases, final gate. Task40 own-custody
page/ack/return/scanner/offline follows; then41–48/F1–F4. Long-run goal ACTIVE.
Task38 COMPLETE at5ed6df25; fullweb249/44, build/lint green before39 additions.
No migrations. Never claim whole39 finished from this backend checkpoint.

## Task38 COMPLETE — task39 in progress

Source1119e257: settings backend9tests/4suites PASS, targeted web15/4 PASS,
full warehouse web249/44 PASS58.63s, TypeScript/build/warehouse oxlint PASS.
Proof task38/settings-workbench-verification.json. Initial delegate-count test corrected
for the real eligible admin, rerun full9 green. Owned cleanup completed, volumes kept.
Together with policy, reports, replenishment and overview proofs,38 is COMPLETE.

Task39 now in progress: WO Material section will use named current own custody,
immutable measured-use history, actual source/revision selections and independent
material settlement. New read-only MaterialWorkbench owner API/controller compiles;
real5test integration gate in .omo/runtime/material-workbench-server.sh/log pending.
No39 completion claim. Then40–48/F1–F4 remain open; long-run goal ACTIVE.
No migration;148 next free,177/178 reserved43. All checkpoints pushed regularly.

## Task38 settings UI checkpoint — targeted web verified, integration rerun pending

Added on-demand named policy history and scoped paged delegation workbench, direct-user
or configured-role source picker, independent delegate picker, expiry validation and
captured create/revoke review. Canonical create expectedRevision=0; revoke uses actual
row revision. Same body/key survives ambiguous retries; stale409 reloads current state.
Read-only approval.view can reach settings without location.view; unauthorized actions
and broad IAM reads remain absent. Saved policy preview reused in history.

15 web tests/4 files PASS3.94s, TypeScript and targeted oxlint PASS. New8 tests cover
history paging/names, role binding, independent target, replay and actual revision.
Initial backend9 run had1 test expectation failure: eligible tenant admin makes3 delegate
candidates, not2. Corrected assertion checks all3 actual IDs and excludes source; no
product authorization was weakened. Fresh9 run: settings-workbench-corrected-server.log,
archive task38/settings-workbench-corrected/xml. Full web/build/lint in progress:
task38-full-web.log, task38-web-build.log, task38-web-lint.log. Whole38 remains OPEN until
those checks pass. Next39: integrate WO material planning, field use and settlement.
Goal ACTIVE; then40–48/F1–F4. No migration. Remote checkpoints requested by user.

## Task38 settings read backend checkpoint — verification pending

Implemented new /settings/workbench policy-history, delegations and delegation-candidates.
History requires all version locations visible before totals/pages; delegation rows are
scoped by location and ACTIVE/EXPIRED/REVOKED before paging. Named users/roles/locations
project only onto authorized rows. Historical archived locations remain readable through
current topology/area authorization. Candidate pickers require manage + selected active
location/current policy, direct-user or explicit source-role eligibility and no delegation
chains/cycles. Existing create/revoke commands remain canonical. HTTP page25/max100,
strict unknown/repeated filters and no-store; no raw directory API or UUID entry.

New2 settings-workbench integration tests pending, with existing policy/workbench and
approval delegation tests. Wrapper .omo/runtime/settings-workbench-server.sh, log
settings-workbench-server.log; fresh archive task38/settings-workbench. Frontend history/
delegation still pending. Overview backend4 + web13 verified at4dc77371/adbc8e1e.
Whole38 still open, then39–48/F1–F4. No migration. Long-run goal ACTIVE.

## Task38 overview verified; settings history/delegation next

Overview backend4dc77371:4tests/3suites PASS3m22s (Shortages2, Balances1, Privacy1).
13web/3files PASS6.68s (OverviewAPI2, OverviewUI5, ReportsUI6); TypeScript/oxlint PASS.
Proof task38/overview-verification.json. Owned cleanup complete; volumes retained.

WarehouseOverviewPage replaces the old root component through the existing route
facade. Permission-gated independently loaded/paged shortage, replenishment, approval,
inspection/repair return and oldest transit panels; actual source links and scoped
totals. No cross-unit sum or broad requests for unauthorized panels. Route mapping
now exhaustive rather than nested conditional. Reports/replenishment already verified.

Task38 remaining: bounded named policy version history and delegation workbench,
then full web/build checks; actual dashboard/report browser can join task45 acceptance.
Existing delegation.list is unbounded and fails entire list if any location unscoped;
use a new scoped paged read projection before exposing it. Delegation create/revoke
already enforce current policy/source membership, both principal location+area, no
chains/cycles, max30days and actual revision. Do not introduce raw UUID entry.
Then39–48/F1–F4; goal ACTIVE. No migrations;148 next free,177/178 reserved43.

## Task38 overview shortage query checkpoint — verification pending

Added GET /stock/shortages for inventory.item.view: active SKU minimum compared with
current admitted available stock inside current visible locations, including zero-stock
SKUs. Positive shortages filter in SQL before totals/pages; no costs or legacy quantity
projection. Only page/size/SKU/location filters allowed to avoid misleading partial
status/date comparisons. Two new HTTP tests cover exact MM, zero-stock EA, pagination,
current authority, empty scopes/foreign tenants and strict filters. UI overview next.
Reports and replenishment checkpoints verified; whole38 remains open.

## Task38 replenishment verified; overview next

Replenishment backend d874d2d0:12tests/4suites PASS2m13s, including new2 workbench
and old3 basic/4 commands/3 inbound.16web/3files PASS4.60s, TypeScript and oxlint PASS.
Initial new UI tests corrected jsdom dialog polyfill/role options/required-label matcher.
Proof task38/replenishment-workbench-verification.json. Owned cleanup done; volumes retained.

New Pengisian Stok route provides named rule/request lists, state/active server filters,
read-only current versus captured quantities, create/edit exact-unit rules, bounded
history, recompute, accept/cancel/archive with captured revisions/replay, and binding
an actual receipt line/current revision. Cost/stock/ordinary master read permissions
are not prerequisites for reading or editing an existing rule. Creating a rule uses
named SKU/location pickers. Accepted requests block archive until cancellation.
Receipt binding follows existing backend singular-line exact-quantity contract; it
requires one matching received line (serial multi-line batch binding is not added).

Task38 overview shortages/transit/pending drilldowns and remaining settings history/
delegation next. Reports and policy settings already verified. Then39–48/F1–F4.
Formal long-run goal ACTIVE. No migrations;148 next free. Branch checkpoints pushed.

## Replenishment compile correction; fresh verification next

9dd7fe96 initial backend build failed in13s: missing imports for inbound MasterKind,
SkuSnapshot and LocationSnapshot. No tests executed; copied old report XML is NOT
replenishment evidence. Imports corrected; fresh archive task38/replenishment-corrected
for same12 tests. Reports7 proof remains valid. Replenishment UI under development.

## Task38 reports verified; replenishment backend checkpoint

Reports7backend/3suites PASS2m1s (compiled c155856b, run bd01230b);18web/3files
PASS4.64s, TypeScript and final oxlint PASS. Proof task38/reports-workbench-verification.json.
All owned QA cleanup completed; volumes retained. Reports route operational.

Replenishment named workbench implemented; verification NEXT. New read API requires
request.view and current warehouse/area, never general SKU/location permission. SQL
filters active rules/pending state before totals/pages. Read-only detail compares live
position/suggestion with captured request; never creates a request on GET. Archived
location/history remains readable within current scope, but ineligible for replenishment.
New2 backend tests plus old3 basic/4 commands/3 inbound scheduled via private
.omo/runtime/replenishment-workbench-server.sh (log replenishment-workbench-server.log).
No migrations. UI replenishment/overview/settings history-delegations pending;38 open.

## Task38 reports UI checkpoint

Reports page now supports all nine scoped report kinds, named SKU/location and WO
filters, bounded server pages, paired date range, exact signed ledger/cost values,
unknown cost totals distinct from zero, CSV all-filter export (server max1000), and
revision-bound receipt/issue/return print preview with fresh authority read before print.
Cost/provenance/report gates run before fetch; report-only users need no master reads.
Blob URLs and temporary print portal cleaned on close/navigation. Existing route wired.
18web tests/3files PASS4.64s (reportAPI4/reportUI6/stockUI8), TypeScript PASS.
Oxlint initial two refresh warnings extracted constants into reportPresentation.ts;
final lint next. No backend changes. Run existing ReportIT4/PrivacyIT2/ExportIT1 next
against owned PostgreSQL, then save portable proof. Whole38 still OPEN:
overview/replenishment/settings history-delegation remain; then39–48/F1–F4.

## Task37 COMPLETE; task38 operational screens next

Actual discrepancy browser at product c155856b / fixture 2b6ff32f / run a51dfd4a:
2 tests PASS in 63.566s, desktop1280 and touch mobile375. All-UI receipt100m,
partial60m, independent role/user/grants/policy, separate checker login, cost absent
from checker HTTP, actual approval effect and final60000MM available+40000MM LOST.
14 discrepancy screenshots reviewed;12 stable images curated with SHA256. Two modal
captures caught entrance animation; excluded from portable screenshots. No flow failure.
Proof task37/discrepancy-browser-verification.json. Owned cleanup complete, volumes retained.
Together with source-specific count/approval/recovery/evidence/disposition proofs and
213web/36files PASS, task37 is complete. Plan checkbox updated; tasks1–37 complete.

Task38 policy settings backend8/web7 already verified; overview, replenishment,
reports/exports/print and remaining settings history/delegation still open. Then39–48
and F1–F4. Formal long-run goal ACTIVE. No migrations; next free148 (177/178 reserved43).
Continue from current branch work/warehouse-completion tracking origin/feat/warehouse-workorder.

## Task37 full web verified; real discrepancy browser starting

213web tests/36files PASS37.19s atc155856b; TypeScript/Vitebuild PASS, warehouse and
browser-fixture oxlint PASS.8policy backend PASS2m17s at2dbab388 and15disposition
backend PASS4m2s atbccb6feb. Source-mapped proofs under task37/task38.
Browser fixture at2b6ff32f extends returns.spec.ts with real role/user/area/scopes/policy
setup, separate approver login, actual decision and60000MM AVAILABLE+40000MM LOST.
Run .omo/runtime/warehouse-discrepancy-browser.sh discrepancy-initial, log
.omo/runtime/warehouse-discrepancy-initial.log. Archive task37/discrepancy-initial.
Do not mark37 complete until this desktop/mobile run passes and screenshots reviewed.
Task38 remaining overview/replenishment/reports/delegations/history; then39–48/F1–F4.
No migrations; next free148. All prior owned QA cleanup done; volumes retained.

## Task37 evidence verified; disposition UI and authority checks checkpoint

2026-09-25: evidence6tests/3suites PASS3m3s at556f435e (receipt real MinIO2,
actual-WO approval1, baselineworkbench3);12web PASS6.82s. Transfer recovery9backend
PASS4m34s at28b9b415 +14web atda5a57a0. Portable proofs task37/evidence-verification.json
and transfer-recovery-verification.json. Owned cleanup finished; volumes retained.

Disposition UI now supports current-return-bound full-quantity LOSS/SCRAP request,
independent approval links, persisted history and compensation to quarantine using
actual disposition+return revisions. Customer title/read-only/stale guards and exact
captured commands tested:13web/2files PASS3.78s. Source reads strengthened to current
original-WO authority before counts/page, separate transactions for public-port denial.
Request methods keep original transactions.15backend tests selected next:Disposition2,
DispositionGuards11(expanded area-revocation case),Compensation2(expanded read area cases).
Private wrapper .omo/runtime/disposition-workbench-server.sh; log disposition-workbench-server.log.

Next: finish this backend verification, build policy-settings UI (task38 prerequisite
for task37 all-UI approval browser; currently settings route unavailable). Then extend
web/e2e/warehouse/returns.spec.ts approved discrepancy separate login, full web gate,
complete37 and continue38–48/F1–F4. No migrations. Do not mark whole37 complete yet.

# Whole-plan continuation





## Task37 approval baseline verified; evidence and discrepancy recovery next

Approval baseline source0ccf3ab1 verified: correctedWorkbench3 PASS1m53s plus
25 unchanged tests/6suites PASS at7b84c704 (not one combined28green batch).
28web/5files PASS5.66s, TS and targeted oxlint PASS. Portable proof:
.omo/evidence/warehouse-workorder-asset-provenance/task37/approval-verification.json.
Owned cleanup completed; volumes retained. No migrations. Required next work is
listed immediately below: approver evidence review, owner-bound discrepancy recovery,
disposition UI, actual separate-login discrepancy browser. Whole37–48/F1–F4 active.

## Task37 approval UI green; corrected backend test rerun next

Approval backend 7b84c704 compiled and ran28tests/7suites in6m29s:27passed,1failed.
Failure is new test expectation303 vs actual normalized101/1 for totalMinor101 and
quantity/basis3/3; product policy calculation is correct. Test now checks101/1.
25 unchanged tests in6suites passed (Count15,Approval2,Source4,Compatibility1,
TransferDiscrepancy2,original-WO workbench1). CorrectedWorkbench3 rerun next and
must reach actual final-effect/history assertions previously blocked by that check.
Owned cleanup completed, volumes retained. Do NOT call initial whole batch green.

UI implemented: strict typed approval reads, server-derived source/current action,
policy evaluation before request, captured request/decide/rework, named sources and
frozen count comparison, current-vs-sealed revisions, paged history/filters, actual
operation-backed final effect, self/current-authority denial. Receipt detail now
links to source approval. 28web/5files passed5.66s (approval10/count10/receipt8),
TypeScript+targeted oxlint passed after unused test argument correction.

Required remaining task37 concerns discovered during implementation:
1. Evidence review: current document projection includes count sheet references,
   but receipt attachments and signed title evidence still need an approval-safe
   read/presentation path for approver-only users. Do not expose raw source/storage.
2. Transfer discrepancy recovery: generic rework currently appears possible for
   ADJUSTMENT but owner.validate insists source.revision0 and apply posts0. Existing
   report only accepts DISPATCHED/PART_RECEIVED, so rejected/expired reports cannot
   be corrected through owner UI. Implement an owner-bound new discrepancy report
   after prior terminal/no-effect request, retain old references, prevent pending/
   approved supersession and recheck old-source current binding; hide generic rework
   for ADJUSTMENT. Need actual backend+UI stale/rejection tests. Do not ship a dead end.
3. Source query supports only registered owners (rejects arbitrary inventory docs).
   List scans fixed100 keyset batches, runs actual owner locks per transaction before
   page/count. This is bounded memory; large queues may warrant later batch optimization.
4. New read list adds selected result within transaction closure; consider append
   only after successful commit. Action gates/commands still recheck actual authority.
5. Disposition forms and actual separate-login discrepancy browser still open.
Whole37–48/F1–F4 ACTIVE. No migrations. Read task37 implementation notes below.

## Task37 approval read projection implemented; backend verification next

Count milestone feda5972/dcb8ce10 complete within task37: count-verification.json
17 backend/10 web. New approval query/projection/service/controller now implement
source/workbench/details/history page; raw list/history preserve response shapes
and share stricter current owner/WO checks. Candidate SQL filters sealed source,
SKU+serial on same line, dates/operation/location/code and scans fixed100 keyset
batches. Each current owner gate has its own transaction; denial caught outside
avoids rollback-only poisoning. Only selected page retained, totals after gate.
Explicit document fields omit internal source/cost/storage data; count line capacity
never projected, comparison only sealed SUBMITTED+ observations. Current action
eligibility reuses actual tier authority; own/excluded actor blocked. Final effect
requires actual approval effect+operation+applied movement. Cost omitted unless allowed.

NOT VERIFIED YET. Next private approval-workbench-server.sh runs new3 workbench+
new1 original-WO revocation workflow, existing count15 with comparison assertions,
existing approval2/source4/compatibility and transfer discrepancy2. Await real build,
fix any compilation/HTTP assertions, save proof. Approval UI still pending. Source
link/source revision/current permissions are server-derived; no client movement/tier.
No migrations. Whole37–48/F1–F4 active; do not call this task complete yet.

## Task37 count UI and backend verified; approvals next

Blind count backend 6a4de392: 17 tests/2 suites PASS, BUILD SUCCESSFUL 3m37s,
owned cleanup completed, volumes retained. Initial 7b5f4687 run had 15 old tests
passing and two new rejection checks unhandled; fixed the new controller advice.
Count UI: 10 API/component tests PASS (4.44s), TypeScript and targeted oxlint PASS.
Implemented named quantity-free assignments, actor-only start/observe/submit/recount,
immutable history pages, exact quantity input including zero, COUNT_STALE reload,
submitted-only reviewer comparison and canonical counts route. New docs/warehouse-counts.md.
UI needs location.view for creating a location selection; assigned counters need only
count permissions. No normal stock/lot reads in blind flow; no initial measured value.

NEXT: approval source/detail/current actor action projection, safe scoped workbench,
bounded history, cost redaction, actual request/decide/rework UI and typed effects;
disposition forms where owner contracts allow. Separate-login discrepancy browser
still pending. No approval/count browser completion claim yet. Whole37–48/F1–F4 active.
No migrations; V175_147 remains current. Save next portable proofs before continuing.

## Task37 blind count reads implemented; backend verification next

Task36 COMPLETE a95c35a0 (product99b98b41/test2c82db3f), proof completion.json,
178web/build/lint plus source-specific backend and actual transfer browser saved.

Task37 now has raw typed count commands/review and approval request/decide/rework
wrappers; TypeScript/oxlint passed. New InventoryCountQueryApi and isolated query
DAO/service/controller provide named /counts/workbench, /{id}/details, submitted-only
/{id}/review/details, /positions and /locations/{locationId}/counters. Position
choices OMIT all physical/book/reserved/capacity/cost quantities, including HTTP.
Zero verified positions remain selectable; identity/status/custody distinguish them.
Count list filters SKU/serial/location/state/code/paired dates; creator or assigned
counter and current topology scopes apply BEFORE page/count. Reviewer-only users
use review owner without count/stock permissions. Public IAM names/current count
permissions+scopes filter counter choices. Raw mutation/views unchanged.
New /{id}/history/page bounds latest facts and own-counter visibility before total;
legacy history now default25/max100 ascending. Internal mutation facts stay complete.

New WarehouseCountWorkbenchIT two HTTP workflows cover no stock permission/quantity
leak, named assignments, scoped paging, reviewer-only submitted comparison, revoked
scope and two counters/history pages. NOT BACKEND VERIFIED YET. NEXT run private
count-workbench-server.sh (new2+existingWarehouseCountIT15 =17 expected/2suites),
await compilation/tests/cleanup and save fresh proof. No migrations; V175_147 current.
UI not built yet; next typed named reads+count screen, approval document/action/cost
projection and saved source links, disposition forms, actual separate-actor browser.
Whole37–48/F1–F4 active. Keep remote checkpoint commits; no merge/deploy.

Approval discovery caution: raw source() contains full internal canonical JSON,
including cost/storage references—never expose raw. Calling owner locks and catching
access exceptions inside a shared transaction may poison rollback-only via mandatory
public WO proxies; use nonthrowing visibility or well-defined transaction boundaries.
Current list only checks snapshot locations; current owner/WO gates must match get.
See task-37.md for further contract notes and task40 camera requirement.

## Task36 COMPLETE —178 web, source-specific backend and actual transfer browser proof

Task36 product99b98b41 and test-only correction2c82db3f complete. Full178web/28files
passed36.10s plusTS/Vite production build/warehouse oxlint. Corrected transferList4
passed1m55s, plus unchanged5 transfer tests passed in earlier run (not one9greenbatch).
Fresh transfer-filters-verification.json and completion.json map all source-specific
proofs including return11/original-context6/RMA11/reacquisition5 backend runs and
actual2 desktop/mobile100/60/40 transfer browser @49e8a1d4 (see proof for exact SHA).
All10 browser screenshots reviewed earlier. Cleanup completed; volumes retained.

Delivered source-bound return intake, measurement/inspection/reset, vendor service,
replacement receipt continuation, original-customer RMA/current revision/receiver,
signed original-WO title request plus persisted approval link, current owner/scope
and bounded named lists/history/filters. Raw operation replies remain unchanged.
No migrations. V175_147 remains latest applied. Task36 checkbox now complete.

NEXT TASK37: see task-37.md for approval/count/disposition owner constraints.
New counts.ts and approval command wrappers are currently UNCOMMITTED task37
foundation; TypeScript/oxlint passed, no page/business verification claimed yet.
Need named source/current actor action reads with cost redaction, paged history,
blind count source choices without expected quantities, full UI and actual separate
approver discrepancy browser.37–48/F1–F4 remains active. Task40/41→45 explicitly
owns full technician return/RMA browser, and task40 camera progressive enhancement.
Continue commits and remote checkpoints; no QA processes running.

## Task36 full178web green; transfer test input corrected, rerun next

Product99b98b41 passed178 web tests/28 files in36.10s, TypeScript+Vite production
build and warehouseAPI/pages/components oxlint exit0. Portable full-web proof saved.
Transfer backend9 tests:8passed, new mixed-line filter case failed at padded serial
lookup. Confirmed separately using JShell with same Spring request builder: encoded
URL serial=%20filter-serial%20 becomes literal %20filter-serial%20, while .param gets
real spaces. Corrected test to actual .param, no product behavior changed.
NEXT rerun WarehouseTransferListIT4 tests via transfer-filters-corrected-server.sh;
remaining5 transfer backend tests @99b98b41 passed and unchanged. Need collectactual
success/freshXML and cleanup before mark36complete. Fullweb no repeat needed for
this test-only correction. Earlierreacquisition5/RMA11 proofs saved. No migrations.

Task37 preparation notes saved task-37.md (approval read/actor/cost gates, blind
count constraints, persisted deep links).36stillOPEN;37–48/F1–F4 remain active.

## Task36 transfer filters implemented; backend verification next

Reacquisition backend corrected5dd0f46e passed5 tests/4 suites in3m36s with no
compiler warnings; fresh portable proof saved. Owned cleanup completed. UI29tests
plusTS/lint passed and saved ddac902a. Transfer frontend filters passed12 tests/2files
5.27s plusTS/oxlint; existing3 transferDraft tests included in upcoming fullweb.
Added server SKU+exact canonical serial bound to SAME document line, paired created
from-inclusive/until-exclusive dates max366days, strict filter/history parsing.
Scope and receiver eligibility still precede count/page. New actual mixed cable+
serial HTTP case verifies no cross-line matching, pagination, time boundary and
transit scope; malformed filters extended. NOT BACKEND VERIFIED YET.

NEXT run transfer-filters-server.sh (List4+Contract3+Transfer1+Serial1 =9 expected),
full warehouse web suite and build/lint; await fresh proof. Actual100/60/40 transfer
browser @49e8a1d4 remains valid for unchanged commands; full return/RMA browser45
is deferred until40/41 per plan. Task36 OPEN until these checks pass and finalscope
review; then37 approvals/counts/adjustments. No migrations; remote checkpoints active.

## Task36 reacquisition UI saved;29 web checks passed, backend still running

Reacquisition UI reads actual active original-WO signature, handles204 absence and
permission denial, checks changed signature before/after authenticated download,
requires reason/reference/customer-proof confirmation and reviews captured command.
Persisted paged title requests resume actual sourceDocumentId approval links; current
CUSTOMER title stays until effect. Original historical owner keeps list reachable
when current title becomesISP. Applied return revision is distinct from decision.
29 affected web tests/6files passed10.90s, TypeScript/focused oxlint exit0. Docs updated
for operator flow, read contracts and default25 history; no return/RMA browser claim.

Backend corrected source5dd0f46e compile/testcompile passed;5 tests/4suites still
running via session80021, log reacquisition-discovery-corrected-server.log. Await
actual completion/cleanup and fresh XML (first8cec4ee8 compilefailed, no validproof).
NEXT save backendproof, implement remaining transferSKU/serial/date/locationfilters,
then final task36 checks and37 approvals. Whole36–48/F1–F4 remains active; no migrations.

## Task36 reacquisition discovery compile correction

First verification @8cec4ee8 failed compile: Jackson3 JsonNode.map resolves its
own transformation overload. Replaced with explicit asSequence().map().toList()
in paged result and test role extraction. No tests from this failed run count as
proof (copied XML can be stale). NEXT reacquisition-discovery-corrected run with
fresh archive,5 tests/4 suites expected. Frontend form/evidence implementation is
uncommitted, TS and focused oxlint passed; behavior tests next. Task36 OPEN.

## Task36 persisted reacquisition discovery implemented; verification next

Added bounded /returns/{id}/reacquisition-requests with current return/approval
read permissions, original WO area and current/historical location scopes before
pagination/count. Entries bind actual title document, source revision, signed proof,
and separately recorded applied return revision. Raw command response unchanged.
Original assignment references now include historical legalOwner so the list remains
reachable after approved CUSTOMER→ISP change. No cross-module name joins or migrations.
Reacquisition command now also checks historical repair location, matching return read.
Extended actual independent approval test with two persisted source docs, paging,
read-only reader, tenant/permission/revoked scope and actual applied-vs-unapplied refs.
Kotlin JDBC lambda inference warnings and redundant String conversion cleaned up.
NEXT run reacquisition-discovery-server.sh (5 tests/4 suites expected); frontend form
and signature read still to implement. RMA24web/11backend checkpoint ac1c59f2 pushed.
Task36 OPEN; keep remaining36–48/F1–F4 and remote commits active.

## Task36 RMA UI saved; 24 web and 11 backend checks passed

RMA read backend @c7612c9e passed11 tests/4 suites in3m45s; portable
rma-reads-verification.json saved and owned cleanup completed, volumes retained.
UI selects named original-customer REPAIR WO, reads actual current revision and
active assigned receivers, binds technician custody/transit and scanned same serial,
reviews one captured command, and reloads persisted named handover after success
or409. Actual acknowledgement remains technician flow (task40), read UI can refresh
DISPATCHED/RECEIVED. Completed CUSTOMER post-repair inspection hides repeat inspect
that would invalidate the closed repair revision.24 affected web tests/5files passed
5.72s; TS/oxlint exit0. Actual return/RMA browser remains deferred to task45 after40/41.

NEXT signed original-WO evidence reacquisition form plus persisted request discovery,
then transfer C8 filters and docs/final task36 checks. Backend Kotlin inferred Set type
warning in RmaWorkOrderAdapter line50 and redundant test String.toString need small
cleanup with next compile. No migrations. Task36 OPEN; whole36–48/F1–F4 continues.
No QA processes running. Commit and remote checkpoint each coherent change.

## Task36 RMA read contracts implemented; backend verification next

Added /returns/{id}/rma-work-orders/{workOrderId} to read actual current revision,
code/title and active assigned technician names for original customer REPAIR WO.
Uses return.manage, current return/repair locations and physical closed-repair
origin, through public InventoryRmaWorkOrderPort implemented by workorder module.
No unrelated material-request permission needed. Existing named WO list will still
use workorder.order.view for selection. Added /rma-handovers/{id}/details wrapper
with current names and locations, preserving raw get/command bytes and owner gates.
New meaningful manager-only/current-revision/active-tech/type/customer/scope test,
and existing actual RMA HandoverIT extended to named dispatch/ack details.
NOT VERIFIED YET: NEXT run rma-reads-server.sh (new read +3 existing RMA suites).

Replacement UI20tests/TS/lint and original-context6backend proof saved b3064bef.
RMA/reacquisition UI and transferfilters remain. No migrations. Task36 OPEN.

## Task36 replacement form saved; 20 web and 6 backend checks passed

Backend original assignment metadata @62159f68 passed6 tests/4 suites in4m38s.
Proof return-asset-context-verification.json saved; cleanup completed, volumes
retained. Prior11 return discovery tests @d1395e7b remain valid for unchanged areas.
Replacement UI now creates only a new draft receipt, bound sameSKU/newserial and
original title, with optional exact cost (unknown is not zero). Existing drafts
and received replacements are discovered through bounded persisted list and real
receipt GET; actual named receipt link resumes receiving/inspection. No auto
physical receipt or disappearance of original device. Cost fields absent without
cost permission.20 affected web tests/4 files passed6.97s plusTS/oxlint exit0.

NEXT RMA dispatch and read UI, signed-evidence reacquisition and its persisted
continuation, transfer C8 filters. Task36 still OPEN, all36–48/F1–F4 active.
No migrations changed. Current QA processes stopped. Commit/push each checkpoint.

## Task36 original assignment references added; verification next

RMA/ownership investigation found source workOrderId is the REMOVAL work order,
while replacement/reacquisition owner uses the ORIGINAL assignment work order.
Added nullable references.assetOrigin {assignmentId,customerId,workOrderId} from
persisted local inventory lineage, separate from source workOrderId. No new name
snapshot or cross-module implementation join. Extended LOAN/SALE and remnant
HTTP tests to distinguish these IDs. Frontend decoder and fixtures updated.
NEXT run return-asset-context-server.sh (6 tests /4 suites expected), then continue
replacement form/list, RMA named customer repair WO/current revision, signed
original-WO evidence and reacquisition continuation. Changes not yet verified.
Previous11backend @d1395e7b and17web @33200083 remain baseline. No migrations.

## Task36 return workbench checkpoint — 11 backend and 17 web tests passed

Return discovery backend @d1395e7b passed 11 tests / 7 suites in 4m56s. Fresh
portable return-discovery-verification.json saved; owned cleanup completed and
volumes retained. Scope, source eligibility, no double receipt, LOAN/SALE,
6-revision repair history and persisted RMA continuation were verified.

New typed return/source/repair/replacement/reacquisition/RMA contracts plus exact
intake/inspection/repair builders. Return page now has scoped named list and
filters (including paired dates), source lookup, intake, measured whole remnant,
serial/reset inspection, same-serial vendor dispatch/receipt, ownership warning,
old inspection vs post-repair reset warning, history and permission reasons.
Actual writes capture one body/key, then reload GET;409 reload discards old form.
Existing handoverId disables duplicate warehouse inspection.17 affected web tests
(9 page /4 API /4 helper) passed3.66s; TypeScript and focused oxlint exit0.
No real return browser claim; task36 transfer browser remains verified @49e8a1d4.

NEXT implement supplier replacement receipt form and persisted continuation list,
customer RMA dispatch/actual handover read and signed-evidence reacquisition link
flow. These APIs exist but page actions are not built yet. RMA needs original
customer context and named eligible repair WO/technician selection, not free UUID.
Check source document customerId metadata and public work-order/evidence reads.
Task37 approval request/decision deep links still to build. Transfer C8 list
SKU/serial/date filters remain to review before marking36complete. Task36 OPEN;
whole36–48/F1–F4 continues, no migrations changed. No active QA processes.

## Task36 return discovery implemented; backend verification next

Added separate named /returns/workbench and /{id}/details, eligible /sources,
and bounded latest-first /{id}/history/page. Raw GET/list/operation/history shapes
stay unchanged; legacy history now defaults25/max100. Strict serial/query/date/
origin/state/location/SKU/identity/owner filters. Sources require return.manage,
actual verified whole Q position, residual acknowledgement or independent asset
removal, and exclude previously intaken documents BEFORE paging/count. Read only.
Named metadata uses current inventory names and public IamApi receiver name.
Minimal rmaHandoverId points to existing handover owner read; no invented current
physical state from the old return snapshot. Historical repair location now gates
ordinary return read/replay/history/list, consistent with repair actions.

New source/scope/names/immutable snapshot integration case plus extended asset
LOAN/SALE independent sources, bounded6revision history, repaired-location scope
and persisted RMA reference checks. NOT VERIFIED YET. NEXT run private
return-discovery-server.sh, await compile/test/cleanup, inspect fresh results.
Transfer browser proof @49e8a1d4 complete174c6545.149 web/7transfer backend baseline
unchanged. Return UI still required; task36 remains open. No migrations.

## Task36 transfer browser verified at49e8a1d4; return implementation next

Final transfer-final passed both projects in 38.005s, no failures,
skips, flaky or global errors. All10 current synthetic PNG reviewed; mobile label
now readable. Saved screenshots and portable transfer-browser-verification.json.
Owned cleanup completed and volumes retained.149 web tests and7 transfer backend
baseline remain green (source-specific workbench proof). Task36 NOT complete:
next source lookup, named return reads, inspection/repair/replacement/RMA UI.
No migrations changed; keep36–48/F1–F4 active and push coherent checkpoints.

## Task36 transfer browser passed; final mobile label check next

Actual transfer-initial browser @5305ce67 passed both desktop/mobile in39.501s,
0 failures/skips/flaky/global errors; owned cleanup completed, volumes retained.
Reviewed all10 synthetic PNG: real100m intake, draft, dispatch100m, receive60m,
40m transit0available and60m destination available. One mobile label truncated;
shortened resolved header to Diselesaikan while existing explanation distinguishes
independent resolution from receipt. NEXT rerun transfer-final, review/save10PNG
and portable proof. Task36 stays open for return/repair UI.149web and7backend
baseline remains valid; no migrations. Current source and return investigation
saved for recovery; keep whole36–48/F1–final active.

## Task36 source10087229 —149 web tests and7 backend tests passed; browser next

Full warehouse/API/pages/components plusDataTable/WO/Payment/MultiCombobox:
149tests/22files passed16.96s. Includes9Transferpage cases and6API/helpercases;
affected15tests+TypeScript+focusedlint passed previously. Server transfer-history
7tests/3suites passed, cleanupcompleted, proof in task36/workbench-verification.json.
NineUIcases cover scoped denied links, namedpagedlist, actualsourceBalanceId draft
and ownrecipient withoutIAMdirectory, bounddispatch, actual60/40partialreceipt,
409reloadnoblindretry, actor/ownership/resolvednotreceipt, discrepancyawaitsapproval,
and serverhistorypaging. New browser returns.spec.ts builds real100mreceipt and
checks draft/dispatch/60received40transit and60available via visibleUI only.
NEXT run warehouse-transfer-browser.sh transfer-initial, await bothdesktop/mobile,
review all10PNG, fixifneeded. No otherQA processes running. Task36 stillopen:
returnsource lookup+inspection/repair/replacement/RMA UI required next;37approvals
links have sourceDocumentId query to implement. Keepwhole36–48/F1–F4 active.
No migrations;149webgreen baseline will support relatedreturnUI checks.

## Task36 Transfer page checkpoint; history verification next

Transfer discovery3newHTTPcases passed @6af0af41,1m46s, alongside18existingcases
passed @496d1d85. Portable task36/discovery-verification.json records per-suite
hashes/source (21distinct, not one21testgreenbatch). Cleanup completed.

Implemented actual Transfer route/list/detail/create, named locations/receiver,
source position+exactqty+lot read, dispatch, partialreceive, fullremainderdiscrepancy,
permission/actor reasons, currentquantity table and saved history. Inputs capture
sourceBalanceId and server-boundreceiver; read fresh GET after command. Customer
ownership warning; nocancel or fakeavailable/receipt. Ownuser can be selected
withoutIAMdirectory permission. Approval deep link needs task37 destination flow.
Build+6helper/APItests+TS passed; newFastRefreshwarnings fixed by separate helper,
finalTS/oxlint passed. Page behavior tests and actual browser NOTRUNYET.

C8 review found oldtransferhistory unbounded. Added bounded legacyarray (default25,
max100, oldascendingorder) and /{id}/history/page typed server count/latest-first;
strict pageparams and currenttargetscope. Added true3operation paging/assertions
and denied newhistory path tointegrationcase. Backend history changes UNVERIFIED:
NEXT run transfer-history (ListIT+Contract+basicTransferIT), then page tests and
actual100/60/40 desktop/mobile. Return source/inspection/repair/replacement still
needed before36complete. Newdocs describe history compatibility explicitly.
No migrations; whole36–48/F1–F4 active. Commit/push everycoherentcheckpoint.

## Task36 typed transfer commands green; scope assertion correction

Server @496d1d85 compiled and ran21tests:19passed,2failed only because new scope
assertions expected403 while existing WarehouseMasterService intentionallyreturns
404 NOT_FOUND for hidden locations/areas. Confirmed source lines133/154, corrected
those assertions only (missing permission still403). All18existingtransfer cases
passed, plusnewinactive-receiver/current-name/immutable-response case. Cleanupdone.
Rerun3newdiscoverycases to reach remaining scope-restoration checks; production
backend unchanged from496d1d85. No stale XML used as proof.

Web typed transfers/read wrapper/captured commands and exact draft/partialreceipt
builders added.6meaningful cases+TypeScript+focusedoxlint passed. CoversDRAFTzero,
60/40 transit vsresolved conservation, referencebinding, response-loss samebytes/key,
sourceBalanceId, allocated/wrongcustodian/overreceipt, distinctlocationsandreceiver.
No Transfer page yet. NEXT backend3case outcome; build actual Transfer page/editor,
then return-source discovery/inspection/repair and desktop/mobile100/60/40 journey.
Task35complete;36–48/F1–F4 active;no migrations.

## Task36 transfer discovery compilation correction

Initial server run @7fa9af4c failed production compile: validatePage was private
to return service, not shared. Replaced with explicit page>=0,size1..100 guard.
No tests executed; copied stale XML is not evidence. Cleanup completed.
NEXT rerun transfer-discovery-fixed with the same new3cases+transfer regressions.

## Task36 transfer discovery implementation — backend verification next

Added InventoryTransferQueryApi list/details wrapper {transfer,references}; old
GET/mutation/history responses untouched. Query scopes source/transit/destination,
resolution target and movement locations before count/page. Active/technician
receiver eligibility comes via public IamApi before pagination; names also via
public IAM. Local SKU/serial/lot/location refs are current, no cost/email.
Strict query keys/repetitions/blank/page/state/location validation. Existing
get/history/replay/approval source access now includes resolution target.
3 new HTTP integration cases cover actual draft/partial60/40, scope revocation,
independent denial, inactive receiver, names/old snapshot compatibility and strict
filters. NOT VERIFIED YET. Next run transfer-discovery server wrapper plus all
WarehouseTransferIT* and contract regressions; no browser running.
Task35 complete75ad8b43/source31b26399. No migrations. Remaining36–48/F1–F4 active.
After backend: typed Transfer UI and actual100/60/40 browser, return source lookup,
inspection/repair/replacement/RMA UI and role/conflict tests before36complete.

## Task35 COMPLETE — final source31b26399; task36 next

Actual issue-release-value browser passed2/2 (desktop1280/mobile375),58.245s;
0 failed/skipped/flaky, no global errors. Packaged backend+web builds passed.
All8 final synthetic screenshots reviewed and saved with SHA256 in task35/
verification.json. Release60m/re-reserve preservedONU allocation; pick physical
cut+serial scan, unpick/repick same cut, reload, named receiver+partial review,
print, dispatch and actual60m+1ONU transit0available verified through real UI.
No simulated technician receipt. Final14 affected web cases/TS/oxlint passed;
134distinct web cases verified across relevant runs. Backend allocation10,
issue-list13,demand-mapping7 cases have separate source-specific portable proof.
Cleanup completed; volumes retained. Task35 checkbox nowcomplete;1–35 complete.

NEXT task36 transfer/return/repair screens; investigation in task-36.md includes
actual contracts, access-before-page query design and named source lookup.
No task36 production implementation yet. Whole36–48/F1–F4 goal remains active.
Applied migrations throughV175_147 unchanged;148unused,177/178reserved43.
Use per-commit handoff+ledger and push feat/warehouse-workorder; no main merge.

## Task35 final browser selector correction

issue-final @2dcabb7a failed both projects at the new release assertion: actual
cell text includes its responsive column label (Dicadangkan0,000 m). The release
POST succeeded and actual displayed quantity was correct. Test now targets the
warehouse-cell-value element for exact quantities. Browser exited1 with cleanup
completed, volumes retained. NEXT run issue-release-value, then review final PNG.
No product changes; 14 affected web tests/TS/oxlint remain valid at2dcabb7a.

## Task35 release alignment and visual spacing — final browser next

Real issue-picker-fixed @76c9bc76 passed both desktop/mobile in 54.9s; eight
synthetic screenshots reviewed. Pick/unpick/repick/dispatch, named receiver,
partial confirmation and actual 60 m + 1 ONU transit worked. Cleanup completed.
Final review found release owner only permits the full unpicked allocation, so
UI now locks release quantity and explains release/re-reserve; helper rejects
partial release. Browser adds actual cable-only release/re-reserve while ONU
reservation stays intact. Scoped paragraph margins reduce mobile scroll gaps.
14 affected web tests, TypeScript and focused oxlint passed (0 errors/warnings).
NEXT commit current source, run issue-final via warehouse-issue-browser.sh,
review all final PNG and save portable evidence before checking task35 complete.
Task36 investigation ready; whole plan 35–48/F1–F4 active. No migrations changed.

## Task35 source76c9bc76 — third browser running; 134 distinct web cases verified

NONEplanning10requesttests+TS+focusedlintpassed. Pickerfix11cases+TSpassed;
baseline132cases plusnewpicker andNONE =134distinct cases acrossaffectedruns
(notone134testbatch). Portablepicker-none-verification.json captures exactsources.
Actual thirdbrowser warehouse-issue-browser.sh issue-picker-fixed RUNNING,
session84397, .omo/runtime/issue-picker-fixed.log, source76c9bc76.
No product/testchangeswhilebrowserruns. Await full desktop/mobile results,
review screenshots, fix/rerun ifneeded before35complete. Earlierbrowserfailed
technicianselectorthenrealMultiCombobox selection; bothfixedwithregression.
Next36investigationnotepadready;wholeplan35–48/F1–F4 active. No migrations.


## Task35 technician fix verified; NONE planning permission alignment

7c606fb2:11tests/3files (newMultiCombobox regression,9request,1WO) +TSexit0 passed.
Oxlint0errors, pre-existinginitialLabels effectwarning inMultiCombobox recorded.
Portalref fix isverified beforebrowser. Also removedextraSKUview gatefromopening
planeditor: authorizedplanner withoutSKUview canstilldeclareNONE+reason, asbackend
permits; editoralreadyblocksrequiredmaterialrowswithoutSKUview. NewactualUIflowtest
and9existingrequesttests/TS/oxlint RUNNINGrequest-none-permission.log.
Customerfallback now distinguishes missingname from absentcustomerID.
NEXT awaitchecks then thirdbrowser issue-picker-fixed; no browserrunningcurrently.
Baseline132web@f2fc7fb4 +newpickerregression, newNONEtestpending. Keep whole35–48/F1–F4.


## Task35 real technician-selection bug fixed — checks running

issue-selection-fixed@f2fc7fb4 bothbrowsercases passedvisiblemenu selector but WO
POST hadassignees:[] despiteclick. Actualpayloadfilteredfromprivate trace confirms;
no credentials printed. MultiCombobox outsidepointer handler onlyignoredrolelistbox,
while actualFluentmultiselectportal hasrolemenu. Itclosed onoptionpointerdownbefore
selection inChromium. Addedref toactualListboxslot andusespopupRef.contains(target),
soonlyownportal countsinside, regardlessrole. Browsernowassertsaria-checkedtrue.
New userEventrealFluent regression failsbeforefix (menuclosesafterfirstselection)
andchecks2persistedchoices+outsideclose. Fixedtest+WO+9request/TS/oxlintRUNNING
technician-picker-fixed.log. No browsercurrentlyrunning. Lastbrowsercleanupdone.
NEXT awaitchecks, runissue-picker-fixed viawarehouse-issue-browser.sh,thenvisualreview
andfurtherfixes asneededbeforetask35complete. Baseline132web@f2fc7fb4 remainsvalid.
Task36notesready;wholegoal35–48/F1–F4 active. No migrationschanged.


## Task35 sourcef2fc7fb4 — 132 web tests green, second browser running

132tests/18files passed aftersharedModalfix, TSawaitedexit0, focusedoxlint0errors.
Portableworkbench-dialog-verification.json saved. Task35 actual browser RUNNING
warehouse-issue-browser.sh issue-selection-fixed, session4450/runtime
issue-selection-fixed.log; sourcef2fc7fb4. Initialsource4e6d4b3d bothbrowserfailures
werewrongselectorrole(option vsactualmenuitemcheckbox), corrected2f101b2d.

Added docs/warehouse-workbench.md operatorsteps matchingcurrentUI andcorrected
warehouse-issues.md staleintro/unpick-cancelclaim againstactualcontext. Docs only
whilebrowserruns; product/testsourcekeptstable. NEXT awaitdesktop/mobileoutcome,
visualreviewPNG, fixandrerunbefore35complete. Continue36–48/F1–F4.


## Task35 confirmation dialog accessibility correction — verification running

New remaining-demand review test found actual Modal had two nested unnamed dialog
roles. Added useId/aria-labelledby to native dialog, keptone native role andmoved
headingid; removedredundantinnerrole. This enables namedconfirmation access for
screenreaders andtest. Requestreview initial1of13failed;12passed. Fullwarehouse+
DataTable+WO+PaymentGateway tests/TS/focusedlint RUNNING request-review-accessibility-web.log
because sharedModal changed. NEXT await then browser issue-selection-fixed. Browser
notrunning, lastinitialbothfailedtesttechnicianselector whichisfixed2f101b2d.
No completed35claim. Continue35–48/F1–F4.


## Task35 first browser failed on test selector — correction prepared

issue-initial@4e6d4b3d builtbackend/web, bothbrowserprojects failed beforeWOcreate:
Fluent MultiCombobox exposes menuitemcheckbox; testaskedroleoption andmatchedhidden
native WOtablefilteroption. Screenshot/accessibilitytree confirmsvisibletechnician
menucheckbox. Changed onlybrowserselector to actualmenuitemcheckbox. No forcingclick
or simulatedassignment. Allreceiving/putaway/usercreation hadcompleted. Cleanupdone.

Also fixedautoFIFOreview toshow remainingbackorder quantities ratherthan fullplan;
NONEreview includesreason. NewmeaningfulUIregression makes9pagecases;9page+4action
andTS/oxlint RUNNINGrequest-review-web.log. Previous127webproofstillvalidbaseline,
newchangeawaitingchecks. NEXT run warehouse-issue-browser.sh issue-selection-fixed
withnewsource; currentbrowsernone. task36.md containsread-onlytransfer/returnAPI
investigationfornexttask. Task35remainsopen;continuewholeplan35–48/F1–F4.


## Task35 source4e6d4b3d — full web verification passed; browser running

127tests/17files passed (warehouseAPI/pages/components,DataTable,existingWOtest),
TypeScript awaitedexit0. Oxlint exit0/noerrors,1warning WOarea fetchloadingstate set
insideeffect; recordedinportableworkbench-web-verification.json. Initial focused
three failureswerejsdomdialogpolyfill, fixed; no producterrors hidden.
Actual warehouse-issue-browser.sh issue-initial RUNNING (.omo/runtime/issue-initial.log),
source4e6d4b3d. Keep product/tests stable untilfinish; wrapperarchives35/issue-initial.
Await actualdesktop/mobile results, visuallyreviewsyntheticPNGs, correctissues and
rerunbefore35complete. Ownedcleanuptrap retainsvolumes. Continuewholeplan35–48/F1–F4.
Reviewconcernfornextedit: autoreserve dialog currently lists fullplan quantities;
label remainingbackorderperline to make exact reviewed remainingneed clear.


## Task35 real browser scenario prepared — full web checks running

Request workbench source2bd54f5d initially had3of8 UItests fail because jsdom lacks
HTMLDialogElement.showModal, not product actions. Added dialog stub matching other
warehouse tests;22tests/3files +awaitedTS+oxlint nowpassed. No swallowed assertions.
Added4meaningful action tests (historic allocations, exactrelease, bounds/mapping,
stale/issuebound/serial mismatch). Fullwarehouse+DataTable+WO tests/TS/oxlint RUNNING
request-workbench-full-web.log. Must await trueexit before claim.

Actual UI browser issue.spec.ts prepared with fulfillment.ts helper: signup/area/
masters/1000m10ONUreceipt/putaway/technician/WOcreation/100m2ONUplan/60m1ONUreserve/
pick/unpick/repick/namedreceiverpartialdispatch/current immutable slip print/transit.
No browser API writes or seededbusinessdata, nofakeack. Actual browser NOTYETRUN.
Found old WO create form omitted areaId entirely; added scoped namedarea selector
and includesareaId inPOST, neededfor real restricted-area operator flow. Backend
alreadyrequiresarea; existingauthorizationunchanged. Addedoptional inspectionRequired
flag to catalogbrowserhelper (defaultsunchanged) for receiptwithoutmandatoryinspection.

NEXT await fullwebchecks thenrun new warehouse-issue-browser.sh issue-initial wrapper
(basedonstockwrapper archive35 specissue.spec.ts); actual desktop/mobilevisualreview,
fixandrerunaffectedchecks before35complete. Continue36–48/F1–F4. No migrations.


## Task35 workbench UI wired — focused tests running; browser pending

Mapping/backend sourcef7bbd0d5 passed7tests/4suites in3m59s. Old posting fixture
now uses actual owner APIs; preserved60missued/40mused/20maccountable/40mbackorder.
Portable demand-mapping-verification.json saved; cleanup complete, volumesretained.
Plan editor5tests +9API tests and TS passed. Initial lint command used ESLint
incorrectly (repo usesoxlint); actual oxlint rerun passed, no repo toolchanges.

WarehouseRequestsPage now wired: paged namedWOselection, current summary/allocation
reads, plan/NONE/template/substitution editor, submit, FIFO or exactpartial/manual
identity reserve, release, named allocation pick, stored pagedissue list/actual
accepted quantities, namedreceiver and partialack dispatch, issue-awareunpick,
freshly authorized immutable slip printing, history and visible WO_TRANSIT setup.
Staleallocations disable actions;409reload discards edits; uncertainretry captures
samekey/body. No inferred received or physicaltransit from unaccepted amount.
Work-in-progress UI TypeScript initially passed;8new pagecases +5draft+9API/TS/oxlint
RUNNING request-workbench-web.log. No actual issue.spec browser yet. NEXT await/fix
focusedtests, add meaningful actionhelper cases asneeded, actual issue.spec using
existing real receiving/setup helpers desktop/mobile, visualreview, requiredchecks.
Task35 remainsOPEN. Continue36–48/F1–F4 after35.148nextunused;177/178reserved43.


## Task35 editor and posting fixture checkpoint — verification running

Demand mapping sourcea8a1cd41: 6/7 tests passed (new mapping,2demand,3supply);
old WorkOrderMaterialsITPostedFacts failed on obligation FK because direct issue
fixture omitted inventory_issue_line. Rewritten to actual owner pick/dispatch/
acknowledge/report-use APIs, preserving60missued/40mused/20maccountable/40mbackorder.
No production/schema change. Rerun all7 via material-mapping-server.sh aftercommit;
first failure archived demand-mapping, do not mark whole suite green.

Shared MaterialPlanEditor now implements explicit reviewed template copy, exact
MM/EA, NONEreason, current-plan-line substitution permission+reason+compatibility;
never carries older substitution into next revision. Five meaningful draft tests
plus9APItests/TS/lint RUNNING material-editor-web.log. New typed allocation reader
retains actual metadata/revisions and complete unpaged historical list.
UI is not wired yet; no browser proof. NEXT finish request workbench/list/reserve/
pick/unpick/dispatch/slip, focused tests and actualissue.spec desktop/mobile.
Continue35–48/F1–F4. No migrations;148nextunused,177/178reserved43.


## Task35 demand line mapping checkpoint — backend verification pending

Issue discovery sourcee90857a3 passed13 tests/5suites in3m39s, including3Modularity,
actual partial60m/full100m receipt, paging/unpick/repick, source/transit scope,
substitution revocation and permissions/tenant. Portable issue-list-verification
saved; all owned cleanup completed, volumes retained. Typed discovery source045611af
9tests+TS/lintpassed. Actual request/issue UI still notimplemented.

Added MaterialLineTotals.demandLineId nullable default at end (legacy-compatible),
populated only from actual persisted demandLine?.id; planLineId remains distinct.
One new real HTTP test: draftnull -> submit returns actualdistinctID -> first explicit
partial reserve60m of100m ->60mreserved/40mbackorder, unchanged1000mphysical.
Frontend codec preservesnull oractualID;9webtests+awaitedTS passed. Backend mapping
case NOTYETRUN. NEXT `.omo/runtime/material-mapping-server.sh` (new mapping plus
existing demand/postedfacts/supplyprojection). Keep backend stable during run.

Then implement shared MaterialPlanEditor + WarehouseRequestsPage workbench, named
WO search/pages, request quantities, exact partial/manual identity reserve, named
allocation pick/unpick, stored issue discovery/current receiver, dispatch and print.
Plan editor copies template into explicit reviewed lines (do not send lines:null
and review a potentially changed template); NONE explicitreason, substitutions
original planline/SKU+reason+override. Actual issue ack remains40/45.
Continue35–48/F1–F4; goalACTIVE; no migrations (148nextunused,177/178reserved43).

## Task35 typed issue discovery and paged WO selection — 9 web tests green

Frontend issueModels now decodes current header/revision/unpicked plus actual
accepted/dispatched/picked totals; refuses accepted>dispatched or inconsistentunits.
materials.listIssues uses actual newpagedendpoint. workOrders.ts uses strict small
WO views and legacy PageResponse.content conversion, server query/page25; preserves
nullable customer/assignee names, no first-page truncation.9focused tests passed;
TypeScript exit0 and warehouse API lint passed after defaulting optional decoder
error-path argument. No UI yet. Backend issue-list-initial remainsRUNNING against
e90857a3;3Modularity +1actual partial/fullreceipt case passed sofar. Await fullrun.

IMPORTANT remaining backend mapping before manual/partial reserve UI: current
MaterialLineTotals exposes planLineId but not demandLineId. IDs are DIFFERENT.
Add nullable demandLineId at end of public MaterialLineTotals and populate from
actual demandLine?.id in InventoryMaterialService.summary. Draft maynull; submitted
must match actual persisted demandline. Do NOTderive UUID or assume equalsplanline.
Only one production constructor exists; add defaultnull for compatibility and test
actual query/reserve beforeallocation exists. materialTotals decoder mustpreserve
null/notguess; UI requiremapping for selectedpartial/override. Do after active
backend run completes, keeping current proofsource stable. No migrationsneeded.

After metadata/discovery complete: build shared plan editor, paged WO selection,
request totals/reservation choices, allocations namedmetadata, issue-aware pick/
unpick/dispatch/currentreceiver and printable immutable slip. Browser actualUI
setup+WOplan/reserve/pick/transit; technicianack added40/45. Continuewholeplan.

## Task35 persisted issue discovery implemented — verification pending

Allocation metadata source2671ff3a passed10 tests/4suites in4m9s, zero failure/skips;
portable allocation-metadata-verification.json saved; owned cleanup completed.
Seven typed material/issue web tests +TS/focusedlint passed84591d57.

New GET /api/work-orders/{id}/materials/issues accepts strict page,size,state;
public InventoryIssueApi + existing workflow obtains currentWO/IAM/cutover context.
WarehouseIssueQueries uses current visible_locations before count/paging, requires
all source and actual movement destinations (includingreceipt), and excludes
substituted snapshots withoutoverride. No cost/evidence metadata. Returns header
state/revision/unpicked, latest immutable sender/receiver, createdAt, and per-line
actual picked/dispatched/summed accepted quantities. It does NOT infer physical
transit from dispatched-minus-accepted (loss/exception canclose pendinggoods).
Existing slip endpoint unchanged; receipt advances header beyond sliprevision.
New tests cover2issue cases(paging/unpick/repick/dispatch/scoperevocation/substitution/
permission/tenant/invalidqueries) +1partial/fullreceipt case. NOTYETRUN.

NEXT `.omo/runtime/issue-list-server.sh` against new source:3new tests, existing
serial/replayguards +Modularity. Keep backend source stable. Then actual typed
issue list frontend +request/plan/pick/dispatch/slip UI and realbrowser task35.
No migrations changed. Continue35–48/F1–F4,goalACTIVE.

## Task35 typed material/picking contracts checkpoint

Added materialModels.ts, issueModels.ts and materials.ts using actual source DTOs.
Plan PUT returns MaterialPlanSnapshot; submit-request returns MaterialSummary;
warehouse owner reserve/release returns document/revision/operation/shortage ack;
pick/unpick/dispatch returns immutable IssueSnapshot with named sender/receiver.
Strict decimal strings and requested=unpicked+picked+issued+backorder; no received
quantity fabricated from dispatched or still-accountable. Parent sourceIdentityId
and resulting cut dimension.stockIdentityId remain distinct. Missing plan remains
unplanned, nullable customer preserved, substitution keeps original named snapshot.
Captured commands use existing retry key/body; no new permission bypass.
Seven focused tests + awaited TypeScript exit0 + focused lint passed.

Backend allocation metadata run against2671ff3a stillRUNNING at checkpoint:
3WarehouseIssueITSerial +2WarehouseQueryITCompatibility passed; reservation
compatibility/supplyprojection pending. `.omo/runtime/issue-metadata-fixed.log`,
wrapper issue-metadata-server.sh archives task35/allocation-metadata-fixed. Initial
run1f8003e7 failed only test compilation and stale copied XML must not count.
Do not alter backend source until run completes; save portable proof afterwards.

NEXT implement discoverable paged issue list under material workflow context via
InventoryIssueApi, then request UI. Reuse WarehouseQuerySql visible_locations and
current IAM/location/area/effective-site scope beforepaging; issue.view+request.view;
source document lines and ALL actual issue movement destinations including receipt
mustbe visible; substituted snapshots need override. Add header currentstate/revision
separately from immutable sliprevision, named sender/receiver and line totals from
actual dispatch plus SUM(material_receipt_line.accepted_base), never infer accepted.
Do not label dispatched-minus-accepted as physical transit: issue exception/loss
can close pending goods. Physical transit already comes stock query. List tests must
cover paging/unpick/repick/dispatch, source/transit revocation beforecount/paging,
substitution override revocation, tenant/read permission and partial/full receipt.
MaterialReceiptFixture provides real100m dispatch/60m ack then40m; no mockedwrites.

Existing WO selector API is paged legacy content/page/size/totalElements. Avoid
searchOpenWorkOrders (truncatesfirst50). Reservation ownerroute is needed forwarehouse
operators: outer workflow reserve uses WO edit-context; plan save/submit requires
WOupdate/assign (or assignedfield). Physical picking uses lockForIssue with WOread.
No issue list or request UI yet,task35OPEN. Continue35–48/F1–F4,goalACTIVE.

## Task35 metadata test compilation correction

First metadata check at1f8003e7 failed compileTestKotlin before tests: Jackson3
JsonNode.map chose its member, not Kotlin Iterable.map, so AssertJ list assertion
had the wrong receiver type. Convert through asSequence().map().toList().
Production Kotlin compiled. Do not treat copied stale XML from first attempt as
proof. Rerun archives allocation-metadata-fixed; runtime issue-metadata-fixed.log.
Frontend materialModels.ts is independent UNVERIFIED preparation, not yet committed.
Next await actual backend tests then implement issue discovery/received totals.

## Task35 started — named allocation metadata; verification pending

Task34 complete0e42889a (final source75f8ba41,2browser45.5s,100web,11backend).
ReservationAllocation now adds nullable skuCode/skuName/serial/lotCode/locationName
from actual scoped reservation dimensions and current master metadata. The existing
serial issue regression asserts10 canonical serials and correct names before pick.
No quantity/revision/permission changes; legacy fields retained; no migrations.
NEXT run `.omo/runtime/issue-metadata-server.sh`, then implement scoped persisted
issue discovery/received totals with proper source/transit/receipt destination scope
and current request+issue permissions, before typed request/picking/slip frontend.
Task35 UI/browser notimplemented. Read detailed task-35.md actual API contracts.
Continue35–48/F1–F4. GoalACTIVE;148nextunused,177/178reserved43.

## Task34 COMPLETE — stock explorer verified on desktop and mobile

Final source75f8ba41, stock-explorer-readable:2passed45.5s, zero failure/skipped/flaky.
BootJar/TypeScript/webbuild passed.100 web tests across12 files passed,8 stock page
cases rerun after test-typing correction.11 backend query/bucket tests across7suites
passed at3046bb05. Portable task34/verification.json and6 reviewed synthetic images
saved. Earlier premature TS claim corrected; initial failure record remains.

Real UI:1000m/10ONU intake,900m/8available and100m/2quarantine; exact lowstockminimum,
serial lookup/5events/origin, lot5segments/3active/2split with1000m conservation,
parent/child navigation, known/unknown costs, scoped quarantine/position drilldown.
No stock explorer mutation. Textwrap now keeps current location names and document
revisions readable on desktop/mobile. All owned QA processes/containers/network
stopped;volumes retained. Task34 checkbox complete; goal remains ACTIVE.

NEXT task35 demand/picking/issue. Read task-35.md actual contract preparation.
Need discoverable persisted issue list after reload (currently only slip byUUID),
actual received totals distinct from dispatch, and named allocation metadata; then
typed APIs/shared plan editor/request workbench, actual browser reserve/pick/unpick/
dispatch with transit. Receiver acknowledgement UI belongs40, E2Eextension45.
Continue35–48/F1–F4. No migrations changed;148 nextunused,177/178reserved43.

## Task34 functional browser green; visual refinement pending recheck

stock-explorer-compiled at3d377e6f: both actual desktop/mobile passed44.8s, zero
failed/skipped/flaky. BootJar + web build passed; owned cleanup stopped processes
and containers/network, kept volumes. All requested stock/serial/lineage/scoped
quarantine assertions passed; no explorer mutations. Reviewed8 synthetic captures.
Found inherited resource-table ellipsis clipping document revision and current
location text. Warehouse cell values now wrap on both widths; stock detail paragraph
spacing reduced; movement labels translated for operators. No business behavior
changed. Task34 staysOPEN until new visual browser check. NEXT wrapper run
stock-explorer-readable. Task35 actual contract preparation saved in task-35.md.

## Task34 compilation correction — real browser still pending

stock-explorer-initial at e9e57a60 stopped BEFORE Playwright: bootJar passed but
web TypeScript found unsupported `exact` in two Testing Library ByRoleOptions.
The preceding TS pass claim was premature; the actual pending compiler failed.
100 unit tests did pass. Corrected two test queries (string name is already exact),
then awaited TypeScript exit0 and reran all8 stock page tests: passed. Initial
ui-verification.json now records the real failure; compile-correction.json records
cause/fix. Owned cleanup passed with volumes retained. No browser acceptance yet.
NEXT `.omo/runtime/warehouse-stock-browser.sh stock-explorer-compiled`, review
both screenshots only after actual success; then finish34 and continue35–48/F1–F4.

## Task34 explorer UI implemented — 100 web tests green; browser pending

Backend operational buckets against 3046bb05: 11 tests / 7 suites passed in 2m54s,
including exact reserved/picked quantities, quarantine/transit and scope/privacy;
owned cleanup passed, volumes retained. Portable bucket-verification.json saved.
Stock page now has summary/positions/assets/lots/unknown, server filters/paging,
named historical master selectors including archived rows, scanner GET lookup,
asset/position details, origin receipt links, exact costs and permission redaction,
lot conservation and paginated parent/child segment navigation, immutable history.
Drilldown retains location/condition/bucket; invalid/blank/duplicate URL rejected;
unknown legacy units remain unknown and inconsistent conservation remains an alert.
100 tests / 12 files passed (full warehouse + DataTable); lint passed. Initial
TypeScript failed in test queries, corrected in the next checkpoint above.
Eight new stock page tests cover quantities/pages, server buckets, archived named
filters, provenance denial, unknown units, hidden/exact costs, scope failure and
inconsistent/truncated lineage. No warnings in changed warehouse files.

NEXT run `.omo/runtime/warehouse-stock-browser.sh stock-explorer-initial` (new
wrapper archives task34, owned lock/cleanup retained). Extended receiving.spec.ts
uses only actual UI/API: receipt 1000m/10ONU, rejected 100m/2ONU, staged putaway,
serial lookup and 5-event trace, reel 5 segments / 3 active / 2 split, cost/origin,
quarantine filter and location-preserving position drilldown. No mocked browser
writes/responses. Screenshot top scroll corrected and mobile labels left-aligned.
Do not mark task34 complete until both projects pass and screenshots are reviewed.
Continue 35–48/F1–F4; goal ACTIVE. No migrations changed;148 next unused,177/178
reserved for43. This is a recoverable implementation checkpoint, not completion.

## Task34 typed explorer API and bucket filters checkpoint

Metadataquery e1634743:11tests/7suites green2m46s,portable display-metadata-verification.
Typedstock.ts addspositions/assets/lots/segments/unknown/discriminatedtimeline,cost
KNOWNvsUNKNOWNvsabsent;legacyunitnullpreserved; compositeevent:reservationIDs; exact
quantityunitconsistency; treechildcountbeyond100andconservationfalse visible.
stockRow includesactual nullableminimumQuantityBase.63API/receipttests green,
TS/lintgreen,portableapi-verification. No StockPage yet, task34 staysOPEN.

Backendbucket filter implementedONLYstocksummary/positions:AVAILABLE/RESERVED/PICKED/
TECHNICIAN/TRANSIT/INSTALLED/QUARANTINE. Preserves exactphysicalstatus semantics.
Filtersbeforeserverpaging; reserved usesunpicked+picked notstatusRESERVED; technician
excludesused/lost/disposed. Unsupportedendpoint/bucket rejected400. Existingbalance
IT nowasserts900000physical/600000available/200000unpicked/100000pickedreservedreel,
positivePICKED/TRANSIT/Q andno remainingtech,unchangedwritecounts. NOTYETVERIFIED.

NEXT .omo/runtime/stock-bucket-server.sh. ThenactualWarehouseStockPage+detailpanels
usingtypedAPIs,quantity/status/nameselectors/history/lineage; browserreceivingextension
forassettraceandtreewithsame realsetup. See task-34.md earliercontracts/visualnotes.
Source stable duringserverbuild. GoalACTIVE;continue34–48/F1–F4;nomigrations.

## Task34 started — scoped stock display metadata; verification pending

Task33 complete andpushed54c7fd16 (2browser40.9s,86distinctunit,11backend;sourcea39aaea8).
Task34 beginsadditivequerymetadata:stock.minimumQuantityBase fromactualSKU,
asset.locationName fromvisiblelocations,movement.currentLocationName explicitly
currentnot historicalsnapshot. ExistingQueryITIdentityextendedfornames/minimum;
no migrationsorlegacyDTOchanges. Frontendstockexplorer notimplemented yet.

NEXT .omo/runtime/stock-display-server.sh tests querybalances/identity/privacy/
compatibility/serialfilters/lotfilters/staged. Then typedstockassetlottimeline APIs,
WarehouseStockPage+tests and receivingbrowserextension as task-34.md describes.
Do notmark34completebeforebothrealbrowserprojects/visualreview. Current task33
receipt contracts: draft->ReceiptView,receive/inspect/putaway->{id,revision,state,
operationId}thenGETdetail. Avoid previous assumedfullmutationresponse mistake.
Continue34–48/F1–F4;148nextunused;177/178reserved43.

## Task33 COMPLETE — real receiving desktop/mobile green

receiving-transition-fixed againsta39aaea8:2passed,0failed/skipped/flaky,40.9s.
ActualUI emptytenant setup andsourcepreset; draft1000m+10ONU,duplicate correction,
scanner,editpreservinggroupcost,privateevidenceupload/reload/download,receive,inspect
reject100m+2ONU,putaway800m+8ONU then100m. FinalPUTAWAYrevision6/history7;
stockUI900m+8ONUavailable,1000m+10physical withrejected100m+2inquarantine.
CurrentGET followsactualtransitionack,notfabricatedfullresponse. Both desktop/mobile
receipt/stock screenshots reviewed andportabletask33/verification.json committed.
85baselineunit +57focused afterfix incl1new=86distinct;TS/lint/bootJar/webbuildpassed.
Backendmetadata/costVisible11tests/5suites passedf16352e6;portablefinalproof saved.

AllownedQAprocesses/containers/networkstopped;volumesretained. Task33checkbox complete.
Nexttask34 stock/device/lotexplorer:read task-34.md preparation,actualquerycontracts;
extendsame receiving.spec.ts forserialtimeline/reelconservation. Continue34–48/F1–F4.
GoalACTIVE. No migrationschanged;148nextunused;177/178reservedtask43.

## Task33 receive acknowledgement corrected; browser rerun pending

receiving-initial againstf16352e6:both desktop/mobile reached real draftcreate/edit,
serialduplicate correction,scanner,private upload/reload/download andsuccessfulreceive.
Both failed at32.85s because the client/test assumedtransitionreturnedReceiptView.
Actual ReceiptTransitionService returns{id,revision,state,operationId}; stockcommitted
but strictclientdecoder rejectedshape and correctlyretaineduncertaincommand.
CorrectedAPI withseparate stricttransitiondecoder;receive/inspect/putaway nowreloadGET
before renderingnewactions. Browser transitionhelper observes thatrealGET,thenchecks
lines/pieces fromdurablesnapshot. Addedunitactualack->GET->inspection;57focusedAPI/
receipt tests passed (85baseline+1new totalcoverage86),TS/lintpassed.

Finalbackendmetadata+costVisible againstf16352e6:11tests/5suites green2m47s,portable
metadata-final-verification.json. AllQAownedcleanup passed,volumesretained. Firstbrowser
failureprivate under task33/receiving-initial; no completionclaim. NEXT run
.omo/runtime/warehouse-receiving-browser.sh receiving-transition-fixed,inspectboth
screenshots aftergreen. Continue34–48/F1–F4; task34 preparation saved. No migration.

## Task33 receipt UI implemented —85 unit tests; real browser pending

Task32 complete atf9d95a9a. Task33 now routes to actual receipt list/draft/detail,
receive, inspection, partial putaway, private evidence upload/download/pages and
history/reference save. Named selectors; explicit source setup preset; bulk serial
and scanner Enter (never stock submission); exact quantities; original grouped
serial cost/conversion preserved on edit. Full draft replacement requires server
costVisible plus cost.view so hidden costs cannot become null. Receipts can still
be received/inspected by operators without cost access. Fresh GET after every command.
Multipart retry captures immutable bytes/file name/revision/key; no public URL.
Current piece IDs/revisions/dispositions from server, accepted remainsQ untilbin;
rejected never bypasses inspection. No stock seed or request/response mocks in E2E.

85unit passed +TypeScript/lint; no warnings in changed warehouse files. Metadata
backend beforecostVisible addition:11real tests passed at052df40a in2m46s. Portable
foundation-verification.json +metadata-verification.json saved. costVisible addition
and receipt UI browser still need verification; task33 checkbox stays OPEN.

NEXT .omo/runtime/receipt-metadata-final-server.sh (same11 aftercostVisible) then
.omo/runtime/warehouse-receiving-browser.sh receiving-initial. E2E real setup,
1000m/10ONU receipt/edit, duplicate serial correction, scanner, evidence reload/
download,100m+2ONU rejection,800m+8ONU putaway thenremaining100m, final900m/8ONU
available, revision6/history7/reference download. Inspect screenshots after green.
Continue34–48/F1–F4. No migrations;148unused; source stable while QA runs.

## Task33 receipt metadata checkpoint — verification pending

Task32 complete atf9d95a9a,4real browser and76unit green. Task33 adds paginated
GETreceipt attachments using current receipt authority/scope, locked intake and
stable createdAt/id order, safe metadata +matchesCurrentIntake flag. Old evidence
remains downloadable but not eligible for replacement intake. Names of source and
inspection now come from immutable receipt intake snapshot. No migration changed.
New WarehouseReceiptITMetadata covers paging, reload, stale binding, permission,
scope/tenant denial and no storage key/URL leak. Existing receipt regression selected.
NEXT run .omo/runtime/receipt-metadata-server.sh; compile/tests not yet claimed.
Then implement typed receipt API, captured upload and receipt UI/browser. Task33
remains OPEN; continue34–48/F1–F4 after actual acceptance.148 nextunused.

## Task32 COMPLETE —76 unit tests and4 real browser cases green

catalog-layout-fixed against99868d60:4passed,0failed/skipped/flaky. Real desktop and
375px touch journeys cover full empty-tenant setup, master edits/archive/duplicate,
user area/scope grants and restricted read-only access. Both screenshots reviewed:
mobile labels match values, action buttons reachable, correct warehouse breadcrumbs,
no horizontal document overflow. Portable task32/verification.json and reviewed
synthetic screenshots committed.76unit (61warehouse+15DataTable), TS/lint/bootJar/web
build passed. Owned processes/containers/network stopped; volumes retained.
Task32 checkbox complete. Continue33–48/F1–F4; task33 receipt contracts in task-33.md.
Goal ACTIVE. No migrations changed;148 unused and177/178 reservedtask43.

## Task32 functional setup green; mobile table and breadcrumb refinement

catalog-area-fixed against beecbc60:4 passed,0 failed/skipped/flaky,59.084s.
All owned cleanup succeeded, volumes retained. Reviewed desktop/mobile screenshots
revealed resource-table mobile header/value alignment and the global catalog crumb
incorrectly saying Paket Internet. Added warehouse-only responsive label/value rows
with accessible column headers retained, full-size row actions, and full-path warehouse
breadcrumbs. Other resource tables retain their existing presentation.
76 targeted tests (61 warehouse +15 DataTable regressions), TypeScript and lint passed.
Browser assertions now cover the actual warehouse breadcrumb and action target size.
NEXT: run catalog-layout-fixed, review both screenshots, then save portable task32
proof and mark32 complete. Task33 contract notes ready; continue33–48/F1–F4.

## Task32 desktop setup passes; mobile area prerequisite layout fixed

catalog-initial against3dc45f23:3passed/1failed59.67s. Both task31 navigation cases
and the full task32 desktop setup journey passed. Mobile setup failed at existing
AreasPage: its unwrapped horizontal create row pushed the Tambah button outside
viewport. Screenshot inspected: code/name inputs extended past375px. Fixed only
that prerequisite form to wrap with flexible12rem/16rem bases and min-width0.
This is a real UI fix, not a forced browser click. All owned cleanup succeeded;
volumes retained.61 unit tests remain green from the preceding checkpoint.

Next .omo/runtime/warehouse-catalog-browser.sh catalog-area-fixed. Need both full
setup journeys green and inspect screenshots before32completion. Continue33–48/F1–F4.
Task33 contract preparation saved in task-33.md; implementation not started.

## Task32 catalog implemented —61 unit tests; browser setup pending

/warehouse/catalog now routes to actual location, SKU, supplier and user-scope tabs.
Setup checklist explains explicit area grants and links existing area/user admin.
Location editor supports kind, same-area warehouse/bin parent, optional named site
and active custodian selection, issue eligibility, read-only/archive and confirmations.
Current reference404 preserves its stored ID with an explicit unavailable-name label;
other failures show error, never fabricated names or a successful empty directory.
No mutation follows merely selecting a parent/user. Named selectors are searchable
and paginated. Master list now uses existing DataTable resource presentation.

Scope panel reads actual grants including revoked revision. It cannot infer zero
when GET fails; only an absent entry in a successfully read list uses expected0.
Grant/revoke review names user/location and explains inherited versus direct access.
409 reloads the current grant without automatic resubmission. Role and area remain
independent authority.61 unit tests passed (57prior+4 location/scope); TS+lint passed.
Portable task32/catalog-verification.json. No task32 browser success claimed yet.

Extended e2e/warehouse/setup.spec.ts to4 real cases total across desktop/mobile:
existing task31 journey + new UI area creation/self-assignment/login; warehouse/bin/
quarantine setup; SKUcable exact82500MM minimum andONU; supplier; edits of all3;
referenced root archive denied; unused SKU archive succeeds/read-only; duplicate code
keeps editable draft; UI role/user/area creation and scoped readonly grant; reader
sees main+child but not standalone quarantine. No SQL seed or API bypass for setup.
New catalog.ts browser helpers use only UI actions and observe actual responses.
helpers.createUser accepts optional area checkbox labels/prefix, original31 unchanged.

NEXT: run .omo/runtime/warehouse-catalog-browser.sh catalog-initial with private log.
Wrapper archives under task32, retains volumes and owns cleanup/locks as before.
Resolve real UI failures, inspect mobile screenshot, capture portable proof and mark32
only after required cases pass. Then33–48/F1–F4. Goal ACTIVE. No migrations added.

## Task32 IN PROGRESS — editor/picker foundation57 unit tests green

Task31 complete and published170d2158 (real browser source55b19223; desktop+mobile2
passed and49unit, portable evidence/screenshots). All owned QA stopped; volumes kept.
Task32 has generic WarehouseMasterPanel (search/page/state, create/edit/read-only,
archive confirmation with real revision), SKU and supplier editors using captured
commands and native form validation; they are NOT yet wired to /warehouse/catalog.
Location editor, user-scope panel, actual catalog/setup checklist and browser setup
extension remain to implement. No task32 browser claim or checkbox completion.

WarehousePicker uses named bounded search/pages and preserves selected references
across searches; no silent first-page truncation. API setup.ts decodes existing IAM
user/site pages, areas and warehouse-scope grants; malformed/missing revision fails.
masters.ts adds typed get-by-id. Error messages preserve archive/business reasons
instead of treating every409 as stale. Pagination moved to shared warehouse control.
Button AppButtonProps now distributes Omit over Fluent's button/anchor union to
preserve native form prop (type-only change; no runtime implementation change).
DESIGN.md picker/scope conventions updated before these controls.

57unit cases passed (prior49 +8new setup/editor/picker cases); fullweb tsc-b and lint
passed. Portable task32/foundation-verification.json. Tests cover actual MM minimum
payload, retained editable supplier draft after409, archived/read-only no save,
actual stale revision+explicit reload, selector preserves chosen name acrosspages,
invalid IAM paging/missing grantrevision, and meaningful archive reason. Extend with
location/scope/archive-page behaviour and actual browser setup before32completion.

Next read task-32.md contract preparation below. New tenant listener really exists:
WarehouseTenantCreatedListener initializes ENFORCED/NEW_EMPTY atomically on tenant
created event (so later receipt UI needs no fake cutover for new tenants).
Continue32–48/F1–F4. No migrations changed;148unused; no active backend/QA sessions.

## Task31 COMPLETE —49 unit tests and both real browser projects green

setup-mobile-fixed against55b19223: desktop1280x900 and touch/mobile375x812 both
PASSED (2tests,12.35s,zero failures/skips/flaky). Real UI signup, role/user creation,
independent approver login, overview+queue, stock API403 and route denial, unknown
route unavailable, no horizontal document overflow. All warehouse requests were
real, no mocked responses or SQL inventory seed. API+Vite proxy readiness proved
warehouse_e2e/warehouse_app/owned marker; role NOSUPERUSER NOBYPASSRLS. All339
migrations through147 booted. Server bootJar and web TypeScript/Vite passed.
49 API/control unit tests and14 prior API/session/nav regression tests are separately
recorded. Portable verification.json +reviewed synthetic-account desktop/mobile
screenshots committed under task31. Raw result JSON/auth-bearing traces stay private.

All owned QA processes/containers/network stopped cleanly; volumes retained.
Task31 checkbox now checked; whole goal stays ACTIVE. Next task32 setup/catalog
CRUD and user warehouse scope UI; read task-32.md preparation and actual contracts.
Current other warehouse routes explicitly unavailable pending32–41; do not claim
full operational UI complete. Continue32–48/F1–F4 with commits/remote checkpoints.
No migration changes;148 nextunused,177/178 reservedtask43. No subagents or deployment.

## Task31 desktop passes; mobile drawer selector fixed

setup-label-fixed against9b794f79: real desktop journey PASSED, including signup,
UI-created approval-only role/user, overview+queue, stock API403, stock route denied,
unknown route unavailable and no horizontal page overflow. Mobile reached the same
approver login then failed opening navigation: translated-offscreen drawer still
satisfies Playwright isVisible(). Helper now reads header aria-expanded at<=820px
and uses actual touch tap to open drawer and section. No application code changed.

Next run .omo/runtime/warehouse-setup-browser.sh setup-mobile-fixed. Need BOTH
projects green in one run before marking31complete. Last run1passed/1failed31.4s,
all owned cleanup passed, volumes retained. No migrations changed. Continue32–48/F1–F4.

## Task31 browser reached real UI; required-field selectors corrected

setup-health-fixed failed compilation only (Any? health detail); now requireNotNull
uses previously validated values. setup-health-compile-fixed built successfully,
API+Vite proxy health passed with the owned marker/database/app role, then BOTH real
browser projects reached signup and failed the same test selector: Fluent adds a
required asterisk to label text. Accessible textbox name is correct; exact getByLabel
was too strict. Helpers now match the field label without requiring exact raw text.
No application signup or permission failure has been observed yet. Both failed runs
cleaned owned processes/containers successfully, volumes retained.

Next .omo/runtime/warehouse-setup-browser.sh setup-label-fixed, verify complete
signup/role/user/approver journey. Task31 remains OPEN;49 unit proof remains valid.
Task32 preparation notes saved separately (contracts, explicit area grants, scopes,
UI prerequisites), implementation not yet started. Whole goal ACTIVE.

## Task31 browser readiness correction (not yet verified)

Published a030927f contains49 green unit tests and the new navigation/controls.
setup-initial browser attempt built server/web and booted all339 migrations through
V175_147 in warehouse_e2e, but readiness failed before any browser test: SMTP health
was DOWN although email is intentionally disabled, and no warehouse health indicator
actually existed (earlier handoff assumption was wrong). Added profile-only
WarehouseQaHealthIndicator querying database/current app role/marker and requiring
NOSUPERUSER/NOBYPASSRLS/no role/db creation; mismatches fail DOWN. Disabled only the
unused mail health probe in warehouse-e2e. No production profile behavior changed.

Next run .omo/runtime/warehouse-setup-browser.sh setup-health-fixed with private log.
Check actual compilation, owned API/proxy readiness, then real signup/approver browser
journey; no green browser evidence yet. Keep task31 OPEN until both projects pass.

## Task31 navigation and transaction controls — browser harness pending

Warehouse routes now use independent permissions and a separate Gudang & Logistik
sidebar group. /warehouse and /warehouse/approvals use the current paginated API;
approvers do not need inventory.item.view. /warehouse/stock reads typed quantities.
Old WarehouseOperationsPage is a compatibility export; client-generated approval
hashes are removed from the active/legacy web entry. Other planned routes and
unknown paths explicitly report unavailable until their tasks are implemented.

Shared controls under components/organisms/warehouse: exact quantity field/readout,
manual/keyboard serial lookup (lookup only), named lines, status, timestamp/history,
and captured-command dialog. Network/invalid-success ambiguity locks dismissal and
retries the original command; definite 400/402/403/404/409/422 rejections allow return
or explicit document reload. Query state discards obsolete filter results.
49 unit tests passed (44 API +5 controls); TypeScript passed. Lint passed with
existing repository warnings. Isolated jsdom dialog shim is only in unit tests.
No real browser success claimed yet.

New real browser harness: playwright.warehouse.config.ts, e2e/warehouse/helpers.ts
and setup.spec.ts; actual UI signup, role/user create, login independent approver,
API readiness database/user/marker assertion, denied stock API and route, explicit
unknown route, 375px touch/mobile and desktop. No API response mocks or SQL seeds.
application-warehouse-e2e.yml disables scheduling/demo/radius/SMTP fallback/throttle
and automatic provisioning; existing qa.sh owns backend/database/object store.

NEXT: run .omo/runtime/warehouse-setup-browser.sh setup-initial (private log),
resolve actual failures and capture sanitized portable browser evidence before
checking task31 complete. Wrapper owns outer flock fd8; qa uses fd9; cleanup retains
volumes. Then finish32–48/F1–F4. Goal ACTIVE. No migrations changed;148unused.

## Task31 API foundation —44 tests and TypeScript green

Quantity, runtime codecs, master/stock/lookup DTOs and immutable command transport
now implemented under web/src/api/warehouse.44 tests passed with plain npm test;
`npx tsc -b` passed. Portable evidence task31/api-verification.json. Shared
masters.ts exposes typed list/save/archive commands and stock/identity reads.
Responses validate UUIDs, safe numeric revisions/pages, string quantities, unit/
display consistency, tracking/ownership/states and bounded pages; malformed data
throws WarehouseDataError instead of empty success. Optional nullable fields remain
null and unknown additive fields do not leak through typed views.

command() captures serialized JSON/key once, coalesces concurrent submissions and
reuses both after network loss or401 token refresh. It deliberately reauthorizes
via server on later execute(), never caches a prior successful reply as permission.
Uses api.request and original Idempotency-Key; no client approval hash. Tests prove
input-object edits do not mutate captured retries, conflicts retain keys and bad
success bodies reject. All this is unit evidence, NOT browser acceptance.

Next: shared warehouse route/gate/nav and named controls; replace old inventory
warehouse shell; build real setup.spec.ts desktop/mobile harness and isolated
warehouse-e2e profile through existing qa.sh browser. App.tsx also has a misleading
RequireAnyPermission hardwired to canViewHotspot; do not reuse it blindly for
warehouse. Add a warehouse-specific gate or correctly generalize with regressions.
No backend QA active, migrations unchanged,148unused. Task31/whole goal OPEN.

## Task31 quantity foundation saved —34 web tests green

Task30 complete/published11468066 (53 green,main422cf536). Task31 now has design
specs, exact bigint quantity conversion/formatting and20 quantity tests.14 existing
API-client/session and shell-nav tests also pass. Vitest workers disable Node native
webstorage when supported so jsdom owns localStorage; plain npm test works on Node26.
Portable evidence task31/quantity-verification.json. No package/dependency changes.

Task31 remains OPEN: runtime DTO validation, retained mutation retry keys, actual
warehouse routes/navigation/shared controls and real desktop/mobile browser harness
plus isolated warehouse-e2e backend profile are next. Detailed source paths and
acceptance notes in task-31.md. No backend QA active.147 immutable;148unused.
Whole-plan goal ACTIVE;31–48/F1–F4 remain. Keep committing/pushing recovery notes.

## Task30 COMPLETE —53 reports/receipt regression tests green;task31 started

reports-replay-fixed against422cf536 passed53 tests with zero failures/errors/skips
in5m35s. Portable sanitized evidence: task30/verification.json. Owned QA stopped
cleanly and retained volumes. Both complete real1km+10ONU LOAN/SALE journeys,
100m+1 issue,82.5m use+1 actual install,17.5m accepted return reached917.5m/9ONU,
zero field cable and unchanged replay. Costs retain1000006/1000000 IDR and1999995/10
USD source bases, yielding separate82500IDR/200000USD HALF_UP totals. Physical
1002-leg export rejection and narrowed334-row export passed. All print snapshots,
price-draft edits, current cost revocation, foreign scope, explicit unknown legacy
units, stock-card pre-range opening and formula guards passed.

Receipt replay fix also passed all40 receipt cases: current authority/assignee/
warehouse scope still apply, old successful reply survives WO progress, changed
payload or fresh stale command cannot post, and revoked scope still denies. No
migrations were added; allthrough147 immutable,148unused. Plan tasks1–30 complete.
Whole-plan goal ACTIVE:31–48 and F1–F4 remain. No main merge/deploy/reset.

Task31 now has uncommitted web/DESIGN.md warehouse-control specs and
web/src/api/warehouse/quantity.ts +quantity.test.ts.20 precision/input tests pass
with `env NODE_OPTIONS=--no-experimental-webstorage npm test -- src/api/warehouse/quantity.test.ts --maxWorkers=2`.
Plain npm test first failed20 cleanup hooks because Node26 native localStorage
shadows jsdom (undefined without --localstorage-file). Do not fake browser storage;
use the scoped Node flag, or make the test runner handle supported Node versions.
Repo deploy uses Node22. Need commit this small31 checkpoint, then finish shared
runtime DTO parsing, stable retry transport, warehouse routes/navigation/controls,
real desktop/mobile browser harness and external-adapter isolation profile. Existing
qa.sh browser deliberately refuses missing31 config/profile. See task-31 notes.

## Full numeric report passes; delivery replay lifecycle fix under validation

reports-full against aae97d92 executed11 tests/5 suites,2 failures,0 errors/skips,
3m22s. All4 report basics,3 modularity,CSV formula test and actual1002-ledger export
bound passed. BOTH full LOAN/SALE journeys reached correct917500MM/9ONU/zero field
cable, exact82500IDR and200000USD totals, captured original bases and visible serial
chain. They failed only at final acknowledgement REPLAY after WO start: fulfillment
checked the old expected WO revision before inventory could return the stored reply.

Moved that expected-WO-revision check to InventoryMaterialReceiptService AFTER its
existing actor/resource/hash/cutover/location-authorized replay path, together with
the existing issue/current-WO revision check. New postings keep both comparisons.
Current field permission, active assignee, active WO, live plan, same receiver,
authority/cutover fences and scoped receipt checks all remain before any reply.
No SQL or persisted contracts changed. Added WorkOrderMaterialReceiptITReplayLifecycle2:
progress replay returns identical original, changed payload/new stale action denied,
revoked field scope denied, and stale first receipt cannot post.

Added WarehouseReportPrivacyIT2: current cost revocation removes historical print
cost and blocks cost export; explicit migration-owner staged unknown raw quantity
never receives inferred units/available balance. Enhanced historical-cost test with
a new supplier quote draft and a later price edit, which must not reprice old use.
Added docs/warehouse-reports.md with paths, filters, exact cost math, snapshot/age
semantics and bounded exports.

Current .omo/runtime/reports-replay-fixed.sh / .log selects *WarehouseReport*IT,
*WarehouseReportCsvTest,*WorkOrderMaterialReceiptIT* and ModularityTests. Expected
reports13 plus receipt regression cases; do NOT assume final count/result yet.
Archive task30/reports-replay-fixed/xml; private reports-replay-fixed-database.log.
Task28 COMPLETE183 green; task30 OPEN pending this run and final acceptance review.
Allthrough147 immutable;148unused. Whole-plan goal ACTIVE;keep commits/pushes.

## Report custody correction and full11-case validation running

reports-compile-fixed executed7 tests with2 failures,0 errors/skips,2m8s. Unknown
cost, filter guards and3 modularity tests passed. Privacy reached revoked scope but
the test used0 instead of1 as the grant revision; corrected fixture. Physical917500
available/82500 consumed passed, then custody-aging counted the consumed sink under
the former technician. Custody/transit reports now exclude CONSUMED/LOST/DISPOSED.
No ledger or stock quantities were changed to fix the report.

Added strict optional workOrderId filter only for work-order-costs; invalid UUID,
duplicate value and unsupported-filter paths reject. Unknown quantity summary is
bounded by base unit (line pages still carry SKU/WO). Added unknown-stock report
using existing explicit legacy-unverified projection; no inferred units. Historical
print rejects DRAFT versions whose mutable lines are not guaranteed frozen.

Current .omo/runtime/reports-full.sh / .log selects *WarehouseReport*IT plus
*WarehouseReportCsvTest and ModularityTests, expected11. New complete numeric2,
real1002-ledger export1 andCSV1 cases compile; execution result not yet known.
Archive task30/reports-full/xml; private reports-full-database.log. All migration
versions through147 immutable,148 still unused. Task28 COMPLETE with183 green;
task30 OPEN until full acceptance/evidence, later30–48/F1–F4 work remains.

## Report CSV compilation fixed; initial integration running

reports-initial failed compileKotlin before any tests (14s): Jackson JsonNode.map
selected its member overload instead of Kotlin collection mapping. CSV now converts
to a sequence explicitly. reports-compile-fixed is compiled and running the same
WarehouseReportIT4 + ModularityTests3; the3 modularity cases have passed so far.
Do not label initial copied XML green; cleanup after compilation copied stale data.

Added WarehouseReportJourneyIT2 full real1km+10ONU /100m+1ONU /82.5m use /17.5m
accepted return /LOAN or SALE deployment journeys, with actual HTTP-created customer,
mixed IDR/USD HALF_UP source costs and replay checks. Added WarehouseReportExportIT1
real334-device receive/putaway ->1002 visible ledger legs (oversized CSV rejection),
and WarehouseReportCsvTest1 formula/control/quoting cases. These4 new cases are NOT
selected by the running initial suite and have NOT compiled/run yet. Next selector
must include *WarehouseReport*IT and *WarehouseReportCsvTest (expected11 total with
modularity). Still need task30 acceptance review, WO filter/summary bounds, report
scope and any SQL/fixture failures from execution. All148+ migrations unused.

## Task28 COMPLETE —183 affected regression tests green

asset-loss-regression against c09c6da9 completed183 tests/11 suites with zero
failures/errors/skips in13m20s. Replacement48, ownership72, episode revisions30,
returned dispositions17, compensation12, actual returned-device reuse1 and
modularity3 all passed. Owned QA stopped cleanly with volumes retained. Portable
sanitized evidence: task28/existing-flow-regression-verification.json.

Together with30 disposition/compensation guards,6 compensated-asset reuse cases
and15 active-loan-loss/guard cases already verified, task28 acceptance is satisfied:
approved document-bound exact quantities; immutable original costs/evidence;
independent policy approvals; loss of a live ISP loan closes the existing episode
without fake recovery; customer-owned SALE property is denied; exact original
posting linkage for correction; closed/reused/installed downstream state cannot
be casually reversed. Plan task28 is now checked. Compensation remains scoped to
returned LOSS/SCRAP, as documented; arbitrary ledger rewrites are not exposed.
All migrations through147 remain immutable;148 next unused, reserve before use.

Whole plan remains ACTIVE. Task30 initial report foundation4414f9b6 is pushed;
its7-case reports-initial QA is now running, NOT yet green. See task-30 notes.
30–48 and F1–F4 remain. Continue autonomously with commits, remote checkpoints,
sanitized evidence and recovery notes. No main merge/deploy/reset.

## Task30 report foundation checkpoint — NOT yet verified

Task28 existing-flow regression still runs against c09c6da9 in
.omo/runtime/asset-loss-regression.sh / .log (173 PASSED, no failures at last
observation; NOT a final result). Task28 remains unchecked. All migrations through
147 applied/immutable;148 next unused. No new migrations in this checkpoint.

Task30 now has WarehouseReportService/controller/persistence, shared scoped
stock, ledger stock card with pre-range opening, movement/serial chain, continuous
current-dimension custody age/transit, assignment loan/sold status, operational WO
use costs, historical receipt/issue/return print DTOs and bounded CSV. Costs keep
original receipt numerator/basis and exact integer HALF_UP per line; per-currency
totals and unknown quantities remain separate. Handover/removal/loss does not charge
the original installation again. Report.view is independent of item.view; cost.view
is required for WO costs and gates print costs. All outputs omit canonical payloads,
customer labels, evidence/object keys and authority/session data. Scoped locations
precede counts; stock card opening is calculated before dates. CSV max1000, rejects
page/size and oversize rather than silently truncating, neutralizes formulas.

Four WarehouseReportIT tests authored: real82500 use/17500 inspected return with
917500 available and historical print, unknown cost, current scopes/privacy and
filter/export boundaries. NOT executed yet. .omo/runtime/reports-initial.sh / .log
is queued behind the existing QA lock; selects WarehouseReportIT + ModularityTests,
expected7. Archive task30/reports-initial/xml; private reports-initial-database.log.
Do not trust any old XML copied by cleanup after a compile failure.

Task30 still requires actual full1km+10 ONU fixture/1ONU installation, mixed
currencies/rounding, hard oversized-export proof, nonempty loan/sold/aging/transit
coverage, revocation/history review and fixes from initial run. Print names use
actual captured SKU snapshots (receipt/issue/origin); missing old snapshots remain
NOT_CAPTURED, never reconstructed from mutable master. Review bounded response
summary growth and exact historical print scope. Full-plan goal stays active:
30–48 and F1–F4 remain; no main merge/deploy/reset. Continue committing and pushing
coherent tested fixes and portable sanitized evidence. Never commit private runtime.

## Task28 active-loan loss VERIFIED — existing-flow regression running

asset-loss-guards against c09c6da9 passed15 tests /3 suites, zero failures/errors/
skips,3m7s. Both primary LOAN/SALE paths and10 safety scenarios passed.147 applied
22:49:14.237 JKT and is immutable: 5da98c83d788aa398a8b70e43e583b2fa1f694e25dd9bf334c27f8f25cccb31f.
All143–147 migrations are immutable;148 next unused, reserve before creation.
Owned QA stopped cleanly with volumes retained. Portable sanitized evidence:
.omo/evidence/warehouse-workorder-asset-provenance/task28/active-loan-loss-verification.json.

An accepted active ISP loan can now be independently declared lost without fake
removal/return records. Exactly one approved LOSS closes its existing assignment
and customer episode and queues provisioning atomically, retaining original loan,
handover, installation and telemetry history. Actual recovery during pending
approval becomes durable STALE; races produce one closure. Direct SQL pending
effect/assignment closure, requester/delegation, unknown cost and revoked replay
are denied. Scope filtering precedes pagination. Rebuild and pending replacement
permit retirement both passed. Existing returned-disposition/compensation proof
was already30 green; actual compensated-device reuse was6 green separately.

Current .omo/runtime/asset-loss-regression.sh / .log checks CustomerAssetReplacementIT,
CustomerAssetOwnershipIT,CustomerAssetEpisodeRevisionIT,WarehouseDisposition*IT,
WarehouseCompensation*IT,WarehouseReturnITReuse and ModularityTests. Archive
.omo/evidence/warehouse-workorder-asset-provenance/task28/asset-loss-regression/xml;
private DB log asset-loss-regression-database.log. Do not assume count/result yet.
Product main remains c09c6da9. Wait for this affected existing-flow regression,
then save evidence and assess task28 acceptance before marking complete.
Task28 and whole-plan still OPEN;30–48/F1–F4 pending. Docs/warehouse-dispositions.md
now describes actual return compensation and active loan loss contracts.
All current checkpoints are pushed with explicit local SSH key; no main merge,
deploy or reset. Goal remains active until the whole plan is actually finished.

## Task28 lost-loan outbox binding fix — 15-case validation running

20bb5ce9 is published. asset-loss-evidence-fixed executed5 tests/1 failure,
0 errors/skips,1m56s.146 applied22:46:28.708 JKT, immutable: d7126fe6d9ab06ef9415d3ebbebf86de03193a142359d7db31c0bd6c8968f837.
LOAN draft/replay/get/list, policy-derived approval request and requester403 passed.
The checker effect reached COMMIT but rolled back because144 compared the outbox
payload with the approval response. PostingDocuments correctly emits the physical
posting/legs snapshot instead. SALE rejection and3 modularity tests passed.

147 was reserved before creation and changes that comparison to the exact expected
posting JSON derived from the two actual ledger legs, including identity, custody,
condition, title, quantity, unit, document line and endpoint; no approval/stock/episode
guard was removed. Actual source validation and all existing ledger rows remain.

Current .omo/runtime/asset-loss-guards.sh / .log selects WarehouseAssetLoss*IT
(2 primary journeys +10 guards) and ModularityTests3, expected15. Archive
.omo/evidence/warehouse-workorder-asset-provenance/task28/asset-loss-guards/xml;
private DB log asset-loss-guards-database.log. Do NOT claim147 applied or tests green
until execution confirms.148 next unused after147 applies. All143–146 immutable.
When these pass, run the relevant removal/return/compensation regressions, persist
sanitized verification and assess task28 acceptance before marking complete.
Whole-plan goal remains active;30–48/F1–F4 pending. No main merge/deploy/reset.

## Task28 active-loan loss query fixes — five-case verification running

0200fecf is published. Its asset-loss-effect run executed5 tests/2 failures,2m1s.
144 applied22:42:24.363 JKT and is IMMUTABLE:
41ffc26a006cdc7b55ac1ffd4e07e02bfc1b5abbd52c05d2bdce4bf846089708.
Both cases stopped in the shared deployment validator at an unqualified
`authorization_id` introduced by144.145 was reserved first and qualifies only
those references; no validation was removed.145 applied22:44:24.291 JKT, immutable:
95037d52b3ae6acf430c8a5c2c49d9ffafe7b47ac8931cf30812115c87bdeba4.

The asset-loss-effect-fixed run executed5 tests/1 failure,1m40s: SALE rejection
and3 modularity cases passed. LOAN reached draft source capture then failed on
PostgreSQL precedence in `body->'evidence'-'receivedAt'`.146 was reserved first
and adds only the necessary parentheses in the143 function, forward-only.

Authored10 WarehouseAssetLossGuardsIT scenarios plus shared fixture: real recovery
while approval waits -> durable stale; raw SQL effect/assignment closure denied;
unknown receipt cost; simultaneous approvals; reject/fresh request; requester
through delegation; revoked replay; scoped/redacted pagination; projection rebuild
with dated telemetry preserved; pending replacement authorization retirement.
These10 cases are NOT verified yet. Current .omo/runtime/asset-loss-evidence-fixed.sh
/ .log still selects only WarehouseAssetLossIT2 + ModularityTests3 to verify the
complete effect first. Archive task28/asset-loss-evidence-fixed/xml, private DB log
asset-loss-evidence-fixed-database.log. No146 apply or LOAN green claim yet.

After that run succeeds, run all12 asset-loss cases + relevant existing removal,
return disposition/compensation and modularity regressions. Keep task28 OPEN until
this source path and its guards pass. All143–145 bytes immutable;147 next unused
once146 applies. Whole-plan goal active;30–48/F1–2–3–4 remain pending.
Remote checkpoint command uses explicit local key (see previous note).

## Task28 active-loan loss effect checkpoint — validation pending

143 applied 22:34:18.972 JKT and is immutable:
d613a31245fe294a38113ff109e3fb52b4467d348f2d6d642cae02bc23f57936.
Request run: 5 tests, 2 failures, 1m56s. Both fixtures first stopped before the new
API because signature replacement lacked correctionReason. Fixed fixture signed
assessment revision then ran5 tests/2 failures/1m50s: LOAN reached new document
insert and exposed a source FK error (handover ID differs from its posting document
ID); SALE material-summary helper was not appropriate after accepted sale. The
request now resolves the actual acceptance operation ID;144 fixes the immutable143
assertion forward. Fixture reads WO revision directly and does not manufacture stock.

144 was reserved BEFORE creation and now implements the ASSET_LOSS approved effect:
LOSS policy / one LOSS movement / exact paired CUSTOMER_INSTALLED -> LOST legs,
original title and quantity retained, one DISPOSED event and immutable approval
source. It seals current source and closes the existing assignment. Customer and
fulfillment implement public inventory ports for atomic episode retirement and
provisioning outbox; original deployment/handover/obligation rows stay intact.
New loss-linked ONU event/retirement records preserve customer history. Unconsumed
permits depending on the lost assignment are retired and cannot later be consumed.
Existing removal and return validators remain intact; historical title/deployment
validation accepts only a complete sealed loss effect. RLS, old/new deferred routes,
replay, current source checks and exact ledger projection reconciliation apply.

Current .omo/runtime/asset-loss-effect.sh / .log selects WarehouseAssetLossIT (2)
and ModularityTests (3), expected5. Archive task28/asset-loss-effect/xml; private
DB log asset-loss-effect-database.log. No144 apply or green effect claimed yet.
Check execution before editing SQL. Once applied144 is immutable;145 next unused.
Next add stale/recovery/title, self/delegate, competing approvals, direct-SQL
forgery, scope/replay, unknown cost, old permits and rebuild guards; then task28
completion evidence. Task28 and whole-plan remain OPEN;30–48/F1–F4 pending.

Push f88ba8a5 initially failed public-key authentication. Explicit local key works:
env -u GIT_SSH_COMMAND -u GIT_SSH git -c core.sshCommand='ssh -i /home/fajar/.ssh/id_ed25519 -o IdentitiesOnly=yes -o BatchMode=yes' push origin HEAD:refs/heads/feat/warehouse-workorder
Confirmed f88ba8a5 published. Do not print private key/env credentials.

## Task28 active-loan loss draft checkpoint — validation pending

Added InventoryAssetLossApi, request/get/scoped-list at /api/v1/warehouse/asset-losses,
WarehouseAssetLossService/Store/models and HTTP error mapping. Request binds an
accepted LOAN handover, active ISP assignment, exact physical identity/position,
assignment/title/WO revisions, current evidence object and original receipt cost.
Replay preserves the original draft after current authority/location/cutover checks.
No customer name, address, evidence object key or cost appears in the public view.

V175.143 was reserved before creation. inventory_asset_loss_request is forced RLS
and append-only, captures actual assignment/asset/segment/balance/handover/customer
installation/ONU revision/evidence/WO/cost rows, and seals one DRAFT0 ASSET_LOSS
header/line with no posting. Do NOT assume143 applied until the run log confirms.
All migrations through142 remain immutable. Next SQL must use144 after143 applies.

Current .omo/runtime/asset-loss-request.sh / .log selects WarehouseAssetLossIT
(LOAN full approval journey, SALE rejection) and ModularityTests (3), expected5.
Archive task28/asset-loss-request/xml, private DB log asset-loss-request-database.log.
The approval owner/effect/episode retirement are not implemented yet, so the LOAN
journey is expected to stop after draft/replay/read at approval. Inspect actual
execution and migration state before editing SQL or claiming test results.

Next: ASSET_LOSS maps LOSS policy and posts exactly one approved LOSS from customer
custody to LOST, closes assignment and retires its customer episode in the SAME
transaction, persists recovery closure and a provisioning outbox. Revalidate all
captured source revisions; stale/rejected decisions cannot move stock. Extend
historical deployment/title validation forward to accept the sealed loss closure,
without fake removal/return records. Add independent/delegated, stale/recovery,
concurrent, SQL-forgery, replay/scope, rebuild and title/customer-property guards.
Task28 and whole-plan remain OPEN. Prior6 actual reuse cases and30 combined cases
are GREEN and portable evidence was published at04ffd8ef (product567a3113).

## Task28 asset compensation VERIFIED; active loan loss remains open

The compensation-asset run against f87b1f21 passed 6 tests / 3 suites, zero
failures/errors/skips, 2m49s. Both LOSS and SCRAP assets were actually recovered,
compensated, reset/inspected, issued and installed to a different customer. An old
correction using the CURRENT return revision was denied; committed replay stayed
nonphysical and all old/new assignment and ONU histories remained intact.
The existing plain asset reuse test and 3 modularity tests also passed.
Portable evidence: .omo/evidence/warehouse-workorder-asset-provenance/task28/asset-compensation-verification.json.
Owned QA stopped; volumes retained. Product main remains 567a3113. All work through
f87b1f21 was pushed to origin/feat/warehouse-workorder.

Scope assessment: task28 needs an approved LOSS path for an unrecovered ISP loan,
not only inspected RETURN dispositions. Current active assignments remain recoverable
and cannot be written off. Implement a separate document-bound ASSET_LOSS request
and LOSS-policy approval, preserving original deployment/handover records and title,
retiring the assignment and customer episode atomically with a single LOSS posting.
Require actual asset/assignment/title/WO/source revisions and evidence; exclude SALE
customer property, independent self/delegate approval, no fake physical removal or
return intake. Recheck changed installation/title/recovery state at decision time.
Expose recovery closure from approved loss without changing the original obligation.
143 is next unused; reserve before creation. All migrations through142 immutable.
Task28 and whole-plan goal remain OPEN; 30–48/F1–F4 still pending.

## Task28 return disposition and compensation VERIFIED — asset reuse test running

The compensation-guards run against567a3113 product source passed30 tests/6 suites,
0 failures/errors/skips,4m54s. All20 earlier disposition/modularity cases plus2
LOSS/SCRAP compensation/reinspection journeys and8 compensation guards passed.
142 applied22:19:08.476 JKT and is IMMUTABLE:
f792dc3f6d64f6190dac9b575e5c74e66022a6fc41ae4922f8a12aeeb4cff83a.
Owned resources stopped with volumes retained. Portable sanitized evidence:
.omo/evidence/warehouse-workorder-asset-provenance/task28/compensation-verification.json.
It records all30 names/counts, XML digests, source main-tree and138–142 checksums.

Compensation now restores exactly one whole piece to Q, reopens its outstanding
return obligation, requires fresh accepted inspection before availability, preserves
original posting/history, and produces one linked REVERSAL. Closed material
settlement, closure while approval waits, competing/rejected corrections, direct
SQL pending effects, available-bin restoration and revoked replay all behave as
required; current projections rebuild correctly.

New WarehouseCompensationAssetIT has2 actual LOAN LOSS/SCRAP recovery -> approved
compensation -> reset/inspection -> normal issue -> different customer installation
journeys. It then uses CURRENT return revision to attempt another correction of
the old disposition, expects SOURCE_NOT_VERIFIED409, and checks the new installation
and old episode remain intact. Original correction replay must stay nonphysical.
These2 new tests were not in the30-case run and have not passed yet.
Current .omo/runtime/compensation-asset.sh / .log selects those2, the existing
WarehouseReturnITReuse (1), and ModularityTests (3): expected6. Archive
.omo/evidence/warehouse-workorder-asset-provenance/task28/compensation-asset/xml;
DB log compensation-asset-database.log. Product main is unchanged since567a3113.

Task28 stays OPEN until the remaining acceptance and source-scope assessment are
finished. Assess the plan's loan-obligation approved-loss requirement before
claiming complete: currently disposition accepts inspected RETURNs, and compensation
accepts their exact loss/scrap movements. It cannot yet write off an unrecovered
active customer loan or arbitrary issued/warehouse/vendor stock.143 is next unused
SQL version; reserve before creation. All SQL through142 is applied immutable.
Whole-plan goal remains active;30–48/F1–F4 remain. No main merge/deploy/reset.

## Task28 compensation outbox checkpoint — 30-case regression pending

Published6cca7fd1 compensation-effect ran5 tests/2 suites,2 failures,0 errors/skips,
2m5s.141 applied22:16:00.258 JKT and is IMMUTABLE:
636454e183295ddb3e433cb7a2e1e5056a4881f8dacafc450a12d7ab4e8baac0.

Both real reversal requests and approval requests passed, and requester self-decision
was denied. Checker posting rolled back at inventory_outbox_event_kind_check:
the new DISPOSITION_REVERSED event needed a forward enum-constraint extension.
142 reserved before creation and adds only that event, preserving the existing
outbox check.141/140 and earlier SQL are untouched.

Added8 WarehouseCompensationGuardsIT cases: closed settlement before request;
closure after approval request -> durable STALE; concurrent corrections -> one
reversal; rejected source immutable/fresh request; available-bin destination denied;
revoked scope denies committed replay; raw SQL pending effect denied; rebuild
preserves exactly one restored piece and original posting. These are authored,
not yet verified. Specific installed/reused-asset compensation and broader loss
scope remain outstanding, along with task28 completion evidence.

Current .omo/runtime/compensation-guards.sh / .log selects WarehouseCompensation*IT,
WarehouseDisposition*IT and ModularityTests (expected30 tests/6 suites). Archive
.omo/evidence/warehouse-workorder-asset-provenance/task28/compensation-guards/xml;
DB log compensation-guards-database.log. Inspect execution before claiming142
applied or any compensation committed. Initial20-green disposition evidence remains
portable at adb4ca79; task28 remains OPEN. Next source change must preserve current
validation identity; no DB reset or applied-migration edits.

## Task28 compensation effect checkpoint — 141 authored, validation pending

Published base27ec2709 compensation-request actually executed5 tests/2 suites,
2 failures,0 errors/skips,1m59s. Both LOSS/SCRAP compensation draft201 and exact
replay succeeded; both stopped at approval/request because owner was missing.
140 applied22:10:14.007 JKT and is IMMUTABLE:
ebbc1297ff12996825eac5601ec7367b282e8f564b25eacd9eaaa35343f0fd4e.

This checkpoint adds WarehouseCompensationAdmission/Owner/EffectStore. It maps
DISPOSITION_REVERSAL to ADJUSTMENT policy, binds the compensation snapshot into
approval source, rechecks original source/current sink/WO/material/asset revisions,
and posts one REVERSAL linked to originalPostingId. A matching single
DISPOSITION_REVERSED outbox event is wired in PostingDocuments and approval event
lookup. The nonphysical warehouse.return.restore step advances only return history
back to RECEIVED_IN_INSPECTION/QUARANTINE. Old posting stays POSTED1; no original
ledger rewrite. Generic rework requires a fresh compensation request.

141 was reserved BEFORE creation. It captures approved live source, enforces one
compensation per original movement, exact paired legs/approval/operation/outbox and
return transition, and routes old/new row changes through deferred guards. It
extends140 DRAFT lifecycle and existing return validators forward. Existing139
settled-return calculation already excludes restored Q until fresh accepted
inspection. Current ledger guard allows later legitimate reinspection/reuse.

Current .omo/runtime/compensation-effect.sh / .log selects WarehouseCompensationIT
(2 full reversal/reinspection journeys) and ModularityTests (3). Archive
.omo/evidence/warehouse-workorder-asset-provenance/task28/compensation-effect/xml;
DB log compensation-effect-database.log. No141 successful apply or test result
claimed yet; check logs before modifying SQL. Never edit140 or older applied SQL.
Next add closed-before/after-request, competing/rejected/duplicate corrections,
revoked scopes, raw SQL bypass, rebuild and reused installed asset rejection.
Task28/whole-plan goal remain active; vendor/outstanding loss scope still open.

## Task28 compensation draft implementation checkpoint — validation pending

The published20-green initial return-disposition evidence is adb4ca79 (product
18447663). Overall task28/whole-plan goal remain open. New work in this checkpoint:
InventoryCompensationApi, typed input/view/context/record, WarehouseCompensationService,
WarehouseCompensationStore and controller. POST/GET/list are under
/api/v1/warehouse/dispositions/{dispositionId}/compensations; GET detail adds/{id}.

Request requires original POSTED1 LOSS/SCRAP, its actual APPLIED movement and
linked latest return revision, whole remaining ISP sink position, no prior linked
compensation, no active assignment/reservation, and an open material lifecycle.
WO is locked through the public port before topology/return/stock. The new
source captures WO/asset/material revisions; destination is quarantine only.
Immutable actor/key replay returns original draft after current access checks.
List authorizes original locations before querying and filters new quarantine
destination scope before pagination; DTOs expose no cost/customer fields.
WarehouseReturnStore.position gains explicit status parameter default QUARANTINE,
with compensation passing LOST/DISPOSED. Existing callers retain Q behavior.

140 was reserved before creation. inventory_compensation_request has forced RLS,
append-only snapshots, actual original effect/ledger/return/material source capture,
and exact DRAFT0 header/line/no-posting seal. DISPOSITION_REVERSAL is admitted as
new document kind. New SQL is not yet known applied: inspect current run before
changing it;139 and older remain immutable. No compensation approval owner or
physical effect is implemented yet; 141 or later must extend lifecycle forward.

Current .omo/runtime/compensation-request.sh / .log selects WarehouseCompensationIT
(2 real LOSS/SCRAP reversal journeys) + ModularityTests (3). Archive is
.omo/evidence/warehouse-workorder-asset-provenance/task28/compensation-request/xml,
database log compensation-request-database.log. Expect new request to progress
to approval/request missing owner; do not call these5 tests green without logs.
No changes to main/deploy; checkpoints push HEAD:refs/heads/feat/warehouse-workorder.

Next effect: add ApprovalPostingKind.DISPOSITION_REVERSAL, map policy to ADJUSTMENT
without taking existing ADJUSTMENT owner from transfer discrepancy. Seal approval
source with compensation record; revalidate current sink and material revision
before decision and posting. One new REVERSAL movement compensates original ID,
restores QUARANTINE/QUARANTINE at target, preserves owner/quantity and original
posting. Exactly one explicit matching outbox kind; update both PostingDocuments
default kind and WarehouseApprovalStore.event selection. Add linked nonphysical
warehouse.return.restore step, original return history validators, new effect
capture/exact deferred guards and old/new table routing. Original139 effect already
allows later return revision and uses generic current ledger reconciliation.
Reject closed settlement, duplicate reversal, downstream reused/installed/consumed
state. Reinspection after restoration must be required before availability and
returned residual re-settlement. Task28 also needs broader loss scope assessment.

## Task28 initial return LOSS/SCRAP VERIFIED — compensation remains open

The disposition-verified run against18447663 product source completed20 tests/4
suites,0 failures/errors/skips,2m42s. All2 residual LOSS/SCRAP settlement journeys,
11 source/permission/concurrency/replay/integrity guards,4 serialized LOAN/SALE
cases, and3 modularity checks passed. Owned QA cleanup completed with volumes
retained. Portable sanitized evidence is committed at
.omo/evidence/warehouse-workorder-asset-provenance/task28/return-disposition-verification.json.
It contains every test name/count, XML digest, product main-tree identity and
138/139 checksums; raw private XML/logs remain excluded from commits.

Verified behavior: independent posting moves the exact quantity once, preserves
original returned quantity and old customer assignment history, and closes the
returned residual obligation without new physical postings on WO settlement.
Customer title, bad quantities, unknown cost, currency mismatch, direct SQL fake
effects, requester delegation, revoked scope and stale sources reject correctly.
Competing approvals produce one effect; both MM and serialized projections rebuild.
All10 old return/approval regressions also passed in preceding28-case mixed run.

Task28 is still OPEN. WarehouseCompensationIT has2 authored but unexecuted cases;
its proposed endpoint is not implemented. Next: a new DISPOSITION_REVERSAL source
kind with ADJUSTMENT policy, actual original movement linkage, independent approval,
current disposed position and closed-material lifecycle checks, and one paired
REVERSAL restoring QUARANTINE plus a nonphysical return-history step. Preserve
original posting; reject duplicate/reused/installed/consumed rollback. Broader
vendor/outstanding loss paths still need assessment.140 is available but NOT yet
reserved/created.138 and139 remain immutable. Overall goal continues beyond28.

## Task28 exact outbox/error contract checkpoint — 20-case rerun pending

The disposition-guards combined run against dac2434b completed28 tests/8 suites,
12 failures,0 errors/skips,6m18s. All10 return/delegation/expiry regressions and
3 modularity checks passed. New disposition source-stale, unknown-cost and scoped
paging checks passed. Failed positive posts raised
DISPOSITION_EXACT_APPROVED_POSTING_REQUIRED because PostingDocuments generated a
default DISPATCHED event in addition to supplied DISPOSED. Its new LOSS/SCRAP
mapping now derives DISPOSED, retaining the existing exactly-one-event DB guard.
No139 SQL changes;139 remains applied immutable.

Customer-owner and invalid-quantity requests were rejected in the service but
escaped as ServletException: the new controller was missing from WarehouseHttpErrors
assignableTypes. Added it, preserving existing error/status contracts. The new
delegation test failed while creating the grant (INDEPENDENT_APPROVER_REQUIRED):
its delegator was not configured in policy. Corrected fixture policy to include
requester + independent checker before requesting approval, then delegates after
request to test actual decision-time requester exclusion.

Added2 source-control guards (USD vs IDR policy and raw SQL pending-approval effect)
and docs/warehouse-dispositions.md with current supported workflow/limits. Current
.omo/runtime/disposition-verified.sh / .log selects only WarehouseDisposition*IT
and ModularityTests: expected20 tests (2 residual,11 guards,4 asset,3 modularity).
Archive task28/disposition-verified/xml, DB log disposition-verified-database.log.
Results pending; do not claim committed disposal until this run passes.

WarehouseCompensationIT is separately authored (2 LOSS/SCRAP cases) and NOT in that
run. It expects POST /dispositions/{id}/compensations, then independent ADJUSTMENT
approval of a new document, one REVERSAL linked to the original movement, restored
QUARANTINE, outstanding17.5m until fresh inspection, original history unchanged,
replay/duplicate protection. No compensation implementation or140 migration exists.
Use140 onward for new SQL; reserve before creation. Closed-settlement and reused
asset reversal guards still needed. Task28 remains OPEN and whole-plan goal active.

## Task28 approval event and asset coverage checkpoint — regression running

Published base0daab7e3 preserves applied139 (SHAa9a1f5567678d9b4841bad38b3f2fd79f4256efeeb65b8563fe442923de2e489).
The disposition-costed run executed5 tests/2 suites,2 failures,0 errors/skips,
2m12s. Costed draft and independent approval request passed; requester self-decide
returned403 as expected. Both actual checker decisions reached posting but the
transaction rolled back before commit: WarehouseApprovalStore.event selected only
older event kinds and threw NoSuchElementException for DISPOSED. Added that exact
event to its lookup; no SQL changes and no fallback success.

Added4 actual serialized-device cases: LOAN LOSS/SCRAP retain closed assignment
history and survive balance rebuild; SALE LOSS/SCRAP must reject customer-title
writeoff without creating a request. Shared serial receipt fixture now also uses
the optional actual-cost hook, default unknown for all prior fixture users.

Current .omo/runtime/disposition-guards.sh / .log runs WarehouseDisposition*IT,
WarehouseReturnSettlementIT, WarehouseReturnITIntegrity, WarehouseApprovalITDelegation,
WarehouseApprovalITExpiry and ModularityTests. Archive task28/disposition-guards/xml;
database log disposition-guards-database.log. Results pending; do not claim a
committed loss/scrap effect until this run proves it.13 new guard/asset cases are
included with the2 full residual settlement cases and affected regressions.

Next complete missing compensation as a NEW linked approved movement restoring
only quarantine, with a source-bound return transition and open/closed obligation
checks. Do not duplicate ADJUSTMENT owner (transfer discrepancy owns that kind).
Active/reused/consumed downstream state must reject implicit rollback. Vendor and
other outstanding loss paths remain to assess before task28 can be marked done.

## Task28 source-cost checkpoint — 139 applied, posting validation pending

139 is now APPLIED and IMMUTABLE. SHA256:
a9a1f5567678d9b4841bad38b3f2fd79f4256efeeb65b8563fe442923de2e489.
The first effect run failed42601 from ERaRCODE at SQL187 (4 tests,1 failure,
1m7s); Flyway rolled back21:46:37.717 JKT. The next run failed the guarded
return terminal-state anchor:117 had inserted DRAFT handling (4 tests,1 failure);
Flyway rolled back21:48:04.973 JKT. Both fixes preceded139's FIRST successful
application at21:49:43.056 JKT. Never edit139 or earlier applied migrations again.

The disposition-ledger run executed5 tests/2 suites,2 failures,0 errors/skips,
1m53s. Both real LOSS and SCRAP request201/replay paths passed. Approval request
correctly returned COST_BASIS_REQUIRED because the shared material fixture had
no receipt cost. No physical disposition has passed yet;3 modularity tests passed.

Added a shared fixture cost hook (default unknown preserves prior behavior),
WarehouseDispositionFixture with declared actual source receipt cost, and9 new
behavioral guards: unknown cost, bad quantities, changed inspection, rejection
and fresh request, competing approvals, requester delegation, revoked scope,
scoped paging and posted MM rewrite/rebuild. Guards are authored, not yet run.

Current validation .omo/runtime/disposition-costed.sh / .log selects the2 full
LOSS/SCRAP settlement cases plus3 modularity checks. Archive task28/disposition-costed/xml.
After it passes run WarehouseDispositionGuardsIT and affected return/approval
regressions; add forward migration140 if runtime invariants need correction.
Compensation, returned asset loss/scrap and remaining task28 acceptance still open.

## Task28 approved physical effect checkpoint — validation running

Supersedes the older draft-only status below. Published base7944bb50 includes
138; disposition-request actually ran4 tests/2 suites,1 failure,0 errors/skips
in1m46s. POST disposition201 and exact replay passed; the failure was missing
approval source owner at test line43. Flyway138 applied21:34:36.248 JKT and is
IMMUTABLE:34a2b183f2a4f1395981b5efdf5e14d3033ca9f7121b767c9d3f2ab52c74ea3b.

This checkpoint adds independent LOSS/SCRAP owners, admission revalidation,
approval-kind wiring, one paired physical posting and a linked nonphysical
warehouse.return.dispose operation.139 was reserved before creation. Its new
immutable effect captures the live source, binds both operations/approval/legs,
validates EA and MM current positions against applied ledger, extends existing
return histories/lifecycle forward, and counts approved disposal as settlement
of the original returned residual without counting another issued disposition.

The initial verification is .omo/runtime/disposition-effect.sh / .log; owned
private archive task28/disposition-effect/xml and disposition-effect-database.log.
It selects WarehouseDispositionIT + ModularityTests. No result or successful139
application is claimed yet; check logs before changing this SQL. Earlier138 and
older migrations must never change. Task28 stays OPEN; loss/compensation and
adversarial/scoping/concurrency/rebuild checks remain. Task26 remains COMPLETE
with its published53-test22-suite portable evidence at7c6eb6e5.

## Task28 draft request implementation checkpoint — validation pending

Task26 completion checkpoint7c6eb6e5 is published with53 green tests/22 suites and
sanitized portable evidence. Overall goal remains active. Task28 is NOT complete.

The real initial disposition-red baseline executed1 test/1 failure/0 errors/skips,
1m32s. All actual receipt/use/residual return/inspection/policy setup passed; the
new POST /api/v1/warehouse/dispositions returned404 at test line37. Archive:
.omo/evidence/warehouse-workorder-asset-provenance/task28/disposition-red/xml.

Authored InventoryDispositionApi, typed LOSS/SCRAP input/view, controller/service,
WarehouseDispositionStore/Record. Initial request accepts exact ISP-owned RETURN
quantity in RECEIVED_IN_INSPECTION; SCRAP additionally requires DAMAGED. It locks
WO through the public inventory-owned port before warehouse topology/documents
and physical source, captures actual receipt/lot cost, checks custody/return and
approval-request permissions, and stores immutable actor/key/source snapshots.
Read/list expose no cost or customer fields; current location/area/site scope is
applied before pagination. No physical posting or approval owner exists yet.

138 was reserved BEFORE creation in docs/warehouse-migrations.md. It captures the
real return operation, current balances/asset/segment, WO revision and original
cost, and seals a DRAFT0 header/line with NO movements/operations. Draft-only
restriction must be extended forward when implementing real independent effects.

First disposition-draft compile succeeded;4 tests/2 suites/1 failure/0 errors/skips,
59s:3 modularity passed,1 context startup failed. Flyway138 failed42601 near CASE
inside the large IF at SQL line29 (position5663), and logged at21:32:45.351 JKT
"Changes successfully rolled back". No138 apply succeeded. Added parentheses to
that CASE before successful application; old137 and earlier files unchanged.
Minor get/list state handling was also tightened before the corrected build.

Corrected run is .omo/runtime/disposition-request.sh / .log, archive
.omo/evidence/warehouse-workorder-asset-provenance/task28/disposition-request/xml,
database log disposition-request-database.log. It selects WarehouseDispositionIT
and ModularityTests. Confirm migration application before treating138 immutable.
The behavioral test should next reach approval/request; that owner and posting
are deliberately unfinished, so do not call4 tests green without actual results.

Next: implement independent LOSS/SCRAP owner + exact effect, nonphysical linked
return state transition, and settled-return calculation for authorized disposal.
Then add loss/vendor custody, compensation, and adversarial/scoping/replay cases.
Detailed design constraints follow below; no resetting DB or changing applied SQL.

## Task26 COMPLETE —53 combined tests passed

return-combined-check passed53 tests/22 suites/0 failures/errors/skips,
6m47s, against product source c050efeb. All return, recovered-loan reuse, original
customer RMA/reacquisition, source/title integrity, independent approval, scope
revocation, vendor repair/replacement, inspection/rebuild and pagination cases
passed together. Separate ordinary receipt inspection regression passed10 tests
in4m44s. Product source has not changed since that successful compilation.

Sanitized portable evidence is committed at
.omo/evidence/warehouse-workorder-asset-provenance/task26/verification.json:
actual suite/case names, nonzero counts, XML digests, main tree identity and131–137
SQL digests. Raw XML/logs remain private because they may contain HTTP tokens.
All applied migrations remain immutable; no QA volumes were reset. Task26's plan
row is now checked. Original-device CUSTOMER RMA is supported; distinct customer
replacement remains quarantined and cannot borrow the old device's permit.

Continue task28 (not complete): real17.5m scrap scenario and design notes are in
task-28.md / WarehouseDispositionIT. Its queued disposition-red run starts after
combined QA cleanup. Record its actual failure, then implement the document,
independent approval, exact posting, return transition and obligation settlement.
No task28 migration is reserved yet. Tasks28,30–48,F1–F4 remain open; the overall
goal is still active, not achieved. Commit and push each coherent checkpoint.

## Task28 behavioral checkpoint; task26 combined checks still running

Task26 compiled product source c050efeb is under return-combined-check. RMA
acceptance/deployment/guards/handover/reacquisition have passed so far; the full
suite is still pending, not a completed gate. No new SQL has been reserved.

Task28 adds WarehouseDispositionIT and task-28.md. The exact17.5m damaged residual
scenario is authored; .omo/runtime/disposition-red.sh is queued under the same
QA lock after task26. It selects only WarehouseDispositionIT, archive
.omo/evidence/warehouse-workorder-asset-provenance/task28/disposition-red/xml.
No disposition endpoint exists yet. Expected initial failure is POST404 after
real return+inspection setup. Do not call this task28 implementation or success.
Continue with its owner/approval/effect/settlement implementation after recording
that actual baseline; retain all task26 and137 immutability checks.

## Combined return regression restarted after pagination compile correction

Published f35e8621 contains the inspected replacement flow and scoped-list change.
First combined run stopped at main compilation (13s, no executed tests): Jackson3
JsonNode.map resolved to its member instead of Kotlin collection map. Corrected
with asSequence().map(...).toList(). No SQL changed;137 remains immutable. The
failed return-combined-regression/xml may contain copied stale receipt results;
DO NOT use them as combined evidence.

Corrected run: .omo/runtime/return-combined-check.sh / .log,
archive task26/return-combined-check/xml and return-combined-check-database.log.
Main compilation has passed; test compilation/execution remains pending.

Task28 preparation only: WarehouseDispositionIT now describes a real17500MM
damaged residual -> independent SCRAP approval -> exact disposed sink and WO
settlement. No disposition endpoint exists yet, so that test has not passed.
It is not selected by the combined task26 run. Do not infer task28 complete.

## Supplier inspection verified; scoped pagination under combined regression

Published parent checkpoint11c3b518.
return-replacement-inspection-green passed10 tests/4 suites/0 failures/errors/skips,
4m44s: replacement LOAN/SALE inspection and putaway/rebuild2, ordinary receipt
11-line disposition1, receipt inspection guards4, modularity3. Evidence archive:
task26/return-replacement-inspection-green/xml. Real private MinIO attachment used.
Receipt inspection derives expected owner from the immutable vendor replacement
request; ISP putaway remains mandatory. CUSTOMER inspection succeeds but stays
QUARANTINE, ISP inspection+putaway becomes AVAILABLE/SERVICEABLE. Both preserve
origin/old vendor custody and rebuild without extra movement.

Additional review found replacement list filtering after LIMIT. It now reuses
WarehouseQuerySql visible_locations (location/area/site ancestry) before pagination,
returning only public link views. One new guard puts a hidden draft before a
visible draft at size1. This pagination change is NOT yet verified.

Combined run queued/starting: .omo/runtime/return-combined-regression.sh and .log,
archive task26/return-combined-regression/xml, database log return-combined-database.log.
It selects WarehouseReturn*, WarehouseSupplierRepair*, WarehouseCustomerRma*,
WarehouseSupplierReplacement* and ModularityTests under the host QA lock. Confirm
nonzero full counts and zero failures/skips before marking26 complete.137 remains
immutable; this checkpoint contains no SQL edits. Full new-device CUSTOMER RMA is
not inferred from same-asset RMA permits. Task28 explicitly handles old-device
loss/scrap; receipt of a replacement does not dispose the old physical device.

##137 verified supplier replacement policy and current position

Published checkpoint a0d5f17b contains136. It applied at21:05:46.921 JKT;
20 tests/5 suites/7 failures/0 errors/skips,4m35s. All7 failures were filled-cost
requests rejected before admission: JSON operator precedence interpreted `cost`
as JSON. Null-cost requests and existing receipt/approval/modularity passed.
136 is IMMUTABLE, SHA2569c1eb477eca82db04b769074e0f60d400637ea724953593515478594b0b5cb50.

Forward137 fixes only that cost expression and connects replacement current
asset/balance/movement dimensions to the existing APPLIED-ledger validator.
return-replacement-policy-green passed10 tests/2 suites/0 failures/errors/skips,
2m53s: supplier guards8 plus LOAN/SALE admission2. Actual approval+replay, unknown
cost, stale source/direct and approval, rejection/fresh request, concurrent drafts,
revoked-scope replay and raw CUSTOMER->ISP asset/balance rewrite all pass.
137 is now APPLIED AND IMMUTABLE, SHA256847e1c48684fba0f9d59bb987d3b682875c2e2023e78e10d45b46caf2a1502c3.
Evidence archive: task26/return-replacement-policy-green/xml; private matching
runtime .log and return-replacement-policy-database.log. No DB reset.

Next validation queued under the same host lock: return-replacement-inspection-green,
new2 LOAN/SALE inspection/putaway/rebuild cases, ordinary receipt disposition and
inspection guards, modularity. Its two source files remain a separate uncommitted
change: receipt inspection derives CUSTOMER title only from verified replacement;
normal putaway still requires ISP. Do not claim these tests have passed yet.
Task26 remains open pending this and combined return regressions. Task28 remains
open for loss/scrap/compensation, including explicit old-vendor disposition; do not
silently close old repair when a distinct replacement arrives.

##136 declared cost/rejection and8 supplier guards — first validation running

135 is APPLIED AND IMMUTABLE, SHA256: `ab998db9effece220beed9b979164b54aee915531ec81a3c9bc4f722ba1b2abd`.
The corrected run passed12 tests/4 suites/0 failures/errors/skips,1m45s:
LOAN/SALE vendor replacement2, ordinary receipt1, approval guards6, modularity3.
New replacement asset is distinct, real same-vendor RECEIPT, owner ISP/CUSTOMER
as captured, QUARANTINE; old asset remains vendor custody. Archive
return-replacement-posting-green/xml. Published25ebf655 includes the pre-apply
SQL delimiter correction; failed135 attempt fully rolled back, then correct apply.

136 reserved BEFORE creation and now authored. Optional SupplierReplacementCost
(totalMinor,currency) feeds normal receipt cost input. Null cost is omitted from
new input serialization so old134 canonical replay is preserved; no zero invented.
136 captures exact vendor-declared cost/denominator1 and freezes document-line
cost against intake. It supports DRAFT1/REWORK_REQUIRED only with matching rejected
approval; generic rework returns controlled409 directing a new immutable request.

New WarehouseSupplierReplacementFixture/GuardsIT adds8 cases: actual customer
replacement approval+cost+replay; unknown cost policy rejection; competing drafts
one new asset; direct stale source; durable approval stale+replay; rejection then
fresh request; current scope on receive replay; and SQL unposted CUSTOMER->ISP
asset/balance rewrite. That last case deliberately tests possible missing current
physical-ledger binding for a replacement with no installation history. If it
exposes a gap, use existing warehouse_assert_recovered_position (120) and final
asset/balance routing in a NEW forward version after136 applies; never weaken it.

Current `.omo/runtime/return-replacement-guards-green.sh` / matching log and
return-replacement-guards-database.log, archive return-replacement-guards-green/xml,
session96350 runs the8 new cases + prior12. Read log for actual compile and136
apply status; after successful apply136 is immutable. No137 declared/created.
Then finish further tenant/source/immutability checks as justified, replacement
inspection/reset and CUSTOMER original-customer handover/installation, old-vendor
custody disposition, packaged HTTP and remaining plan. Task26 still OPEN.

##135 first SQL attempt rolled back; delimiter corrected before any successful apply

return-replacement-admission-green compiled but failed Spring/Flyway startup:
SQLSTATE42601 syntax error near patch$, at135 line126. Log20:57:27.517 JKT explicitly
says changes successfully rolled back (also repeated in later test contexts).
The adjacent dollar tags at END $function$$patch$ accidentally contain the outer
DO $$ terminator. Inserted a newline between the tags.135 had NOT applied, so this
is a correction to an unapplied failed version;134 and all older bytes unchanged.

Current `.omo/runtime/return-replacement-posting-green.sh` / matching log, DB log
return-replacement-posting-database.log, archive return-replacement-posting-green/xml,
session74078 re-runs the12 selected cases. Check log for actual135 success before
any further SQL edits. Last70536553 published; correction follows in new commit.

Further functional gap found during source audit: replacement input currently has
no optional declared cost, while configured RECEIPT policy correctly rejects
unknown cost (COST_BASIS_REQUIRED). Add explicit optional public replacement cost
only from actual vendor evidence, never synthesize zero. Omit null new input field
from serialization to preserve canonical replay of existing134 requests; update
capture validator via a later declared version after135 applies. Then test the
actual replacement approval path, not merely ordinary receipt approval regression.
Rejection/new immutable request lifecycle and receipt source/physical graph guards
also remain pending, followed by inspection and original-customer handover.

##135 replacement admission/effect — first database validation pending

134 is APPLIED AND IMMUTABLE. Corrected initial creation (internal
ReceiptDraftContext/replacementDraft, normal HTTP input unchanged) now passes
LOAN/SALE request201 and replay. That run finished6 tests/3 suites/2 failures/
0 errors/skips,2m40s:3 modularity and normal WarehouseReceiptITReceive pass;
both supplier cases reach receive and are blocked by134's intentional DRAFT-only
REPLACEMENT_REQUEST_RECEIPT_BINDING. Archive return-replacement-draft-green/xml.

135 was reserved before creation. Source now adds one immutable replacement receipt
mapping per repair/receipt/asset/operation, capturing current old-asset/vendor/
return/WO source and checking actual RECEIPT/APPLIED legs/owner/outbox at commit.
Direct receipt and approval receipt use the same typed binding; owner defaults ISP
only for ordinary receipts. The new asset has real vendor RECEIPT provenance;
old physical asset/assignment are preserved. Receipt operation ID is generated
before admission to bind the deferred mapping to its actual operation. Approval
source includes replacement snapshot. SupplierReplacementAdmission locks original
WO before warehouse locks; current source is checked with receipt/return/asset
held before any admission or final approval decision. Authority/view replay still
checks current WO/locations.

First return-replacement-receipt-green attempt failed production compilation in14s
because AssetHandoverWorkOrderPort import pointed at port/outbound instead of the
public inventory package. Import fixed; no tests or135 application occurred in
that attempt (copied XML is stale). Corrected current run:
`.omo/runtime/return-replacement-admission-green.sh`, matching log/DB log,
archive task26/return-replacement-admission-green/xml, session28075. It compiled
both source sets and is running2 supplier,3 modularity,1 normal receipt and6
ordinary approval tests. Read log for135 apply/result; after successful apply
135 bytes are immutable. No136 reserved or created.

Still needed: real replacement approval path, competing distinct drafts/receives,
source mutation/scope/tenant/replay/SQL graph tests, controlled rejection/new-request
flow (135 presently supports only DRAFT0 and received receipt; generic replacement
rework is not implemented), replacement inspection/reset, CUSTOMER original-
customer custody/reinstallation, and explicit old-vendor disposition/history.
Then packaged HTTP proof and the rest of the plan. Task26 remains OPEN.

##134 supplier replacement request/source draft — first validation running

The supplier baseline compiled and ran2 tests/1 suite,2 failures/0 errors/skips,
1m33s. Both LOAN and SALE reached missing POST replacement-receipts404 after real
receipt/install/handover/removal/intake/inspection/vendor dispatch. Archive
`task26/return-replacement-red/xml`; no fake physical seed or invented provenance.

Source now implements InventorySupplierReplacementApi POST/GET
returns/{id}/replacement-receipts, strict request, original WO/return/asset locks,
current locations, tenant/actor/hash replay, a real nested RECEIPT draft with an
internal key based on new request UUID, and original customer/WO/owner binding.
New134 was reserved BEFORE creation and captures/seals actual vendor custody,
closed original assignment, repair case, prior return and normal receipt intake.
134 deliberately allows only DRAFT0 with no physical receipt effect; admission,
one-replacement consumption, subsequent inspection and handover remain pending.
CUSTOMER draft line retains CUSTOMER; no change to old asset or old assignment.

Current `.omo/runtime/return-replacement-request-green.sh`, matching log and
`task26/return-replacement-request-green/xml` (session25146), compiles/runs both
supplier cases plus3 modularity cases. Read log for compile/apply status. After
any successful134 apply its bytes are immutable. No135 declared or created.
Expected next missing behavior is physical receipt admission; first fix request
capture/binding if exposed. Last published e5d2cef1 is the fully verified direct
quarantine implementation:13/3/0/0/0,3m41s (6 title,6 approval,1 full history/reset/
replay/rebuild);133 and all earlier migrations immutable.

Next implementation must lock replacement source before topology/receipt locks
for BOTH normal receive and approval receive, validate current source before
approval final decision, choose admission owner only from captured replacement
binding, and seal one receipt/new identity per repair with an immutable effect.
Include replacement binding in approval source snapshot. Normal receipt payloads
and historical snapshot shapes remain unchanged. CUSTOMER inspection/return-to-
original-customer and old vendor custody disposition must remain explicit.
Then finish task26 packaged proof and remaining plan; task26 is still OPEN.

## Direct quarantine reacquisition verified —13 tests green

return-title-final-green passed13 tests/3 suites/0 failures/errors/skips,3m41s:
6 return title guards (changed source, competing approvals, SQL forgery/append-only,
rejection/new request, vendor repair after reacquisition, current scope/replay),
6 existing approval guards and the full direct quarantine -> ISP -> reset release
case. The latter also proves request/decision replay, changed payload conflict,
historical CUSTOMER/CUSTOMER/CUSTOMER/ISP/ISP views and projection rebuild without
new movements. Archive `task26/return-title-final-green/xml`.

133 is APPLIED AND IMMUTABLE, SHA256
`c874e69adf2098a8caf956ebf5918f6154f1b2188979601e45c40d40c0700410`.
132 is immutable (`e38b4f88081afc6c625ec60cb5e5da6fdc4e333f7bf2703e04c4541a19a57f47`).
Previous shared run:15 tests/6 suites/2 failures/0 errors/skips,4m4s. Its2 failures
were test expectations of200 for STALE; existing durable approval contract is409.
Those expectations now pass. Shared supplier repair2, return integrity3,
installed RMA reacquisition1 and modularity3 all passed that run.

Rejected immutable RETURN_TITLE requests now return controlled409 on generic
approval rework; callers create a new request with current evidence. Documented
API and updated test include this behavior. No historical SQL was edited.

Next: actual vendor replacement. New WarehouseSupplierReplacementIT specifies
LOAN/SALE genuine recovered repair -> replacement receipt request -> receive new
physical identity with normal RECEIPT provenance, same vendor/owner and quarantine.
Endpoint POST/GET returns/{id}/replacement-receipts is NOT IMPLEMENTED yet.
The baseline `.omo/runtime/return-replacement-red.sh` / matching log (session45699)
is queued/running after the final green run under the fixed host QA lock.
Read the log; no baseline outcome or new migration134 is claimed. It will use the
retained owned test DB/volumes and expects the new route to expose missing support.

Implement replacement through a real vendor RECEIPT plus immutable repair linkage;
CUSTOMER replacement must not become ISP available stock or acquire a fabricated
old issue/source ID. Preserve original asset/history/vendor custody explicitly.
Then inspection/original-customer return or ISP issue, packaged HTTP proof and
remaining plan. Task26 stays OPEN;28/30–48/F1–F4 remain. No134 declared/created.
Canonical remote is `git@github.com:waduh67/qqweasdjlkasdjkwqeqwe.git`; push only
`git push origin HEAD:refs/heads/feat/warehouse-workorder`.

##132 immutable;133 physical leg revision correction under validation

Checkpoint includes RETURN_TITLE independent approval owner, policy exclusions,
canonical approval source, one CUSTOMER->ISP quarantine posting and immutable
return-title effect/return revision. Current return/repair reads now use the
latest proven owner; original customer assignment remains unchanged.

132 applied and immutable, SHA256 `e38b4f88081afc6c625ec60cb5e5da6fdc4e333f7bf2703e04c4541a19a57f47`.
First run compiled production/tests, passed3 ModularityTests and failed the one
full reacquisition case at approval commit: RETURN_TITLE_EXACT_QUARANTINE_POSTING.
4 tests/2 suites/1 failure/0 errors/skips,1m49s. Evidence
`task26/return-title-effect-green/xml`; private DB log captures13:32:39.697 UTC.
The comparison incorrectly equated revisions of distinct CUSTOMER/ISP balances.
133 is a forward correction excluding only that per-dimension revision.

Current `.omo/runtime/return-title-guards-green.sh` / matching log (session91757)
runs direct reacquisition,5 new stale/race/SQL-integrity/rejection/repair cases,
ModularityTests, return integrity, supplier repair and installed RMA reacquisition.
Read current log before editing133: after any successful apply it is immutable.
No result claimed yet. New5 cases were not previously compiled. Owned QA cleanup
retains volumes; fixed host QA lock remains required. Prior131 request/replay201
and3 modularity cases passed; its only failure was the then-missing approval owner.

Next resolve this run, add current-scope/evidence/exact-history checks as needed,
finish supplier replacement with actual new-asset/vendor-receipt provenance,
packaged proof, then remaining plan. Task26 remains OPEN. Push only
`git push origin HEAD:refs/heads/feat/warehouse-workorder`; origin must remain
`git@github.com:waduh67/qqweasdjlkasdjkwqeqwe.git`.

##130 ten green;131 direct quarantine request is in its first validation run

130 is APPLIED AND IMMUTABLE, SHA256 `77379d190d8291dd7303f06fb4c19693e863b28a9653ba0faa6b8a25f079311b`.
The renewal run passed10 tests in3 suites,0 failures/errors/skips,2m47s:
6 RMA guards including scope restoration/new permit and competing distinct permits,
3 signed acceptance/key/history cases and full independently approved RMA ->
removal/inspection/new-customer normal reissue. Archive
`task26/rma-authorization-renewal-green/xml`.129 earlier passed4/2/0/0/0,2m30s.

Source now includes InventoryReturnReacquisitionApi POST returns/{id}/reacquisition,
strict input, current WO/return/asset/scope locks, canonical replay and a separate
RETURN_TITLE draft request.131 captures real CUSTOMER quarantine position,
original closed assignment/accepted handover and signature evidence, then seals
source/request/document binding. No stock/title posting is enabled in131. The
full direct-return test previously proved404 after a genuine recovered SALE.

Current `.omo/runtime/return-title-request-green.sh`, matching log and archive
`task26/return-title-request-green/xml`, runs that full case plus ModularityTests.
Compilation and first131 apply/status are pending. It should next expose missing
approval owner/policy dispatch; those and the approved posting/return revision
transition are NOT IMPLEMENTED. Read current logs before editing131; after any
successful apply it is immutable. No132 declared or created.

Needed next: implement RETURN_TITLE approval owner and policy exclusions from
requester/receiver/original handover/removal actors; approved CUSTOMER->ISP posting
must remain in QUARANTINE at the same custody/condition, append linked return
revision/operation without editing closed assignment, and require reset inspection
before availability. Adapt return inspection to view.legalOwner and strict return
history validator through explicit approved-title step. Keep one physical posting
and preserve current source/approval/cutover/replay fences. Then vendor replacement,
packaged proof and remaining plan.26 stays OPEN.

##128/129 title continuity; focused runs and missing direct-return route

rma-reacquisition-green completed17 tests/5 suites/2 failures/0 errors/skips,4m29s.
The4 previous shared regressions are repaired:2 acceptance races,2 allowed draft
DELETEs (all5 scope variants passed). Ordinary title approval, all3 RMA acceptance
and4/5 RMA guards passed, including simultaneous RMA installs. Failures:
1. RMA independent approval hits historical ASSET_REMOVAL_HISTORY_POSITION_BINDING.
  129 now uses the existing continued recovery/APPLIED ledger owner proof while
  keeping physical identity and original episode title immutable. See task-26.md.
2. Revoked pending install returns409 STALE_AUTHORITY, matching the existing WO
  epoch contract; the probe incorrectly expected404. Corrected to assert exact
  stale code and zero writes, then require a fresh permit after restoration.
  A unique RMA handover execution constraint may block that legitimate renewal;
  prove with the queued focused test before any source/schema correction.

127/128 are APPLIED AND IMMUTABLE.129 may already apply in the running direct-
quarantine red probe; check current status before edits. Migration bytes:
- V175_127__warehouse_draft_delete_return_row.sql: `1210635a11525370c6e35efcedc919239225a97b776d147907ee632f496eb024`
- V175_128__warehouse_rma_reacquisition_title.sql: `5c57b1faec84685ac29ecd7b09fb070f7645bcfe227aed3c178fc41fbf5c2da8`
- V175_129__warehouse_recovered_title_continuity.sql: `9fc3857eb00db6e7dbd4a237fa2e86e9d9b6b13387beae9999f6969ccb01965d`

Active/queued wrappers under the host QA lock (each same-name log/archive):
- return-reacquisition-red: real SALE recovery/inspection, new direct quarantine
  reacquisition route missing, NOT YET VERIFIED. Request test requires independent
  title approval, preserved old assignment, Q until inspection and replay no effects.
- rma-title-continuity-green: full RMA approval/removal/reissue plus3 physical
  ledger integrity cases (including unposted CUSTOMER->ISP rewrite). Pending.
- rma-authorization-renewal-red: one corrected scope/renewal/replay case. Pending.
Do not infer successful outcomes from stale copied XML on compile failures.

No130 declared. Direct quarantine API and supplier replacement not implemented.
26 and remaining28/30–48/F1–F4 stay open. Current work branch is
work/warehouse-completion; ordinary push to origin feat/warehouse-workorder only.

##126 regression recorded;127 draft deletion correction and RMA reacquisition probe

The completed rma-acceptance-green run has166 tests/5 suites/4 failures/0 errors/
skips,8m28s. RMA3, forward-fix3, provenance31 all passed; ownership72 had2 races
409 vs200, final-state57 had2 allowed draft deletions leave count1. Raw XML is in
task26/rma-acceptance-green/xml. New custody preview routes before locks and runs
DB validation after owner locks; focused simultaneous duplicate acceptance has
now PASSED in rma-reacquisition-red, whose other new RMA reacquisition test is
still running. Read actual result before title corrections; no128 declared.

127 is declared/created, NOT YET APPLIED by current runner (processResources ran
before creation). It preserves warehouse_transfer_binding_guard scope/bound-
transfer rejection but returns OLD for permitted DELETE. Previously RETURN NEW
silently suppressed every non-transfer draft deletion. Do not edit applied126.

The exact retained-pre125 handover now validates as warehouse_app. Its stored
origin still lacks both extension fields, same hash before/after validation;
archive task26/rma-origin-upgrade-green/probe.log.175.126|t confirmed. No claim of
a pre-migration hash (not captured by the original red probe).

Two extra RMA guard tests are UNVERIFIED: unacknowledged custody, wrong customer,
revoked scope before install/replay, and simultaneous duplicate installs. New
RMA reacquisition test is real signed title0 -> independent approval -> removal/
inspection -> normal reissue to a new customer. Checker fixture corrected to the
actual CUSTOMER_INSTALLED location (current compiled red still has earlier field
location, but source-title request precedes any approval decision). Next run should
include127, all4 failed shared cases, RMA3 acceptance and5 custody/install guards.
26 and whole plan remain OPEN; vendor replacement/direct-quarantine reacquisition
and packaged evidence still need completion. See task-26.md newest entries.

##126 applied — RMA acceptance3 and ordinary regression still running

V175.126 has applied in the running integration test and is IMMUTABLE. SHA256
`cd894209c09945e323c4cc80f397f6857bde25d7f066aad5071e28e57bda0e30`. Main/test compilation passed. All3
WarehouseCustomerRmaAcceptanceIT cases passed: signed non-posting acceptance with
CUSTOMER titleRevision0 (including public ownership read), cross-purpose mint-key
conflict409 and history decoding both normal/RMA sources.

Shared `.omo/runtime/rma-acceptance-green.sh` is still running, same-name log and
archive `task26/rma-acceptance-green/xml`. CustomerAssetOwnershipIT simultaneous
acceptance race has FAILED; most other observed cases passed. Read final XML and
response details before fixing. Source audit: new custodyView invokes a multi-read
DB validator BEFORE work-order/assignment serialization; previous preview did not.
Do not remove validation; investigate moving validation after the normal locks.
The read-only retained-pre125 origin validation is queued at script end and will
not run if Gradle fails, so run it separately after the lock if needed. The prior
red probe and fixed exact SQL are under .omo/runtime/rma-origin-upgrade-* and
rma-origin-upgrade-probe.sql.126 changes comparison only, no stored seal writes.

No127 declared. Next real probe: independently approved reacquisition after signed
RMA (source title revision0), then removal/inspection/reissue. Source audit finds
old title-request revision formula and current RMA-owner assumptions may reject it;
prove before forward correction. Also remaining actual vendor replacement and
packaged proof.26 and whole remaining plan stay OPEN. Save/push ordinary checkpoints
on work/warehouse-completion -> origin feat/warehouse-workorder, no main deployment.

##125 shared52 green — signed handover/key/history probes pending

Source567dc234 passed52 tests in3 suites, zero failures/errors/skips,4m47s:
real RMA reinstall, all48 CustomerAssetReplacementIT cases (including races and
SQL integrity) and3 RMA custody guards. Archive task26/rma-deployment-green/xml.
The unmatched CustomerDeploymentIT selector supplied no generic deployment cases;
those actual named suites still need the later shared regression.

Current checkpoint adds3 unverified WarehouseCustomerRmaAcceptanceIT probes:
non-posting signed customer handover, normal-vs-RMA mint-key conflict, and the
public inventory assignment history containing both execution source kinds.
The shared fixture first completes a real RMA installation for each probe.
Command `.omo/runtime/rma-acceptance-red.sh`, matching log, archive
`task26/rma-acceptance-red/xml`, queued after52 green. Read results before editing
production. No126 declared/created. Applied125 and earlier immutable.

API guide docs/warehouse-returns.md now covers actual material settlement and RMA
custody/authorize/install; signed acceptance and remaining vendor replacement/
reacquisition/packaged proof stay explicitly unfinished.26 stays OPEN.

##125 applied — original-customer RMA installation passed, shared run pending

V175.125 is APPLIED AND IMMUTABLE, SHA256 `61ce87ff20a7fcf0fc5eafc85c6618223a781074a853f6d0939b51d98eb5c00a`.
Read-only owner query during this run returned175.124|t and175.125|t. Normal
main/test compilation passed. Core WarehouseCustomerRmaDeploymentIT PASSED:
real acknowledged repair reinstalls the same physical asset for original customer,
CUSTOMER title retained, null issue, two historical assignments/ONU episodes,
one active episode, zero ISP availability and exact install replay.

RmaDeploymentSource is explicit; no fake material receipt/plan/issue IDs. Common
execution stores nullable receipt/plan ONLY for sealed RMA handover source, with
captured CUSTOMER asset/original assignment/SKU. Shared deployment result/document,
physical posting and episode validators remain in force. Inventory routes RMA
intent/consume to a dedicated service; WO owner validates REPAIR and current
assigned technician. Common mint/consumption/document writers are reused.

Current `.omo/runtime/rma-deployment-green.sh` and matching log/archive
`task26/rma-deployment-green/xml` still running custody guards and replacement
regressions. NOTE selector '*CustomerDeploymentIT*' matches no current class;
do not claim generic deployment coverage from that selector. Correct class names
are CustomerDeploymentFinalStateIT/ForwardFixIT/StrictInputIT/RootInputIT/
TwoCustomerIT/UpgradeIT, plus CustomerWarehouseProvenanceIT. Run appropriate
shared source/graph cases after the remaining RMA compatibility changes.

Next probes/fixes: signed customer handover for already-CUSTOMER RMA must be
non-posting (existing handover source loader assumes normal issue and PSB);
normal/RMA mint-key collisions and public assignment-history source decoding.
These are source-audit follow-ups, not yet reproduced tests. Then vendor
replacement, approved reacquisition and full packaged proof.26 remains open.

## Applied124 verified; RMA installation baseline and custody guards running

V175.124 is APPLIED AND IMMUTABLE, SHA256 `2c246b66a003534f27816277606f95447fb5d16de3f4efa0db5831bba246b2d2`.
RMA handover, supplier LOAN/SALE and ModularityTests passed6 tests in3 suites,
zero failures/errors/skips,2m23s. Archive task26/rma-handover-green/xml. Source
8ccd89d2 was pushed to feat/warehouse-workorder.123 remains immutable/79 green.

This checkpoint adds a shared real repair/REPAIR-WO RMA fixture, three custody
guard cases (wrong serial/work type, revoked receive/read replay, app-role source
rewrite/fabricated non-posting receipt) and an original-customer reinstall probe.
The latter expects purpose RETURN_CUSTOMER_RMA with null issue, then one actual
CUSTOMER installation/new ONU episode, old history retained and exact replay.
All4 tests are unverified: `.omo/runtime/rma-deployment-red.sh`, matching log,
archive `task26/rma-deployment-red/xml`. Check actual outcomes before production
changes. No125 declared or created yet. Current production remains8ccd89d2.

Next: bind RMA authorization/install to this acknowledged handover without a
fabricated ISSUE, preserve original sale title and episodes; then actual vendor
replacement, approved reacquisition and packaged proof.26/whole plan still OPEN.

## Applied123 verified; RMA handover implementation pending validation

V175.123 is APPLIED AND IMMUTABLE, SHA256 `fb47bf2fd2c6bf6c13fb1bac13addccacf5ad20628c609f8bbd619dbad6f1dde`.
The corrected run passed79 tests in16 suites, zero failures/errors/skips,3m56s:
serial LOAN/SALE once-only usage, omitted-close-history rejection and full material
lifecycle/rework/return closure. Archive task26/material-obligation-green-second/xml.

RMA custody baseline failed1/1 at missing rma-handover route404 AFTER a real SALE,
removal, warehouse recovery, supplier round trip and reset inspection. Archive
task26/rma-handover-red/xml,1m18s, zero errors/skips. Its main/test compilation
completed before new sources were copied from private staging; baseline ran123.

Current source adds dedicated CUSTOMER RMA handover: closed inspected repair,
original customer/assignment, assigned REPAIR WO, independent warehouse sender,
physical dispatch to transit and technician acknowledgement, unchanged title.
Inventory calls a workorder-owned validation port. Reads retain access to closed
WO history; commands/replays require current WO revision/assignment and scopes.
Declared124 captures origin, seals exact requests/legs/outbox and immutable
custody history. No regular ISSUE or ISP availability is created. Installation
permit/reinstall, vendor replacement and approved reacquisition remain unfinished.

Run `.omo/runtime/rma-handover-green.sh`, matching log, archive
`task26/rma-handover-green/xml`: custody case, supplier LOAN/SALE and ModularityTests.
Compilation/result/first124 application are pending; inspect before editing SQL.
Do not edit any successfully applied migration. Task26 and remaining plan open.

## Material obligation correction — verification pending

V175.122 passed76 tests in14 suites (zero failures/errors/skips). New serial and
omitted-history probes then failed3/3 on real valid fixtures. Declared V175.123
counts bound APPLIED serial deployment once and seals complete lifecycle source
keys. Its first bootstrap failed42601 reserved alias and explicitly rolled back;
only never-applied123 was corrected. Second run is
`.omo/runtime/material-obligation-green-second.sh`, matching log and
`task26/material-obligation-green-second/xml`;79 selected tests, outcomes pending.
Check actual applied status before any SQL edit. Ceiling122 is immutable.
Continue task26 original-customer sold RMA, vendor replacement, independent
reacquisition, then remaining plan. No whole-task closure yet.

## Active task —26 returns/inspection/repair

- Latest:175.122 applied and is IMMUTABLE. Core17.5m accepted inspection/close
  test passed; full lifecycle/rework run still active in return-settlement-green.
  Two new unverified serial-quantity and missing-close-history probes are saved
  here and queued in material-obligation-red.sh under the same QA lock. Read the
  newest task-26.md and final logs before claiming outcomes or editing SQL.
- Supplier/return12 tests, repair guard/module/contracts24, scoped reads/access4
  all passed with zero failures/errors/skips in their respective runs.
- New material closure test reproduced outstanding17500 after accepted17.5m
  inspection (expected0). This checkpoint adds declared V175.122: separate
  settledReturnBase from sealed accepted return, preserves historical returned
  quantity, binds snapshots/header and retains other close fences. No new stock
  posting on close; material summary now reports actual CLOSED lifecycle.
- Main/test compilation passed. `.omo/runtime/return-settlement-green.sh` and
  matching log, archive task26/return-settlement-green/xml; new closure plus
  lifecycle/rework suites. Check full outcomes and122 applied status before edits.
- Earlier migrations through175.121 are immutable.122 is in its first run.
  Latest task-26.md has receipts and source-audit follow-ups (serial used totals,
  omitted lifecycle snapshot lines) requiring real tests before correction.
- Continue actual vendor replacement, original-customer sold RMA handover/ack/
  authorization/install, approved title reacquisition and packaged proof.26 OPEN.
  Whole remaining plan28/30–48/F1–F4 remains active;25/27/29 closed.
- Work on work/warehouse-completion, ordinary push to feat/warehouse-workorder;
  no main deployment, database reset or agent delegation.

## Current step — transfer/count integration verified

- Latest published base before this checkpoint is `d23bba73`. The permission
  correction passed27 tests with zero failures/errors/skips. Its subsequent
  real HTTP run exposed a product bug: one bulk identity can have both AVAILABLE
  and LOST positions, and multiple transfers can share a transit location.
- Three real-DB failing-first regressions reproduced ambiguous selection:
  count100->80 then dispatch, two simultaneous bulk transfers with independent
  receipts, and independent LOST remainder approval. Added optional
  `sourceBalanceId`; dispatch and receipt now match the frozen source/full
  document-owned transit dimension. Old payload hashes remain unchanged.
- Corrected source passed37 tests in10 suites, zero failures/errors/skips,
  including the three regressions, all count/transfer cases, permission errors,
  module boundaries and legacy payload hashing. `qa.sh wave5` also passed both
  packaged JVM phases with actual public signup and real HTTP: partial receipts,
  independent loss/count approvals, stale count/recount and tenant rejection;
  restart replay matched14 original responses and16 immutable read snapshots.
- JAR SHA256: `70c7ddb8f239cb553d71379c15d4a25742ae3e08859a9f8061695dc1f08f03da`.
  Ignored raw proof: `.omo/evidence/warehouse-workorder-asset-provenance/` +
  `integration-20260924/positions-green/{xml,proof}`. Regression/HTTP lifecycle
  completed and removed only owned containers/processes; volumes retained.
- Current host command is `bash .omo/runtime/integration-http.sh replenishment`,
  log `.omo/runtime/integration-replenishment.log`. It verifies the shared
  packaged lifecycle against task29 again. Finish it before closing25/27/29.
  Then implement26 returns/inspection/RMA, followed by28/30 and remaining plan.
- No migration byte changed; ceiling175.115.1. See `docs/warehouse-transfers.md`
  and `docs/warehouse-wave5-verification.md` for portable behavior and commands.
  Global checkboxes remain unchanged at this checkpoint. Newest entry supersedes
  all historical running/pending descriptions below.

## Recorded integration and harness checks

- Fresh combined regression finished117 tests,1 failure,0 errors,0 skips in34
  suites (9m53s). All14 count and15 transfer tests passed. The only failure was
  `WarehouseReplenishmentITUpgrade` expecting2 migrations after175.112 when the
  combined source correctly applies9. The unchanged production migration boot
  succeeded; the test stopped before its preservation assertions.
- Corrected the test to require both175.115 and175.115.1 among applied versions,
  while retaining second-run zero migrations and all original quantity/rule/
  acceptance-preservation assertions. Exact rerun passed1/0/0/0 in48s. This is a
  corrected focused result, not a claim of a fresh117-test aggregate rerun.
  Original reports are in ignored `integration-20260924/first/`; focused XML is
  in `integration-20260924/focused-http/xml/`.
- Current command: `bash .omo/runtime/integration-focused-http.sh`; after the
  focused test, it built a clean JAR successfully and is running `qa.sh wave5`.
  Review `.omo/runtime/integration-focused-http.log` and the owned server log.
  Complete/fix this HTTP proof, then rerun replenishment's shared lifecycle.
- Installed Playwright Chromium1234/Chrome151 plus its required Arch `alsa-lib`.
  A real headless Chromium launch and local-document evaluation passed. This
  verifies browser tooling only, not a warehouse browser journey.

- Integration checkpoint `675d5007` was pushed to `feat/warehouse-workorder`.
  The fresh combined regression is running against a newly generated owned
  environment; it has passed migration boot and is executing approval tests.
  No final combined test count/result is claimed yet.
- Added `qa.sh wave5`: clean packaged JAR, two real HTTP JVMs, public signup,
  actual IAM/area/master/receipt/putaway setup, partial transfer plus approved
  discrepancy, blind serial/bulk counts, independent variance, real transfer
  invalidating count approval, recount and original-response replay after restart.
  This harness is syntax-checked WIP and has not yet run. Its stock setup uses
  public HTTP only, with no SQL seeds or mocked business services.
- Reused the existing bounded HTTP helper and owned packaged-process lifecycle
  between replenishment and wave5. Rerun both modes after the combined regression
  finishes, preserving failure logs and correcting any reproduced problems.
- Source inspection initially suspected serial counts were excluded, but receipt
  admission actually creates a `SERIAL` inventory_segment with the asset's ID.
  No product fix was justified by that suspicion; the new HTTP scenario verifies
  unchanged serialized counts explicitly. A mixed AVAILABLE/LOST bulk identity
  may expose ambiguous transfer source selection; the new real flow tests it.
- Exact next commands inside one outer-lock/up/stop/down lifecycle:
  `scripts/warehouse/qa.sh wave5` and `scripts/warehouse/qa.sh replenishment`.
  `.omo/runtime/integration-verify.sh` is the current host-local regression wrapper;
  its ignored log is `.omo/runtime/integration-first.log`. Restore the portable
  command list below if the private wrapper is unavailable after recovery.

## Recovery position — 2026-09-24 integration checkpoint

- Remote recovery branch: `feat/warehouse-workorder`. Local working branch:
  `work/warehouse-completion`. Commits use normal explicit pushes; no main merge
  or deployment. The user requests continued work to completion and durable
  checkpoints, including portable notes for another agent after VPS loss.
- Sources imported with original commit references: task25 through `f4297aef`,
  task27 through `57bc533a`, task29 through `9ccd5bb0`. Global plan checkboxes
  remain1–24 complete;25–48 and F1–F4 pending. Source integration alone does not
  close the three child tasks.
- Resolved shared Kotlin conflicts by retaining all controller registrations,
  `ADJUSTMENT`/`COUNT` posting kinds, `TRANSFER_REMAINDER`/`COUNT_VARIANCE`
  business actions and `RETURN_RECEIVED`/`COUNT_POSTED` effect events. Reviewed
  automatic policy-source integration: both transfer-party and count-counter
  independent-approval exclusions remain present.
- Imported migrations175.113 through175.115.1 retain exact child bytes. Fresh
  combined database verification is next. Declare any correction above175.115.1;
  no lower child version may be added. No new migration is reserved yet.
- Task29 historical evidence:191 selected regression tests plus1 live seed,
  zero failures/errors/skips; two packaged HTTP sessions including restart replay
  passed. See task-29.md for artifact identity and reproduction. Task25's note
  records190 earlier passing tests but unfinished HTTP proof. Task27's latest
  code extends its older note; its full current test result must be established.

## Current verification procedure

Use JDK21 and the repository Gradle wrapper. Read `docs/warehouse-migrations.md`
for isolated Docker roles/endpoints and `scripts/warehouse/qa.sh` for validated
commands. On this host, all QA lifecycles hold the outer flock at
`/home/fajar/ftth/warehouse-workorder-asset-provenance-resume/.omo/runtime/wave5-host-qa.lock`.
Create the parent directory if recovering onto a new host. Hold it across up,
checks, Gradle/HTTP/browser, owned stop and environment down. Retain data volumes.
Never copy or print private environment credentials.

First combined checks: `qa.sh server` with `--tests '*WarehouseTransferIT*'`,
`--tests '*WarehouseCountIT*'`, `--tests '*WarehouseReplenishmentIT*'`,
`--tests '*WarehouseApprovalIT*'`, `--tests '*WarehouseContractTest'`,
`--tests '*ModularityTests*'`, `--tests '*WarehouseSchemaITUpgrade*'`,
`--tests '*WarehouseEnvironmentIT*'`, and `--rerun-tasks --no-parallel`.
Archive the actual XML/counts before subsequent runs overwrite them. No combined
result exists at this checkpoint. Correct reproduced failures before claiming
completion; complete packaged HTTP proofs and add missing requirement coverage.

## Remaining sequence

Close25/27/29 after combined proof; implement26 returns/inspection/repair,
28 loss/scrap/adjustment,30 reports. Then31–41 operational web screens and real
browser journeys,42 shared KMP feature,43 preservation/cutover,44 adversarial
durability,45 complete real browser journeys,46 full regression,47 runbooks,
48 CI and F1–F4. Follow the active plan's business invariants. Hardware GPON
certification remains explicitly deferred by the owner's earlier decision.

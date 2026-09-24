# Whole-plan continuation

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

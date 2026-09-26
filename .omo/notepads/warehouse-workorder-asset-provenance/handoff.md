# Warehouse continuation — 2026-09-26

Continue to completion after each checkpoint. User requested a long run with coherent
commits pushed for recovery by another agent. This note supersedes older runtime
instructions; task46 remains in progress,47/48 and final F1–F4 are not approved.

## Current product checkpoint

This checkpoint extends6c6195ae transfer draft editing/V178.10 with COUNT editing and
V178.11. Push target is `work/warehouse-draft-lifecycle`; local edit branch is
`work/warehouse-regression-fixtures`. Do not assume its same-named remote is current.
Original/feature branch remains40cbd34f; R7 has ended, so the original freeze is over.

COUNT adds revisioned PUT and requester-only bounded GET draft, shared
current counter eligibility, full UI hydration/edit/conflict handling and V178.11.
COUNT round/evidence/result rows remain immutable; old create/update response bytes
stay unchanged and replay rechecks old/current scope. No book quantities in the form.
Docs/current schema references now178.11, next178.12; workflow adds5count classes.
The real browser helper edits the saved draft before starting round2.

Local corrected focused checks passed. Initial r1completed67tests/19suites with
65pass/2test expectation failures. R2reran the two corrected test classes14/14PASS.
Exactly those two test sources changed; all2601server inputs compared. Thus53unchanged
passing cases plus14rerun cases give67combined passing cases, including both migration
upgrade fixtures. Proof: task46/local-count-transfer-focused-r2.json, with failedr1
retained. This is combined focused evidence, not an unfiltered full pass.

Web r4passed9/9, warehouse E2E typecheck, lint(exit0,99warnings) and production build.
The editor renders25rows per page and preserves all100 in state and exactPUT. The
100-assignment test passes in1.845s. Earlier unpaged/label-query failed iterations are
retained as safe metadata. Proof: task46/local-count-web-r4.json. Runtime migration
V178.11 is now applied locally and immutable; all365prior migration bytes unchanged.

Browser exceptions desktop/mobile completed2/2PASS, no failures/skips/flakes/retries.
It edits drafts before starting round2, then exercises blind counts, independent
rejection, moving stock and append-only recount. Raw report/traces are privately
archived at original runtime/count-browser-r1-report.json and count-browser-r1-artifacts.
Safe source-bound proof: task46/local-count-browser-r1.json. Cleanup completed; no
local QA remains active. Other browser scenarios/current full CI remain required.

Independent F2parsed the actual server/web reports and current source hashes; safe
note task46/f2-count-transfer-executed-verification.md/json confirms the67combined
and9web results, preserving r1failure and precise gate limits. Earlier source notes
remain dated historical witnesses. This does not grant final F2approval.

All48safe task-N/index.json files are saved with exact old source/proof identities.
Two old safe task29receipts were recovered from the original checkout with exact
reviewed hashes; all historical references resolve. No early evidence was invented.
Asset-exception UI design: task46/f1-asset-exception-ui-design.md.

## Outstanding implementation and review

- Push this coherent COUNT/transfer correction checkpoint, capture its CI run and keep
  implementing C7/C8 while full current regression runs. V178.11 is applied and immutable.
- C7 idle expiry for operational receipt/material-plan/transfer/count drafts AND retained
  unapproved immutable proposals. Prefer append-only terminal decisions plus SQL liveness
  guards/current read overlays; do not mutate sealed source bodies or post stock.
  Full design/race/locking/projection map: task46/f2-draft-expiry-design.md. No expiry code yet.
- Specialized C8 interpretation: f1-proposal-semantics.md. Corrected successor proposals
  can comply; no blanket PUT on sealed LOSS/TITLE/etc requests is required. Still fix
  unusable TITLE_CORRECTION rework, supplier replacement's false generic edit action,
  and assess missing ASSET_LOSS/TITLE_CORRECTION creation UI callers against plan scope.
- Complete current regression, independently review the48safe task-N evidence indexes, F1/F2/F3/F4, runbook
  and release closure. F3 real browser/manual independent review has not started. F4
  independent positive/negative SQL preflight remains pending host availability.

## Validation and known results

- CI36149519466 at149ecfb2 COMPLETE SUCCESS. Root and separate F4 reviewer authenticated
  actual artifacts:3792modern tests/612suites,266focused,7historical+1projection,376source
  inputs, all15jobsPASS. Safe proofs task46/ci-149ecfb2-complete-server-verification.json
  and f4-ci-149ecfb2-server-recheck.md/json. Proves149only, not transfer/count changes.
- CI36160896348 at6c6195ae COMPLETE FAILURE at compileTestKotlin (five AssertJ Consumer
  overloads in TransferDraftIT); no server tests ran. Application build/migration and
  all nonserver jobs passed. Root actual web artifact revalidation confirms579tests
  (task46/ci-6c6195ae-web-verification.json). Other6cnonserver report contents await
  direct revalidation; successful job labels alone are not artifact verification.
- Local full-server-R7 at40 timed out exit124,2930observedPASS/0observedFAIL and no
  complete modern XML. Historical7+projection1 passed. Safe proof task46/local-full-r7-incomplete.json.
  Cleanup completed; volumes retained. Never mark incomplete run successful.
- CI36137258991 at e178,36135632377 at40,36147421484 at a1d4 all completedSUCCESS.
  e178actual nonserver proof is task46/ci-e1782718-nonserver-verification.json.
- CI36148238334 atd8failed test compilation;149fullpass supersedes it for that source.
- Older2c1fullserver proof3783tests/609suites,241focused,7+1,375inputs/all15jobsPASS is
  task46/ci-2c1d8e08-complete-server-verification.json. Keep actual historical identities.

Private original-runtime helpers download/decrypt/revalidate CI evidence without raw
output: download-ci-evidence.py, download-native-evidence.py, revalidate-nonserver.py,
revalidate-server.py. Adapt old hardcoded expected web counts to actual source/report;
never relabel old evidence. Actual current full CI/browser validation remains required.

## Isolation, recoverability and user decisions

User approved separate reviewers and explicitly chose rejecting tenant deletion when
protected warehouse history exists, with Suspend guidance. Empty tenant deletion remains
supported and passed. GPON physical hardware certification was deferred; offline/MIB/docs
proof still required. No main merge, image publication or production deployment authorized.

Review detached worktrees: warehouse-f1-review, warehouse-f2-review, warehouse-f4-review,
all under original .omo/runtime. Reviewers are read-only for product. F4 already inspected
e178tested image archives privately; don't duplicate/download/load/publish unnecessarily.
Safe preliminary review files are in evidence root, dated/bound to their actual old source.

Serialize all local Gradle/Java/Docker/browser/web QA lifecycle under outer fd8 lock:
`/home/fajar/ftth/warehouse-workorder-asset-provenance-resume/.omo/runtime/wave5-host-qa.lock`.
qa.sh uses separate fd9 checkout warehouse.lock. Ports25432/29000/17880/14188.
JAVA_HOME=/usr/lib/jvm/java-21-openjdk; original runtime/gradle-home is GRADLE_USER_HOME.
Task-owned stop/down retain volumes. No volume deletion/reset/stash/rebase/amend/force-push
or unrelated process kills. Temporary4GiB swap `/swap/warehouse-development.swap` is not
in fstab; don't swapoff under load. Applied migrations remain immutable; use forward fixes.
Historical upgrade fixtures use separate databases with public schemas, never sibling schemas.

PUBLIC repo: never print/commit env, credentials, raw XML/system-out, traces, DB dumps or
plaintext decrypted artifacts. Private runtime0700/logs0600; launch with umask077. Force-add
only exact reviewed safe evidence/notes paths. Evidence artifacts are age-encrypted for the
maintainer's existing SSH Ed25519 identity; preserve that private identity outside Git.

Commit with GIT_MASTER=1 and author fajarxfce <fajaralamsyah000@gmail.com>. Push explicit
refs with inherited GIT_SSH/GIT_SSH_COMMAND unset and core.sshCommand using
`ssh -i /home/fajar/.ssh/id_ed25519 -o IdentitiesOnly=yes -o BatchMode=yes`.
After R7 safely ends, integrate validated descendants into original/feature and continue
all required checks. Never mark completion merely because a checkpoint was saved.


## 2026-09-26 sealed proposal checkpoint (parent d6baf827)

Task46 remains in progress. Current receipt GET/list expose domain editability; supplier replacement proposals reject fresh ordinary draft PUT while historical response bytes stay unchanged. Web hides the unsupported editor and explains how to create a corrected proposal from the source return. TITLE_CORRECTION no longer offers generic rework; a fresh proposal uses current ownership/evidence after rejection.

Validation: actual local87/87 backend tests in4classes and14/14 web tests, warehouse E2E typecheck and production build passed. See task46/local-sealed-proposal-{server,web}-r1.json and independent f1-sealed-proposal-patch-review.md/source.json. Two mistyped backend selectors executed nothing and are explicitly excluded; ModularityTests will run with the next change. These checks precede the new asset exception creation UI. No new migration; V178.11 remains immutable and next is178.12.

CI36237393856 at d6baf827 is still running; reviewer is authenticating its actual nonserver artifacts. Follow-up asset exception UI/API context is being implemented in the validation worktree. Do not treat its uncommitted files as tested. C7 expiry and final full regression/F1-F4 remain open. No deployment or main merge.


## 2026-09-26 CI catalog correction (parent cc47f434)

Remote work/warehouse-draft-lifecycle contains cc47f434 (sealed proposals). CI36237393856 at d6 failed one of325 focused tests: WarehouseDeferredGuardCatalog lacked the newly introduced COUNT final guard. Full server/historical regression was skipped. Both report directories contain identical XML copies; do not count them twice. Fixed the explicit catalog using the actual OLD/NEW tenant assertions; no migration changed. Actual local145/145 tests across5classes pass, including the62-case selective-timing/schema suite and ModularityTests. That run includes initial uncommitted asset-context code and does not certify the later reviewer fixes; see local-asset-exception-server-r1.json.

Independent actual nonserver d6 artifacts all pass:22browser,6legacy,581web,44shared,2native compile tasks,8preflight checks and matched image/JAR/source/migration hashes. See f4-ci-d6baf827-nonserver-recheck.* and f4-d6-source-input-hashes.json. No aggregate CI/F4 pass.

Still in task46. Asset exception GET/UI initial tests passed, but reviewer required customer-area checks again on fresh/replayed POST and approval.view for loss's original requester continuation. Root is implementing those plus real browser creation/rejection journeys in the validation worktree. C7 draft expiry and final full regression remain open. Highest schema remains178.11, next178.12.


## 2026-09-26 C8 office asset exception checkpoint (parent 99ad992d)

Task46 remains in progress; tasks47/48 and finalF1-F4 are not accepted. New selected-assignment exception context and customer-panel UI let an authorized office requester propose TITLE_CORRECTION or ASSET_LOSS without technician workbench authority. The server loads original work-order/signature/current ownership context under current authority and warehouse/customer scopes. Late locked customer-area checks cover context and legacy ownership reads plus fresh/replayed title/loss commands. Sealed proposal creation preserves stock/title/recovery state; the original requester needs approval.view to continue, and final effect requires an independent approver. Replay keeps original response bytes.

Actual local proof, reported separately: serverR2 87/4 passed before the final legacy ownership-read correction; current serverR3 13/2 passed and all2602server inputs match. WebR2 18/18 plus E2E typecheck/lint/build passed; only the browser helper subsequently changed. BrowserR3 actual4/4 passed, desktop/mobile sale-RMA and loan-reuse,2426 matching application inputs, outer runner and owned cleanup exit0. R1 failed all4 because a fixture prefix created whitespace in email; R2 passed2desktop/failed2mobile because a gridcell-padding click did not activate the customer button. Both were corrected without bypassing the UI. Eight R3 C8 screenshots were independently inspected. See local-asset-exception-{server-r2,server-r3,web-r2,browser-r3}.json and f2-asset-exception-{executed-verification,browser-review}.*. Bounded F1 C8 source recheck has no open source blocker; no final F1/F2/F3 approval.

CI36238965245 at99ad992db31a6922ec1adf1957af81bf747426e6 is COMPLETE SUCCESS, all15jobs. Separate F4 reviewer authenticated actual3810modern tests/616suites,325focused/50suites,7historical+1projection,378historical source inputs and366migrations to178.11. Same-commit nonserver:22browser,6legacy,584web,44shared,2native compile-only tasks and8preflight probes. See f4-ci-99ad992d-{server,nonserver}-recheck.* plus f4-99ad992d-source-input-hashes.json. This covers99ad, not the newer C8 code or C7. cc47 actual325focused had the same one missing catalog-key failure asd6; that failure is fixed and absent in99ad.

C7 is still UNAPPLIED. Recovery sketch is .omo/drafts/warehouse-draft-expiry.sql, explicitly outside Flyway. Highest applied migration remains178.11; next178.12. Initial/revised F1 sketch review notes identify receipt renewal seal, evidence INSERT, backfill-lock and inverse-effect boundaries. The latest sketch adds protected DB activity/TTL, append-only terminal overlay, typed RLS tables, canonical receipt save capture/seal, early child/decision/effect hooks, structural inverse guards and pending-approval terminal invariant. Final field-binding patch is awaiting bounded source recheck; no SQL was executed and none of this is C7 acceptance.

Next: push this C8 checkpoint; verify candidate SQL transactionally against an owned178.11 fixture before finalizing migration; add canonical receipt-save seal call after existing intake identity; implement worker/current read projections/UI/stable DRAFT_EXPIRED mapping and allfamily actual-clock/race/privilege/upgrade tests. Keep original response bodies unchanged. Adapt two current-binary/old-schema fixtures with pinned pre-C7 HTTP setup then current-code handoff; no schema-missing runtime expiry fallback. Full guidance is f4-c7-upgrade-fixture-design.* and existing F2/F4 expiry designs. Commit/push coherent progress, continue through full current regression and independentF1-F4; do not stop at this checkpoint.


## 2026-09-26 C7 core checkpoint (parent 1d14f4b9)

Task46 remains in progress. This checkpoint implements database-owned idle draft deadlines, append-only terminal overlays, SQL admission guards, a bounded expiry worker, current-state/read/filter overlays, and general/specialized web explanations. Only accepted receipt/transfer/count draft PUT renews its pinned policy; old original responses remain replayable. Material summary exposes planState separately from demandState and retains the latest expired plan identity/revision. Disposition/compensation/asset-loss, return-title, supplier-replacement and opening current readers now expose expiry. Late approval decisions and worker races terminate without physical effects. Default policy is seven days; owner changes only affect newly created identities. See docs/warehouse-draft-expiry.md.

V178_12__warehouse_idle_draft_expiry.sql has actually been applied through Flyway in owned QA. Its SHA256 is add301336feaf41c740f5d6026206500aed07c6bae7c20f168d7aa4ae11adca3. Freeze those bytes; next migration is178.13. The older .omo/drafts SQL copy is an archived pre-application design checkpoint, not the migration authority. Rollback-only schema probe preceded the applied test run.

Executed local proofs are separate: receiptR1 98/4 pass (5new clock tests,28receipt form,62deferred scope/catalog,3modules), all2607server inputs stable. FamiliesR1 compiled no tests because one new JsonNode assertion needed asSequence/toList. FamiliesR2 executed40tests/12classes:37pass,3new material failures from an incorrect test endpoint. All11new receipt/transfer/count/approval tests passed there, including real clock waits, scheduler-disabled commands, admitted-before-deadline transfer/count controls and two-worker/late-decision race. Corrected only material test endpoints to submit-request; materialR1 then7/3 pass (3material expiry/history/replacement/submitted controls,1existing workflow,3modules), all2611server inputs stable. These reports are not a single unfiltered pass. General webR2 88/12 pass +E2E typecheck/lint/build,523inputs. Source advanced to specialized projections afterward. WebR3 115/116 pass; corrected new opening-manifest expectation to compare the decoded retained projection, not raw fixture fields. WebR4 result is recorded in its separate proof when available.

Independent F1 bounded core review found material state/expiry race and omitted summary signal; both fixed before applied receipt QA. F2 authenticated receipt98/4+web88/12 at their captured sources; no final F1/F2/F3 acceptance. C8 CI36252487886 at1d14f4b9 has independently verified all13nonserver jobs:22browser/8specs/16spec-project,6legacy,595web,44shared,2native compile tasks,8preflights;366migrations to178.11. Server job was still running at last observation. C7 is excluded from that CI. Actual99ad CI3810modern/616suites remains last fully authenticated all-green baseline.

Required next work, still authorized:
- Complete short owner-policy expiry matrix for sealed LOSS/SCRAP, DISPOSITION_REVERSAL, ASSET_LOSS, TITLE_CORRECTION, RETURN_TITLE, supplier replacement RECEIPT, ADJUSTMENT and OPENING_BALANCE. Assert current read/list/detail, exact replay, rejected due command/rework, frozen source/evidence/stock/ownership, and fresh replacement where supported. Cover expired scope revocation, blocked approval/posting around deadline, child evidence/control non-renewal, missing activity and policy-only tenant cleanup.
- Adapt exactly two modern old-schema fixtures (WarehouseTransferDraftUpgradeIT at178.9 and WarehouseCountDraftUpgradeIT at178.10) to a pinned pre-C7 application process for HTTP setup, close old process, full migration, fresh current app on same owned DB. Current strict readers cannot boot old schema. NO schema-missing => live fallback, no skipped gates. Preserve original PUT/replay/posted/started and repeat-migration assertions, plus actual178.11→178.12 backfill past/future/nonfinite cases and unchanged sources. Existing F4 design in task46/f4-c7-upgrade-fixture-design.* recommends pin d6baf827 (byte-identical old helpers); pin is not a historical CI pass claim. Optional move to historical runner requires coherent exact class/count changes; moving alone does not prove current-code handoff.
- Enforce current schema capability at startup after migration, without runtime opt-out; do not weaken old schema to satisfy tests. Adapt old-process handoff before final unfiltered regression.
- Real browser C7 deadline crossing and visible retained history for desktop/mobile; full current server/historical/web/shared/native/preflight and final independentF1-F4. Then tasks47/48/runbook/release integration. No main merge/publication/deploy authorized.

All current local QA continues under shared outer fd8 lock. Private logs, raw XML, manifests with credentials and response bodies stay under original runtime. Save coherent commits and explicit remote checkpoints; do not stop at this checkpoint or call task46 complete.

WebR4 finished: actual116/17 pass,523web inputs stable, E2E typecheck/lint/build pass, runner exit0. See local-draft-expiry-web-r4.json. Local server/web source hash inventories are c7-core-{server,web}-source-hashes.json. C7 core checkpoint is ready to commit/push; next work remains as listed above.

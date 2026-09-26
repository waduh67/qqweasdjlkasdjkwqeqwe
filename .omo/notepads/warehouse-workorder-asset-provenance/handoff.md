# Active continuation — authorized server setup and final warehouse review

Read this block first. Dated sections below are historical checkpoints, including
older statements about deployment permission. The user has now explicitly authorized
setting up their Azure VPS and domain. Continue the long run through completion;
commit and push recoverable checkpoints. No main merge or registry publication.

- Edit worktree: `.omo/runtime/warehouse-regression-validation`, branch
  `work/warehouse-regression-fixtures`; push to `origin/work/warehouse-draft-lifecycle`.
  Application/build/test/workflow/migration inputs remain at
  `1306b65c34167b2d48f4817ce72990fed3b85aa3`. Prior checkpoint `db09c3c2` adds five
  deployment/docs changes and three shared-proxy files; it is no longer a whole
  3693-input equivalent checkout. The other 3688 recorded inputs remain unchanged.
  This checkpoint adopts bounded deployment runtime/review evidence only.
- Preserve CI `36265279847`: all14nonserver jobs/artifacts and focused compatibility
  passed; fullserver/fresh/historical has run since19:40:01UTC. Authenticate its actual
  final artifacts before final approval. Do not count duplicate/cancelled runs as passes.
- F3 is PASS (36intended browser cases +8preflight; prior failed/interrupted attempts
  retained). F1/F2/F4 still need current fullserver and the new deployment delta.
- Deployment fixes: separate `warehouse_owner` Flyway credentials from non-owner
  `warehouse_app`; preserve restricted clock grants; correct canonical Spring demo
  secret binding, storage key requirements, optional environment-only SMTP health,
  and shared existing-proxy operation. Mandatory CI gate remains intact.
- Actual private production bootstrap R2 PASS:367migrations through178.12, strict
  missing-secret rejection, production=true/noQA, readinessUP, runtime role flags,
  real schema CREATE/policy DELETE denials, login and same-image restart; cleanup0.
  R1's SMTP-related readiness timeout is preserved, not relabelled. Safe receipts:
  `task46/deployment-production-bootstrap-r{1,2}.json` and
  `task46/deployment-bootstrap-adoption.json`. SMTP delivery remains unconfigured.
- Exact CI server/web archives are loaded on the VPS. Config bytes and every ordered
  uncompressed layer match after re-export. Docker29 reports OCI manifest IDs, unlike
  the classic Docker config IDs; retain both identities. No application rebuild.
- The pre-existing site's Caddy owns80/443. Its configuration/site remain intact;
  FTTH TLS is valid through Cloudflare and directly at origin, serving maintenance503.
  Shared-proxy overlay will publish only the optional8880 gateway port. App/DB/NBI
  ports remain internal. Eight production infrastructure services are running with
  restart0; production backend/web/gateway are not yet activated. Existing Drive is200.
- Isolated network/storage R3 PASS: real gateway/login,137-byte S3 put/get/delete,
  CWMP401Digest/zero devices, private NBI200, FreeRADIUS configuration accepted.
  R1/R2 private fixture failures remain preserved. Cleanup0, no QA containers remain.
  Shipped backup/non-replacing restore drill PASS on separate retained QA database:
  367migrations,1tenant/1user/0customers and clock ACLs preserved. This is not a
  populated production recovery claim. Independent raw authentication is adopted in
  `task46/deployment-runtime-adoption.json`; source/runtime limitations remain explicit.
- Private activation helper now requires exact host configuration, complete current CI
  and source-bound independent readiness receipts. It restores/reloads only FTTH's
  managed proxy block if activation fails after switching, including TERM/INT, while
  preserving current unrelated site bytes and database history. F4 source findings
  closed;14 local synthetic fault-injection tests passed. No activation execution yet.
  Read private original-checkout `.omo/runtime/ACTIVE-WAREHOUSE-RUN.json` and
  `.omo/runtime/azure-ftth-setup` for exact helper, host, key and evidence. Never print
  private files, stop/down unrelated applications, or remove volumes.
- User supplied the production admin email and approved a random password because
  the supplied password did not meet the existing16-character production minimum.
  The environment and credentials are saved root-only0600 on the VPS. Use the host
  ftth-compose wrapper so both proxy and pinned image overlays are always applied.
  Next: authenticate current full CI, obtain independent activation readiness,
  activate authorized production and configure VPN1194/TCP on unused10.8.0.0/24,
  verify HTTPS/login/backups/listeners and existing site, finish F1/F2/F4, close46–48,
  integrate validated feature descendants, then present concrete owner acceptance.
- Old `work/warehouse-task29` was integrated by prior cherry-picks/adaptations and is
  not an ancestor of the current branch. Do not merge it again or force-update it.
  Validated descendants can later fast-forward original `work/warehouse-completion`
  and `origin/feat/warehouse-workorder` from40cbd34f after rechecking refs.
- Migration178.12 is applied and immutable; next178.13. Reject tenant deletion with
  protected history; use Suspend. Physical GPON certification is deferred, while
  offline documentation/MIB coverage remains required.

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


## 2026-09-26 C7 upgrade/startup checkpoint (parent a9cd278f)

Task46 remains in progress. The two existing modern transfer/count upgrade regressions now seed their real HTTP commands in a separate JVM compiled from pinned pre-C7 application1d14f4b9aebcd1c1a9e8c84041259ff56a422005. That process closes before full current migration and a fresh current application context on the same owned fixture database. Baseline provenance, unchanged source/command fingerprints, original replay, live draft PUT, noneditable dispatched/started/posted controls and repeat migration remain asserted. This preserves the two modern classes and existing historical7+projection1 class counts. Raw transfer dispatch wire bytes are not separately asserted yet (parsed response and stored operation fingerprint are).

Current application eagerly requires the complete clock capability after DB initialization, including six RLS/FORCE tables, functions and enabled admission/immutability triggers; old explicit Flyway targets and partially disabled installations refuse startup. No runtime schema fallback. qa.sh prepares the pinned compiler/runtime classpath for unfiltered, FQN, simple, method and wildcard selectors. Current driver is compiled only in the detached old checkout; source inventory pins2595 historical inputs. CI encrypts both focused/full handoff response files and private logs; never commit these raw files.

Local upgradeR1 failed before tests because the Gradle init also ran in included build-logic; fixed with findProject guard. upgradeR2 actual7/4PASS:2handoffs,2startup refusal cases,3modules, source manifest stable, runner and owned cleanup0. Five existing workflow gate tests passed. After R2 only qa.sh selector recognition was expanded following review;7actual selector-block probes passed. See local-draft-expiry-upgrade-r2.json and c7-upgrade-{server,pinned}-source-hashes.json. R2 is not a full regression or aged/nonfinite backfill proof.

Bounded independent F1 specialized-current review at a9cd found no blocker; saved f1-c7-specialized-current-review.md/source.json. Current upgrade review independently verified2595 old source hashes and classpath separation; no product/handoff blocker, no finalF1. F2 reviewer retries failed remote compaction/rate limit and did not produce acceptance. C8 CI1d still running full server,13nonserver passed. C7 CIa9 queued behind1d and predates the fixture adaptation. No finalF1-F4/task47/48.

Next work: actual-clock specialized proposals matrix (new test files may be unstaged while this checkpoint is saved), old178.11 bounded-backfill edge cases, raw dispatch replay assertion, actual browser expiry crossing, full current regression/audits and task47/48. Applied178.12 SHA remains add301336feaf41c740f5d6026206500aed07c6bae7c20f168d7aa4ae11adca3; next178.13. Continue checkpoint pushes without cancelling an active complete CI. Source freeze only while an owned QA runner is active; outer fd8 required for every local QA lifecycle.


## 2026-09-27 C7 specialized/browser checkpoint (parent b29cde33)

Task46 remains active; finalF1-F4/tasks47/48 unaccepted. Actual specializedR3 passes18tests/6suites:11new real-clock cases across LOSS/SCRAP/DISPOSITION_REVERSAL/ASSET_LOSS/TITLE_CORRECTION/RETURN_TITLE/supplier-replacementRECEIPT/ADJUSTMENT/OPENING_BALANCE,4existing opening flows,3modules. Compare retained source/header/lines and complete physical balances/assets/assignments/movements/title/return/recovery/repair-consumption facts; original replay preserved and late actions rejected. Expired pending discrepancy allows a fresh report but old approval cannot post. A fresh same-serial supplier replacement receives under a new60second owner policy; the original never consumes the vendor replacement. Title requests retain business-payload dedup across keys; a fresh review uses a distinct reason. Existing opening GET assertion now compares its additive DRAFT state plus the exact original body fields.

Attempts are explicit: specializedR1 compilefailed duplicate new local name,0tests. R2passed17/18 because new title fixture superseded signature then sent old evidence. Corrected to latest signature; assetR1passed4/5 (all actualexpiry assertionspassed) but final fresh identicaltitlepayload hit the existing canonical business dedup. R3 explicitly asserts that409and then201with a renewed review reason, plus stronger repair registry assertions requested by independent reviewer. No product/SQL guard was loosened. See local-draft-expiry-specialized-{r2,r3}.json, c7-specialized-r3-source-hashes.json. All R3inputs stable,runner+cleanup0.

Real browserR1passes2/2 desktop/mobile, build+E2Etypecheck and runner/cleanup0, all captured app/e2e/QAclock inputs stable. It creates3connectors entirely viaUI under an actual12second owner policy, opens editor beforedeadline, awaits DBtime, verifies latePUT409DRAFT_EXPIRED, reloads revision0EXPIRED with original lineID/SKU/quantity/lot and zeroeffects, retains onehistoryrow and expiredlist, opensfreshdraft. ReceiptBody now returns to current detail when a reloaded editor's draft has expired. Added mandatory ninth CI browser spec draft-expiry. New QAclockfixture is restricted to owned warehouse_e2e, generated browser actor/UUID inputs, checked markers; it does not override application time. Root inspected2screenshots; stickyheader overlay in fullpage capture while scrolled is an artifact limitation, so test-only scrollTo0 added afterward; clean repeat pending. See local-draft-expiry-browser-r1.json/c7-browser-r1-source-hashes.json.

Docs migration head corrected to178.12/next178.13 and source-matched expiry review steps. Legacy browser preflight had hardcoded178.11; changed only actual expectedversion/reportidentity to178.12 (positive2/negative6 unchanged), pending execution. Existing workflow acceptance tests5pass after adding ninthspec. F1 independently reviewed specializeda9 plus startup/handoffb29, no bounded blocker; notes/sourcehashes committed. Fresh bounded F2 reviewer expiry_quality_review identified scopecoverage and direct repairconsumption/linecardinality assertions; repair/cardinalityfixedbefore successful executions, newscopetest is stilluncommitted and running. No finalapprovals.

At this checkpoint root is running private draft-expiry-backfill-r1.sh session60596 underfd8, freezing server/src and qa.sh/draft-upgrade-bootstrap/init. Uncommitted follow-up (excluded from checkpoint proof): WarehouseDraftExpiryScopeIT and WarehouseDraftExpiryUpgradeIT, oldseed expiryfamily12sources, rawtransferdispatch wirebytes, qa.sh thirdclass selector. Initial backfill fixture attempts UPDATE sealedplan timestamps; likely rejected by correct immutability guard. After actual result, replace those mutations with an owned temporary BEFORE INSERT timestamp fixture installed before oldHTTPseed and removed before migration; never disable guards. New barrier observes actual blocked migration relationlock, then commits writer before capturing baseline. Preserve tests/sourcefingerprints and oldwirebody. Next run backfill/scope, cleanbrowsercapture, webregression, fullcurrentCI, finalaudits andtask47/48. Keep exactunfiltered reports beforefocus.

CI1d36252487886 lastseen fullserverrunning ~74minutes at00:10WIB,13nonserverpassed,noserverartifact. a9CI36255319779 was superseded whilepending,zerojobs. b29CI36257465052 pending behind1d. ActiveCI notcancelled; preserve it. No finalcurrentfullpassclaim. Highestapplied178.12 immutable SHAadd301336feaf41c740f5d6026206500aed07c6bae7c20f168d7aa4ae11adca3. No merge/publication/deployment authority.


## 2026-09-27 C7 backfill, scope and policy cleanup checkpoint (parent a85b3fda)

Task46 remains open for current full regression; tasks47/48 and finalF1-F4 are not accepted. Targeted C7 implementation checks are now complete. The new pinned178.11 upgrade fixture creates six transfer drafts and six material plans via the old HTTP application, using temporary BEFORE INSERT timestamp fixtures for old/future/nonfinite/recent/current dates. Existing immutable guards stay enabled and temporary fixtures are removed before migration. Actual ROW EXCLUSIVE relation lock blocks the migration's ShareRowExclusiveLock; baseline follows lock release. This models an old writer's relation lock, not a concurrently executing business mutation. Current migration preserves source/command fingerprints, clamps deadlines, retains original response bytes, rejects due dispatch/submit, and is repeatable. Existing old transfer dispatch now also compares raw replay wire bytes. Expired receipt scope revocation denies detail/replay and omits list rows before and after worker materialization.

BackfillR1 passed8/9; its new owner UPDATE fixture was correctly rejected by tenant/immutable guards before migration. R2 passed12/13: the full new12source backfill, two existing handoffs, startup negatives, scope case, original deletion cases and modules passed. Only the new policy-only deletion setup lacked the explicit row tenant GUC. Fixed that fixture with a scoped owner transaction, also configured policy in the protected ONU-history case. TenantR1 then passed7/7 across4deletion cases and3modules, all2625inputs stable, runner and cleanup0. R2 remains recorded as failed, with separate corrected proof; never claim one aggregate pass. See local-draft-expiry-backfill-{r1,r2}.json and local-draft-expiry-tenant-r1.json.

TenantEraser now excludes only inventory_draft_policy plus the existing cutover/authorization controls from the protected-history scan and manual deletion. Unused configuration cascades with the final empty tenant row. Business/activity/expiry/receipt-command history remains protected; direct app policy DELETE stays denied and a tenant with protected history must use Suspend. No migration changes. Independent F1 found no privilege/history bypass; F2 bounded review authenticated R3specialized/R1browser and current backfill sources with explicit lock-proof limits. Safe notes/source inventories are included.

BrowserR2 actual2/2 desktop/mobile passed with build+E2Etypecheck and all2437inputs stable, runner/cleanup0. Root viewed both clean top-scrolled screenshots: original3unit line, zero physical effect, EXPIRED explanation and retained revision0history. Full webR1 then passed607tests/117files plus E2Etypecheck, lint(exit0 with existing warnings) and productionbuild;523inputs stable, runner0. See local-draft-expiry-browser-r2.json and local-draft-expiry-web-full-r1.json. Current operation/review/deploy docs now all point to178.12; historicalversion references retained.

Next, still authorized: push this checkpoint, then independent expiry_quality_review receives the serialized localQA lease for nine browser specs, required extraedges/restart and legacy/preflight178.12. Root handles current fullCI and any concrete findings; no redundant focused loops after passing checks. Agent may write private runners/evidence only, product sources remain root-owned. F1 preparing all48/C1-C11 mapping, F4 authenticating CI. At00:27WIB old1d CI36252487886 was91minutes into fullserver,13nonserverpassed, nofailure/serverartifact; a85CI36258514131 pendingzerojobs. 99ad remains last fully authenticated all-green baseline. Newpush may supersede pending run; never cancel active complete CI or call pending/cancelled a pass.

Applied178.12 stays SHA256 add301336feaf41c740f5d6026206500aed07c6bae7c20f168d7aa4ae11adca3; next178.13. Continue through current regression, source-matched runbook/release gates, F1-F4 and final user review. Original feature worktree40cbd34f still needs validated descendant integration. No main merge, image publication or deployment authorized. Private ACTIVE-WAREHOUSE-RUN.json tracks the next actual session/lease. All QA uses shared outerfd8; preserve raw reports privately and stop/down owned services only, retain volumes.


## 2026-09-27 mandatory offline protocol gate checkpoint (parent 9d540107)

Product, migration, build and browser inputs remain byte-identical to9d540107. This checkpoint changes only CI/docs/evidence. F1 completed a source/evidence mapping of all48 rows/C1-C11 and found that current warehouseCI compiled SNMP/collector dependencies without executing their own offline parser/protocol tests. Historical task23 claims25SNMP/22collector at a028c6a9, but matching raw reports are not retained here; those claims are not accepted as current proof. PhysicalGPON certification remains deferred.

Required CIserver job now first executes :contract:test :snmp:test :collector:test with --rerun-tasks --no-build-cache, then requires each module's nonzero successful JUnit results. Failure blocks the existing server/acceptance/reusable-workflow/publication/deploy dependency. Protocol raw XML/logs are included only in the existing encrypted server archive. F4 found the initial cache-execution gap; forced-rerun flags fixed it, and bounded source recheck found no remaining issue. Actual module execution is still pending current CI or the next available localQA lease. F3 reviewer executed existing workflow guards5/5 and actionlint0 underfd8, with hashes independently checked byroot; see local-protocol-ci-workflow-r1.json and f4-protocol-ci-source-review.md. Currentdocs reproduction includes the same forced execution.

IndependentF3 actualmain run at9d540107 passed18cases: setup4,receiving2,provenance2,issue2,returns4,exceptions2,numeric2. Outer session15215 then ended143 during customer-assets bootJar before browsercases; cause unknown, no test assertionfailed. Complete18caseproofs preserved, ownedrecovery stop0/down0 and all captured inputs unchanged. It is not an uninterrupted24casePASS. Reviewer resumed onlyremaining6cases (customer-assets4,draft-expiry2) in fresh session91795, private task46-expiry-quality-review/f3/remaining-r1. Additional private edge/restart checks and legacy/preflight178.12 remain. Reviewer holds localQAlease; root must not start localQA or change frozen product/build inputs until lease returned. Two screenshot capture-state limitations will receive settled private captures; no product defect inferred.

F1 preliminary all48matrix/source-evidence/E(N)index saved with explicit old/currentproof bounds and openGPON/currentfull gates. Fresh F2reviewer quality_final_review is continuing source review, with incremental private notes; rate-limit interruptions are not approvals. Old1dCI36252487886 stillfullserverrunning at17:54UTC;9dCI36259535857pendingzerojobs. Latest checkpoint push may supersede that pending run naturally. Preserve active fullCI. Current fullserver/shared/native/image/legacy evidence and finalF1-F4/tasks46-48 remain open. Continue work after push; no mainmerge/publication/deployment authority.

## 2026-09-27 executed offline protocols and independent browser checkpoint (parent 6bfb1337)

This checkpoint contains evidence and recovery notes only. Product, build, workflow, fixture and runner inputs remain identical to 6bfb13374db245e0f4d192c94ade42fdb07cd613; product sources remain identical to 9d540107. Task46 and finalF1-F4 remain open. Do not restart a full regression merely to relabel evidence-only descendants: compare the tested source inputs and retain the actual tested commit/artifact identity.

Fresh local offline protocol execution passed197tests/35suites: contract2/1, SNMP25/3 including8 documented GPON profiles, and the entire collector170/31. All25Gradle tasks actually executed with --rerun-tasks --no-build-cache. All130captured inputs matched before/after and exact Git bytes; three actual CI JUnit validators passed, runner0. F1 independently parsed every raw testcase, report/runner/log hash and exit receipt. This closes the task23 current offline-evidence gap; it is not hardware certification or a fullCI pass. See local-offline-protocol-r1.json, offline-protocol-r1-source-hashes.json and f1-offline-protocol-executed-verification.*. Raw private reports are under original .omo/runtime/offline-protocol-r1.

Independent F3 continuation passed6/6 (customer-assets4, draft-expiry2), with runner/stop/down0 and the same application JAR d34ddfc9b920794e280d73e44da2c5221bf53a5f2ce324ac15d03c6607130466. Combined with the preserved18main cases, this is24actual successful single-attempt cases across both projects and nine specs, not an uninterrupted24case run. All2486captured inputs match. Independent trace extraction confirms the numeric tenants retain917500mm/9available ONUs/1active accepted loan installation. Separate loan-reuse tenants finish917500mm/8available ONUs/2active installations plus1retired episode; sold-RMA retains CUSTOMER/SALE title,9available ONUs and1active/1retired episode. See f3-main-matrix-authentication.json and f3-asset-wire-authentication.json.

F3 additional edge/restart checks are still open. ExtraR1 had two private-fixture failures from clicking a correctly disabled offline confirmation button; no POST occurred. The corrected fixture separately asserts offline denial and then drops only a real committed backend response to test exact retry. ExtraR2 discovered duplicate private specs because archived harness copies lived recursively under testDir. Reviewer controlled-stopped it (exit143, stop/down0, source/JAR unchanged); preserve partial failures and traces, do not count this as a pass. Private config is being restricted to the exact top-level specs with list-test verification before R3. The wrapper must also forward TERM to its owned timeout process group, since timeout uses a separate group. Reviewer owns the serialized QA lease for corrected extra/restart and then legacy/preflight178.12. Root must not run localQA or mutate product/build inputs until the lease is returned. Private ACTIVE-WAREHOUSE-RUN.json and task46-expiry-quality-review/f3 process/exit records are authoritative for active processes.

Fresh F2 source review and independent local evidence authentication are saved in f2-current-source-preliminary.* and f2-current-local-evidence-authentication.json. No additional blocker within reviewed scope; remaining changed-file review and current full regression are required. F4 current preparation binds3693source inputs and all367migrations, preserving every historical migration byte; final current image/CI/preflight proof remains required. These bounded preliminary reviews are not final approvals. Reviewer sampling rate limits do not constitute test failures or approval; preserve durable QA runners and resume the existing reviewers.

At18:21:35UTC old1dCI36252487886 completed SUCCESS on all15jobs; actual server/historical artifact authentication is underway, so its old raw counts are not yet certified here. Current6bCI36260966532 started18:21:37UTC, including the new forced offline gate and nine browser specs. Preserve this active full run. Required next: corrected F3 edges/restart/current legacy preflight, authenticate currentCI artifacts, finish F1-F4, close source-matched tasks46-48 and integrate validated descendants into original feature worktree40cbd34f. No main merge, publication or deployment. Continue after checkpoint push.

## 2026-09-27 explicit expiry wire expectations checkpoint (parent c122e5a9)

Current6bCI36260966532 completed FAILURE: all14nonserver jobs succeeded, but the compatibility selection ran326tests/50suites with two stale WarehouseContractTest expectations. Its state list omitted EXPIRED and error list omitted DRAFT_EXPIRED; fullserver/historical were skipped. Independent F4 authenticated raw assertions and all report hashes; duplicate report directories are one focused execution. Offline protocols197passed in this CI. See f4-ci-6bfb1337-server-recheck.* and its source inventory. Never promote this to a full server pass.

Corrected only four explicit test expectation lines: receipt/transfer/count EXPIRED and 409 DRAFT_EXPIRED. The exact enum equality and serialization/status assertions remain enforced. No application, migration, workflow or browser input changed. Local contract-expiry-r1 actual15/15passed (12contract,3Modularity) with all3693captured inputs stable, runner0, no DB/Docker started. The previous raw reports were archived before this run. See local-contract-expiry-r1.json and contract-expiry-r1-source-hashes.json. Current unfiltered regression still must execute after this correction.

Old1dCI36252487886 is now independently authenticated COMPLETE SUCCESS:3820modern/617suites,325focused/50separate,7historical+1projection,378historicalinputs,all15jobs. F4 verified675XML files and691encrypted-archive members. See f4-ci-1d14f4b9-server-recheck.json; prior nonserver proof stays unchanged. This is the newest fully authenticated complete baseline, but it predates C7.

Fresh F2 completed the later source delta review with no additional concrete blocker:69server Kotlin files,33web product files,3new migrations plus unchanged posting/quantity/maker-checker/customer/reuse boundaries and selected fixtures. It independently verified the offline197raw cases and current130inputs. Safe f2-source-review-6bfb1337.* and f2-offline-protocol-authentication.json are included. FinalF2 still waits for complete current server/Modularity and F3/release evidence.

The former F3 reviewer repeatedly hit sampling rate limits after R2 cleanup. Fresh independent workflow_final_review now owns the serialized QA lease and independently reauthenticated all24actual main cases. R2 additional partial-count failure expected a second observation button after the measurement had already been saved; actual page correctly showed a40m grid row. Private assertion corrected, archived original sources retained. R3 exact discovery passes2edge+4restart cases. Durable wrapper2452369/startticks29684523 launched18:51:34UTC; process/stop/exit records under original task46-expiry-quality-review/f3/extra-r3-durable-*. Root must not run QA or change frozen inputs while it executes; then reviewer runs legacy/preflight178.12. Use validated durable-stop.py for owned cancellation only. No finalF1-F4/tasks46-48 yet. Continue after push; no main merge/publication/deployment.

## 2026-09-27 independent workflow completion and receipt wording checkpoint (parent c90250d2)

F3 independently completed the required36browser cases:24main across the preserved18+6continuation,2extra edge cases,4restart readbacks and6legacy cases. The legacy runner also executed all8preflight probes at178.12 (2positive,6expected-negative); original two customer/ONU pairs, migration checksums, zero stock and the upgraded MM catalog survived restart. All2486captured inputs and application JARd34ddfc9b920794e280d73e44da2c5221bf53a5f2ce324ac15d03c6607130466 remained stable; all completed runners stop/down0, owned PID records/ports absent. See f3-independent-execution-verdict.json, f3-extra-restart-authentication.json, f3-legacy-authentication.json and f3-supplemental-visual-review.json. Original raw evidence remains private; new reviewer personally inspected20supplemental screenshots and authenticated real wire responses.

The additional edge proof is actual backend commit200 followed by an aborted browser reply and exact same-key/body/original-bytes retry200, one40000mm return,60000mm plus1ONU retained by the old technician after reassignment/cancellation, accepted40m physical segment and one posted partial blind count with unchanged940000mm/9available units. Separate original numeric tenants retain917500mm/9available units, no technician custody, DONE/APPROVED and one active ISP/LOAN/ACCEPTED installation after a distinct backend start. R3 edge2passed but restart2passed/2failed: the private fixture immediately navigated again while reload's authentication refresh was unfinished. Preserve that failed run1/cleanup0. Corrected fixture waits for visible restored state; R4 executed only4restart cases, allpassed0/cleanup0, carrying prior two edge reports by hash with zero edge reexecution. This does not certify arbitrary rapid double-navigation authentication behavior.

F3 found one misleading display claim: technician residual handover stays at its received-for-inspection event even after the separate warehouse return is ACCEPTED. The paragraph incorrectly said inspection was still needed, and the shared label implied ongoing inspection. This checkpoint changes only those two displayed strings: received for inspection, with stock availability following the return document's inspection result. No predicate, DOM structure, status key/tone, DTO, command, permission or physical behavior changes. Intermediate R1 seven page tests/lint/build passed before the shared label correction; current R2 full607tests/117files, E2Etypecheck/lint/build allpassed with523source hashes stable and runner0. See local-residual-receipt-copy-{r1,r2}.json and their inventories. No new copy-only tests. F3 directly reviewed the two-string diff and authenticated the actual R2 report; the old36browser cases keep their original source hashes plus this explicit bounded delta.

F1 refreshed the all48/C1-C11 mapping at clean c902 and prepared48canonical index replacements. Root verified every candidate/prior hash and192verbatim historical-block comparisons before adoption. Old task-time proofs remain historical; no old execution was invented or promoted to current. Seven new reference hashes and current source mapping are recorded in f1-index-refresh-c90250d2-{manifest,integrity}.json and f1-current-evidence-recheck-c90250d2.json; prior matrix is preserved separately. The two later UI wording changes are outside that clean c902 capture and bound by their own R2 source hashes. Current fullserver/F1/F2/F4/tasks46-48 remain open.

CI6b full failure and nonserver success are authenticated and retained. Root cancelled duplicate evidence-only c122CI36263779477 while it was compiling, before focused/full server tests; its code was identical to already-authenticated failed6b, so no new full report was lost and cancellation is not a pass. Corrected c902CI36264069182 started afterward; at19:11UTC it had13successful nonserver jobs, focused compatibility running, full server still pending. This new UI-copy checkpoint needs current nonserver artifacts. After push, supersede c902 before full-server execution only if it is still pending; preserve an already-running full regression and its actual report. Record the resulting active run in the private recovery pointer. Do not count cancelled, pending or incomplete CI as passed. No local QA remains active after webR2. Continue current full regression, final reviews and validated feature integration; no main merge/publication/deployment.

## 2026-09-27 F3 final acceptance records (parent 1306b65c)

Evidence-only checkpoint; every product/build/test/workflow input remains identical to1306b65c34167b2d48f4817ce72990fed3b85aa3. Independent F3 now explicitly PASSES, with no remaining blocking browser/UI finding in its scope. Final report is .omo/evidence/warehouse-workorder-asset-provenance/f3-workflow.md; task46/f3-final-verdict.json and f3-copy-{source-review,web-authentication}.json preserve36intended browser cases,8preflights and current607web assertions plus the two-string delta. Failed/interrupted runs retain their original outcomes. This verdict does not imply fullCI, hardware certification or other reviewer approval.

F4 independently authenticated the same six local legacy browser cases and all eight app-role/read-only preflight outputs. Exactly two original customers/two ONUs and169old migration checksums survive; current367migrations reach178.12. Positive per-tenant counts are customer1/ONU1/movement-header1/position0, not a claim of zero historical/metadata activity. All local runner/stop/down exits are0 and no local QA remains active. Its1306source preparation confirms only the two displayed strings differ fromc902 and all other3691inputs remain identical. Final F4 still needs actual current full CI/image artifacts.

C902CI36264069182 was cancelled while fullserver remained pending. F4 independently verified cancellation during the focused compatibility step, fullserver/historical skipped,47raw artifact members and35protocol XML files. Those197protocol cases passed; no completed focused/server XML was available, so do not infer a count of actually executed focused cases. Verification, cleanup and acceptance steps failed after cancellation; this remote cancelled-run result is distinct from successful local QA cleanup. All14nonserver jobs succeeded at its older UI text. Cancellation is not an aggregate pass.

Current final-code CI is36265279847 at1306b65c, started19:14UTC. Preserve this complete run. Later evidence-only commits do not alter its tested inputs; retain exact source/image identity and compare hashes rather than requiring another full regression solely for notes. Root is continuing until current fullserver/historical/nonserver/image gates pass, finalF1/F2/F4 close, tasks46-48 are accepted and validated feature descendants are integrated. The latest fully authenticated whole-CI baseline remains1d3820modern/617suites plus325focused/50 and7historical+1projection, predatingC7. No main merge, publication or deployment is authorized.

## 2026-09-27 current nonserver archive acceptance (parent 58cce7e0)

Evidence-only checkpoint; product/build/test/workflow inputs stay identical to1306b65c34167b2d48f4817ce72990fed3b85aa3. F4 independently authenticated all15current nonserver archives (13encrypted) from CI36265279847:24browser cases across9specs/18spec-project executions,6legacy browser cases,607web tests/117files,44shared tests,2native compile tasks and8read-only preflight probes. All14nonserver jobs succeeded; no failed/skipped/retried/flaky cases were accepted. See task46/f4-ci-1306b65c-nonserver-recheck.{md,json}; the earlier partial incremental-r1 snapshot is preserved separately. Root verified and adopted exact reviewer bytes and all3693current source hashes, recorded in f4-ci-1306b65c-nonserver-adoption.json.

Actual saved images/configurations and their embedded JAR match the current browser and legacy receipts. The JAR remains d34ddfc9b920794e280d73e44da2c5221bf53a5f2ce324ac15d03c6607130466. All367migration bytes match tested source. V172→178.12 preserves2customer/ONU pairs and169historical Flyway checksums, with per-tenant customer1/ONU1/movement-header1/position0 and no verified stock. Image smoke verifies real HTTP readiness/read/write/replay before and after restart using unchanged images. Publication/deployment was not performed; native compilation makes no runtime/release claim. Existing five negative workflow-gate tests and actionlint exit0 are independently reauthenticated in f4-current-release-guard-recheck.json, without redundant execution.

Current server remains in the focused compatibility step; unfiltered fullserver and historical checks are pending. Preserve the active code run36265279847 and authenticate its actual complete output. This checkpoint is not aggregateCI/F1/F2/F4 approval. F3 finalPASS remains valid with its recorded source-bound two-string delta. No localQA is active. F1 is refreshing final current mapping; F2 must review the bounded last contract/UI changes and current complete regression once available. Continue until tasks46-48, final reviewer gates and validated feature integration are ready for owner acceptance. No main merge, image publication or deployment.

## 2026-09-27 final source review preparation (parent7785fcff)

The focused compatibility CI step succeeded at19:40:01UTC. The unfiltered fullserver/fresh/historical step started immediately afterward and remains running. Root's read-only watcher is original .omo/runtime/watch-final-warehouse-ci.py, with latest safe metadata in watch-final-warehouse-ci-latest.json; its current tool session is81696. F4's matching server verifier/recovery instructions are durable in the reviewer worktree. Resume F4 after the current server artifact is available, then finalF2 and finalF1. No actual current server counts or final approval are inferred from a successful focused step.

F1 prepared final-source1306 mapping and48canonical index candidates. Initial adoption validation found a stale reference name before any canonical file changed. The independent reviewer corrected it to the committed f4-ci-1306b65c-nonserver-recheck.json and rehashed all dependents; no duplicate alias was created. Root then adopted exact corrected bytes, verified all48candidate/prior hashes,192historical blocks and48prior-c902 current blocks verbatim,298implementation inputs and4current index references. See f1-1306b65c-source-evidence.json, task46/f1-1306b65c-proof-reference-correction.json and f1-index-refresh-1306b65c-adoption.json. The draft remains explicitly pending current fullserver/finalF2/F4.

F2 independently reviewed the last four contract-expectation replacements and two UI display literals,137previous manually reviewed hashes and all3693current source inputs. It reparsed actual local12contract+3Modularity cases and607web assertions/117files with523matching web inputs and successful fail-fast typecheck/lint/build. It reconciled the final F3 proof chain and attributed current nonserver findings to F4. No additional blocker found; finalF2 still needs actual current server/focused/historical/Modularity evidence and finalF4. See task46/f2-final-preparation-1306b65c.{md,json}. No QA repeated.

Root cancelled the redundant evidence-only7785CI36266692136 while pending with zerojobs; captured source diff contains only .omo/evidence and .omo/notepads. GitHub confirms completed/cancelled, zerojobs. Active complete codeCI36265279847 remains untouched. This saves duplicate execution and is not an aggregate pass or bypass of the current code gate. Future evidence-only pushes may create another pending duplicate; retain exact input equality and do not cancel a changed-code regression. All private snapshots/reports remain private. Continue the long run to actual current fullCI, final reviews, task46–48 closure and validated feature integration, then owner acceptance.

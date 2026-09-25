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

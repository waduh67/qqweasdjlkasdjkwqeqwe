# Warehouse continuation — 2026-09-25

Continue to completion after each checkpoint. User requested a long run with coherent
commits pushed for recovery by another agent. This note supersedes older runtime
instructions; task46 remains in progress,47/48 and final F1–F4 are not approved.

## Current product checkpoint

This commit adds revisioned PUT/edit UI for saved operational transfer drafts and
V178.10. Original create/update response bytes remain in immutable operation history.
New edits authorize original sender and old/new location scopes, validate the new
receiver/positions, replace lines at the next revision and never post/reserve stock.
Drafts with disabled receivers remain discoverable/repairable. Prior create/edit
replay checks current authority and old/current scopes without rerunning receiver
execution eligibility. Posted transfer bindings remain immutable.

GET obtains topology before reading the record; detail references bind exact line IDs.
History filters each original snapshot's locations before counting/paging. SQL rejects
all-null binding removal, missing command revisions and unrecorded reason changes.
Six new HTTP tests cover full replacement, replay/scope, raw SQL, disabled receiver,
PUT/PUT and PUT/dispatch races, and a controlled PUT/details race. A separate upgrade
test seeds real draft/dispatched commands in178.9 and checks preservation/replay plus
edit after178.10. Three added web tests and the real browser returns scenario exercise
edit. Workflow now selects45focused classes including all transfer regression groups.

Validation so far is SOURCE ONLY:28Python guard tests, actionlint and diff check PASS;
364prior migration files are unchanged. Kotlin compilation, SQL/HTTP/upgrade tests,
web lint/build/unit and current browser/full regression remain pending. See
`task46/transfer-draft-source-checks.json`. Independent reviewer found no remaining
critical/high or migration source blocker after fixes; this is not final F4 approval.

Save/push this checkpoint to `work/warehouse-draft-lifecycle` so its CI can run without
replacing the still-running legacy/report/feature jobs. Local edit branch remains
`work/warehouse-regression-fixtures`; do not assume its same-named remote is current.
The previous remote `work/warehouse-legacy-read-scope` is149ecfb2, report remote
`work/warehouse-report-scope` is a1d4eb19, feature remote is40cbd34f.

## Outstanding implementation and review

- F1 COUNT saved-draft PUT + visible edit, preserving blind counts, original requester,
  immutable rounds/facts/results, old/current replay scope and current counter eligibility.
  Detailed source design: task46/f1-count-draft-design.md. Next migration is178.11.
- C7 idle expiry for operational receipt/material-plan/transfer/count drafts AND retained
  unapproved immutable proposals. Prefer append-only terminal decisions plus SQL liveness
  guards/current read overlays; do not mutate sealed source bodies or post stock.
  Full design/race/locking/projection map: task46/f2-draft-expiry-design.md. No expiry code yet.
- Specialized C8 interpretation: f1-proposal-semantics.md. Corrected successor proposals
  can comply; no blanket PUT on sealed LOSS/TITLE/etc requests is required. Still fix
  unusable TITLE_CORRECTION rework, supplier replacement's false generic edit action,
  and assess missing ASSET_LOSS/TITLE_CORRECTION creation UI callers against plan scope.
- Finish48safe task-N evidence indexes (current draft mapping saved; don't invent missing
  early historical runs), current complete regression, independent F1/F2/F3/F4, runbook
  and release closure. F3 real browser/manual independent review has not started. F4
  independent positive/negative SQL preflight remains pending host availability.

## Active validation and known results

Original checkout `/home/fajar/ftth/qqweasdjlkasdjkwqeqwe`, branch work/warehouse-completion,
remains FROZEN at40cbd34f while local full-server-R7/session39048 runs. Projection1 and
historical7 PASS; modern tests last observed2800PASS/0FAIL, not a complete result.
Safe monitor: original `.omo/runtime/safe-test-progress.py .omo/runtime/full-server-r7.log`.
Original `.omo/runtime/ACTIVE-WAREHOUSE-RUN.json` records live process pointers.
Archive/validate complete R7 output before touching original or starting other host QA.

- CI36131581036 at2c1d8e08 COMPLETE SUCCESS: actual3783modern tests/609suites,
  241focused/23suites,7historical+1projection,375sourceinputhashes,all15jobsPASS.
  Proof task46/ci-2c1d8e08-complete-server-verification.json. It proves that old commit only.
- CI36137258991 ate1782718: compile and30-class focused stepPASS, full server still
  running last check; actual complete nonserver reports revalidated (22browser,6legacy,
  576web,44shared,2native compile tasks,image/restart,8preflights). Source report fix
  moves direct work_order reads to owner API with ordered FOR SHARE area/tenant scope.
- CI36135632377 at40: full server still running last check.
- CI36148238334 atd8a4ae29: COMPLETE FAILURE from test-only Jackson collection overload
  compile errors; server tests never ran. Every nonserver job completed success (not yet
  independently revalidated here).149ecfb2 fixes explicit list mapping without weakening
  assertions. CI36149519466 at149: compilePASS,focused currently running; nonserver running.
- Queued36147421484 ata1d4 may remain pending behind reportCI.3624 duplicate36134311367
  was cancelled before any job only after exact product/test/migration/runner equality to40;
  safe cancellation receipt is saved. Never cancel active useful validation.

Private original-runtime helpers download/decrypt/revalidate CI evidence without printing
raw output: download-ci-evidence.py, download-native-evidence.py, revalidate-nonserver.py,
revalidate-server.py. Adapt hardcoded expected web counts to current actual source/report
when new tests are added; never relabel old evidence. Keep honest failed/incomplete runs.

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

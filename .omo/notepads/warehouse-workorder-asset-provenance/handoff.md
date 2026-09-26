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

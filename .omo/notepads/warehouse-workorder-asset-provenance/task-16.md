# Task16 findings

## Implementation and initial evidence

- Starting checkpoint: 803899f8562fed662142fd5c0b6dd72f220934b2; clean tracked worktree and local/upstream match.
- Before production edits, the corrected baseline ran 1/0 failures/0 skips: task15 acknowledgement of 100000MM leaves issued technician custody 100000MM, consumed zero, usage snapshots/facts zero, and historical issue accepted_base zero. An initial test table-name typo is recorded separately and excluded from feature-red evidence.
- Feature-red ran 2 tests/1 failure/0 skips before production edits. The baseline still passed; report-use82500MM reached the unavailable route. The original XML is retained in task-16/red-test-results.tar.
- InventoryMaterialUsageApi is the new receipt-aware public owner contract. Existing legacy report-use DTO/API remains closed rather than guessing receipt identity from an issue line.
- Inventory owns USAGE documents, immutable header/lines and accepted receipt identities. Fulfillment only coordinates public workorder/IAM/inventory APIs under the existing cutover/authority/WO locks.
- First use creates use revision1. Further submissions are rejected with an explicit corrective-command boundary; no prior snapshot or consumed material is rewritten. SERIAL fails closed until task20. NONE requires an authoritative submitted NONE plan plus reason and has no movements/facts.
- Initial expanded usage gate passed 65/0/0 including exact 38, deterministic race cases, rollback injection, RLS and real WO rejection/resubmission.
- The bounded 255-test gate exposed a consumed guard incorrectly rejecting non-consumed projection rebuild and a validator-catalog enumeration update. V175.17 preserves applied V175.15/.16 and fixes the projection case; the repeated 255-test gate passed.
- A later rollback-only warehouse_app probe reproduced zero-quantity intermediate reclassification of consumed stock. V175.18 was reserved before creation to make consumption terminal across updates and projection replacement. Earlier migration bytes are preserved.
- Manifest-only commits 935142db, f5685f88 and 2b16c9a9 were each immediately pushed. Product verification/delivery is still in progress; these entries are not a DoneClaim.
- CodeGraph is unindexed and LSP refuses this external worktree. Kotlin compiler, Spring, PostgreSQL and packaged HTTP are the verification authority; no clean-LSP claim.

## Final verification

- V175.19 separates the immutable source-plan WO revision from the current locked WO revision. A real failing-first test after /start passed after the correction; type/action/customer and current assignment remain checked.
- V175.20 validates the final projection row for legitimate same-transaction cut/use while retaining terminal consumption. The original posting regression and terminal-state checks pass.
- V175.21 closes four reproduced app-role append paths: duplicate movement header, alternate-namespace header, extra fact without usage linkage, and extra paired legs. All four failed first and now reject. Reads/replays revalidate the stored graph without repairing history.
- Final task16 suite87 passed. Two final exact38 runs used --rerun-tasks and passed. Final posting/schema/contracts/Modularity256, material flows168 and foundation127 passed. XML ledger638 distinct =549 prior +89 task16 (87 workflow cases plus2 posting checks), zero failures/errors/skips.
- The XML checker initially undercounted repeated Gradle parameterized display names. Occurrence-aware identities now preserve all testcase instances and are independently checked against suite counts and malformed-report controls.
- Clean no-cache bootJar executed19 tasks and has SHA256 a95a87fd528cbca6f00f765472894bc0f80153b2d87512c1da151690719e7780. Packaged classes and V175.14-V175.21 bytes match current files; test probes are absent.
- Real packaged HTTP/PostgreSQL QA passed: dispatch100000MM, acknowledge100000MM, start WO, report82500MM, technician17500MM, consumed82500MM, transit0, one usage/fact/posting. Original issue accepted_base remains0. Same-key body survives two SIGKILL restarts; excess/wrong-actor/new-key/revocation/reassignment denials and QA reject/resubmit leave physical state unchanged.
- Manual database role is warehouse_app, superuser=false, bypassrls=false. No fulfillment effect, ONU mutation, return, scrap, loss or disposal was produced. Serialized usage remains closed for task20; corrections/settlement/returns remain explicit future boundaries.
- Cleanup passed: zero owned temporary schemas/processes/containers/network/listeners and two retained volumes. Product commits were individually pushed; this required local notepad is intentionally untracked and excluded from commits. Plan checkbox is untouched.

## Delivery receipt

- Final local/origin SHA: 2a94c125a6d0449ec51ff823b5f4acd415c6a922; ahead/behind0/0. All25 task16 commits have author/committer fajarxfce <fajaralamsyah000@gmail.com> and were pushed immediately.
- Exact38 changed product files and all migration hashes are recorded in `.omo/evidence/warehouse-workorder-asset-provenance/task-16/DoneClaim.json` with status EXECUTOR_VERIFIED. Independent verification remains the parent's responsibility.
- Full evidence retains the baseline, failing-first cases, intermediate failures, corrected XML accounting,638 distinct passing tests, clean build, packaged HTTP/DB proof and cleanup. No evidence, runtime, credentials or notepad was staged.

## Independent correction: T16-CONSUMED-PROJECTION-LINEAGE

- Independent verifier ses_f6c378fb0ffeXPqdr3RxtZYYHG found that zero/deleted consumed projections and reusable descendants could commit while replay still returned200. This supersedes the original executor claim for that invariant; task16 is not marked complete.
- Reproduced before production edits:12 cases/4 failures/8 controls. Zero/delete yielded consumed0 with fact82500 and replay200; direct/deep descendants yielded technician100000, consumed0 and replay200.
- Manifest-first V175.22 SHA256 46268940054dbe1211f477a4d365a18c94b2ecfe1013977138c4a9ccc0296700 anchors final consumed dimensions/quantity and terminal leaf identity in immutable verified posting history. All mutation sides capture old/new identities and ancestors, including DELETE; replay uses the same validation without repair. All predecessor bytes remain unchanged.
- Correction34 cases passed:12 projection/control,18 GUC/timing,3 observed-lock races,1 disposable upgrade case proving both historical corruptions change replay/read from200 to409 without repair. Full workflow121, exact38 twice, posting/schema/contracts256, material flows168, foundation127 passed;672 distinct =638 prior +34 correction, zero failures/errors/skips.
- One unchanged issue-restart test hit the existing empty port-file read race. Its failed batch is preserved, and the complete unchanged168-test batch passed on rerun. No unrelated test code was modified.
- Clean no-cache bootJar SHA256 26fe9ba9c1b84e8d14d028ee20a38e034930394f74a368dc175fa09af9e9977f;19 tasks executed. Current packaged HTTP/warehouse_app QA rejected committed zero/delete/descendant, accepted exact transactional rebuild/zero-restore, and preserved82500 consumed/17500 technician through replay, restarts and QA rejection. No later-task effects.
- Cleanup removed all owned processes/schemas/containers/network/listeners, preserving both volumes and inherited warehouse_schema_5eb76f1cf2b14942b56ec471dba2f94a.
- Six correction commits immediately pushed; local/origin28d9e2d719b888ac38df42f421346820b1bed219, ahead/behind0/0, author/committer fajarxfce <fajaralamsyah000@gmail.com>. Product worktree clean; this required notepad remains untracked.
- Additive correction DoneClaim: `.omo/evidence/warehouse-workorder-asset-provenance/task-16/forward-fix/DoneClaim.json`, status FIXED_PENDING_INDEPENDENT_REVERIFICATION. Original evidence remains intact. No task17 work or plan checkbox change.

## Final independent confirmation and checkpoint

- Executor `ses_f6d2405f0ffe5BE5ng1ZbJLaN2` original claim and forward correction are retained above; verifier `ses_f6c378fb0ffeXPqdr3RxtZYYHG` independently confirmed the corrected task16 as `confirmed`/`high`.
- The consumed projection lineage finding is resolved by V175.22 SHA256 `46268940054dbe1211f477a4d365a18c94b2ecfe1013977138c4a9ccc0296700`; final product head remains `28d9e2d719b888ac38df42f421346820b1bed219`.
- Confirmed numeric result is acknowledged100000MM, consumed82500MM, technician17500MM, with one usage snapshot, one material fact and one posting. Task17 remains unchecked; next action is fulfillment material settlement.

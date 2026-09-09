# Learnings — warehouse-workorder-asset-provenance

Conventions, patterns, and successful approaches discovered during work on this plan.

_Auto-scaffolded by /start-work. Append new entries below - never overwrite._

---

## 2026-09-07T02:16:30Z - Task01 isolated baseline

- Branch baseline ebf98fdf has 169 migrations, max V172, historical gaps V56-V58. Unchanged InventoryMovementLedgerTest passed 4/4 without DB access.
- server:test already uses one fork, forkEvery50, heap768m. Server CI is commented out; web-test is the only enabled test deploy gate.
- Docker socket requires sudo -n on this host. Explicit local socket works; existing Compose projects are not task-owned.
- Spring test annotation properties override environment. SchedulingSpringContextIT had a literal ftth_test URL and needs an explicit SPRING_DATASOURCE_URL placeholder to avoid escaping the warehouse runner.
- test profile intentionally replaces ObjectStorage with memory storage. Environment proof invokes the real S3StorageConfig/S3ObjectStorage adapter directly against task-owned MinIO without changing old test profiles.

## 2026-09-07T02:30:00Z - Task01 final verification

- Exact WarehouseEnvironmentIT command and reruns each executed5 tests with0 failures/skips. Existing ledger+scheduling regression executed5; bootJar metadata matched the executable BOOT-INF archive.
- 25 adversarial assertions passed; separate failed Docker inventory probe refused before Compose. Two real startup SIGTERM interruptions cleaned owned containers and recovered idempotently. A held lock refused after30seconds.
- Explicit down then stop returned0, all four task ports were clear, and all five preexisting container identities remained unchanged. Task-owned volumes retained; no production resources mutated.

## 2026-09-07T02:30:21Z - Task01 commit receipt

- Single atomic commit b9111e1ae64d26ee18e49247678ce79d5175bf33, requested Indonesian message, author/committer fajarxfce. Seven intended files only; worktree clean. No .omo/runtime/build outputs or credentials committed; no push.
- Full DoneClaim and proof are in the task worktree at .omo/evidence/warehouse-workorder-asset-provenance/task-1/DoneClaim.json. Main plan checkbox was not edited by this delegated worker.

## 2026-09-07T03:38:35Z - Task02 executable public contracts

- Unchanged baseline ModularityTests passed 3/3. Reflection-based WarehouseContractTest then executed 12/12 failing tests for missing contract classes/C7 permissions before production edits; no skipped tests or compile-failure-only red claim.
- Final contract filter runs WarehouseContractTest (12) and MaterialWarehouseContractTest (7): 19/0 failures/0 skips, repeated. ModularityTests remains unchanged at 3/0/0; existing PermissionCatalogTest + InventoryMovementLedgerTest passed 7/0/0.
- Clean serial bootJar contains 101 top-level public contract/API/DTO/enum classes. jar tf + javap and byte-for-byte comparison against compiled Kotlin output passed; SHA256 f35dd95298d444873e9fc2964cc2ca2853e8bfc0b0d6d6d77373a55817111e93. XML assertions reject zero/skipped/mismatched testcase counts.
- Held warehouse QA lock refuses exit64 after30s. An untracked sentinel survived a real focused rerun unchanged and was removed afterward. qa.sh stop + test-environment.sh down left zero owned containers/PID records/task-port listeners, both owned volumes retained.

## 2026-09-07T03:41:09Z - Task02 commit receipt

- Atomic commit c136e9c0a4721511a1193ad57a55a2f0d3625456: docs(warehouse): tetapkan kontrak stok dan material WO. Author/committer fajarxfce <fajaralamsyah000@gmail.com>;19 intended files only. Final worktree status clean; no .omo/build/runtime/credentials committed and no push/history rewrite.
- Detailed DoneClaim.json, staged.diff, commands, original red and final XML counts, artifact signatures/hash and cleanup receipt reside in the worktree task-2 evidence directory. Post-commit artifact probe reconfirmed101 public contract types and unchanged executable SHA256. Main checkbox remains for orchestrator confirmation.

## 2026-09-07T04:35:00Z - Task03 exact quantities and identity

- Baseline characterization passed5 tests before production changes: inventory trims serial but preserves case, uppercases MAC without changing separators; inventory domain and ONU rehydration preserve raw strings; ONU creation retains its existing format restrictions.
- WarehouseQuantityTest compiled and ran10 failing tests for missing behaviors before implementation, then the unchanged10 passed. Expanded final exact filter runs102 tests (quantity55, boundary3, conversion16, identity28), repeated102/0failures/0skips. Independent1000-case split/decimal checks and1000-case BigInteger test oracle cover arithmetic beyond fixed fixtures.
- Regressions passed33/0/0: WarehouseContractTest12, MaterialWarehouseContractTest7, ModularityTests3, InventoryDomainTest4, legacy identity baseline5, OnuStatusObservationTest2. Production clean/no-cache Kotlin+bootJar build passed. Packaged JVM probe printed exactly82500|82.500|AA:BB:CC:DD:EE:FF; seven key packaged classes match current compiled bytes.
- Task03 stop/down left zero owned containers/E2E PID records, four task ports free, both volumes retained. Temporary Java source/extracted runtime and dirty sentinel were deleted after assertions; no production environment used.

## 2026-09-07T04:39:12Z - Task03 commit receipt

- Five dependency-ordered atomic commits pair each implementation with direct tests:613a5633 quantity/unit,0a0d2fc8 receipt ratio,cffe5f40 common/inventory identity,2a6cbf40 customer adoption,8f21dd4e wire integration/docs. Final commit8f21dd4e6e71f0744b729af5334579dee6fa93e1 uses requested `feat(inventory): tambah kuantitas presisi dan identitas kanonis` title. All author/committer=fajarxfce <fajaralamsyah000@gmail.com>.
-13 intended files committed; worktree clean and git diff --check passes. No .omo/build/runtime/credentials committed; no push/history rewrite. Synthetic zero/skipped/mismatched XML reports each refused exit1. Full task-3 DoneClaim.json/source.diff/counts/logs/artifact/cleanup retained in worktree evidence; main checkbox untouched.

## 2026-09-07T05:40:00Z - Task04 schema verification

- Baseline task3 HEAD still had169 migrations/maxV172 and clean tracked worktree. Real WarehouseEnvironmentIT passed6/0/0 before new source; initial WarehouseSchemaIT compiled and failed6/6 assertions before M01/M02 implementation.
- Reserved V173/V174 expand20 tenant-owned tables plus legacy dimensions. Full packaged migrations pass both clean and V172 upgrade fixtures; conflicting raw serial/MAC groups remain ownerless CONFLICT claims with all candidates, unknown bulk quantities remain null base units, known serialized legs retain exact1EA.
- Exact WarehouseSchemaIT filter passed26/0/0: metadata6, app-role adversarial12, policy/JPA5, fresh Spring-context restart1, clean/upgrade2. Manual warehouse_app SQL confirms tenantB sees0, cross-tenant FK23503, INSERT42501, immutable tenant UPDATE23514, both versions installed_by warehouse_owner. Evidence under worktree task-4.

## 2026-09-07T06:00:51Z - Task04 final receipt

- After manifest-first V174.1 canonical correction, exact schema suite passed27/0/0. Combined rerun passed170/0/0 (schema27 + task1/2/3 and legacy regressions143). Clean no-cache bootJar executed19 tasks; SHA256642c8ec00274924fad8367ee25543e26a5d49aa4048502cc7053f4842ce482eb.
- App backend self-termination rolled back uncommitted fixture; recovery saw0 rows. Zero-match filter refused exit1, dirty sentinel preserved then removed. stop/down proof:0 owned containers/PID records/task-port listeners,0 temporary schemas,2 volumes retained.
-25 intended files committed in11 dependency-ordered commits, all author/committer fajarxfce <fajaralamsyah000@gmail.com>. Final d3c093cb3ddf43f3b4a3d4267010aa1fd82f82ca subject `feat(inventory): persistensikan dokumen dan saldo gudang`. Clean git status, no .omo/artifacts/credentials committed, no push/history rewrite. Full DoneClaim.json and per-commit diffs under task-4 evidence; checkbox untouched.

## 2026-09-07T07:10:04Z - Task04 independent-verifier corrections

- Independent needs-fix findings were reproduced before production edits: six tests failed. AV-01 logged commitState00000/freshAvailable1 against unchanged V174.1; the same regression now logs23514/0. Unapproved opening and cross-lot/spendable-parent commits also failed-first.
- Manifest-first V174.2 adds deferred source-chain and terminal-position guards plus a closed opening-approval extension point. All prior migration hashes unchanged. Exact schema command passed46/0/0 twice; combined prior170 plus19 new cases passed189/0/0. Clean no-cache bootJar passed19 tasks, SHA256f605030afb6a9984b946ebde14d845682b638f8e3568d510c35e44c78b852356.
- Real TenantApi.ensureTenant now publishes a root event; synchronous MANDATORY owner listeners create policy and IAM epoch in the same transaction. Tests prove actual creation, duplicate delivery, existing-tenant behavior, original GUC restoration, two injected failure rollbacks, and fresh-context restart.

## 2026-09-07T07:15:00Z - Task04 forward-fix commit receipt

-14 intended files committed in6 forward-only commits from d95806b8 through579ced21890caff4bbda31e8934bea437e8cd5c5; every author/committer is fajarxfce <fajaralamsyah000@gmail.com>. No applied migration bytes changed, no amend/history rewrite/push, no evidence or credentials staged.
- App-role manual driver independently rejected AV-01/02/03, then committed a valid same-lot split and read exact quantity on a fresh connection. All disposable schemas and extracted manual runtime removed. Cleanup confirms0 owned containers/PID records/task-port listeners,2 retained volumes. Updated additive DoneClaim is task-4/forward-fix/DoneClaim.json, status FIXED_PENDING_INDEPENDENT_VERIFICATION; same verifier resumption remains required.

## 2026-09-07T08:44:49Z - Task04 aggregate lot capacity correction

- Verifier ses_f8526de97ffeAXdgJag71WChmx confirmed prior fixes and found independent roots exceeding one receipt lot. Six new tests failed before production edits: sequential root100+100 committed with physical/reserved200, and concurrent roots60+60 over baseline20 both committed to140.
- Manifest-first V174.3 locks lot rows and validates aggregate VERIFIED parentless roots at commit, including terminal roots. Red reproduction now returns23514 and total100; latch/pg_stat_activity race observes the waiting lock, exactly one00000 and one23514, final allocation80 of100. No sleeps used.
- Exact schema suite56/0/0 twice, combined199/0/0 including prior AV-01/02/03 and tenant creation, clean no-cache bootJar19 tasks. Manual SQL driver confirmed roots40+60, rejection of third1, same-lot split, retained retirement capacity and impossible terminal deletion. Evidence is additive under task-4/forward-fix/lot-capacity/.

## 2026-09-07T08:49:06Z - Lot capacity forward-fix receipt

- Five intended files committed in3 new commits:2f564419 manifest,71e2869c fixture,39e55604340a5e9666de20fad90195d1046a49f3 capacity migration and direct regressions. Author/committer all fajarxfce <fajaralamsyah000@gmail.com>. No applied migrations amended, no reset/rebase/push, clean worktree.
- V174.3 SHA2564c9db58cb48cf16a2650af527da355880dc38ce01ba0bb4fbb1faea4a354eb97; Flyway checksum-1161602472 installed_bywarehouse_owner. All4 prior migration hashes verified unchanged. Cleanup0 owned containers/PID records/listeners,0 temporary schemas,2 volumes retained. Updated DoneClaim is task-4/forward-fix/lot-capacity/DoneClaim.json; independent verification remains pending.

## 2026-09-07T09:48:09Z - Task04 deferred tenant scope correction

- Nine failing-first/control cases executed before production edits:5 failed,4 passed. Cleared/foreign/stale scope committed excess totals101; balance-only provenance with cleared/foreign scope also committed an invalid extra physical row. Correct/restored over-capacity controls already rejected23514.
- Manifest-first V174.4 now validates captured row tenant at deferred execution and treats missing/invisible capacity lot as23514, with invoker rights and unchanged FORCE RLS. Generic deferred guards cover all stock/source/snapshot tables with deferred invariants. Owner staging fixture now supplies its tenant explicitly.
- Exact schema suite66/0/0 twice; combined209/0/0 with the concurrency race still00000/23514 and total80. Manual app-role four-case matrix rejected over-allocation23514 with fresh100 in every case, while legitimate correct/restored writes committed100. Fresh V174.3->174.4 upgrade and validation/no-op passed.

## 2026-09-07T09:51:28Z - Deferred scope correction receipt

- Five intended files committed in3 forward commits d374d601,c0217df6,055231de91a9e3b47d3db7f98afe6a8b4cddd41a; all author/committer=fajarxfce <fajaralamsyah000@gmail.com>. V174.4 SHA25616404b02edde46bcb7ef0b108b6d234346f0b31abb5dab3ad008bbb32caaaacb, Flyway checksum440566142 installed_bywarehouse_owner. All5 prior migration hashes unchanged.
- Clean no-cache bootJar passed19 tasks, SHA25639abb018a96d4ff826bcf6e91ef1f2f39f7f1ce627375bf9421b51741a4b5cce. Cleanup0 owned containers/PID records/listeners and temporary schemas,2 retained volumes; no evidence/credentials staged or history rewritten. Updated additive claim: task-4/forward-fix/deferred-scope/DoneClaim.json. Independent verifier resumption remains required.

## 2026-09-07T11:12:49Z - Task04 selective constraint timing

- Catalog audit found20 custom DEFERRABLE warehouse triggers bound to8 functions. Six actual validators lacked internal scope assertions; capacity and companion were already guarded. Before edits39 cases ran with12 failures, including exact companion-immediate bypass and every missing internal guard family; correct/restored valid and actual invalid-data controls also ran.
- Manifest-first V174.5 places the scope assertion first inside claim, provenance, source, origin, conservation and usage validators. Final catalog verifies all8 functions have internal entry assertions and SECURITY INVOKER. All prior SQL hashes unchanged.
- Expanded exact schema suite122/0/0 twice; combined265/0/0. Timing matrix covers all8 functions against5 scopes, seven actual invalid invariants, two late-source-mutation cases, catalog completeness and V174.4 upgrade/no-op. Manual52-case driver passed; staged-balance totals remain[40,40,40]. Existing concurrency remains00000/23514 with total80.

## 2026-09-07T11:17:09Z - Selective timing forward-fix receipt

- Five intended files committed in3 forward commits15b8d088,457156a7,a544c03d56afda03d655c5c1761f670125514411; author/committer all fajarxfce <fajaralamsyah000@gmail.com>. V174.5 SHA25617a15b427fb7b92a1231d67e174c033def48dd4bf0d5f45d733431146ca0d5ac, Flyway checksum740912170 installed_bywarehouse_owner. All6 prior migration hashes unchanged.
- Clean no-cache bootJar19 tasks passed, SHA25600456ff792669b5596119545bd146401fa836f2fe8a8f30d22ea54a7c4998c32. Cleanup confirms0 owned containers/PID records/listeners and temporary schemas,2 retained volumes. Updated additive evidence claim: task-4/forward-fix/selective-timing/DoneClaim.json. Same independent verifier resumption remains required; checkbox untouched.

## 2026-09-07T13:12:03Z - Task05 posting verification

- Unchanged baseline passed252 tests. PostgreSQL/Spring WarehousePostingIT compiled and executed10 failing tests for the absent posting binding before implementation; original red log/XML retained, not reported as a compiler-only failure or existing rollback implementation proof.
- Final exact filter passed36/0/0 twice: numeric/restart/phase rollback/usage11, adversarial13, concurrency5, legacy entry points4, reservations3. Combined schema/quantity/contracts/Modularity/environment/legacy regression passed303/0/0.
- Standalone Java harness invoked WarehousePostingService against warehouse_test as warehouse_app. Final SQL:17 headers,36 legs,19 balance rows,2 material facts; paired sum0,0 unbalanced postings,0 serial-position violations. Warehouse917500MM/9EA, consumed82500MM/1EA, technician0MM/0EA. Connection self-termination at LEGS rolled back all partial changes; fresh context retained identical totals/history. Runtime probe excluded simulator/collector jars in final run.
- Clean no-cache bootJar executed19 tasks successfully, SHA256bd798443f5e085d39e1214e46244a331be4a51e6aa612f05461ba738bd828aaf. Legacy in-memory ledger/reconciliation helpers are test-only and absent from executable JAR. V173-V174.5 unchanged.

## 2026-09-07T13:40:08Z - Task05 final receipt

- Final continuity audit reproduced one missing invariant: partial BULK MM moved without lineage. Added failing-first BULK_MM_PARTIAL case (14 tests,1 failure), then required whole-piece moves for every MM identity. Fungible missing-row/multi-dimension races now use EA stock.
- Corrected final exact WarehousePostingIT suite37/0/0 twice; final combined regression304/0/0. XML counts independently checked for nonzero classes, zero skipped/errors/failures and exact testcase counts. Manual service+SQL proof rerun after correction retained17 headers/36 legs, zero unbalanced postings/serial-position violations, exact917500MM/9EA warehouse and82500MM consumed; interruption/rollback/restart passed.
- Final clean no-cache bootJar19 tasks passed; SHA256f3f1367b90031f0ba0291599cafde0ab7ed70b4fcb3f1109cd13afde22f769f0. Runtime probe removed,0 owned containers/schemas/listeners/probe processes,2 volumes retained.
-29 logical files (including2 moves) committed in13 dependency-ordered commits, all author/committer=fajarxfce <fajaralamsyah000@gmail.com>. Final66b32a47869f58bbd58211e7f5094c8235c07a48 subject feat(inventory): satukan pembukuan ledger dan custody. Worktree clean, no migration edits, evidence/credentials/artifacts staged, push or history rewrite. Full claim: worktree .omo/evidence/warehouse-workorder-asset-provenance/task-5/DoneClaim.json. Main checkbox unchanged for orchestrator verification.

## 2026-09-07T15:53:30Z - Task05 AV5 corrections and push

- Read verifier ses_f83de1ba3ffesysl0fn4fQMZNY report; all five blockers reproduced before production edits (28 tests/22 failures,6 controls). Separate wrong-identity/unbound issue-line probes also failed-first (16 tests/2 failures).
- Corrected exact WarehousePostingIT suite69/0/0 twice; combined task1-5 regression336/0/0. All original numeric/restart and five concurrency cases remain passing. Eight rollback phases now use explicit test-only JDBC instrumentation, not production application events.
- Manual warehouse_app SQL confirmed both leg orders ISSUED/ISSUED across12 rebuilds each; transit IN_TRANSIT|false|0 rejected; mismatched fact rejected without debit;3000000000EA fact/effect persisted with legacy quantityNULL and legacy reader0; phase event class absent and external listener observations0.
- Clean no-cache bootJar19 tasks passed; SHA2561afb7fbbcca802fe8a49f94edca0174b73e3ca075cf98ee48d8599da68cce7e8. Packaged phase event/test injector absent; bytecode contains no publisher or phase callback. All migrations unchanged. Runtime probes removed;0 owned containers/schemas/listeners,2 task volumes retained.
-20 files committed in8 coherent forward commits; each immediately pushed normally to origin/feat/warehouse-workorder as requested. Final47398f83491a2861a5dfdf06055a157a3d20107a confirmed by ls-remote; ahead/behind0/0 and clean worktree. All author/committer=fajarxfce <fajaralamsyah000@gmail.com>; no force/history rewrite/config change or evidence staging. Updated claim: task-5/forward-fix/DoneClaim.json, pending same-verifier confirmation.

## 2026-09-07T18:38:29Z - Actual line bindings and picked increases

- Verifier ses_f8358f7b9ffezDBYdyrjPqXgXG remaining cases reproduced before edits: ineligible pick committed IN_TRANSIT|false|0|40; actual B with line/source A committed mismatch1. Full new red suite15 tests/11 failures, with valid descendant/release controls. Nonmaterial source binding also failed-first separately.
- Eligibility now covers picked increases independent of total. Every actual OUT/IN is bound to line and issue lineage, including pending split children. Per-line quantity retains only exact local remnant conservation; full-parent pure remnant split remains valid. Source budgets aggregate current lines and prior facts under a real source-line lock.
- Final exact posting87/0/0 twice; combined regression354/0/0. Eight rollback phases, original reference journey/restart, old concurrency/rebuild and new lock-observed source-budget race pass. Manual app-role SQL confirms rejection/rollback, unpick/release controls and60000MM consumed/40000MM valid descendant.
- Clean no-cache bootJar19 tasks passed, SHA256ba7b0e7327b7a3d746e86f5c4113b8345aaf550ef437b9e05a9d03a5df5a28e0.7 files in4 forward commits, each immediately pushed normally; final383e9dd8588a2b1353d0954d4a728e6adca60713, upstream0/0, clean worktree.0 task containers/schemas/listeners/probes;2 volumes retained. No migration/config/history rewrite or evidence staging. Current claim: task-5/forward-fix/actual-bindings/DoneClaim.json.

## 2026-09-07T20:12:31Z - Retained material facts correction

- Latest verifier report reproduced before production edits: new suite14 tests/7 failures, including100000MM facts against80000MM accepted with only60000MM moved. Retained-only, swapped-equal, cross-line capacity, stationary mapping and pure-local factual claims also failed-first.
- PostingLineAllocation now carries retained/fact-eligible inbound identities and fact capacity from one authoritative position calculation. Facts map uniquely to inbound line, exclude unchanged stock and fit line-local capacity; source budgets/physical retained exclusion are preserved.
- Exact posting101/0/0 twice; combined regression368/0/0. Original reference/restart, eight rollback phases and concurrency/rebuild gates remain green. XML testcase counts verified.
- Independent JVM before/after classpaths: untouched baseline bootJar ba7b0e... committed invalid100000MM facts; corrected code rejects with facts0/warehouse0/technician100000. Moved-only control commits facts60000/warehouse60000/technician40000 in both, as warehouse_app. Extracted baseline and runtime probes removed.
- Clean no-cache bootJar19 tasks passed; SHA256fa2502c12358234087db047d7652fa8a35bea2b46e3a5ab6447eebb905284cd7, no phase/test hooks in artifact. Five files committed in3 forward commits, each immediately pushed normally. Final0a63ac1d9ea74c6c723b4375b3daebc59056f2ec equals origin; clean worktree,0 owned containers/schemas/listeners/probes,2 volumes retained. No migrations/task6/history/config changes or evidence staging. Claim: task-5/forward-fix/retained-facts/DoneClaim.json.

# Issues — warehouse-workorder-asset-provenance

Problems and gotchas encountered during work on this plan.

_Auto-scaffolded by /start-work. Append new entries below - never overwrite._

---

## 2026-09-07T02:16:30Z - Task01 harness findings

- CodeGraph has no worktree index. LSP rejects worktree paths because its tool cwd remains the main checkout; bash syntax checks and real Gradle compilation provide executable verification instead.
- Docker29 internal bridge did not publish localhost ports: NetworkSettings.Ports was null despite HostConfig.PortBindings. Task-only normal bridge plus strict localhost bindings works; no host firewall or existing network was modified.
- MinIO rejects underscores in bucket names. Runner maps warehouse_test/warehouse_e2e to warehouse-test/warehouse-e2e.

## 2026-09-07T02:55:27Z - Task01 verifier correction: _JAVA_OPTIONS

- Independent verifier found the unguarded _JAVA_OPTIONS JVM channel could override datasource/Flyway after the shell printed PASS. Safely reproduced against127.0.0.1:1 before edits: exit1, five failures, not required exit64.
- Added _JAVA_OPTIONS to the existing override guard; regression also checks the four already-guarded JVM option channels and Docker/sudo sentinels. New regression failed before the guard fix, then passed. Real malicious command now exits64 with exact refusal and no environment PASS/Gradle/Flyway output.
- Exact WarehouseEnvironmentIT ran twice with6 tests/0 failures/0 skips; focused ledger+scheduling regression5/0/0. stop/down succeeded, owned containers/PID records gone, task ports clear. Original proof retained; correction artifacts under task-1/java-options-correction/. LSP tool cwd limitation unchanged.

## 2026-09-07T02:56:41Z - Correction commit receipt

- Fix committed as cfed3f5a801cf77bc18ec15634c2d100493c8f69 directly after b9111e1a; no amend/history rewrite. Two product files only, author/committer fajarxfce, worktree clean. DoneClaim updated with before/after evidence; independent verifier resumption remains pending. Plan checkbox untouched.

## 2026-09-07T03:38:35Z - Task02 tooling and compatibility

- LSP diagnostics were attempted on every changed source but reject worktree paths outside tool cwd; Kotlin compilation/tests are authoritative. CodeGraph unavailable by task contract. No tooling/config installation or workaround changed task01 safety behavior.
- Jackson3 moves FAIL_ON_NUMBERS_FOR_ENUMS to tools.jackson.databind.cfg.EnumFeature. Initial test compilation exposed this; corrected before the executable missing-contract red run. Unknown fields and numeric/unknown enum values are rejected with an explicit strict contract mapper, not by claiming legacy endpoint configuration changed.
- unzip is absent; artifact proof uses the JDK jar extractor and cleans its temporary extracted classes. No new dependency installed.
- Legacy MaterialConsumptionApi.forCustomer is explicitly deprecated without changing behavior. Subscriber360's expected deprecation warning remains until its planned V2 migration; unrelated existing compiler warnings were not changed.

## 2026-09-07T04:35:00Z - Task03 verification details

- CodeGraph/LSP remain unavailable for the external worktree by task contract; real Kotlin compilation and focused/modularity tests are the evidence, not an LSP-clean claim.
- Initial characterization fixture used Mockito.any against a Kotlin nonnull parameter and failed1/5 before production changes. Replaced that test-only stub with a default Answer; baseline then passed5/5. Original failure XML/log retained separately; not counted as feature red.
- Initial clean build restored compilation from cache; repeated clean with --rerun-tasks --no-build-cache to prove a real rebuild. Existing unrelated compiler/deprecation warnings remain. Final test organization separates pure domain and application-boundary tests without weakening coverage.

## 2026-09-07T05:40:00Z - Task04 upgrade ordering

- First collision fixture exposed PostgreSQL55006: a deferred claim FK had pending events when later ALTER TABLE added audit/revision columns. Clean empty migration did not expose it. Explicit SET CONSTRAINTS ALL IMMEDIATE before subsequent DDL fixed the real upgrade failure; original failure retained and clean/upgrade reruns passed. Only disposable schemas had been migrated during iteration; retained public schema received final files once.
- PostgreSQL driver is runtime-only in Gradle; test fixture datasource uses Spring DriverManagerDataSource, not a new compile dependency. Two initial fixture compile errors were corrected, not counted as schema red evidence.
- CodeGraph/LSP unavailable for external worktree as required; Kotlin compilation, real Flyway/PostgreSQL and app-role SQL are evidence, not an LSP-clean assertion.

## 2026-09-07T05:50:00Z - Task04 canonical SQL correction

- Final manual probe found default PostgreSQL uppercase/trim differs from Kotlin ROOT for sharp-s/ligatures and non-space whitespace. A new upgrade test failed against V174 with real assertions. Reserved M02 splitV174.1 in docs manifest BEFORE creating SQL, after checking its filename was free; V175-V178 remain untouched and V173/V174 checksums unchanged.
- Forward-only V174.1 adds ICU-root full uppercase and exact Kotlin whitespace trimming, retains previous candidate canonical/claim fields and old reserved aliases, and aligns verified asset claim checks. Clean/upgrade/Unicode plus12 app-role adversarial tests pass15/0/0. Original canonical red log/XML retained.

## 2026-09-07T07:10:04Z - AV-01/02/03 regression correction

- Original green metadata/constraint tests did not validate the complete admission chain: tenant FKs alone allowed VERIFIED positions over unresolved assets/lots, opening state substituted for approval, and quantity-only split conservation allowed foreign lots and live parents. New tests reproduce the precise missing relationships, not just table existence.
- Existing retirement test now explicitly rejects retiring a claim while its segment remains active/positive, then verifies atomic zero-balance/terminal-segment/claim retirement and preserved uniqueness. No test was removed to restore green.
- CodeGraph/LSP remain unavailable in the external worktree. Real compiler, Flyway, app-role JDBC/SQL and Spring context tests remain authoritative.

## 2026-09-07T08:44:49Z - Aggregate allocation and snapshots

- A per-segment quantity bound was insufficient: multiple roots each below received quantity could double the lot. The forward check sums numeric root quantities, never child quantities or ACTIVE-only quantities.
- A lot row lock alone cannot refresh a REPEATABLE READ snapshot after a committed competing writer. Lot segment mutations therefore explicitly require PostgreSQL READ COMMITTED semantics; REPEATABLE READ/SERIALIZABLE fail closed40001 and must retry the entire mutation transaction under that profile. Read-only snapshot queries remain unaffected. Two isolation regression cases pass.
- CodeGraph/LSP unavailable as instructed; no clean-LSP claim. All prior applied SQL hashes remained byte-identical.

## 2026-09-07T09:48:09Z - Deferred invoker RLS is not missing-data tolerance

- V174.3's missing-lot CONTINUE was unsafe: RLS invisibility at deferred execution is not permission to skip an invariant. V174.2 provenance scans could likewise see no rows after context changed. New scope guards reject before those scans; capacity independently hard-fails its missing reference.
- No SECURITY DEFINER or RLS bypass was added. Explicit owner staging now retains the row's tenant scope through commit, even though the migration role itself has BYPASSRLS. The baseline owner fixture was adjusted rather than exempting owners from the invariant.
- CodeGraph/LSP remain unavailable; real Kotlin/Flyway/PostgreSQL verification used. Earlier SQL bytes/checksums were all preserved.

## 2026-09-07T11:12:49Z - Independently schedulable constraints

- A separate companion scope trigger was insufficient: SET CONSTRAINTS could validate it early and leave the actual invariant pending under changed RLS context. Every actual validator now reasserts scope independently as its first statement; no reliance on trigger ordering or companion timing remains.
- Initial manual Java run lacked AssertJ required by the reusable test fixture. Its partial log is retained; adding the already-installed AssertJ jar to temporary driver classpath enabled the complete manual matrix. No production dependency or code change was needed, and disposable schemas/runtime were cleaned.
- Correct-scope missing usage posting retains its original23503 domain/FK failure; scope violations consistently23514. No error behavior was weakened to make tests pass. CodeGraph/LSP limitation remains unchanged.

## 2026-09-07T13:12:03Z - Task05 lock and harness observations

- Creating competing draft lines and posting in the same transaction first acquires FK key-share locks; task04 FOR UPDATE identity/lot fences can then require whole-transaction retry (409). Race tests for normal posting transitions persist drafts first, then race posting. No applied schema guard was weakened; downstream command services must respect this preparation/lock protocol.
- Initial split comparison used an Int zero in a Long equality and rejected a legitimate zero parent; explicit0L corrected it. Original failing numeric log retained. No stock arithmetic uses floating point.
- Standalone test runtime classpath initially included simulator jars and attempted a denied bind on port161. Final manual runner excludes simulator/collector artifacts; completed isolated proof has no simulator startup. Self-terminated JDBC connection logs expected Hibernate cleanup exceptions, but fresh connections prove rollback.
- CodeGraph/LSP unavailable in worktree as instructed. Real Kotlin compiler, PostgreSQL, Spring contexts and executable artifact used instead; no clean-LSP claim.

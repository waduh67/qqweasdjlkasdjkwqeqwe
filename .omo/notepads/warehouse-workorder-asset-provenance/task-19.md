# Task19 Findings

## Executor checkpoint

- Started at 65e3128a3ed8b62884d7fdabd435adde39efdac6, verified task18 ancestor 58e509247bc46b9888d9dd731233aa16c8cb8d15.
- Final product checkpoint cbc0093a0b9354d310b86ac627c1a30dac83e233. Nine scoped commits, each immediately pushed. Author/committer remains fajarxfce; no identity overrides or history rewriting.
- Reserved M04 V175.48-.54 before creating their SQL. V175.47 and predecessors remain byte-identical; no V176+ used.
- Existing ONU rows retain IDs/raw serial/customer links and original topology as LEGACY_UNRESOLVED. Assignment history references existing physical asset IDs. No stock or receipt is inferred from legacy ONU data.
- Verified active identity/interval guards use physical row locking and final-state half-open interval checks, with independent tenant assertions. Sequential reuse adds new ONU/assignment records; old history remains attached to its original customer.
- Typed AssetAssignmentPersistence exposes owner append/close/history only. InventoryDeploymentApi remains unimplemented; tasks20-23 operational behavior was not added.

## Verification and pitfalls

- 362 distinct tests passed across bounded suites: final task19/quantity/identity/customer/modularity 144, warehouse schema 129, task18 lifecycle/rework 75, monitoring/GIS 14. Exact CustomerAssetSchemaIT 19/0/0 repeated.
- Initial schema red counts were observed but original XML was overwritten; integrity red 4/4 and canonical red 1/1 are retained. Do not claim every early run has archived XML.
- SQL CHECK treats NULL as success: a VERIFIED whitespace raw serial could hide behind a valid canonical column. V175.54 uses total comparison with NOT VALID to preserve boot compatibility while rejecting new invalid rows. No applied migration was edited.
- Real receipt testing exposed a PL/pgSQL record/table-alias ambiguity; V175.52 fixes it forward-only.
- Existing GIS/monitoring tests created serial-only ONUs as setup. Their fixtures now explicitly stage legacy rows as migration owner, without weakening app-role admission or adding a production test hook. Monitoring assertions still run through real HTTP/collector paths.
- Packaged final bootJar SHA256 f1e99368d743a611b5c36ecda5c47f5b864bf44a4bcc03a65a41c34d97083800. Clean no-cache build executed all 19 tasks.
- Manual packaged migration preserved two colliding and one malformed ONU; two real servlet boot contexts returned HTTP200 UP. Mixed LEGACY/ENFORCED tenants and legacy rows survived restart. Separate real-receipt reuse fixture preserved A's row/telemetry, activated B, denied overlap/foreign scope and changed receipt/stock totals by zero.
- Pending app authorization interrupted through its own backend PID rolled back both authorization and history. Dirty sentinel hash remained unchanged. Failed initial manual boot logs are retained but excluded from passing evidence.
- CodeGraph is unavailable and LSP refuses the external worktree; real compiler/Spring/PostgreSQL and packaged bytes are authoritative. No tool/index configuration changed.
- All newly changed source files meet the 250 pure-LOC ceiling. MonitoringEndToEndIT remains in the warning band at 213; its extracted fixture is 88.
- Evidence: .omo/evidence/warehouse-workorder-asset-provenance/task-19/DoneClaim.json and associated XML/manual/build logs. Independent review and plan checkbox remain orchestrator-owned.
- Cleanup stopped owned processes and removed owned containers/network only; volumes and inherited public schema are retained.

## T19-AV-01 forward correction

- Same verifier: ses_f6106f2ddffeO2YMQRB9OAUq0x. Task19 remains unchecked; this is a correction claim awaiting independent re-verification, not task19 completion.
- Base cbc0093a0b9354d310b86ac627c1a30dac83e233. New product head 5ac5bc09213ae3617cf5898b0db5089bd3646a49, local/remote identical, ahead/behind 0/0. Five coherent commits were pushed immediately, using unchanged fajarxfce Gmail identity.
- Red: four real app-role tests, three failures because mismatched INSTALL and unresolved REMOVE (CONFLICT and LEGACY_RESERVED) committed; exact valid acknowledged-issue control already passed. Red log/XML retained under task-19/forward-fix.
- Reserved and added V175.55/.56 only. All V175.48-.54 and predecessors retain their prior hashes. The shared validator requires a VERIFIED active serialized asset, ADMITTED identity claims, real verified origin and purpose-specific source bindings. Current authorization and initial history validate the same final truth.
- INSTALL/REPLACE/RMA return require the exact acknowledged physical issue. REMOVE uses a verified same-asset prior assignment, not an arbitrary issue. RMA return additionally requires customer ownership and SALE mode; it never permits unresolved origin. REPLACE binds a different prior physical asset for the same customer.
- Every current/history/source deferred validator asserts its captured tenant internally. Source coverage includes I/U/D, old/new identity discovery, physical row locking and existing claim/origin locks. Actor references gain a NOT VALID composite tenant FK without rewriting old rows.
- The validated SQL read gate rejects preserved invalid authorization history. Bad pre-V175.55 requests remain raw-readable for reconciliation, but cannot pass validated reads or consumption updates. No migration scans all old requests or waits for reconciliation.
- Final bounded gates passed: task19/auth/integrity 72; schema/quantity/identity/customer/ONU/Modularity 267; task18 lifecycle/rework plus monitoring/GIS 89. After excluding the repeated 19-case schema class, 409 distinct tests passed. Authorization-specific coverage is 47 cases; exact CustomerAssetSchemaIT ran twice at 19/0/0.
- Deterministic retirement races admit exactly one commit. Normal/cleared/mismatched/restored/selective timing, source changes after early validation, purpose controls, immutable metadata/history and bad-record upgrade all pass.
- Clean no-cache bootJar SHA256 42e4db55010f4a3944f5cf0f1411ee27dba4290bf89ed7e4679a682ddca8c566. Packaged manual proof rejects mismatch/unresolved requests with 23514 and zero history, commits valid authority with exact history, preserves bad historical authority across upgrade, and retains collision/restart/sequential-reuse behavior. Valid pending authorization interruption returns 57P01 and rolls back both rows.
- Structural guards do not implement current IAM/cutover freshness, repair-case validation or task20 mint/consume/install workflow. Unconsumed authority pins source validity; consumed immutable history does not permanently prevent later physical retirement. Future executable readers must use the shared assertion rather than treat raw history as authority.
- Evidence is additive: .omo/evidence/warehouse-workorder-asset-provenance/task-19/forward-fix/DoneClaim.json. Previous evidence is preserved. Tooling limitation remains: CodeGraph unindexed and external-worktree LSP rejected; compiler/real PostgreSQL/artifact gates are authoritative.
- Owner contexts/connections, temporary schemas, extraction directories and owned containers/network cleaned up. Volumes and inherited schema retained. This notepad remains untracked and is not staged.

## T19-AV-02 timezone correction

- Same verifier ses_f6106f2ddffeO2YMQRB9OAUq0x; task19 remains unchecked. Base 5ac5bc09213ae3617cf5898b0db5089bd3646a49, new local/remote head b219e9e87cda6d5f85df3eac8f40c022a79b7c58, ahead/behind 0/0. Four coherent commits each pushed immediately using unchanged global Gmail identity.
- Red read/source tests both failed. Stored created_at 2026-07-20T10:00:00-04:00 and regenerated UTC 2026-07-20T14:00:00+00:00 had equal native instants and every other JSON field equal. New York validated; UTC raised the exact-history error. Red values/log/XML are retained under task-19/timezone-fix.
- V175.57/.58 were reserved before creation. V175.55/.56 and all predecessors remain byte-identical. No history JSON rewrite and no global/database timezone change occurred.
- Shared warehouse_authorization_snapshot_matches discovers native timestamptz fields from the actual authorization composite row type. It enforces exact keys and non-timestamp JSONB values, parses explicit-offset timestamp text and compares instants null-safely. Both current fields, created_at and nullable consumed_at, are covered. Native history recorded_at remains immutable audit metadata outside the JSON payload.
- Validated read, consume/update and source deferred guards all retain the shared authorization assertion; its snapshot predicate and authorization history-insert validation now use the same semantic helper. Assignment-history behavior is unchanged.
- The malformed +25:00 control exposed PostgreSQL's separate timezone displacement error. V175.58 handles it as a non-match without editing applied V175.57; supplemental red evidence is retained.
- New coverage: 88 timezone/mutation cases. Creator/reader matrix covers UTC, America/New_York, Asia/Kathmandu and Australia/Lord_Howe. Both New York's one-hour DST fold and Lord Howe's half-hour fold retain distinct instants. Consumed timestamp history, same-backend pool reuse, pool restart, future nullable expiry, malformed/missing/extra fields and all 21 non-timestamp bindings are covered.
- Bounded gates passed: task19/auth/integrity 160; schema/quantity/identity/customer/ONU/Modularity 267; lifecycle/rework/monitoring/GIS 89. Excluding the repeated 19-case schema suite gives 497 distinct passing tests. Exact CustomerAssetSchemaIT passed twice at 19/0/0.
- Invalid pre-V175.55 authorization history remains unchanged; validated reads and consumption reject its physical-source defect in all four timezones, rather than failing on offset formatting.
- Clean no-cache bootJar SHA256 80d630be8f45d089dad8b18933b8a9165240050dd8c30153e06a2ea4c49b0546; all 19 build tasks executed. Packaged authorization 8f90eb8b-2bd6-4868-9589-ff9fd7f65306 validated and revalidated source changes in all four zones without changed history bytes; true instant mutation compared false and native history-time update rejected23514. Cross-zone consumption and fresh-connection reads passed.
- Packaged AV-01 rejection/positive controls, invalid-history upgrade, collision boot/restart, mixed tenant stages, chronological reuse, zero receipt/stock delta and interruption rollback remained green. No task20 runtime behavior was added.
- Additive claim: .omo/evidence/warehouse-workorder-asset-provenance/task-19/timezone-fix/DoneClaim.json. Prior evidence preserved. CodeGraph remains unindexed and LSP rejects external-worktree paths; actual compiler/PostgreSQL/Spring/artifact gates are authoritative.
- Owned contexts/pools/connections, disposable schemas and extraction directory cleaned up; QA stop/down succeeded, volumes and inherited schema retained. No local notes/evidence/runtime/secrets/build artifacts staged. Future schema evolution must explicitly preserve snapshot shape; missing keys are deliberately not guessed as null.

## Final state

- Task19 is confirmed and marked checked in the plan. Executor/verifier receipts, V175.48-.58, and the next task20 action are preserved above and in the tracked ledger.
- Task20 remains unchecked; no task20 implementation or runtime behavior was started.

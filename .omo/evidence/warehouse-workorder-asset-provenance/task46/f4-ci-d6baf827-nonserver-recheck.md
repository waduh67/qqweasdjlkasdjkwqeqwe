# F4 independent nonserver CI recheck: d6baf827

Verified completed nonserver evidence for `d6baf827ce9bcdae6222acc7c63b8fe64b89fa87` in CI run36237393856. **The overall run failed:** server compilation passed, focused compatibility failed, the full server/fresh/historical stage was skipped, and aggregate acceptance failed. All13 nonserver jobs succeeded. This note is not a server pass, later-source approval or final F4 approval. Root retains the server artifact investigation.

| Actual evidence parsed | Result |
|---|---|
| Web Vitest |581 passed tests|
| Shared KMP |44 passed tests across all seven required modules|
| Modern Playwright |22 passed tests across eight specs; both desktop/mobile projects per spec (16 spec/project executions)|
| Legacy Playwright |6 passed tests over before/after/restart, both browser projects|
| Native compile log |Both iOS ARM64 and simulator ARM64 tasks actually COMPILED; no native runtime/release claim|
| Read-only preflight |2 positive and6 intended negative checks against upgraded178.11|
| Packaged image smoke |Before/after restart each reports14 persisted replays,16 reads,4 stock kinds and3 served static assets|

Actual JSON/XML testcase/result entries agree with their published counts and report hashes. Browser results have no failures, skipped cases or retries; shared XML has no failures/errors/skips and exact testcase counts. Vitest assertions are all passed with no pending/todo/failed tests. Native task records and success markers were checked against its actual log, not inferred from the job label.

Downloaded14 artifacts privately:12 encrypted evidence archives, the native compile artifact and the full saved image archive. Every ZIP matches the GitHub SHA-256/size/source-run metadata. Every encrypted archive matches its encryption manifest and was authenticated with the existing authorized age identity, then extracted under private permissions with relative regular-file paths and the expected file count. No keys, HTTP payloads, XML system output, traces or fixture rows are included in this safe report. Completed downloads were reused when the final customer-assets artifact arrived.

The actual legacy database snapshots preserve exactly2 customers and2 ONU records through upgrade/restart, including their IDs/values, and retain all169 prior Flyway checksums. Pinned169 historical migration files are byte-identical to Git d6baf827. The upgraded snapshot has366 migrations through178.11. All eight raw preflight output hashes match; positive reports use warehouse_app/public/ENFORCED and BEGIN/ROLLBACK, with each scoped tenant retaining1 customer/1 ONU,0 positions,1 zero-opening movement and no verified stock quantity. Negative reports fail for the intended migration, unit and cutover mismatches. The preflight SQL SHA-256 matches Git at the tested commit. These probes were executed by CI; this reviewer did not execute a new database probe.

The saved server/web Docker tar hashes and image configuration hashes match the smoke receipt and source labels. Exact tested identities are server `sha256:a007e9251fcb28c1ec27203843358251fecadf74c2e1c68f66d7d6e7122a8a9c` and web `sha256:1620720667558363750230af117f5f43451bd59fe749629e10152e26132eb30a`. The embedded server JAR SHA-256 is `22de1ff7dc9f30cacd08e66339fe3ee6ff4c20b6eca6152dddef50e0c15e6c55`, identical to the artifact hashes in all eight browser receipts and the legacy receipt. All366 embedded migrations and both packaged application YAML files match Git bytes. The image has15 static web files, and its nginx configuration matches source. Actual private smoke phase reports and saved state counts agree with the published proof. No Docker load/run, registry publication or deployment was performed by this reviewer.

`f4-d6-source-input-hashes.json` records SHA-256 values for3,282 tracked Git inputs at this exact commit across workflow/build scripts, server, web, mobile, migrations and relevant deployment configuration. Its manifest digest and all report/archive hashes are recorded in `f4-ci-d6baf827-nonserver-recheck.json`. This uses the tested Git objects, not the older detached review worktree's product files.

No nonserver artifact remains pending for d6baf827. Outstanding scopes are the failed server/focused gate and required complete regression, later proposal/expiry changes and their affected gates, any required final release preflight, and independent final F4 review. Earlier successful commits cannot supply those missing results. No local QA/build/browser/database process was started.

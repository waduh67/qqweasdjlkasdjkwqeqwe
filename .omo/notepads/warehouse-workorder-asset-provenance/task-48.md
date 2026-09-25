# Task48 — CI/release gates (IN PROGRESS)

Foundation: warehouse workflow requires full server + historical upgrade, web checks,
seven shared KMP test modules, eight real browser specs, legacy UI cutover/restart,
and native compile. Deploy build/publish requires the reusable workflow to succeed.
Result validators reject empty/failing/skipped/flaky/missing cases; exact acceptance
script rejects every failed/cancelled/skipped/missing prerequisite. 17 tests pass;
actionlint passes. Raw reports are age-encrypted, public summaries are allowlisted.
Existing maintainer SSH Ed25519 PUBLIC recipient configured as repository Actions
variable; real five-report roundtrip byte-identical. Private key never uploaded.

PENDING: remote full CI run, task-owned Docker image smoke (backend JSON/read/write,
frontend routing, restart and same image identity), publication dependency proof,
final compatibility/runbook audits. Current Dockerfile/context changes are drafts.
No image was published, no main merge and no production deployment performed.

## First remote CI run 36114803368
At 42356cf2: web 576 tests / 115 files PASS, lint/typecheck/build PASS; native
iOS Arm64 + simulator Arm64 COMPILED; shared 44 tests in all seven modules passed
but result validator rejected standard KMP [jvm] suffix. Regression red1 of11,
corrected parser green11/11 and validates the identical decrypted CI reports.
Full workflow correctly FAILED acceptance. Server/browser/legacy never executed:
pinned official MinIO registry now returns unauthorized; DockerHub alternatives
also denied and official binary download returns410. Source release is accessible.
Preparing QA-only source build with fixed upstream tag/commit/tar checksum and
base-image digests; no byte-equivalence claim to old vendor image.
Full-server R2 was safely terminated while still QUEUED (empty log), so final full
server gate can use the fixed dependency configuration. It ran zero tests.
R5 browser continues using its original environment; no source/config change.

## QA dependency source build verified
MinIO source build completed successfully from the checksum-pinned upstream release
archive and pinned Go/Alpine bases. Runtime reports correct release and source commit.
QA Compose now builds this local image before startup (separate cold-build timeout);
Timescale points to the already-tested immutable registry digest. No production
Compose changes. All seven WarehouseEnvironmentIT tests PASS, including real S3
round-trip, PostgreSQL role/marker/migration and forbidden override guards. Exact
source file hashes/image identity in task48/minio-source-verification.json.
Full-serverR3 session11018 now runs historical then complete modern server against
this source-built MinIO. PreflightR2 session47709 follows under host lock. Do not
edit their wrappers or tested backend/QA source while active.

## Exact-image gate checkpoint (real smoke execution pending)
Added image-smoke.sh/image-http.py using separate owned fixture DB, exact image IDs,
real Nginx bundle+gateway, backend+gateway JSON identity checks, real wave5 receipts/
transfers/counts/reviewer authority and full restart/replay. No SQL business seeds.
The warehouse images job builds/loads once, smokes, archives and binds saved image
configuration/tar hashes. Deploy loads these same images, verifies both before any
push, checks registry manifest config digests, and uses immutable registry references.
The reviewed production Compose is uploaded to a versioned release directory and
SHA-checked before use, preserving existing env/mount base/project. No actual image
push/SSH/deploy performed. Feature CI verifies only; publish remains main-only.
24 guard tests PASS, including execution of acceptance with each failed/skipped/
cancelled/missing prerequisite and archive/tag/revision validation; actionlint PASS.
Real Docker smoke still PENDING until CI executes it; no task48 completion claim.

LIVE full-serverR3 session11018: environment7PASS, historical projection phase PASS,
now complete modern server suite. It archives all XML before any focused rerun.
PreflightR2 session47709 queued after full server. Do not edit their wrappers or
backend/QA harness inputs while they execute. Product browser22 and legacy6 verified.

## Actual image CI PASS at c622a85b
CI36116539885 images job PASS. Real server/web Docker images verify3bundleassets,
14 immutable command replays,16 stock/document/history snapshots,4 stock kinds,
backend+gateway JSON role/DB marker, and identical container image IDs after restart.
Exported image archives were downloaded and their SHA256/config hashes rechecked
locally; safe proof docker-image-smoke-verification.json. Full archives retained
privately under .omo/runtime/ci-remote-r2/tested-images. No registry push or deployment
was executed. All eight CIbrowserjobs,legacy6,web576,shared44 andnative2 also PASS.
Server job still running; overall release is NOT PASS until full regression green.
Warehouse workflow now queues checkpoint pushes rather than cancelling the ongoing
full regression, so complete reports survive frequent recovery commits.

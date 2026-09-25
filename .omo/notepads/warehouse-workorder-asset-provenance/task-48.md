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

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

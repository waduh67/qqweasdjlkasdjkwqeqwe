Completed C8 nonserver artifact review for run 36252487886 at `1d14f4b9aebcd1c1a9e8c84041259ff56a422005`. All thirteen nonserver jobs have successful metadata and actual passing artifacts. The server gate remains in progress; its results, aggregate CI and final F4 acceptance remain pending. This evidence covers 1d14f4b9 only, not uncommitted C7 or migration 178.12. No local QA or product edits were performed.

Actual authenticated passing results:

- 22 browser tests across 8 specs / 16 spec-project executions.
- 6 legacy-browser tests, 595 web tests, and 44 shared tests across seven modules.
- 2 native compile tasks, confirmed from the actual log. No native runtime/release certification is claimed.
- Two positive and six negative read-only preflight probes. Exact rows for two original customers and two ONUs remain unchanged after upgrade/restart; all 169 prior Flyway checksums and 169 pinned migration files are preserved. No new SQL probe was executed by this reviewer.

All 14 selected archives matched GitHub digest/size metadata; 12 encrypted archives were authenticated with the authorized age identity. Actual reports match their safe receipts and contain no failure, error, skipped or flaky result. Completed downloads were reused. Private archives and decrypted material remain under this review worktree's `.omo/runtime/ci-run-36252487886`.

The saved Docker image archives were inspected without loading or running them. Config hashes, source labels and actual image-smoke receipts agree. Embedded JAR SHA-256 `fbadae89b73777da16018bfbee3d3414ba2b3bfe092c48bbd55e375951037590` matches every browser/legacy receipt; all 366 migrations, both application configuration resources and the nginx configuration match exact Git source. Image smoke verifies fourteen persisted replays, sixteen reads and four stock kinds before and after restart. Nothing was published.

All 366 migration filenames and SHA-256 values are unchanged from verified 99ad, with highest version 178.11. `f4-1d14f4b9-source-input-hashes.json` binds 3,287 source inputs to this commit. The adjacent JSON contains reviewed artifact/report/source hashes, counts and job metadata. There are no pending nonserver artifacts; current server evidence and final F4 remain outstanding.

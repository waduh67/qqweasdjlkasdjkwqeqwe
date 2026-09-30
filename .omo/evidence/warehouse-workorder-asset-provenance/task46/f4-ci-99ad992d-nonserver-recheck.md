Completed nonserver artifact review for CI run 36238965245 at `99ad992db31a6922ec1adf1957af81bf747426e6`. All thirteen nonserver jobs have successful metadata, and their actual artifacts were independently authenticated and parsed. The full server gate is still running; aggregate CI and final F4 remain pending. This evidence does not cover later uncommitted C8 changes.

Actual passing evidence:

- 22 browser tests across 8 specs and 16 spec-project executions, plus 6 legacy-browser tests.
- 584 web tests and 44 shared tests across seven modules. The web count is read from this artifact, not transferred from the earlier d6 run.
- 2 native compile tasks, checked against the actual log. No native runtime or release certification is claimed.
- Two positive and six negative read-only SQL preflights, including migration-version, unit and cutover rejection. The two original customer rows and two ONU rows remain exact through upgrade and restart; all 169 historical Flyway checksums and 169 pinned migration files are preserved. The upgraded database has 366 migrations through 178.11.

All 14 selected artifacts were downloaded and matched GitHub digest/size metadata; 12 encrypted archives were authenticated with the authorized age identity. Complete downloads were reused. The adjacent JSON contains actual report hashes/counts, artifact identities and job metadata. Raw XML, logs, traces and decrypted fixtures remain private in the review worktree's `.omo/runtime/ci-run-36238965245`.

The saved Docker archives were inspected without loading or running them. Config hashes and source labels agree with the image-smoke evidence before and after restart. Embedded JAR SHA-256 `9a84a1561ef9e950a6de2e6b0e1fc3707c684a2398dfc5503311beda0701293e` matches every browser and legacy receipt. All 366 embedded migrations and 2 application configuration resources match this Git commit; the nginx configuration also matches. Image smoke records fourteen persisted replays, sixteen reads and four stock kinds in each phase. These are tested image identities; nothing was published.

`f4-99ad992d-source-input-hashes.json` records 3,282 source-file SHA-256 values from the exact tested Git commit. No local Java/Gradle/browser/Docker/DB QA or new preflight was executed by this reviewer. Current server XML, the completed aggregate gate, later-source verification and final F4 approval remain outstanding.

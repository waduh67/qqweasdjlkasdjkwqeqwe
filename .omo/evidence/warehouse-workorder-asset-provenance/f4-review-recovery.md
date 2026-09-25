# F4 review recovery

Current reviewed source is detached e17827188175d51a9281cc56c50b950f4fe9e38f in
`/home/fajar/ftth/qqweasdjlkasdjkwqeqwe/.omo/runtime/warehouse-f4-review`.
Read `f4-release-preliminary.md` and its four adjacent safe JSON records.

Completed: 25 lightweight negative/control tests and actionlint; 364-migration
SHA256 inventory and pinned-history comparison; current image artifact download,
GitHub ZIP digest, both Docker archive/config/tested-ID checks; embedded server
JAR / all migrations / application config and web static/Nginx inspection; private
revalidation of 12 encrypted archive hashes, actual 22 browser + 6 legacy + 576
web + 44 shared cases, native compile log, image phase reports, old/new/restart
legacy rows/checksums, and existing 2 positive + 6 negative preflight outputs.

Do not redownload completed images. Private archive state is
`.omo/runtime/f4-current-images/{artifact.zip,server.tar,web.tar,verification.json}`.
Do not Docker-load or publish. Embedded JAR hash is exactly the CI browser JAR
hash 5c7767be59fa804a8e73ff7ee6d63ac8c2dad41786d957573a75b937660dd870.

Existing raw evidence is under original workspace `.omo/runtime/ci-run-36137258991`.
The root safe nonserver summary is under
`.omo/runtime/warehouse-regression-validation/.omo/evidence/warehouse-workorder-asset-provenance/task46/ci-e1782718-nonserver-verification.json`.
Never print raw XML/system-out, env, login/session data, traces, or fixture rows.

Approval remains withheld. Root reports F2 real compatibility read scope/projection
defects and F1 draft edit/expiry gaps. Review final fixes and current full server /
aggregate evidence. Root must coordinate independent F4 SQL preflight under the QA
lock; this reviewer only parsed existing CI outputs. Do not start application,
DB, Docker, browser, or Gradle work without root coordination. No product edits,
plan checkbox changes, Git mutations, publication, merge, SSH, or deploy are allowed.

Root classified feature-diff whitespace in immutable V175_132 lines264/450 as a
non-blocking immutable exception; do not change its bytes. Current worktree diff
check is clean. The inherited 273 tracked .omo files were reported to root for
owner-override context against the plan default; no new review evidence was staged.

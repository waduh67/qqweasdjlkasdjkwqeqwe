# F4 preliminary source and artifact review

Verdict: **approval withheld**. This is a preliminary review of commit
`e17827188175d51a9281cc56c50b950f4fe9e38f`, against feature base
`ebf98fdf270b30ac30b7a01b1f609b39e8414618`. It is not `f4-release.md` or a release
approval. Known product findings from the other independent reviewers remain
open, full current gate evidence is not established by this report, and this
reviewer has not executed a new SQL preflight.

The reviewer used the detached `warehouse-f4-review` worktree. Product source,
plan checkboxes, other worktrees, database state, and running QA resources were
not changed. No Java, Gradle, Docker, browser, DB, SSH, publication, commit, push,
or deployment command was executed. Image inspection used Python archive readers.

## Verified release artifacts

CI run `36137258991`, tested-image artifact `10865254666`, was downloaded into
this worktree's private `.omo/runtime/f4-current-images/` directory. Its ZIP
SHA256 matches GitHub's artifact digest:
`f1c4918b1759b8ff8f2469fd0fedebba21f35ef8fa7ad3bb1af7b6e7668bff43`.

Both Docker archives match their recorded archive and configuration SHA256.
The configuration SHA256 also equals the actual tested immutable image ID:

| Service | Tested image ID |
| --- | --- |
| server | `sha256:754d74c805d804a570a925718488cfe46988f6debcb11ecaef934cec3b947ce1` |
| web | `sha256:4c06e4663040669c5ed77f474687fd7f26725ffc0960c7ff9390ba25985924c2` |

The embedded server JAR SHA256 is
`5c7767be59fa804a8e73ff7ee6d63ac8c2dad41786d957573a75b937660dd870`, exactly the
JAR recorded by all eight current browser lanes and the current legacy-upgrade
application. All 364 embedded migrations match current source bytes. Embedded
`application.yml` and the explicit QA profile also match source. The runtime
entrypoint and non-root `ftth` user match `server/Dockerfile`. The removed
in-memory inventory ledger/approval/reconciliation services are absent from the
JAR. No private runtime, env, or raw test files occur in the application payload.
No warehouse, Spring override, or credential environment variable is baked into
either image configuration.

The web archive contains 15 static files, including actual HTML/JS/CSS. Its
Nginx config matches `web/nginx.conf` byte for byte. The image/config and Compose
hashes are recorded in `f4-release-images.json`. Images were not loaded, run, or
published; no registry publication result is claimed.

## Dependency gates and executed negative checks

`.github/workflows/deploy.yml` requires the complete reusable warehouse workflow
before `build-and-push`; deployment requires `build-and-push`. The warehouse
acceptance job requires server, web, shared, all eight browser matrix lanes,
legacy upgrade, native compilation, and image smoke. There is no conditional
skip or `continue-on-error` on those prerequisites. The full server invocation
has no test filter, and focused XML is removed before the full suite starts.

Publication downloads this run's `warehouse-tested-images-${github.sha}` archive;
it does not rebuild server/web. `scripts/warehouse/publish-images.py` validates
commit, archive digest, saved config, tested ID, and main-run identity, checks
both loaded images before any push, then checks the pushed manifest's config
digest. Deployment consumes immutable registry digest references and the exact
Compose file, checking its SHA256 in the release directory. GenieACS remains a
separate existing build, selected by commit tag. Review did not execute any
publication or deployment step.

Executed locally on this source: 11 result-parser tests, 4 real age encryption /
decryption tests, 5 workflow tests, and 5 image-proof tests; all 25 passed.
`actionlint` passed for warehouse, deploy, and mobile-materials workflows.
The workflow tests execute the actual acceptance script for every prerequisite
with failure, skip, cancellation, or absence and require rejection. Parser tests
reject missing/empty reports, failed migration cases, skipped tests, header/body
inconsistency, missing mobile cases, flaky results, retries, and global browser
errors. Image-proof tests reject other commits, mutable tags, missing readiness /
restart / executed read-write facts, and archive revision/tag mismatch.

These guard tests establish rejection behavior, not an application release pass.
The historical-overlay test source was inspected but not run here because it
creates disposable Git commits, outside this review's no-commit instruction.

## Actual existing CI evidence revalidated privately

All 12 encrypted nonserver archive hashes were rechecked. Actual decrypted
reports were parsed, matched to the published report hashes, and their executed
cases revalidated: 22 current browser cases, 6 legacy browser cases, 576 web tests,
and 44 shared JVM tests. Both browser projects executed in every required lane;
the parsers reject retries, skips, flaky results, or zero cases. These results
agree with the root review's `task46/ci-e1782718-nonserver-verification.json` in
the `warehouse-regression-validation` worktree.

The raw native log contains the two required successful iOS compile tasks and a
successful build. The metadata explicitly has `nativeRuntimeOrRelease=false`.
The raw image phase reports match the image proof: each phase has 14 persisted
replays, 16 persisted reads, four stock kinds, and three static assets; real HTTP
is true and SQL business seeding is false. These are existing CI executions.

Private before/after/restart database snapshots show the same two customers and
two ONUs, including exact IDs, customer associations, raw serials, and recorded
customer fields. All 169 pre-upgrade Flyway checksums survive. No source rows
were printed or copied into this report.

The two existing positive runbook preflights were independently parsed from raw
output and checked against the current `preflight.sql` SHA256
`3e7574a81d366997bbff606c5d2e3243ccdbe33a75290ee1d001873d4cee8338`.
Both show read-only transaction completion/rollback under `warehouse_app`, schema
`public`, version 178.9, ENFORCED, one customer, one ONU, zero positions, one
zero-opening movement, and no verified stock quantity. The six raw negatives
each contain the expected migration, SKU-unit, or cutover mismatch error; all
eight report hashes match the preflight proof. This is revalidation of existing
CI execution, **not a newly executed independent F4 SQL probe**.

## Scope, migration, and recovery boundaries

`f4-migration-inventory.json` records SHA256 for all 364 current migrations,
highest version 178.9 and next free version 178.10. There are no duplicate numeric
versions. Every migration present at the feature base, V172 legacy application,
five pinned historical applications, and `2c1d8e08` remains Git-blob identical.
The current manifest documents M01–M06 and the forward extensions; V176's old
reservation is superseded explicitly by the task19 V175.48+ reservation.

`docs/warehouse.md:115` and `docs/warehouse-migrations.md:17` require forward
correction or coordinated verified restore after cutover, preserve old IDs and
raw identities, and forbid checksum edits, direct stock rewrites, validator
disabling, and unaware binary downgrade. The new V178.9 migration changes only
two tenant control-row cascades; direct deletion while a tenant exists still
rejects. `TenantEraser.kt:48` locks the tenant before inspecting protected history,
rejects protected rows before deletion, and leaves these controls to the final
parent cascade. The documented protected-history/Suspend decision and supported
empty-tenant deletion match the owner's supplied decision.

New assignment still enters through customer-owned registration and inventory
deployment authorization: `OnuService.kt:48` rejects serial-only registration;
`InventoryDeploymentService.kt:127` takes cutover/current-authority/WO validation
before inventory mutation and checks issued source, acknowledged custody, purpose,
and ownership. Inventory has no imports from customer/workorder/fulfillment.
Replenishment creates operational suggestions/requests and binds actual receiving;
it does not implement purchase/AP/GL/manufacturing. Source/docs preserve the
LOAN/SALE and existing customer-owned RMA distinction. No additional BYOD or ERP
scope expansion was identified in the reviewed source/artifact surfaces. These
observations do not override the separate F2 findings on compatibility reads.

`docs/gpon-profile-evidence.md` explicitly defers physical certification, records
documentation limits, leaves unknown indexes unverified, and makes the default
undocumented FiberHome profile unavailable. `docs/warehouse.md`, the review guide,
and native workflow distinguish shared/native compile from runtime or release.

Raw XML, login bodies, traces, and fixture snapshots remain in the existing private
artifact directory. Public CI uploads allowlisted counts/hashes plus encrypted
raw evidence; env files are excluded and archive creation rejects symlinks.
Build contexts exclude `.omo`/env files. Supplemental credential-pattern screening
found no private keys, GitHub tokens, or JWTs in 1,920 current changed files or ten
tracked compressed source patches. This is not exhaustive secret certification.

There are 273 inherited tracked `.omo` files, including reviewed evidence and
notepads, despite the plan's default no-`.omo`-commit instruction. Root has been
notified to preserve any explicit owner override context or resolve that scope
exception. This review did not add any of its evidence to Git.

## Findings and required completion

1. Root reports genuine F2 legacy read warehouse/area authorization bypass and
   omission of canonical reservations from the legacy projection; root is fixing
   them. F1 draft editing/expiry gaps are under audit. Those are product findings,
   not merely missing runtime evidence. They require resolution and affected
   source/runtime re-review before final approval.
2. This preliminary report does not establish successful unfiltered server and
   aggregate acceptance gates for the final source. Later product fixes invalidate
   affected e178 evidence; source equivalence alone cannot cover changed behavior.
3. Root must coordinate the required independent F4 runbook preflight execution
   under the host QA lock against the isolated upgrade fixture, then deliver the
   actual positive and expected-negative results for final review. Existing CI
   probe revalidation is recorded separately above.
4. Current `GIT_MASTER=1 git diff --check` passes. Whole-feature diff checking
   reports EOF blank lines in task39/40/41/42 notepads and `http_support.py:60`, plus
   trailing whitespace in immutable `V175_132` at lines 264 and 450. Root classified
   these as non-blocking hygiene and instructed that applied migration checksum
   preservation takes priority. Do not rewrite V175_132. Mutable cleanup may occur
   in a later checkpoint and requires source-equivalence documentation.

Safe supporting records: `f4-release-images.json`, `f4-migration-inventory.json`,
`f4-existing-evidence-revalidation.json`, and `f4-safe-source-checks.json` beside
this report. No newly discovered critical/high image/config publication defect
was identified. Final F4 remains withheld for the reasons above.

# Warehouse CI and private evidence

The `warehouse` workflow runs on `feat/warehouse-workorder` and `work/warehouse-*`
pushes, pull requests to `main`, and manual requests. The deployment workflow calls it as a required dependency before
publishing application images. Server tests include the historical projection
upgrade, seven additional historical upgrades through the complete current migration
chain, and the complete current suite. Historical application versions are pinned.
Browser jobs execute every warehouse spec on desktop and mobile with real PostgreSQL
and object storage. The legacy browser lane also runs read-only SQL preflight after
its real V172 upgrade, with two positive and six wrong-version/unit/cutover probes. Web checks, shared
KMP tests, native iOS compilation, and smoke tests of the actual Docker images
also have to succeed.

The server job compiles current server tests before running historical applications
and checks the repaired compatibility fixtures before its unfiltered regression.
Their reports are archived separately
and removed from the active XML directory before the full run, so an interrupted
full run cannot reuse a successful focused report. Both stages must pass.
The current suite also runs transfer/count draft upgrades and the 178.11 draft
deadline backfill using a separate pinned pre-expiry application process for HTTP
setup. That process closes before migration and startup of the current application
on the same fixture database. These remain current-suite tests; they do not change
the seven historical upgrades or the separate projection test. Their private
bootstrap manifests, response handoffs and process logs are encrypted with the
other server evidence.

Before the server regression, the same required job executes all tests in
`contract`, `snmp` and `collector`, forcing execution without cached task results.
Each module must produce nonzero successful
JUnit results. This includes the documentation-backed GPON parser fixtures and
wire/retry compatibility; compiling those libraries as server dependencies does
not execute their tests. Raw module reports and logs use the same encrypted
server archive. These are offline fixtures and do not certify physical devices.

The image job builds server and web once, runs their exact image IDs against a
separate QA database, and verifies readiness through both the backend and a staging
gateway. Real receipts, transfers, count decisions, authorization checks and replay
run before and after restarting the server container. The web container serves its
actual HTML, JavaScript and CSS bundle. No production services are contacted.

Only after that succeeds are the tested Docker archives uploaded for three days.
Publication downloads these same archives in the same workflow run, validates their
SHA256, image IDs, source revision and configuration digests, then pushes the commit
tags. It verifies that each registry manifest references the tested configuration.
The SSH deployment supplies verified immutable `FTTH_SERVER_IMAGE` and
`FTTH_WEB_IMAGE` registry digest references; `IMAGE_TAG` selects the same commit for
GenieACS. It uploads the reviewed Compose file to a commit-specific release directory
and checks its SHA256 before use. The existing `/opt/ftth/.env`, relative mount paths
and `ftth` project remain the deployment inputs. Application images are
not rebuilt between smoke and publication. Merge and deployment still require the
repository's existing authorization; a feature push only runs verification.

## Reproducible QA dependencies

The historical official MinIO image became unavailable from its registry. QA now
builds the same upstream release from a commit-pinned source archive with an `ADD`
checksum and pinned Go/Alpine base images. This is a source build, not a claim of
byte equality with the former vendor image. Its Docker context contains only the
QA Dockerfile; application credentials are supplied only when containers start.
The upstream license is included in the image. The first build can take several
minutes; subsequent builds use Docker's cache. Timescale is also pinned by digest.
The QA source build does not replace production PostgreSQL or object storage.

The acceptance job rejects failed, cancelled, skipped, or missing prerequisites.
Result validation rejects empty suites, test failures, skipped cases, retries, and
flaky browser results. Native compilation proves compilation only; it does not
prove a native runtime or distribution release.

## Evidence configuration

Set the repository Actions variable `WAREHOUSE_EVIDENCE_RECIPIENT` to an approved
`age1...` recipient or an `ssh-ed25519` public key without its trailing comment.
Keep the matching private identity in the maintainer's protected recovery storage.
The public recipient is required before QA starts. CI never receives that private
identity. The current repository uses the maintainer's existing SSH Ed25519 public
key; preserve access to its matching private key when replacing this VPS.

Public artifacts contain only result counts, suite/project identities, commit and
artifact hashes, and encrypted archives. Raw JUnit, authenticated Playwright
traces, fixture reports, and application logs are encrypted with `age` on the
runner before upload. Environment files are excluded. Artifact retention is
14 days. Gradle binary results are retained even when an interrupted run has not
written its final XML; partial diagnostics do not count as a passing test gate.
Download encrypted evidence needed for a longer review before it expires.
Archive metadata contains its SHA256 and the recipient fingerprint.

After downloading an artifact, verify its SHA256 against the adjacent `.age.json`
metadata and decrypt locally into a private directory:

```bash
umask 077
mkdir -p warehouse-review-private
age --decrypt -i /path/to/private-identity \
  -o warehouse-review-private/evidence.tar.gz server-evidence.age
tar -tzf warehouse-review-private/evidence.tar.gz
tar -xzf warehouse-review-private/evidence.tar.gz -C warehouse-review-private
```

Never attach decrypted reports or traces to a public issue without reviewing and
redacting them. They can contain test account credentials and session tokens.

## Local guard verification

Install Python 3 with PyYAML, `age`, and `actionlint`, then run:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 scripts/warehouse/test-ci-results.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/warehouse/test-ci-artifacts.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/warehouse/test-ci-workflow.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/warehouse/test-publish-images.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/warehouse/test-historical-overlays.py
actionlint
```

These checks exercise the result parsers, actual encryption/decryption, and the
workflow's acceptance script with failing prerequisites, and safe reuse of historical
worktrees without accepting unknown files or changed pinned migrations. Real application gates
remain necessary; a successful parser test does not prove the application passed.

Image publication additionally rejects missing readiness, restart, real HTTP,
stock reads/writes, bundle checks, mutable tags, changed archives, and another
source commit. See [the local review guide](warehouse-review.md) for application QA.
Release acceptance requires successful executed image evidence as well as these
guard tests; the workflow's presence alone is not a completed verification.

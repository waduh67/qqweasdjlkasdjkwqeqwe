# Warehouse CI and private evidence

The `warehouse` workflow runs on feature pushes, pull requests to `main`, and
manual requests. The deployment workflow calls it as a required dependency before
publishing application images. Server tests include both the historical projection
upgrade and the complete current suite; browser jobs execute every warehouse spec
on desktop and mobile with real PostgreSQL and object storage. Web checks, shared
KMP tests, and native iOS compilation also have to succeed.

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
14 days; download encrypted evidence needed for a longer review before it expires.
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
actionlint
```

These checks exercise the result parsers, actual encryption/decryption, and the
workflow's acceptance script with failing prerequisites. Real application gates
remain necessary; a successful parser test does not prove the application passed.

Application-image smoke and exact publication identity are tracked in task48.
Until their executed evidence is recorded, this CI checkpoint is not a completed
release gate. See [the local review guide](warehouse-review.md) for application QA.

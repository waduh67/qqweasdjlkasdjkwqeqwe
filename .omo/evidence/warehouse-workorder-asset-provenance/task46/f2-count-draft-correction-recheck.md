# F2 COUNT correction source recheck

Status: **COUNT-01 and COUNT-02 addressed in the reviewed source; no additional material issue established. Execution gates pending. Not final approval.**

Reviewed the current uncommitted validation patch on `6c6195aecbb785dc5094d382845665e35b8e73ef`. The prior review's server Kotlin files have unchanged hashes. The changed V178.11 and `WarehouseCountDraftIT`, new `WarehouseCountDraftUpgradeIT`, and the small correction to `WarehouseTransferDraftIT` were independently re-read. V178.10 remains unchanged.

## Corrected findings

- **COUNT-01:** `warehouse_assert_count_draft` now reads the exact canonical text and requires its SHA-256 to equal the operation payload hash. It also explicitly counts distinct command revisions. No existing history or row body is rewritten by this addition.
- The new hash-binding test clones a valid complete create into new document/line/operation identities. Its negative transaction changes only the operation hash to zeros; the otherwise identical positive control copies the correct hash. Because a create payload contains no document ID, the unchanged canonical bytes correctly describe both creates. The positive control is important: it prevents an unrelated FK or malformed clone from masquerading as proof of hash rejection. Both assertions still need execution.
- **COUNT-02:** `incoming()` now performs receipt putaway into `stock.setup.bin`, followed by a real transfer dispatch and receive into the warehouse destination. The returned balance uses that same physical identity and destination. This satisfies the receipt BIN restriction while preserving the count scope/replay test's two locations.

## New test source assessment

`WarehouseCountDraftIT` now expands to eight cases. The edited-round case runs both unchanged 100000 MM and independently approved 80000 MM outcomes. Edit revision 1 leads to round revision 2; observation at revision 2 and submit at revision 3 produce a variance source at revision 4. The separate reviewer has destination scope and an independent identity. The case checks no physical change before approval, immutable approval replay, the final POSTED state, exact balance and exactly one COUNT_VARIANCE movement for the variance branch. The unchanged branch checks no movement.

The existing tests still cover original/current replay scopes, requester/tenant/anonymous rejection, counter revocation, malformed input, direct SQL guards and two-thread edit-vs-start/edit-vs-edit. No additional setup or assertion mistake was established by source inspection.

`WarehouseCountDraftUpgradeIT` adds one case, for nine COUNT draft cases across the two classes. It creates a disposable database at schema 178.10 and starts the current application against that target. It seeds DRAFT, COUNTING and unchanged POSTED counts through HTTP, snapshots their document/scope/line/entry/round/observation/result/operation/identity/movement rows, migrates to latest, then checks byte-preserving JSON equality, old command replay, editability of the never-started draft, rejection for active/posted counts, successful start of the edited draft, unchanged physical quantity and no count movements, and an idempotent second migration. `WarehouseSchemaDatabase.migrate()` defaults to latest independently of its constructor's initial target, so the migration call really advances from 178.10.

This is schema-upgrade compatibility with current application code generating the old-schema fixtures. It is not a claim that an old application binary was executed, nor a substitute for the separate packaged-upgrade gate.

The `WarehouseTransferDraftIT` correction uses explicit AssertJ Consumer overloads and changes a hidden receipt destination to a proper warehouse-parent/BIN-child fixture. It does not weaken the scope assertions or the applied V178.10 migration.

## Verification limits

`git diff --check` passed. The coordinator reports a focused local run in progress, but this reviewer has not inspected its completed executed reports. No Java/Gradle/database/browser QA was started by this reviewer; the host QA lock remains with the coordinator. Required next evidence is the actual nonzero nine-case COUNT draft result, relevant existing count/transfer/privacy/regression outcomes, migration/non-owner assertions, completed web checks, and the full current-source F2 gates. Earlier compilation is not proof that these subsequently added tests execute successfully.

The companion hash manifest binds this source recheck to the exact inspected files. The earlier open-finding note remains a historical record; this follow-up supersedes its correction-pending status only for the source hashes recorded here.

# F2 COUNT draft source review

Status: **One SQL command-binding correction and one test-fixture correction identified; compilation, migration execution and regression gates pending. Not final approval.**

Reviewed uncommitted COUNT changes in the validation worktree based on `6c6195aecbb785dc5094d382845665e35b8e73ef`, including the new, unapplied `V178_11__warehouse_count_draft_revision.sql`. V178.10 was read only for interaction context and was not modified. UI hookup, tests and documentation were still being developed by the coordinator.

## Findings and coverage

### COUNT-01 — MEDIUM: draft SQL assertion does not bind payload hash to identity bytes

The reviewed initial V178.11 `warehouse_assert_count_draft` selects `inventory_command_identity.canonical_payload` as JSONB and validates the request fields, but does not compare `inventory_operation.payload_hash` with the SHA-256 of those exact stored canonical bytes. The common identity table has FK/RLS/append-only guards, not a global hash-binding trigger. A raw application-role transaction can supply an edited row, response and matching identity plus an unrelated payload hash and satisfy this draft assertion; later HTTP replay compares the unrelated hash. Normal HTTP creation computes the right hash, so this is a gap in the promised SQL command/replay binding rather than an ordinary HTTP request bypass. Sent to the coordinator with the minimal correction: retain canonical text, assert its exact SHA-256 equals the operation hash, and add a direct SQL negative case.

### COUNT-02 — Test fixture error: second receipt putaway targets a warehouse

The first version of `WarehouseCountDraftIT.incoming(stock, stock.destination)` uses `WarehouseTransferFixture`'s destination, which is `kind=WAREHOUSE`. `ReceiptDispositionPlanning.putaway` requires an issue-eligible `BIN`. Thus the scope replacement and replay-scope tests fail during setup before exercising COUNT. Sent to the coordinator: use a same-area BIN child consistently for putaway/grants/count scope, or seed the warehouse through a real transfer receipt. This is a test correction, not a product defect.

Correction recheck is pending; the observations below describe the otherwise reviewed paths.

- `WarehouseCountService.update` uses the existing transactional mutation path: current cutover/authority, manage and view permissions, topology lock, count-row lock, current location authorization, actor-bound replay, expected revision and requester checks. Only DRAFT without a round/facts can be replaced. The destination is separately authorized, balance positions are locked in ID order, and each selected position must belong to that destination. No physical posting occurs.
- `WarehouseCountStore.replaceDraft` deletes entries before their referenced lines, advances the header's revision, updates scope and rebuilds one complete ordered assignment set. The enclosing transaction rolls all steps back together on failure. Existing observations, rounds and results are untouched.
- `WarehouseCountCounters` shares the picker and command eligibility rule: active user, count manage/view permissions and destination warehouse/area access; the calling actor's self exception is preceded by current destination authorization. This corrects the prior mismatch between the picker and create validation. Current authority fencing prevents a permission/scope edit from being silently mixed into the command snapshot.
- Replay authorizes the current count, verifies original actor/payload/cutover and reauthorizes the location in the original create/update response before returning unchanged bytes. An update to a different resource cannot reuse the same payload hash because the canonical update includes its resource ID. Old create/update results are not rebuilt from the current draft.
- GET `/{id}/draft` requires count manage/view, the current count's visibility and its requester, and DRAFT state. The selected-position query is constrained to the saved balance IDs, current location visibility and a maximum of 100 rows. Its DTO and the references omit physical, expected, reserved and capacity quantities. Missing positions/currently ineligible counters can be shown for explicit replacement rather than silently preserving a now-invalid selection.

## V178.11 assessment

The new mutation exceptions are limited to operational COUNT scope UPDATE and entry DELETE while the owning document is still DRAFT and has no counting/approval/physical history. Scope identity cannot change, entry UPDATE remains forbidden, scope DELETE remains forbidden, COUNT line UPDATE is explicitly forbidden, and original rounds/observations/results retain their existing immutable guards. Existing FORCE RLS and composite foreign keys remain intact.

`warehouse_assert_count_draft` binds the current header, reason, location, partial flag, ordered complete entry set and response to the latest immutable create/update command. It also checks the whole draft command sequence's namespace, requester, resource, action/status, revision and prior expected revision. The existing operation unique action/revision constraint plus those action checks and the final current revision binding prevent a duplicate command slot from satisfying the count check.

The deferred checks allow the temporary incomplete row set inside replacement but require a coherent committed draft. `warehouse_count_draft_header_guard` validates the old DRAFT before its first transition to COUNTING, preventing a mutation from escaping final draft validation by starting the count in the same transaction. Existing lifecycle checks still govern that transition. The strengthened entry insertion guard adds base-unit and admitted active segment-capacity binding. No production fallback, bypass of tenant RLS, broad rewrite of posted count state, or alteration to applied migration V178.10 was found.

Static inspection found the SQL replacement anchor in the existing V175.114 `warehouse_count_scope_guard` definition. This is not proof that the migration parses/runs in PostgreSQL. `git diff --check` passed when inspected; no Gradle, database, Java or browser QA was run by this reviewer.

## UI source inspected so far

The added draft decoder validates DRAFT, distinct selected positions, exact saved identity/SKU/unit/location relationships and assigned-counter membership. The editor preserves the expected saved revision, submits the nested `{expectedRevision,draft}` shape, rejects unavailable selections and uses the existing retained-command dialog for retry/reload. This is a partial UI source check only: final page hookup and tests were still in progress and require the coordinator's completed patch plus executed evidence.

## New backend test source

`WarehouseCountDraftIT` contains six cases after parameter expansion: edited scope/assignment and unchanged-count completion; current/original replay scope revocation; requester/tenant/anonymous and revoked-counter rejection; direct SQL immutability/binding checks; and edit-vs-start/edit-vs-edit on two worker threads. The expected round revision 2 after draft revision 1, observation revision 2 and unchanged-count submission revision 3 follow the actual service transitions. `stock.receiver` is the admin's user ID, so the raw-guard test's admin observation uses the assigned counter. Scope revision 1→2→3 matches the existing grant fixture. No additional setup or assertion defect was established beyond COUNT-02. Neither the tests nor the migration have been executed by this reviewer.

## Required execution before acceptance

1. Apply V178.11 from the current immutable migration chain under the real non-owner application role; execute create→edit→start→observe→submit, including an approved variance and unchanged count. Verify original create/update replay and physical balances are unchanged by edits.
2. Verify stale revision, same-key changed payload, different actor/resource/tenant, old/new location scope revocation, removed counter access and forbidden edit after start/observation/recount/submission/posting. Exercise two edits and edit-vs-start with real concurrent transactions.
3. Direct SQL negative cases must reject incomplete/reordered/mismatched current assignments or command binding, COUNT line UPDATE, scope/entry mutation after counting, count identity deletion and attempted same-transaction corruption followed by start. Keep valid historical command receipts, rounds and results unchanged.
4. Execute the new draft GET without quantity/cost leakage, with missing selected positions and revoked assigned counters; finish and execute UI prefill/update/conflict/retry paths. Compile all constructor/interface callers and run the current focused/full regression gates.

No product changes, migration changes, index operations or runtime tests were performed. This document records source review only and cannot substitute for the required executed cases.

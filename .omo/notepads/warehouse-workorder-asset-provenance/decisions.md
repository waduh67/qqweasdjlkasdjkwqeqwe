# Decisions — warehouse-workorder-asset-provenance

Architectural choices and rationales discovered during work on this plan.

_Auto-scaffolded by /start-work. Append new entries below - never overwrite._

---

## 2026-09-07T02:16:30Z - Task01 safety decisions

- Freeze M01..M06 as V173..V178 respectively, with owners task04/task04/task11/task19/task43/task43. No migration SQL created or historical versions changed.
- Per-worktree hash plus random marker names the Compose project/volumes. down retains volumes; startup interruption removes only owned containers/network. Ports25432/29000 bind only127.0.0.1. App role has no DDL, owner membership, superuser or BYPASSRLS; migration owner is NOSUPERUSER with BYPASSRLS for historic cross-tenant migration DML.
- Browser command fails before starting services until task31 config/spec and warehouse-e2e external-adapter-isolation profile exist. Its health contributor must prove database/user/marker from the active DB connection; generic UP/SPA HTML is insufficient. KMP fails until task42 materials module exists. Neither missing lane is claimed as executed.

## 2026-09-07T03:38:35Z - Task02 ownership and authority boundaries

- All new public APIs are abstract, with no production adapters/beans/default successes. Required Spring references (including a typed deployment validation consumer) fail startup when binding is absent. Existing legacy defaults remain untouched until concrete later adapters arrive.
- Commands contain client intent and expected resource revisions only; tenant/actor/current authority, canonical hashes, approval tiers and movement targets are never accepted as command fields. Server-only DeploymentBinding includes epochs, material/WO/issue/asset revisions and previous-assignment bindings. UUIDs follow existing opaque-root-reference conventions, not authority tokens.
- Inventory owns InventoryWorkOrderValidationPort; workorder will implement it. ValidatedWorkOrderContext attests WO actor/customer/purpose/revisions/epochs only. It deliberately does not claim inventory issue/asset validation; inventory checks its own revisions under later locks, preserving ownership and lock order.
- SessionIdentity is not AuthorityFence. IAM owns current roles/areas/epoch; inventory owns warehouse scope lookup under that fence, avoiding IAM->inventory. Restricted(emptySet) is explicitly zero scope. Customer observation receivedAt is derived by owner, not caller input; attribution sample/query is server-only.
- Documentation specifies strict future endpoint decoding and all C1-C11 obligations, while explicitly separating contract proof from future quantity, transaction, RLS, cutover, assignment and HTTP implementation proof. No migration/controller/service/UI was introduced.

## 2026-09-07T04:35:00Z - Task03 domain boundaries

- StockQuantity stores only checked nonnegative Long base units plus immutable StockUnit; wire conversion lives in inventory.application and verifies both unit/display representations. MM output always has three fractional digits. No Double/Float/BigDecimal in production stock parsing/arithmetic.
- ReceiptConversion preserves original positive numerator/denominator values. GCD cancellation precedes checked multiplication so an overflowing intermediate does not reject a representable exact result. Non-integral base/package results are rejected. Receipt caller must use its snapshotted SKU unit, never mutable catalogue semantics.
- SerialIdentity/MacIdentity are tiny common values with exact raw input and canonical equality/hash; Locale.ROOT safe. MAC supports compact, mixed colon/dash byte groups and conventional dotted quartets, not arbitrary punctuation deletion. Onu.create shares serial codec but rehydrate and old inventory writes/reads remain unchanged.
- StockUnitDefinition exposes immutable SERIAL/LOT/BULK and EA/MM semantics, including one serialized item=1EA. Task04 must enforce snapshot persistence and SKU unit immutability after posting; no persistence policy is implemented prematurely.

## 2026-09-07T05:40:00Z - Task04 persistence boundaries

- M01 remainsV173 and M02V174; no M03+ SQL. New tenant initialization is an explicit InventoryTenantInitializationApi requiring the active tenant transaction, never an absent-policy fallback. DB verifies tenant creation after migration activation and empty stock/history; existing empty tenants startLEGACY.
- Policy exposes transaction-bound shared/exclusive fences and C10 allowlist. LEGACY->VALIDATING records batch/watermark/pending legacy movement IDs; approval-dependent commands/finalization remain typed closed. DB finalization guard also remains closed pending coordinated approval-owner forward migration, rather than accepting fake independent approval.
- Event/outcome/inspection/usage/lot/posting history is append-only. Segment is the common stock identity with serialized identity equal to preserved asset ID; deferred origin/claim/conservation checks support atomic insertion without fabricating historical receipts. Optional legacy exact quantities remain null; no destructive raw-column removal.

## 2026-09-07T05:50:00Z - M02 forward-only split reservation

- M02 now reserves V174 and V174.1, before M03V175; this safe sequential split uses the task's explicit manifest-first exception. It avoids editing already-applied V173/V174 and avoids commandeering another owner's slot. ICU root collation is an explicit PostgreSQL prerequisite for canonical parity; task-owned PostgreSQL provides it.

## 2026-09-07T07:10:04Z - Forward-only provenance and transaction event design

- Reserve M02V174.2 before SQL creation, leaving V173/V174/V174.1 bytes and V175-V178 ownership intact. No global reconciliation/data rewrite is required at boot. Deferred triggers read final persisted rows and recheck sources on mutation; balance/reservation writes lock stock identity rows so terminal transitions cannot race new encumbrance.
- warehouse_assert_opening_approval(tenant,document,revision) is a typed SQL failure extension point, always42501 until task12 binds real independent approval evidence. Neither a status flag nor a caller-selected approval boolean can enable opening origin.
- Tenancy publishes TenantCreatedEvent without inventory imports. Inventory and IAM listeners own their own SQL; shared TenantTransactionJdbc flushes and scopes JDBC on the originating Hibernate connection, restoring its GUC without changing EntityManager tenant identity or opening another transaction. Exceptions propagate and roll back all owner writes.

## 2026-09-07T08:44:49Z - M02V174.3 allocation fence

- Primary manifest and addendum now list V174/V174.1/V174.2/V174.3. V174.3 was verified free and reserved before creation; V173 through V174.2 and V175-V178 ownership unchanged.
- Lock actual lot rows in ordered tenant/id order for old/new segment references before insert/update. A deferred constraint checks final root allocation with an indexed numeric SUM; lot facts remain append-only, no mutable allocation counter or new operational service introduced. Root retirement/split cannot release provenance capacity, children do not consume it again.

## 2026-09-07T09:48:09Z - M02V174.4 scope binding

- Reserve V174.4 in the primary manifest before SQL creation. Replace only the capacity function via forward migration, retaining ordered row locks and final numeric aggregate checks; missing/invisible lot now always raises23514.
- Deferred constraint triggers compare current tenant GUC to NEW.tenant_id on segment, lot, asset, balance, reservation, claim and usage snapshot. Restore-to-correct context is valid and still subject to capacity/provenance checks. No privileged function, disabled RLS, ownership change or later-task workflow was introduced.

## 2026-09-07T11:12:49Z - V174.5 internal guard placement

- Reserve V174.5 before SQL creation; leave V173-V174.4 and V175-V178 untouched. Replace only six unguarded trigger functions, preserving subsequent invariant logic exactly and explicitly retaining SECURITY INVOKER. Existing internally guarded capacity and companion functions need no redefinition.
- Test each function with all other custom deferred constraints forced early. Test invalid data by forcing its actual constraint, not a companion. Re-defer a previously executed source constraint, mutate its claim, then change scope to prove early validation is not reusable authority for a later mutation.

## 2026-09-07T13:12:03Z - Task05 atomic posting boundary

- Posting requires active READ COMMITTED transaction, originating cutover fence and matching Hibernate/GUC tenant. Internal typed commands carry server-owned document/operation/action/revision data. Task06 replay/current-authority/outbox-delivery orchestration remains unimplemented; duplicate post references fail without returning old private payloads.
- Receipt source is explicit boundary leg status RECEIPT_SOURCE with no negative supplier balance. CONSUMED is a named physical sink retaining stock identity and custody; no ordinary outbound from it. MM/serial moves are whole-piece or explicit atomic split; unpicked/picked reservations do not create physical legs.
- Projection rebuild takes existing exclusive tenant cutover fence without changing policy, waits out posting and reconciles balances in-place within one transaction. No TRUNCATE or projection DELETE. This is blocking tenant maintenance, not a claim of nonblocking online rebuild.
- Legacy count commands require verified acknowledged serial issue; unknown integer bulk is rejected rather than guessed as MM/EA. Deprecated count reads preserve historical facts. Approval/count/assignment lifecycle implementations remain later tasks.

## 2026-09-07T15:53:30Z - AV5 transaction invariants

- Status is dimension state, not input ordering: OUT agrees with locked source; all IN agree; IN chooses resulting state, otherwise previous state persists. Immutable physical leg revision stores next locked dimension-state revision using existing schema; rebuild uses inbound revision ordering and restores high-water marks after projection loss. Legacy zero-revision timestamp ties with conflicting inbound states fail closed rather than choosing a UUID.
- Production phase event/callback types and publisher removed entirely. PostingJdbcProbe/TestPostingPhase exist only in test sources and are installed explicitly against JDBC for failure/barrier tests; outbox remains the sole posting delivery boundary.
- New/increased reservations require locked positive AVAILABLE positions and ACTIVE issue_eligible locations with matching custody kind, SERVICEABLE/ISP. Existing accountability can decrease/release after eligibility changes. Facts/usage require locked document context, consumed customer custody, and matching acknowledged declared issue line/identity lineage. Exact Long facts do not depend on representable legacy Int projections.

## 2026-09-07T18:38:29Z - Actual allocation authority

- Total increase OR picked increase requires eligibility. Ineligible unpick may redistribute existing picked into unpicked without increasing total or physical/available stock; safe reductions/releases remain possible.
- Binding checks actual identities, not just the line's declared identity. Committed ancestry and posting-created split ancestry are accepted; same SKU/common ancestor alone never authorizes a sibling. Every line pairs its quantities and bounds its allocation. Only an unchanged-position/status REMNANT is retained; a pure remnant split binds full parent quantity.
- Explicit source issue bindings apply structurally even without facts. Accountable quantity checks include current lines plus prior used/returned facts under source-line FOR NO KEY UPDATE; declared issue quantity bounds ordinary movements. This is local inventory consistency, not task6 IAM/replay orchestration.

## 2026-09-07T20:12:31Z - Factual movement versus physical retention

- Preserve line allocation and retained-remnant conservation. Expose inbound, retained and fact-eligible legs in the same validated result; do not independently infer retention in the fact persistence adapter.
- Every fact must resolve to exactly one inbound leg/line and that leg must represent changed physical position/state for the same piece or split parent. Global matching prevents stationary identities being laundered through swapped line mappings. Per-line factual sums fit both factual capacity and allocation quantity.
- Pure local cuts/remnant reshaping without facts remain valid. Existing source budget uses unchanged allocation, which bounds the now-validated current facts; prior immutable facts are still counted. No historical fact repair or task6 behavior is introduced.
## 2026-09-10 - Wave 2 checkpoint

- Tasks 1-12 are independently confirmed complete. Task 12 verifier `ses_f73a315e5ffeibNLVDMKoYmrnu` approved implementation `ca71af1fd5a103c81dfefbea0ad72e30da26a570` through migrations `V175.3`-`V175.7`.
- Wave 2 is complete; Task 13 is next in Wave 3 and remains unchecked until its own implementation and independent approval.
- Recovery remains immediate-push and no-merge: resume with `/start-work warehouse-workorder-asset-provenance`, push normal fast-forward after each approved checkpoint, and never merge from checkpoint sync.

## 2026-09-11 - Task 13 checkpoint identity policy

- Task 13 approval is recorded against verifier `ses_f726d8e9cffelvc6sK35yHPdt6`, implementation `df33cce4209ce6bb1ecd87ff9b566cadaca6d2e3`, and migrations `V175.8`-`V175.10`; Task 14 remains the next unchecked task.
- Future checkpoint and implementation commits must use the effective global Git identity `fajarxfce <fajaralamsyah000@gmail.com>` with no conflicting per-command identity override. Resume with `/start-work warehouse-workorder-asset-provenance`.

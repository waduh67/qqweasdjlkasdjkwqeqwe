## Task44 V178.1–178.2: serialized deployment witnesses

Both migrations are APPLIED and immutable in the owned QA database:

- V178.1: `c4b6b169403296df9943ee7f981ef21f215f3e43712f92a02524f57088c832e3`.
- V178.2: `ff2b6f79215053a7dd6f26ca54796887268e2b5926e5300e3027d270b36f893b`.

WO material verification binds the actual receipt-backed installation, assignment,
posting, technician and plan line into its frozen snapshot alongside measured bulk
usage. The database checks exact witness equality and planned-line coverage. Shared
assignment locks hold installations stable until the local QA transaction commits.
These witnesses create no movement, usage line or physical asset.

V178 and all earlier migrations stay unchanged. The next free version is V178.3.
Task44 acceptance remains open until restart and remaining concurrency gates pass.
Older sections below record the migration history, not the current task status.

## M06 V178 applied: finalization and full restart verified

V178 is applied and immutable: SHA256
`f6b8780d898577c16f2b63298e9d3fd43244d6e32a02a4c921ae04c7a1c9997d`.
45tests/8suites PASS5m4s: complete-package boot, independent finalization, actual
application shutdown/restart with mixed tenant states, capture and stock regression. Owner-checked finalization stores immutable cutover
receipts and enforces VERIFIED reference scope while preserving unresolved legacy
rows. Source-matched proof: task43/finalization-verification.json.
V177 through177.10 remain immutable. Next free M06 version178.1. Full provenance UI
and remaining acceptance tasks are still open.

## V177.10 applied: legacy closure and admitted review seal verified

V177.10 is applied and immutable: SHA256
`7fb239b034ff5fae3469d857d055acdddeb9129c569a74f43eccea860dd3df08`.
31tests/6suites PASS3m38s; source proof `task43/legacy-closure-verification.json`.
New legacy WO effects/deliveries and business identity changes are closed after
cutoff. Existing terminal replays, reconciliation, leases and ACK remain; current
frozen fulfillment still operates normally. Old tombstones are append-only with
application writes revoked. New evidence/resolutions reject after opening admission,
while existing history remains readable. Next free177.11;178 reserved forM06.
Finalization, mixed-tenant restart and provenance management UI remain pending.

## V177.10 reservation: close obsolete legacy writes and seal admitted review

V177.10 is reserved for legacy fulfillment creation/business/progress fences after
cutoff, append-only old tombstones with application writes revoked, and rejection
of new case evidence/resolutions after committed opening admission. Terminal replay,
current frozen fulfillment and historical reads remain. V177–177.9 are immutable;
V178 remains reserved for M06. This reservation precedes apply.

## V177.9 applied: current raw identity reservations verified

V177.9 is applied and immutable: SHA256
`b66ace6e025d257171a9a5ceff5aa5d7e5deceaa94f1c9871fc64d1e105e6c69`.
Full gate13tests/4suites PASS2m13s; safe source proof
`task43/current-identity-verification.json`. BEGIN reserves actual current raw
serial/MAC while preserving all candidate versions and claims. Invalid MAC stays
unclaimed; current tombstone claims become RETIRED. Approved original baseline
checks current active peers while old reservations still block ordinary receipts.
Raw asset/ONU identity is fixed at cutoff; existing ONU identity immutability and
operational status updates remain. No stock is created by this reservation step.
Next free M05 version177.10;178 reserved forM06. Legacy pending creation closure,
tombstone write closure, post-admission resolution freeze and finalization remain.

## V177.9 reservation: current identity reservations at the cutoff

V177.9 is reserved for append-only versions of identity candidates, conservative
reservation of current raw serial/MAC at BEGIN, and immutable legacy raw identity
after the cutoff. Old candidates/claims remain. Approved baseline conflict checks
will use current active source identities while ordinary receipts retain the
unconditional claim fence. V177–177.8 stay immutable; V178 remains reserved forM06.
This reservation precedes apply.

## V177.8 applied: reviewed legacy effects are permanently canceled

V177.8 is applied and immutable: SHA256
`3bd70b2ff029705331a4c453296ad6604524cd18b4c3aa08aea12ab8dc43b180`.
The focused gate passes14 tests in2m, including real HTTP + PostgreSQL + MinIO.
Cancellation receipts for reviewed pending movements/checkpoints/outbox are written
only by checked owner functions in the same transaction as approved opening.
Original movement/checkpoint/outbox rows and prior completed effects stay unchanged.
Canceled fulfillment reads terminal MANUAL_RESOLVED with the explicit outcome
CANCELED_BY_APPROVED_MIGRATION; stale workers cannot consume/reconcile/reopen it.
Broader normal fulfillment gate37tests/8suites PASS3m51s, followed by7tests/2suites
PASS1m34s for final sources and redacted HTTP409 on changed business/effect binding.
Safe proofs: task43/cancellation-regression-verification.json and
cancellation-final-verification.json. Next free M05 version177.9;178 reserved for
M06. Finalization, all-current identity reservations and closure of obsolete legacy
pending creation remain pending; cancellation does not flip tenant ENFORCED.

## V177.8 reservation: permanent cancellation of reviewed legacy effects

V177.8 is reserved for owner-only, append-only cancellation receipts bound to the
same independently approved opening transaction. Original movement/checkpoint and
outbox rows remain unchanged. Late workers must observe terminal cancellation;
raw mutation and new progress against canceled work must be rejected. V177–177.7.3
remain immutable. V178 remains reserved for M06. This reservation precedes apply.

## Approved opening admission: V177.7 through V177.7.3 applied

All four files are applied and immutable. The real PostgreSQL + HTTP + MinIO
opening gate passes4tests: physical baseline and competing final decisions, empty
baseline, current owner-area staleness/rejection, and rollback at three approval
stages followed by an exact same-key retry. Broader posting/approval regression
passes47 tests in9 suites, no failures/skips,6m28s; source hashes and results are in
`task43/opening-admission-verification.json`.

| Version | Purpose | SHA256 |
| --- | --- | --- |
|177.7|Owner admission, exact original identity/lot/ledger proof, sealed review and all-tier approval|96cfc4248a805342b65b04df3bbe1fc29368de6b6c6da8cac2fa16166ab1c26e|
|177.7.1|All-null valuation permitted only for opening; ordinary exact-cost requirements retained|6d7d10af5298f749c47e0afbef69ce11ea9f96b706635ebb1c85d76e0a955db4|
|177.7.2|Qualified configured tier reference|408dc32e72ec9f3bf101011044acda3e23ae791929260e62e01df7a037b42209|
|177.7.3|Qualified origin document parameter and admission lot variable|1576f7e37ca0bec3e0cbaf190c9dcd03d778ca18cc154a2a4ffb0a2c67592a31|

Only the checked owner function can insert admission witnesses or promote original
legacy assets. Posting and its approval effect/event/inbox receipt commit together.
No legacy price, supplier or receipt is fabricated. A zero-stock baseline has no SKU
or physical line. Current actor and customer/WO area scope are reloaded for review,
decision and evidence downloads. The independent approver has a bounded frozen-case
and private-file API without requiring provenance-management permission. The web
approval workbench now displays sealed cases and verifies original file SHA256
before download. Warehouse web gate257 tests/50files, lint and production build
pass; safe proof `task43/opening-web-verification.json`.

The tenant remains VALIDATING after opening. Pending legacy cancellation execution,
reservation of all current unresolved identities, finalization/M06 and the provenance
web page are still required. Next free M05 version177.8;178 reserved for M06.
Earlier sections below record previous phases and are superseded by this status.

## Opening policy evaluation following V177.6

The existing policy evaluator now requires every configured tier for a sealed
OPENING_BALANCE whose historical cost is unknown. It retains null values internally
and omits monetary fields from the public view. Empty baselines use a real review
location, with no physical line or SKU. Batch creator, resolver, evidence uploader
and opening requester are excluded. Candidates and delegated parties need all
current source-customer/work-order areas as well as warehouse scope.

This phase adds no migration. V177.6 remains immutable; V177.7 is still free and
V178 reserved. Actual independent opening approval/admission/posting remain the
next phase. Verification:15tests pass (Opening4,PolicyEvaluation6,ReceiptApproval2,
Modularity3), safe proof task43/opening-policy-verification.json.

## V177.6 applied: sealed opening review

V177.6 applied in the real HTTP + PostgreSQL + MinIO gate: opening review3,
resolution4 and module graph3 passed (10tests, 0failures/skips, 2m1s).
It is immutable. SHA-256: `702c8a58dfdbecba78faa274bf1a7422ae9193e8fa97b58790714816a242f566`.
Next free version: V177.7; V178 remains reserved for M06.

Authenticated `GET /api/v1/warehouse/provenance/batches/{batch}/review` derives the
complete frozen source manifest plus latest resolutions. Unreviewed available
stock and pending effects block a request. A selected physical asset additionally
requires agreeing duplicate resolutions, including the exact current winner
revision. Historical/installed unresolved cases stay explicit null resolutions.

`POST /api/v1/warehouse/provenance/batches/{batch}/opening` requires the review hash,
current epoch, active review warehouse/bin revision, migration reference and reason.
It verifies the original private evidence bytes again, then atomically creates an
OPENING_BALANCE draft with only the derived physical quantities. Unknown cost stays
NULL. An empty tenant gets an explicit control draft without any SKU, physical line,
evidence upload, stock or fabricated receipt. Approval/admission/finalization remain
closed in this phase. `GET .../opening/{id}` exposes the immutable proposal.

The DB re-derives the manifest and stock lines and seals the draft against later
generic edits. Repeated actor/key/body returns the exact original response, checks
current scope and original files, and never follows a later resolution into a
changed response. Another resolution produces a different review for a new request;
final posting still needs one independently approved business identity per batch.

## V177.6 reservation: immutable opening review

V177 through V177.5 are applied and immutable. V177.6 is reserved for complete
batch review, exact resolution/evidence binding and sealed OPENING_BALANCE drafts.
No physical admission, approval or posting authority is enabled by this phase.
V178 remains reserved for M06. This reservation precedes the first apply.

## V177.5 applied: legacy fulfillment source cases

V177.5 applied during the real HTTP legacy fulfillment gate and is now immutable.
SHA-256: `4b560d0aab1f34aea4ecf3649e936373f330b19140f888854c1ffa97e65ec15d`.
Next free version177.6;178reserved. Final fence/replay gate:16tests PASS, BUILD SUCCESSFUL2m8s.

## V177.5 reservation: legacy fulfillment cutoff evidence

V177–V177.4 are applied and immutable. V177.5 is reserved for fulfillment-owned
legacy checkpoint/outbox source capture and batch-bound reconciliation evidence.
V178 remains reserved. No V177.5 SQL has been applied at this note.

## V177.4 applied: current cutoff snapshots

Full packaged migrations and13tests passed in2m54s, including actual HTTP and a
blocked PostgreSQL legacy writer. V177.4 is now applied and immutable.
SHA-256: `6275f0da7a4d0c23e06aaf94a36f7214fd934d8c28d54f60b283e9966ac3ed50`.
Next free version177.5;178reserved. Snapshot history and raw physical IDs remain.

## V177.4 reservation: current source capture at the cutoff

V177–V177.3 are applied and immutable. V177.4 is reserved for versioned immutable
source snapshots, owner-provided live source views, and exact batch capture after
legacy writers drain under the exclusive cutover fence. V178 remains reserved.
No V177.4 application yet. Historical snapshot rows/IDs must remain intact.

## V177.3 applied: immutable resolution proposals

Full packaged Spring Boot with legacy collisions/orphan references applied V177.3
successfully; nine tests passed in 1m59s. The migration is now immutable.
SHA-256: `aa08b50aae8356e9c7b4f56e37a23abdb430c9d7fa02f6da5785d12b2617a2b5`. Next free version177.4;178reserved.
No admission, approval or finalization guard was opened by this migration.

## V177.3 reservation: evidence-bound resolution history

V177 through V177.2 are applied and immutable. V177.3 is reserved for append-only
case resolution proposals, exact source-unit conversion, and duplicate-source
validation. It does not promote claims/assets, post stock or enable finalization.
V178 remains reserved for final constraints. No V177.3 application yet at this note.

## V177.2 applied: immutable migration evidence

V177.2 applied successfully during the real MinIO evidence gate, including the
post-write database-backend termination test. It is immutable from this point.
SHA-256: `075be14ca62a9e159ce3bcf15c5eaa3b8f4b8c9b39d7cea712688c33729dca6a`.
Next free version is V177.3; V178 remains reserved for final constraints. Metadata
cannot be updated/deleted by the app; object cleanup waits for the batch lock to
settle and confirmed metadata absence. PROVENANCE_RESOLUTION now admits preparatory
case evidence in VALIDATING; migration approval/baseline/finalization remain closed.

## V177.2 reservation: case-bound migration evidence

V177 and V177.1 are applied and immutable. V177.2 is reserved for append-only
migration evidence metadata, bound to a captured batch/case hash and VALIDATING
epoch. Uploads will reuse the existing PDF/image validation and ObjectStorage,
with transaction-settlement-aware cleanup. This records evidence only; it does
not admit stock, approve a baseline or enable ENFORCED. Batch serialization uses
an advisory transaction lock because immutable batch rows intentionally have no
application UPDATE privilege (row-lock queries would require that privilege).

## V177.1 applied: begin-validation receipt

V177.1 applied successfully in the full packaged Spring Boot upgrade at
07:09:43.887 JKT. It is immutable. SHA-256: `456b3c169eb30a406629fc52ae520442e130c81d9cb4fd97eb7aeff9861a4023`.
Application tests reached successful reads, batch capture and response replay;
expected error cases exposed the new controller missing from warehouse error
advice. The Kotlin registration is fixed; applied SQL is unchanged.
Next migration slot is V177.2; V178 remains reserved.

## V177.1 reservation: durable begin-validation command

V177 is applied and unchanged. V177.1 is reserved for the immutable command
receipt that binds batch creation, expected source hash, actor and resulting
cutover epoch. It does not admit stock or relax approval/finalization guards.
The API will capture existing VALIDATING batches without inventing a new cutoff.

## V177 applied: immutable preservation evidence

V177 successfully applied in full clean and V172 legacy-collision upgrade fixtures.
It is immutable from this point. SHA-256: `dcfa14774e95a35af8d86135889fe03dd2085500f704f23f3189a7eed519cb88`.
The first attempt rolled back (PostgreSQL requires immutable generated-column
expressions); no successful V177 was edited. Hashes are now computed on INSERT.
New structures preserve eight legacy source kinds and derive batch manifests
under the exclusive VALIDATING fence. There is no data admission or stock posting.
M05 follow-ups use V177.x; V178 remains reserved for final constraints.

## Task43 M05 reservation: V177 preservation snapshots

V175.148 is the latest packaged applied version. V177 and V178 remain reserved
for task43; V176 was never consumed (M04 shipped as V175.48 onward). V177 will
add immutable legacy provenance cases and migration-batch snapshot structures.
Boot-time staging preserves original IDs/raw units/identities and never chooses
collision winners or posts stock. V178 remains reserved for final admission and
cutover constraints. Once V177 is applied, corrections must use forward versions
(V177.x before the reserved V178, or later), never edit applied SQL.

# Migrasi dan lingkungan QA warehouse

V175.147 applied22:49:14.237 JKT, immutable: `5da98c83d788aa398a8b70e43e583b2fa1f694e25dd9bf334c27f8f25cccb31f`. All143–147 immutable;148 next unused. Active-loan loss verified15 tests/3 suites.

## Reservasi V175.147: payload event kehilangan pinjaman

146 applied22:46:28.708 JKT, immutable: `d7126fe6d9ab06ef9415d3ebbebf86de03193a142359d7db31c0bd6c8968f837`.
Draft, replay, listing, approval request dan self-denial sudah terlewati. Commit
efek ditolak karena144 membandingkan payload outbox dengan response approval,
sedangkan posting owner menyimpan snapshot posting/legs. Slot147 dicadangkan
sebelum pembuatan untuk membandingkan payload dengan exact snapshot ledger itu.
Tidak mengubah kardinalitas, approval, histori atau validasi dimensi stok.


## Reservasi V175.146: perbandingan JSON bukti kehilangan

145 telah diterapkan; jalur draft sekarang mencapai pembandingan bukti pada fungsi143.
Slot146 dicadangkan sebelum pembuatan untuk kurung eksplisit pada operator JSON
sebelum pengurangan key receivedAt. SQL143–145 tidak diubah.


## Reservasi V175.145: qualifier authorization pada loss

V175.144 sudah diterapkan pada run asset-loss-effect; kegagalan fixture berikutnya
mengungkap nama parameter authorization_id yang ambigu dalam validator deployment.
Slot145 dicadangkan sebelum pembuatan untuk qualifier tiga referensi kolom baru.
Tidak mengubah aturan otorisasi atau byte144.


## Reservasi V175.144: efek persetujuan kehilangan pinjaman (task28)

V175.143 applied 22:34:18.972 JKT dan immutable:
`d613a31245fe294a38113ff109e3fb52b4467d348f2d6d642cae02bc23f57936`.
Slot V175.144 dicadangkan sebelum pembuatan untuk efek LOSS pada ASSET_LOSS,
penutupan penugasan/episode, hubungan histori dan antrean provisioning atomik.
Perubahan riwayat dilakukan melalui fungsi/constraint maju, bukan edit143.


## Reservasi V175.143: permintaan kehilangan pinjaman aktif (task28)

Slot V175.143 diperiksa belum ada dan dicadangkan sebelum file SQL dibuat.
Menambah permintaan ASSET_LOSS yang mengikat serah-terima LOAN, penugasan aktif,
identitas perangkat, judul ISP, revisi WO, bukti tersimpan dan biaya sumber asli.
Draft belum boleh memindahkan stok atau mengakhiri episode. Efek persetujuan akan
memakai migrasi maju berikutnya. Seluruh SQL sampai V175.142 sudah applied dan
immutable; V177/V178 tetap dicadangkan untuk task43.


V175.142 applied22:19:08.476 JKT, immutable: `f792dc3f6d64f6190dac9b575e5c74e66022a6fc41ae4922f8a12aeeb4cff83a`. Source corrections use143 onward.

`V175_142__warehouse_disposition_compensation_event.sql` dicadangkan sebelum pembuatan untuk menambahkan event DISPOSITION_REVERSED pada constraint outbox.141 telah diterapkan22:16:00.258 JKT, immutable: `636454e183295ddb3e433cb7a2e1e5056a4881f8dacafc450a12d7ab4e8baac0`.

`V175_141__warehouse_disposition_compensation_effect.sql` dicadangkan sebelum pembuatan untuk approval ADJUSTMENT pada sumber DISPOSITION_REVERSAL, posting REVERSAL yang terhubung, dan pemulihan retur ke karantina.140 telah diterapkan22:10:14.007 JKT, immutable: `ebbc1297ff12996825eac5601ec7367b282e8f564b25eacd9eaaa35343f0fd4e`.

`V175_140__warehouse_disposition_compensation_request.sql` dicadangkan sebelum pembuatan untuk draft koreksi yang menunjuk posting loss/scrap asli, stok sink terkini, dan kewajiban material yang belum ditutup. Efek approval akan diperluas dengan migrasi berikutnya.

V175.139 applied21:49:43.056 JKT, immutable: `a9a1f5567678d9b4841bad38b3f2fd79f4256efeeb65b8563fe442923de2e489`. Runtime corrections must use140 or later.

Baseline `ebf98fdf270b30ac30b7a01b1f609b39e8414618` memiliki 169 migrasi,
versi maksimum `V172__evidence_retention_claim_state.sql`. Celah V56-V58
adalah riwayat, bukan slot bebas. Manifest ini dibekukan untuk branch
`feat/warehouse-workorder`. Task04 menambahkan V173 serta M02 V174, V174.1,
V174.2, V174.3, V174.4 dan V174.5; versi historis tidak diubah.

| Slot | Versi | Pemilik tugas | Cakupan |
| --- | --- | --- | --- |
| M01 | V173 | 04 | Precision, masters, identity claims, cutover/auth fences |
| M02 | V174, V174.1, V174.2, V174.3, V174.4, V174.5, V174.6, V174.7, V174.8, V174.9, V174.10, V174.11, V174.12, V174.13, V174.14 | 04 / 06 / 07 / 08 / 10 | Documents, posting, reservations, inspection, scopes, material plans; canonical identity, provenance chain, aggregate lot capacity, internally scoped deferred validators, durable delivery metadata, fulfillment observations and WO reference revisions; master metadata, control-plane operation binding, reference admission, tenant/site/area consistency and effective ancestry guards; receipt intake snapshots, secured evidence and exact inspection disposition; immutable intake-content evidence binding; reservation allocation links and demand supply snapshots |
| M03 | V175, V175.1, V175.2, V175.3, V175.4, V175.5, V175.6, V175.7, V175.8, V175.9, V175.10 | 11 / 12 / 13 | Versioned policy/rules/tiers/approvers, warehouse applicability, durable settings replay, delegation lifecycle; approval/count source snapshots and repair/replenishment foundations; table-specific policy child validation and exact-value compatibility; sealed approval queue, candidate requirements and command receipts; atomic terminal/effect constraints and deferred tenant assertion; attempted-decision replay context and durable decision bindings; immutable material templates and SKU-bound lines, sealed plan snapshots, demand bindings and material command receipts; deferred complete material submission binding |
| M03 issue extension | V175.11, V175.12, V175.13 | 14 | Immutable issue snapshots and unpick records; issued supply quantities; deferred live issue/reservation binding and explicit UNPICKED lifecycle; terminal dispatch posting proof |
| M03 custody extension | V175.14 | 15 | Immutable receiver acknowledgement, quantitative receipt lines, exact posting and issue lifecycle binding |
| M03 usage extension | V175.15, V175.16, V175.17, V175.18, V175.19, V175.20, V175.21 | 16 | Immutable acknowledged physical usage lines, standalone material facts and explicit NONE snapshots; internally tenant-scoped receipt/posting/fact bindings; projection-rebuild-safe consumed guard and terminal consumption fence; separate source-plan and current WO revisions; final-state projection validation and sealed posting graph |
| M03 consumed truth correction | V175.22 | 16 | Immutable-history-anchored consumed projection and terminal lineage validation, including DELETE and exact transactional rebuild |
| M03 fulfillment settlement | V175.23, V175.24, V175.25, V175.26, V175.27, V175.28, V175.29 | 17 | Immutable approval applicability and usage snapshots; non-posting settlement receipts and internally scoped completion constraints; normalized SQL applicability array comparison; explicit BNG applicability, replay binding, terminal checkpoint seal, visit-link insertion fence and canonical deferred scope entry |
| M03 fulfillment owner correction | V175.30, V175.31, V175.32, V175.33, V175.34 | 17 | Authoritative order/customer and visit bindings, owner-local subscription/BNG/visit receipts, captured owner transitions, complete effect and outbox lineage validation with internal tenant assertions; explicit read parameters and transaction-correlated BNG handoff fingerprints |
| M03 BNG lineage seal | V175.35 | 17 | Immutable initial approval/transaction provenance, validated initial bound actions and complete OLD/NEW reference discovery for exact action-set validation |
| M03 BNG exact-set correction | V175.36 | 17 | Exact sorted receipt/action identity equality, rejecting duplicate IDs that conceal omitted correlated actions |
| M03 material lifecycle | V175.37, V175.38, V175.39, V175.40, V175.41, V175.42, V175.43 | 18 | Immutable custody obligations, lifecycle revisions, specialized WO residual dispatch/acknowledgement and final-state conservation guards; table-specific deferred trigger routing; durable origin obligations, authorized two-party custody handover, positive measured usage deltas and quantitative snapshot truth |
| M03 rework extension | V175.44, V175.45, V175.46, V175.47 | 18 | Additive post-use plan lineage and evidence snapshots, inherited line references, new-plan usage bindings, fresh receipt validation and direct-owner operation fences |
| M04 | V175.48, V175.49, V175.50, V175.51, V175.52, V175.53, V175.54 | 19 | Assignment/authorization/handover storage, legacy ONU episode expansion, immutable history and final-state identity/interval guards |
| M04 authorization correction | V175.55, V175.56 | 19 T19-AV-01 | Shared verified authorization source/purpose validator and read gate; final-state authorization/history/source triggers and physical-asset locking |
| M04 authorization timezone correction | V175.57, V175.58 | 19 T19-AV-02 | Semantic timestamp comparison shared by authorization history insertion and final validation; original snapshot JSON preserved; invalid displacement rejection |
| M05 | V177 | 43 | Preservation, staging, reconciliation |
| M06 | V178 | 43 | Admission-scoped constraints and compatibility gates |

## Task28: kehilangan, scrap dan kompensasi

`V175_139__warehouse_return_disposition_effect.sql` dicadangkan sebelum pembuatan.
138 telah diterapkan dan immutable (SHA25634a2b183f2a4f1395981b5efdf5e14d3033ca9f7121b767c9d3f2ab52c74ea3b).
Draft201 dan replay berhasil; tes berhenti pada handler approval yang belum ada.
139 mengikat keputusan LOSS/SCRAP, posting sink, transisi retur tanpa debit kedua,
dan penyelesaian kewajiban residual. Source dan title harus tetap diverifikasi.

`V175_138__warehouse_disposition_request.sql` dicadangkan sebelum pembuatan.
137 tetap immutable. Tes awal task28 telah mencapai retur dan inspeksi nyata,
lalu gagal pada POST dispositions404 (1 tes,1 kegagalan,1m32s).138 mengikat draft
LOSS/SCRAP ke sumber retur, posisi fisik, bukti dan biaya asli. Draft tidak boleh
memiliki efek fisik; posting dan penutupan kewajiban wajib memakai approval
independen yang dibuktikan terpisah sebelum task28 dianggap selesai.

## Task26: retur dan inspeksi

`V175_137__warehouse_replacement_cost_and_position.sql` dicadangkan sebelum
pembuatan.136 telah diterapkan dan immutable. Tes20 kasus menemukan7 kegagalan
pada precedence operator JSON biaya yang terisi.137 memperbaiki ekspresi itu
serta mengikat posisi terkini aset pengganti ke ledger APPLIED, termasuk
validasi deferred terhadap perubahan saldo/identitas aset.

`V175_136__warehouse_replacement_receipt_policy_context.sql` dicadangkan sebelum
pembuatan.135 telah diterapkan dengan12 tes lulus.136 mengikat nilai vendor yang
opsional pada snapshot receipt dan mengizinkan penolakan approval tanpa mengubah
request asal. Nilai kosong tetap UNKNOWN; tidak diberi harga nol otomatis.

`V175_135__warehouse_supplier_replacement_receipt.sql` dicadangkan sebelum pembuatan.
134 sudah diterapkan dan immutable. Request/replay LOAN dan SALE berhasil;
penerimaan masih ditolak oleh guard draft-only134.135 akan mengikat satu aset
baru, receipt vendor dan posting nyata per kasus servis, dengan pemilik yang
diturunkan dari sumber terverifikasi, tanpa perubahan identitas aset lama.

`V175_134__warehouse_supplier_replacement_request.sql` dicadangkan sebelum pembuatan
untuk mengikat draft RECEIPT vendor ke kasus servis dan hak milik asal.133 tetap
immutable. Penerimaan fisik wajib dibuktikan terpisah; draft pengganti tidak
mengubah aset lama atau memberi stok ISP dari aset pelanggan.

`V175_133__warehouse_return_title_leg_revisions.sql` dicadangkan sebelum pembuatan.
132 sudah diterapkan; tes approval menemukan perbandingan revision kedua leg
yang keliru. Revision balance CUSTOMER dan ISP berjalan terpisah, sehingga133
mengecualikan hanya revision per-dimensi dari perbandingan identitas kedua leg.
Validasi jumlah, owner, lokasi, custody, kondisi, ledger dan approval tetap wajib.

`V175_132__warehouse_return_reacquisition_effect.sql` dicadangkan sebelum pembuatan.
131 telah diterapkan dan immutable; request/replay201 serta3 tes modularitas lulus,
kasus approval berhenti pada owner yang belum tersedia.132 mengikat approval
independen, satu posting CUSTOMER->ISP dalam karantina, dan revisi retur immutable.
Riwayat assignment pelanggan tetap utuh; pelepasan memerlukan inspeksi reset baru.

`V175_116__warehouse_return_inspection.sql` dicadangkan sebelum pembuatan untuk
intake dari sumber retur terverifikasi, inspeksi terukur dan posting pelepasan.
Versi ini mengikuti175.115.1; seluruh migrasi terdahulu tetap immutable.
`V175_117__warehouse_return_lifecycle.sql` dicadangkan sebelum pembuatan setelah
175.116 diterapkan: dokumen inspeksi baru mulai DRAFT dan hanya case yang terikat
asalnya boleh maju ke hasil inspeksi; dokumen retur sumber tidak diubah.
`V175_118__warehouse_returned_asset_continuity.sql` dicadangkan sebelum pembuatan
untuk snapshot penerimaan aset hasil bongkar, posting penerimaan/inspeksi, serta
kelanjutan posisi fisik yang dibuktikan oleh penerimaan tersebut.175.116–175.117
sudah diterapkan dan tetap immutable.
`V175_119__warehouse_return_asset_origin_scope.sql` dicadangkan sebelum pembuatan
untuk memperjelas referensi kolom origin sesudah175.118 diterapkan; tidak ada
perubahan aturan asal barang atau perubahan byte175.118.
`V175_120__warehouse_recovered_position_ledger.sql` dicadangkan sebelum pembuatan
setelah tes app-role membuktikan perubahan kondisi aset+saldo tanpa posting bisa
lolos sesudah retur sah. Kelanjutan recovery wajib cocok dengan saldo ledger
APPLIED per dimensi dan posisi aset tunggal; rebuild proyeksi tetap diperbolehkan.
Seluruh byte175.119 dan sebelumnya tetap immutable.
`V175_121__warehouse_supplier_repair.sql` dicadangkan sebelum pembuatan untuk
case servis vendor yang terikat retur, custody outbound/inbound aset yang sama,
request dan riwayat immutable, serta inspeksi ulang setelah barang kembali.
Title pelanggan tetap CUSTOMER. Penggantian perangkat dan izin RMA kembali ke
pelanggan asal akan ditambahkan tersendiri;175.120 tetap immutable.
`V175_122__warehouse_inspected_return_settlement.sql` dicadangkan sebelum pembuatan
untuk membedakan quantity historis yang dikembalikan dari quantity retur yang
sudah lolos inspeksi. Penutupan material mengurangi outstanding hanya dengan
retur terikat yang telah diterima/diinspeksi, tanpa menghapus returned_base atau
mem-posting stok lagi. Snapshot dan guard penutupan tetap memeriksa sumbernya.
Seluruh versi sampai175.121 sudah diterapkan dan tetap immutable.

`V175_123__warehouse_deployed_material_settlement.sql` dicadangkan sebelum
pembuatan setelah tiga tes membuktikan used perangkat terpasang masih0 dan
penutupan dapat menghilangkan semua baris sejarah. Penggunaan serial dihitung
sekali dari hasil deployment APPLIED yang terikat issue; handover title tidak
menambah penggunaan. Snapshot lifecycle baru wajib memuat tepat seluruh kunci
sumber material saat transaksi ditutup.175.122 sudah diterapkan dan immutable.

`V175_124__warehouse_customer_rma_handover.sql` dicadangkan sebelum pembuatan
untuk serah terima perangkat CUSTOMER dari servis yang sudah diinspeksi kepada
teknisi WO REPAIR pelanggan asal. Dokumen dan penerimaan dua pihak terikat
sumber, serial, revisi dan posting fisik; tidak membuat issue stok ISP atau
memindahkan title. Izin pemasangan kembali ditambahkan sesudah jalur ini.

`V175_125__warehouse_customer_rma_deployment.sql` dicadangkan sebelum pembuatan
untuk izin pemasangan kembali yang bersumber dari handover RMA yang sudah
diterima teknisi. Sumber eksplisit tidak memakai issue/receipt/plan material
buatan; snapshot CUSTOMER dan assignment asal terikat. Guard deployment umum,
posting, title dan episode ONU tetap berlaku dengan cabang RMA yang dibuktikan.
Seluruh migrasi sampai175.124 sudah diterapkan dan immutable.

`V175_126__warehouse_rma_customer_acceptance.sql` dicadangkan sebelum pembuatan
untuk penerimaan pelanggan RMA bertanda tangan tanpa penjualan/posting ulang.
Perbandingan origin historis menormalkan hanya dua kolom RMA baru yang null atau
belum ada; bukti lama tetap byte-identical. Probe app-role atas data nyata sebelum
125 membuktikan penolakan origin akibat penambahan kolom tersebut.125 immutable.

`V175_127__warehouse_draft_delete_return_row.sql` dicadangkan sebelum pembuatan
untuk koreksi guard transfer yang mengembalikan NEW (NULL pada DELETE) untuk
semua draft non-transfer. Dua tes NORMAL/RESTORED membuktikan DELETE diam-diam
melewati row dan count tetap1. Kembalikan OLD setelah pemeriksaan scope dan
immutable transfer yang sama; tidak membuka penghapusan dokumen posted/bound.
175.126 dan seluruh migrasi terdahulu tetap immutable.

`V175_128__warehouse_rma_reacquisition_title.sql` dicadangkan sebelum pembuatan
setelah probe pengajuan alih title RMA yang diterima ditolak SOURCE_NOT_VERIFIED.
Revisi title awal RMA bernilai0: validator pengajuan memakai purpose pada history
assignment tersegel. Authorization RMA yang sudah dikonsumsi harus mengikuti
pemilik episode yang dibuktikan acceptance/transfer; sebelum konsumsi tetap wajib
CUSTOMER. Semua ikatan source, approval, posting, dan histori tetap berlaku.
127 belum diterapkan pada run sebelumnya;126 dan semua versi sebelumnya immutable.

`V175_129__warehouse_recovered_title_continuity.sql` dicadangkan sebelum pembuatan.
Pada keputusan approval RMA, PostgreSQL menolak ASSET_REMOVAL_HISTORY_POSITION_BINDING:
removal episode lama membandingkan pemilik fisik saat ini dengan pemilik historis.
Sesudah recovery diterima secara sah, pemilik saat ini sudah divalidasi terhadap
ledger APPLIED oleh warehouse_received_recovery/warehouse_assert_recovered_position.
Pisahkan title yang dapat berubah dari identitas fisik immutable hanya pada cabang
continued tersebut; sumber title/removal/assignment historis tetap terikat utuh.
Tambahkan probe app-role perubahan title tanpa posting.127/128 sudah applied dan
immutable; koreksi wajib melalui129.

`V175_130__warehouse_rma_consumption_once.sql` dicadangkan sebelum pembuatan.
Probe pemulihan scope membuat izin lama STALE_AUTHORITY secara benar, tetapi mint
baru dengan key baru ditolak IDEMPOTENCY_CONFLICT oleh batas unik handover pada
execution. Pindahkan batas sekali ke konsumsi yang terikat hasil immutable, dengan
tabel mapping handover/result yang diisi dari hasil nyata yang sudah ada. Mint
baru tetap memvalidasi custody dan authority saat ini. Hasil konsumsi tetap unik
per handover; jangan menambah kolom pada execution/result yang disegel acceptance.
Seluruh migrasi sampai129 sudah applied dan immutable.

`V175_131__warehouse_return_reacquisition_request.sql` dicadangkan sebelum pembuatan
untuk dokumen pengajuan alih title perangkat CUSTOMER yang benar-benar diterima di
karantina. Capture mengikat retur/removal/assignment lama, posisi saat ini, bukti
tanda tangan, canonical request dan dokumen draft; tidak memindahkan stok/title.
Keputusan approval dan posting alih title menjadi langkah berikutnya, dengan
validator yang tetap menolak efek sebelum ikatan approval lengkap tersedia.
130 sudah applied dan10 tes RMA lulus; seluruh migrasi sampai130 immutable.

## Wave 5: reservasi paralel task25, task27, task29

Pemeriksaan source pada checkpoint task24 memastikan migrasi tertinggi yang ada
adalah `V175_112` dan tidak ada file atau reservasi untuk `V175_113`,
`V175_114`, atau `V175_115`. Wave 5 mencadangkan namespace berikut tanpa
membuat atau menerapkan SQL:

| Tugas | Namespace pemilik | Pola file yang diizinkan | Cakupan |
| --- | --- | --- | --- |
| 25 | `V175.113` | `V175_113__*.sql`; koreksi sebelum versi lebih tinggi diterapkan: `V175_113_N__*.sql` | transfer/discrepancy |
| 27 | `V175.114` | `V175_114__*.sql`; koreksi sebelum versi lebih tinggi diterapkan: `V175_114_N__*.sql` | blind count/recount; pemilik utama dispatch approval Wave 5 |
| 29 | `V175.115` | `V175_115__*.sql`; koreksi sebelum versi lebih tinggi diterapkan: `V175_115_N__*.sql` | replenishment suggestion/request |

Integrasi 2026-09-24 membawa sembilan file yang sudah ada pada branch child,
tanpa mengubah byte migrasi:

| Tugas | Versi yang diimpor | Cakupan |
| --- | --- | --- |
| 25 | `175.113`, `175.113.1`, `175.113.2` | Transfer bindings, approved discrepancy, quarantine continuity |
| 27 | `175.114`, `175.114.1`, `175.114.2`, `175.114.3` | Blind sessions, approved result, variance variable correction, bound observation commands |
| 29 | `175.115`, `175.115.1` | Replenishment windows, bounded scheduler cursor and receiving snapshot/destination binding |

Maksimum source gabungan adalah `175.115.1`. Koreksi integrasi berikutnya harus
memakai versi lebih tinggi; jangan menyisipkan migrasi child. Database QA task29
yang sudah mencapai `175.115.1` tetap disimpan sebagai bukti child. Integrasi
membuat lingkungan QA baru untuk menjalankan urutan25→27→29 secara utuh.

Hanya worker pemilik namespace yang boleh menambah file di namespace tersebut,
dan nama/file yang hendak dibuat wajib dideklarasikan lebih dahulu di catatan
tugasnya. Worker tidak mengubah reservasi worker lain. Integrator tunggal
merekonsiliasi perubahan manifest dan shared files saat cherry-pick.

Child version seperti `V175_113_1` hanya boleh dibuat sebelum migrasi bernomor
lebih tinggi digabung atau diterapkan. Setelah `V175.114` atau versi lebih tinggi
diterapkan, koreksi task25 harus mengambil versi baru di atas maksimum global;
aturan yang sama berlaku untuk setiap namespace. Tidak boleh menyisipkan child
bernomor lebih rendah di belakang migrasi yang sudah diterapkan. Reservasi ini
bukan klaim bahwa file SQL ada atau bahwa migrasi telah diterapkan.

## M04: reservation task23

Verify-02 reserves V175_110__discovery_canonical_hash_binding.sql,
V175_111__metric_exact_historical_chain.sql and
V175_112__metric_interval_mutation_guard.sql before creation. These close the
joint receipt hash, false BOUND chain and post-insert interval findings. All
applied predecessors through175.109 remain immutable; legacy bytes are retained.

Temporal review reserves V175_106__ordered_topology_transition_time.sql and
V175_107__monitoring_receipt_retention_anchor.sql before creation, following
genuine T1/T7 failures. Applied versions through175.105 remain unchanged.

V175_108__network_observation_edge_history.sql is reserved before creation for
T2's reproduced ODC/ODP uplink changes. Network owns immutable edge history;
customer combines it with retained ONU topology through a public network API.

V175_109__monitoring_attribution_decision_evidence.sql is reserved before creation
after the T8 missing-evidence red. Old/direct rows without producer evidence stay
explicitly unverified; no historical origin or path is backfilled by guesswork.

Verify-01 reserves V175_102__monitoring_metric_attribution.sql,
V175_103__customer_observation_commit_fence.sql,
V175_104__monitoring_receipt_outcome_binding.sql, and
V175_105__cpe_historical_binding_evidence.sql before creation. These address
DB-1, current global CPE ownership coordination, DB-2 and DB-3 respectively.
All versions through175.101 remain immutable. Legacy metric/snapshot bytes are
preserved; unverified history is not silently promoted, and chunk retention remains supported.

2026-09-16 execution inventory: packaged source and live isolated Flyway both
end at V175.89 (275 validated migrations). V175.90-.93 below are reservations,
not applied files on this recovered host. Task23 now uses the existing reserved
V175.90-.92 slots for observation state, durable unassigned observations and
episode-bound CPE snapshots. V175.93 remains reserved; no predecessor changes.

Task23 additionally reserves V175_94__monitoring_discovery_receipts.sql and
V175_95__cpe_snapshot_integrity.sql before creation. V175.93 captures immutable
customer topology observation roots; V175.94 stores original discovery outcomes,
and V175.95 seals CPE snapshots to their current episode and persisted fields.
Applied V175.90-.92 remain unchanged.

Task23 reserves V175_96__cpe_observation_conflicts.sql before creation. A real
cross-tenant ACS ambiguity test reproduced the same global device being exposed
to two tenants. Preserve those rows and append tenant-scoped immutable conflict
records; the scheduler resolves unique owners across all tenants (including
suspended tenants) through the existing public TenantAuthorityDirectory.

V175_97__cpe_unassigned_observations.sql is reserved before creation for durable,
tenant-scoped ambiguity/stale-Inform reasons. Only canonical serial and hashed
ACS identity are retained there, never another customer's SSID/host/IP payload.

V175_98__cpe_parameter_freshness.sql is reserved before creation. A fresh Inform
with cached WiFi lacking post-assignment parameter timestamps reproduced an A-to-B
field leak. Store immutable parameter-refresh evidence and require it for verified
episode exposure. Old snapshots remain unchanged; legacy reads remain compatible.
GenieACS parameter refresh semantics: https://github.com/genieacs/genieacs/blob/master/docs/provisions.md

V175_99__monitoring_discovery_cycles.sql is reserved before creation. Actual task22
removal followed by observation reproduced a permanently resolved inbox row.
Preserve resolved history and permit one unresolved cycle per tenant/serial;
recovery remains quarantined, never available stock or a new physical identity.

V175_100__cpe_assignment_revision.sql is reserved before creation. Handover can
advance an active assignment revision, so observation bindings must read the
actual inventory-owned revision rather than infer it from open/closed state.
The failing handover/cache regression now drives this forward binding correction.

V175_101__monitoring_batch_scope.sql is reserved before creation. Final live QA
reproduced cross-tenant batch suppression from the historical global batch_id
primary key. Preserve existing rows, scope identity to tenant/collector/batch,
enforce tenant RLS and a composite collector FK, and retain replay markers for
the full72h acceptance window plus5min skew. Retention remains tenant-scoped.

Task23 reserves `V175_90__customer_observation_attribution.sql`,
`V175_91__monitoring_observation_binding.sql`, and
`V175_92__cpe_episode_snapshot_binding.sql` before SQL creation. These add
customer-owned immutable temporal attribution, monitoring metric/live-state
binding, and immutable ACS snapshot bindings with current-episode visibility.
V175.89 and predecessors remain unchanged. Physical inspection/reissue remains
task26; temporal reuse tests use scoped schema fixtures, not production bypasses.

`V175_93__customer_observation_episode_roots.sql` is reserved before creation.
The scoped task19 sequential-episode fixture reproduced a lookup failure because
an ONU episode need not have a later task20 command receipt. Attribution binds
the retained ONU root and its real event baseline, never invents a deployment
receipt, and keeps ACS freshness independent of SNMP's last sample timestamp.
Applied V175.90-.92 remain unchanged.

## M04: reservation task22

Task22 reserves the next forward versions before SQL creation:
`V175_80__warehouse_asset_removal_storage.sql`,
`V175_81__warehouse_asset_recovery_history.sql`, and
`V175_82__warehouse_asset_removal_integrity.sql`.
These store immutable physical removal/replacement links and customer retirement,
retain original deployment/title history, and validate exact recovery postings,
replacement consumption and durable provisioning delivery. Recovery is never
AVAILABLE and never transfers legal ownership. V175.79 and every predecessor
remain byte-identical; task23 attribution and task26 inspection are excluded.

`V175_83__warehouse_recovered_authorization_history.sql` is reserved before
creation. The first recovery transaction proved the consumed authorization still
checks the present asset condition as though it were an unconsumed issue. Validate
the sealed recovery graph and its captured installed source for consumed history;
unconsumed authorizations continue to require current serviceable custody.

`V175_84__warehouse_asset_delivery_and_relocation.sql` is reserved before creation
for delivery transition fencing, explicit physical recovery reporting, and
customer-owned idempotent topology relocation receipts. The app-role regression
first demonstrated that a PENDING delivery could be falsely marked SUCCEEDED;
terminal delivery now requires its live claim. Applied V175.80-.83 are unchanged.

`V175_85__warehouse_replacement_authorization_retirement.sql` is reserved before
creation. The two-authorization regression proved an unused competing permit
could prevent recovery at commit. Append an immutable removal-bound retirement
of unused permits, preserving their original issuance and authorization history;
retired permits cannot be consumed. Applied V175.84 remains unchanged.

`V175_86__warehouse_removal_permit_history_binding.sql` is reserved before
creation. A real pending task19 REMOVE permit reproduced a recovery failure:
retirement capture selected it, but the insertion guard accepted REPLACE only.
Retirement now covers every existing prior-assignment purpose without enabling
any new REMOVE/RMA deployment path. Consumed history also selects recovery by
the exact original assignment instead of asset ID alone. V175.85 is unchanged.

TASK22-VERIFY-01 reserves `V175_87__customer_onu_episode_events.sql` and
`V175_88__customer_onu_episode_revision_guards.sql` before creation. These add
immutable, tenant-bound episode events and validated revision reads. Existing
VERIFIED data receives a baseline under the pre-migration opening/retirement
revision semantics; raw inconsistent revisions are not rewritten or adopted.
Future topology changes and retirement advance the event sequence exactly once.
V175.86 and every predecessor remain byte-identical; this is not task23 telemetry
attribution or task26 inspection/reissue.

`V175_89__customer_episode_topology_timestamp.sql` is reserved before creation.
Packaged QA and a failing timezone regression exposed JSON timestamp text
comparison in the new validator. Compare the installed timestamp as an instant
while retaining exact topology keys and stored history bytes. Applied V175.87
and V175.88 remain unchanged.

## M04: reservation task21

The continuation reserves V175.70 through V175.73 before creation:
`V175_70__warehouse_title_correction_storage.sql`,
`V175_71__warehouse_title_approval_execution.sql`,
`V175_72__warehouse_acceptance_origin_seal.sql`, and
`V175_73__warehouse_title_final_state.sql`. These add independently approved
installed-title transfers, source seals and current-title/recovery validation.
V175.69 and all predecessors remain byte-identical; no historical acceptance is
silently reconstructed or approved by the migration.

V175.74 (`V175_74__warehouse_title_origin_row_alias.sql`) is reserved before
creation: executable acceptance found PostgreSQL resolving the `result` alias as
the same-named text column inside to_jsonb. Use an unambiguous row alias while
preserving the already applied V175.70-.73 files.

V175.75 (`V175_75__warehouse_title_signature_expression.sql`) is reserved before
creation to parenthesize JSON extraction before key subtraction in the acceptance
signature comparison. The real acceptance transaction exposed PostgreSQL operator
precedence; applied migration bytes remain unchanged.

V175.76 (`V175_76__warehouse_title_approval_snapshot.sql`) is reserved before
creation. Correction content belongs to the immutable title-request record and
the approval source snapshot, not the existing bounded document reference field.
The document keeps the request ID; approval includes the complete typed snapshot.

V175.77 (`V175_77__warehouse_title_final_seals.sql`) is reserved before creation.
Actual deployment executions also enforce frozen receipt intent at their shared
SQL validator. Correction snapshots retain exact predecessor/recovery context,
and completed correction documents require exactly one operation and transfer.

V175.78 (`V175_78__warehouse_title_authorization_scope.sql`) is reserved before
creation to qualify the execution authorization column against the shared
validator's identically named parameter. V175.77 remains unchanged.

V175.79 (`V175_79__warehouse_title_validator_entry_scope.sql`) is reserved before
creation after the full schema catalog gate exposed non-entry tenant assertions
in deployment and title mutation guards. Every guard asserts its captured tenant
before dispatch; document/fact guards retain their OLD/NEW per-reference checks.

Task21 reserves `V175_67__warehouse_asset_handover.sql` and
`V175_68__warehouse_asset_title_binding.sql` before creation. These slots are for
immutable customer acceptance, installed-title postings, recovery obligations,
and the final-state title/assignment/receipt/evidence linkage. V175.66 and all
predecessors remain unchanged. V176+ is not used.

`V175_69__warehouse_asset_handover_document_kind.sql` is reserved before creation.
The first owner invocation proved the inherited document kind/state CHECK still
rejects ASSET_HANDOVER despite its lifecycle branch. Extend those CHECKs without
changing applied V175.67/.68.

## M04: reservation task20

T20-01/02 reserve `V175_64__warehouse_deployment_fact_cardinality.sql` and
`V175_65__warehouse_deployment_document_lineage.sql` before creation.
DEPLOY has zero customer-material-fact rows: its physical fact is the sealed
assignment/customer-installation graph, not a task16 consumption fact. Every
POSTED DEPLOYMENT document must have one exact authorization, execution, operation,
result, posting and source-bound line. New mutation-side guards and validated
reads reject admitted corrupt histories without rewriting them or blocking boot.
V175.63 and all predecessors remain byte-identical.

`V175_66__warehouse_deployment_consumed_document_scope.sql` is reserved before
creation. The preserved fresh-authorization control exposed that an unconsumed
older authorization must not claim the fresh authorization's legitimate document
as its own extra document. Completed deployment graphs retain the exact check;
every orphan POSTED document independently remains forbidden. Applied V175.64/.65
are unchanged.

`V175_63__warehouse_deployment_posting_seal.sql` is reserved before creation.
A failing app-role test appended an extra movement header to a completed
deployment. Header/operation mutation sides must revalidate the sealed result;
the same forward guard binds VERIFIED assignments and deployment-owned lines.

`V175_62__warehouse_deployment_posting_kind.sql` is reserved before creation.
The committed-transaction probe exposed the cable CONSUME guard requiring a cable
usage snapshot. Serialized installation gets its own DEPLOY posting kind and its
existing deployment result guard; cable consumption guards remain unchanged.

`V175_61__warehouse_deployment_document_lifecycle.sql` is reserved before creation.
The first real consume reached the existing document guard and rejected the new
DEPLOYMENT DRAFT-to-POSTED transition. V175.59 and V175.60 have already applied
in isolated QA and remain unchanged.

Task20 reserves `V175_59__warehouse_deployment_execution.sql` and
`V175_60__warehouse_deployment_final_state.sql` before SQL creation.
Execution bindings and immutable outcomes extend task19 without changing its
authorization row or snapshot shape. Deployment adds an explicit installed
physical position and atomic owner-result validation. All V175.58 and earlier
bytes are preserved; V176+ remains untouched.

## M04: reservation task19

`V175_58__warehouse_authorization_timestamp_displacement.sql` is reserved before
creation. The malformed-offset regression exposed PostgreSQL's distinct
invalid_time_zone_displacement_value error. Handle that parser failure as a
non-matching timestamp without changing applied V175.57 or stored history.

T19-AV-02 reserves `V175_57__warehouse_authorization_snapshot_instants.sql`
before SQL creation. Authorization timestamps are `created_at` (required) and
`consumed_at` (nullable); history `recorded_at` is native immutable audit metadata,
not part of the authorization snapshot. There is no expiry column today.
The comparator discovers native timestamptz columns from the authorization row
type, checks exact JSON keys/non-timestamp values, and compares explicit-offset
timestamp strings as null-safe instants. Future timestamptz fields are included,
not silently ignored. Missing keys, malformed dates and true instant changes fail.
No stored JSON or global/database timezone is changed; V175.55/.56 remain frozen.

T19-AV-01 reserves `V175_55__warehouse_deployment_authorization_truth.sql` and
`V175_56__warehouse_deployment_authorization_triggers.sql` before SQL creation.
Both verifier cases committed before correction: mismatched INSTALL physical
identity and ordinary VERIFIED REMOVE over unresolved/conflicted legacy origin.
V175.48-.54 and all predecessors remain immutable. The correction does not scan,
rewrite or reject historical authorization rows at boot; owner reads/consumption
validation must use the shared assertion. No task20 command is activated.

INSTALL requires a matching acknowledged issue and no predecessor; REPLACE
requires that issue plus another verified prior asset assignment for the same
customer. REMOVE uses its same-asset prior assignment, not an arbitrary issue.
RETURN_CUSTOMER_RMA requires a matching issue, customer-owned verified physical
asset and same-customer/same-asset prior assignment. Repair-case authorization,
live WO/IAM epoch checks and workflow consumption remain task20+ responsibilities.

`V175_54__customer_asset_episode_canonical_truth.sql` is reserved before creation.
A failing VERIFIED whitespace-serial probe demonstrated SQL CHECK's NULL truth
semantics. The forward constraint uses total comparison and NOT VALID so boot
never retroactively admits, repairs or rejects persisted historical rows.

`V175_53__customer_asset_episode_snapshots.sql` is reserved before creation.
Failing app-role probes require accurate initial title/provenance snapshots and
append-only timestamped topology history. No task21 title transition is enabled.

`V175_52__customer_asset_episode_binding_scope.sql` is reserved before creation.
The first real receipt gate exposed a PL/pgSQL row-variable/table-alias ambiguity
inside the final-state validator. Applied V175.48-.51 remain unchanged.

Task19 reserves `V175_48__customer_asset_episode_storage.sql`,
`V175_49__customer_asset_episode_legacy.sql`,
`V175_50__customer_asset_episode_history.sql`, and
`V175_51__customer_asset_episode_binding.sql` before SQL creation.
The explicit task19 execution contract places M04 below V176; these next-free
forward slots supersede the original V176 placeholder. V175.47 and predecessors
remain byte-identical. V176 and later are not used by task19.

Existing ONU rows retain IDs, raw identity, customer and topology snapshots as
LEGACY_UNRESOLVED. No physical asset, receipt or stock is inferred from an ONU.
Verified deployment intervals use half-open boundaries and preserve closed
episodes. Authorization mint/consume, title transitions, removal and monitoring
attribution are later tasks, not operations enabled by this schema expansion.

## M03: reservation task18

Task18 rework reserves `V175_44__warehouse_material_rework.sql` and
`V175_45__warehouse_rework_usage_binding.sql` before SQL creation. Rejected WO
rework appends an additive submitted plan, immutable predecessor/inherited-line
references and an exact evidence revision. Usage binds the current rework plan
without changing the original source plan or consumption. V175.37-.43 stay frozen.

`V175_46__warehouse_rework_fresh_custody.sql` is reserved before creation. The
fresh-receipt delta gate distinguishes its initial receipt source from its own
newly inserted usage line during deferred validation; existing residual-source
semantics stay unchanged. Applied V175.44/.45 are not edited.

`V175_47__warehouse_rework_operation_fence.sql` is reserved before creation.
Direct reservation owner calls and physical operation inserts must honor current
rework evidence, not only the fulfillment route. Release/unpick remain available
for safe cleanup; stale positive allocation and use are rejected.

Task18 reserves `V175_37__warehouse_material_lifecycle.sql` and
`V175_38__warehouse_material_lifecycle_binding.sql` before SQL creation.
Cancellation releases only unpicked reservations; physical residuals require
explicit dispatch and acknowledgement. Returned stock stays quarantined and
nonavailable. These slots do not implement generic transfers or return inspection.
V175.36 and all predecessors remain unchanged; V176 remains task19's slot.

Task18 reserves `V175_39__warehouse_lifecycle_trigger_routing.sql` before creation.
The return test exposed PostgreSQL record-field resolution in a shared deferred
trigger. Route through JSON record fields without editing applied V175.37/.38.

Task18 reserves `V175_40__warehouse_residual_position_truth.sql` and
`V175_41__warehouse_wo_handover_authority.sql` before creation. Origin obligations
retain a durable due timestamp; final projections remain anchored to paired
postings. Handover is WO-specific, dispatcher-authorized and receiver-acknowledged,
not a generic warehouse transfer or return inspection implementation.

Task18 reserves `V175_42__warehouse_positive_usage_deltas.sql` before creation.
Positive measured deltas append a usage revision linked to its predecessor and
current acknowledged custody. Prior consumed facts and settlement receipts are
never rewritten; negative usage is not a return path.

Task18 reserves `V175_43__warehouse_obligation_snapshot_truth.sql` before creation.
App-role red evidence showed a self-consistent but false quantitative snapshot
could append. Bind every line to current owner totals and seal closure at final
transaction state without modifying previously applied migrations.

## M03: reservation task17

T17-AV-3 reserves `V175_36__warehouse_bng_exact_action_set.sql` before SQL creation.
A failing initial-receipt probe admitted duplicate action IDs while omitting a
correlated action despite matching counts. Exact identity equality closes this
gap without modifying the already applied V175.35 or rewriting stored history.

T17-AV-3 reserves `V175_35__warehouse_bng_initial_lineage.sql` before SQL creation.
Ordinary actions retain null provenance forever; fulfillment actions receive
their authoritative binding at INSERT. Old/new action references schedule exact
receipt-set checks independently of receipt array membership. V175.34 and all
predecessors remain unchanged.

`V175_34__warehouse_fulfillment_bng_handoffs.sql` is reserved before SQL creation.
Detached DEPROVISION and shared SYNC_GROUP handoffs are bound to their approval
transaction and immutable payload fingerprints, not an optional access FK alone.

`V175_33__warehouse_fulfillment_owner_read_binding.sql` is reserved before SQL
creation to disambiguate the read validator parameter from checkpoint.target_id.
Applied V175.30-V175.32 remain unchanged.

The independent T17-AV-1/T17-AV-2 correction reserves
`V175_30__warehouse_fulfillment_owner_receipts.sql`,
`V175_31__warehouse_fulfillment_owner_sources.sql`, and
`V175_32__warehouse_fulfillment_owner_completion.sql` before SQL creation.
Order ownership and visit identity/state come from authoritative owner rows;
generic progress cannot replace owner receipts or the durable handoff.
Applied V175.23-V175.29 and every predecessor remain byte-identical.

Task17 reserves `V175_29__warehouse_fulfillment_scope_entry.sql` before SQL
creation. The validator catalog requires the tenant assertion as the first
executable statement, before even assigning the captured tenant variable.

Task17 reserves `V175_28__warehouse_fulfillment_visit_fence.sql` before SQL
creation. A reproduced concurrent visit insertion escaped the locked WO;
the tenant-composite reference fences new links without rewriting legacy rows.

Task17 reserves `V175_27__warehouse_fulfillment_terminal_seal.sql` before SQL
creation. Three failing probes showed completed checkpoint links/state could be
rewritten while receipts survived. The forward seal preserves terminal identity.

Task17 reserves `V175_26__warehouse_fulfillment_explicit_links.sql` before SQL
creation. Provisioning requires a frozen BNG access link, not just a WO type or
subscription. An already approved WO cannot mint a new snapshot after mutation.

Task17 reserves `V175_25__warehouse_fulfillment_effect_arrays.sql` before SQL
creation to normalize varchar/text array comparison in the new validators.
V175.23/V175.24 have been applied in isolated QA and remain unchanged.

Task17 reserves `V175_23__warehouse_fulfillment_snapshots.sql` and
`V175_24__warehouse_fulfillment_binding.sql` before SQL creation.
Approval binds the authoritative WO context and immutable submitted usage.
Settlement only verifies the existing receipt/posting/fact/terminal graph and
records a verification receipt; it never posts stock. Explicit NONE remains
physical-effect-free. Ambiguous legacy handoffs require reconciliation.
V175.22 and every predecessor remain byte-identical; V176+ is untouched.

## M03: reservation task16

The independent T16-CONSUMED-PROJECTION-LINEAGE correction reserves
`V175_22__warehouse_consumed_projection_lineage.sql` before SQL creation.
Consumed posting history, not a currently positive projection, anchors exact
final positions and terminal identities. Balance and segment mutation sides
must revalidate captured identities even after zero/delete; replay validates
the same truth without repair. Exact same-transaction rebuild remains valid.
V175.21 and every predecessor remain byte-identical; V176+ is untouched.

Task16 reserves `V175_15__warehouse_material_usage.sql` and
`V175_16__warehouse_material_usage_binding.sql` before SQL creation.
Inventory owns immutable usage revisions sourced from accepted technician
receipt identities, exact consumption postings and accountable remnants.
Standalone work orders do not fabricate a customer. Explicit NONE usage has
no physical posting; serialized deployment and corrective compensation commands
remain closed boundaries for later tasks. V175.14 and all predecessors remain
unchanged; V176+ remains reserved.

Task16 additionally reserves `V175_17__warehouse_consumed_projection_binding.sql`
before SQL creation. The bounded regression exposed a deferred lookup of an
intentionally removed non-consumed projection during rebuild. The correction
limits consumed validation to consumed transitions and rejects reclassification
of consumed stock as reusable custody. V175.15 and V175.16 are already applied
locally and remain byte-identical.

Task16 reserves `V175_18__warehouse_terminal_consumption.sql` before SQL creation
after a rolled-back app-role probe reproduced zero-quantity reclassification of
consumed stock. Terminal consumption must survive multi-step updates and
projection replacement; all earlier migration bytes remain unchanged.

Task16 reserves `V175_19__warehouse_usage_work_order_revision.sql` before SQL
creation. Starting an acknowledged job advances the owner WO revision without
changing its immutable source plan. Usage binds the current expected WO revision
and the unchanged source-plan revision separately; current assignment, customer,
type and action remain required. All predecessors remain byte-identical.

Task16 reserves `V175_20__warehouse_consumed_final_state.sql` before SQL creation.
The final posting regression exposed an earlier positive projection event from
a valid cut-and-consume transaction. Deferred terminal validation must inspect
the final row, while consumed-to-reusable transitions remain forbidden.
This is a task16 projection correction, not task20 asset assignment.

Task16 reserves `V175_21__warehouse_usage_posting_seal.sql` before SQL creation.
Four failing app-role integration probes reproduced extra movement headers,
late paired legs and facts lacking usage linkage on a committed usage posting.
The owner operation now binds exactly one movement and every posting/fact append
revalidates the complete immutable usage graph. Existing bytes are preserved.

## M03: reservation task15

Task15 reserves `V175_14__warehouse_material_custody_receipts.sql` before SQL
creation. Inventory owns acknowledgement snapshots and accepted quantities,
linked to exact transit-to-technician posting and immutable dispatch identities.
Partial cable custody uses the existing atomic split pipeline with retained
lineage; discrepancy facts do not close transit or create availability.
V175.13 and all predecessors remain unchanged; V176+ remains reserved.

## M03: reservation task14

AV14 reserves `V175_12__warehouse_live_issue_binding.sql` before SQL creation.
It adds an explicit UNPICKED document transition and internally tenant-scoped
deferred guards for live issue/reservation revisions and quantities, including
selective constraint timing. Existing snapshots and migration bytes are preserved.
The live-binding projection also fences task10 commands and reservation expiry.

AV14 additionally reserves `V175_13__warehouse_issue_dispatch_binding.sql` before
SQL creation. A failing app-role probe showed that a fabricated DISPATCHED flag
could otherwise evade a live-only guard. Terminal issue state must retain its
actual immutable paired dispatch posting. V175.12 has already been applied in QA
and is not rewritten.

Task14 reserves `V175_11__warehouse_issue_snapshots.sql` before SQL creation.
Inventory persists immutable issue/line snapshots, explicit unpick records,
and dispatched quantities in demand supply snapshots. The migration extends the
existing posting/reservation model without changing any predecessor or V176+.

## M03: reservation task13

AV13 reserves `V175_10__warehouse_material_submission_binding.sql` before SQL
creation. It adds an internally tenant-scoped deferred final-state validator for
submitted plans, their immutable declaration/submission, the uniquely bound
demand and exact versioned lines. Queries use the same validator to reject
pre-existing corrupt bindings, without repairing history or fabricating totals.
V175.8/.9 and all predecessors remain byte-identical; V176+ remains reserved.

Task13 reserves `V175_8__warehouse_material_planning.sql` before SQL creation.
Task13 additionally reserves `V175_9__warehouse_material_template_lines.sql`
before SQL creation for normalized immutable template lines with composite SKU
unit references, transaction-sealed insertion and active-template archive guards.
Inventory owns versioned work-type/action templates with an explicit current
pointer, immutable master/plan snapshots, submission-to-demand bindings and
actor-bound replay receipts. Existing plan and document tables remain the task10
allocation source. Prior migration bytes and all V176+ slots are unchanged.

## M03: reservation task11

Task12 reserves `V175_3__warehouse_durable_approvals.sql` before SQL creation.
It adds sealed evaluation/source snapshots, immutable tier/candidate requirements,
actor-bound replay and document-bound effect receipts. Legacy approvals remain
staged, never inferred from movement IDs. V175 through V175.2 and all V174 bytes
remain unchanged. Opening approval stays closed without a migration source owner.

V175.4 was reserved before SQL creation to bind the terminal approved outcome to
its owner posting, outbox and inbox receipt at transaction commit. Its header
expiry check alone did not cover later balance-lock waits; AV12 adds the typed
pre-admission/post-lock application guard. Applied migration bytes are preserved.

V175.5 is reserved before SQL creation after the task04 regression gate found
the new deferred approval validator missing its own entry scope assertion.
The correction preserves V175.4 and adds `warehouse_assert_deferred_scope`
inside the validator, not a bypassable companion trigger.

AV12 reserves V175.6 for immutable attempted-decision command context and V175.7
for insert/deferred decision bindings before SQL creation. Slots verified free;
V175.3-.5 and all predecessors remain unchanged. Null-policy legacy decisions
must never attach to executable source-bound requests. No V176+ changes.

Task11 reserves `V175__warehouse_policy_foundations.sql` before creating SQL.
V174.14 and every earlier migration remain byte-identical. V176 and later slots
remain reserved for their existing owners. This expansion does not create
approval requests, decisions, movements, repair jobs or replenishment requests.
Policy settings are control-plane operations; evaluation is not approval.

V175.1 is reserved before SQL creation after the real policy-child insertion
test exposed PostgreSQL record-field resolution across heterogeneous triggers.
V175 was already applied successfully and remains unchanged; V175.1 makes the
approver-only tier check a separate PL/pgSQL branch.

V175.2 is reserved before SQL creation to let new source-bound approval/count
rows omit legacy integer projections rather than fabricate zero or truncate
exact base quantities. Legacy rows remain unchanged; complete new source
snapshots are required whenever those compatibility projections are absent.

## M01/M02: persistence task04

Task10 mencadangkan V174.14 sebelum SQL dibuat. Tambahan ini mengikat reservasi
ke line demand/plan dan revisi sumber nyata, menyimpan snapshot shortage, serta
mengizinkan siklus reservasi baru setelah release tanpa membuka reservasi terminal.
Release/expiry mengembalikan state demand sesuai jumlah tersisa. Semua byte
V173-V174.13 dipertahankan; V175+ tidak dipakai. Gate task04 wajib diulang.

AV8 mencadangkan V174.13 sebelum SQL dibuat, setelah memastikan slot bebas.
Revision/hash konten intake terpisah dari revision operation dokumen. Evidence
baru terikat snapshot konten yang tepat; evidence historis tanpa binding tetap
disimpan tetapi tidak boleh mengotorisasi inspeksi baru. Binding tidak direkayasa
dari snapshot receipt terkini. V174.12 dan seluruh byte sebelumnya dipertahankan;
V175+ tidak dipakai. Gate schema mencakup seluruh tabel task08 dan upgrade174.13.

Task08 mencadangkan V174.12 sebelum SQL dibuat. Tambahan M02 menyimpan snapshot
intake draft, bukti objek privat dan disposition inspeksi yang terikat identitas,
line dan operation. Stok tetap melalui posting task05/command task06. Opening
balance tetap fail-closed tanpa approval independen task12. Tidak mengubah byte
V173-V174.11, tidak memakai V175+, dan tidak mengaktifkan policy approval baru.

Task07 mencadangkan V174.8 sebelum SQL dibuat: metadata category/model/minimum
SKU, site location, serta binding operation master tanpa dokumen stok palsu.
Operasi stok tetap wajib memiliki document_id; hanya namespace master bernama
yang boleh memakai binding master. Guard arsip/referensi dan hierarki diperketat
secara forward-only. V173 hingga V174.7 tidak diubah; V175+ tetap milik task lain.
Gate perubahan ini mencakup seluruh WarehouseSchemaIT, restart/upgrade, dan
WarehouseMasterIT melalui warehouse_app non-owner dengan FORCE RLS.

V174.9 dicadangkan setelah uji V174.8: pertahankan SQLSTATE 23503 untuk referensi
master asing/hilang (bukan 23514), tutup binding operation NULL, dan cegah hard
delete serta referensi saldo/posting baru ke master arsip. V174.8 yang sudah
diterapkan pada warehouse_test tidak diedit ulang.

AV7-02 mencadangkan V174.10 sebelum SQL dibuat, setelah pemeriksaan seluruh
V174.x memastikan slot bebas. Tambahkan key tenant/site bila belum tersedia,
FK gabungan inventory_location ke site, serta guard area site dan parent/site.
FK NOT VALID mempertahankan siteId historis yang sudah dangling tanpa menghapus
data; write baru wajib valid, read/replay menyembunyikan reference lama tidak
konsisten. Jangan memvalidasi atau memperbaiki reference historis secara diam-diam.
Tidak ada perubahan byte V174.9 ke bawah atau pengambilalihan slot V175+.

V174.11 dicadangkan sebelum SQL dibuat setelah pemeriksaan slot V174.x. Koreksi
inheritance menambah fence revisi topology per tenant (FORCE RLS), validasi
deferred atas seluruh ancestry dan subtree yang berubah, scope assertion pada
validator sebenarnya, serta penolakan cycle/depth overflow. Site efektif adalah
satu-satunya site non-null pada rantai, bukan site immediate parent. Histori
tidak konsisten dipertahankan tanpa backfill destruktif dan disembunyikan reader.
V174.10 dan seluruh migrasi sebelumnya tetap byte-identical; V175+ tidak dipakai.

Reservasi tambahan V174.1 dicatat sebelum file dibuat setelah probe PostgreSQL
menemukan upper/btrim default berbeda dari codec Kotlin untuk Unicode/whitespace.
Versi ini berada setelah V174 dan sebelum M03 V175, tidak menduduki slot owner
lain, tidak memakai ulang gap, dan tidak mengubah checksum V173/V174 yang sudah
diuji pada database task. PostgreSQL ICU root collation `und-x-icu` digunakan
untuk uppercase penuh (misalnya sharp-s -> SS), dengan himpunan whitespace
Character.isWhitespace/isSpaceChar yang sama dengan Kotlin trim. Candidate
menyimpan canonical/claim sebelumnya; claim alias lama tidak dihapus atau
diberikan kepada aset lain.

V173 membuat SKU/conversion/supplier, identity claim/candidate, cutover dan epoch
IAM, lot/segment; V174 membuat document/line, operation/outbox/inbox, reservation,
material plan/line, usage snapshot, inspection dan warehouse scope. Semua tabel
tenant baru memakai FORCE RLS, USING/WITH CHECK dan foreign key tenant gabungan.
`inventory_segment.id` adalah stockIdentityId; untuk SERIAL sama dengan assetId.
Lot dan segment bukan pengganti asset fisik lama.

Kolom quantity/raw serial/MAC lama tidak dihapus atau ditafsirkan ulang. Quantity
lama boleh null untuk baris baru yang hanya mempunyai quantity_base; pembaca lama
harus memakai proyeksi kompatibilitas, bukan mengubah MM menjadi count.
Serialized movement leg lama mempunyai unit yang diketahui (EA); bulk legacy dan
saldo dengan unit tidak terbukti tetap quantity_base/base_unit null. Seluruh
baris lama tetap LEGACY_UNRESOLVED dan tidak masuk dimensi saldo VERIFIED.

Satu claim unik tanpa predicate mencadangkan setiap grup canonical; candidate
menyimpan semua sumber asset, tombstone dan ONU beserta raw aslinya. Grup bertabrakan
CONFLICT tanpa admitted_asset_id; tidak ada pemilihan pemenang. Candidate hanya
ditulis migration owner. Deferred FK diverifikasi sebelum ALTER TABLE berikutnya
agar upgrade dengan kandidat tidak gagal karena pending trigger events.

Dokumen dimulai DRAFT; header dan lines membeku setelah transisi pertama. Revisi
update harus tepat sebelumnya+1. Operation/original response, event, inbox,
inspection, usage snapshot, lot dan posting APPLIED append-only. Outbox adalah
event immutable; task06 tetap bertanggung jawab atas protokol delivery/retry.
Receipt origin dan claim SERIAL divalidasi di akhir transaksi untuk mendukung
insert atomik document/line/asset/segment tanpa menciptakan origin palsu.

`InventoryTenantInitializationApi` adalah API internal owner, bukan controller.
Caller wajib memakai transaksi aktif dan TenantContext tenant yang dibuat;
initializeNewEmptyTenant memeriksa waktu pembuatan tenant setelah activation
migrasi serta ketiadaan stock/history. Existing tenant diinisialisasi LEGACY,
termasuk tenant kosong. Missing policy menghasilkan CUTOVER_REQUIRED. Tidak ada
inisialisasi otomatis pada read atau listener asinkron yang mengaku atomik.

InventoryTenantPolicyService menyediakan fence transaksi, C10 allowlist dan
LEGACY->VALIDATING dengan watermark/batch/pending legacy movement IDs. Fence
tidak menggantikan permission check atau memberi hak approval. Operasi migrasi
yang perlu persetujuan tetap INDEPENDENT_APPROVER_REQUIRED; finalisasi mengembalikan
typed INDEPENDENT_APPROVAL_NOT_INSTALLED. Guard DB juga menutup VALIDATING->ENFORCED
sampai pemilik approval menyediakan validasi nyata melalui migrasi forward-only
yang terkoordinasi. Task05/06 wajib menghubungkan seluruh writer lama/baru ke
posting dan fence sebelum mengaktifkan operasi warehouse. Task04 tidak memasang
receipt API, posting service, permission adapter, assignment atau workflow WO.

Uji task04:

```sh
scripts/warehouse/qa.sh server --tests '*WarehouseSchemaIT*' --rerun-tasks --no-parallel
```

Suite menjalankan clean/upgrade semua migration dalam schema temporer bernama
unik, SQL adversarial sebagai warehouse_app, JPA round-trip dan dua Spring context
baru untuk restart. Schema fixture dihapus, database/volume task dipertahankan.
Owner dipakai hanya untuk DDL/fixture upgrade, bukan bukti akses aplikasi.

Jangan memakai ulang atau menomori ulang migrasi yang sudah diterapkan.
Jika merge menduduki slot ini, hentikan implementasi dan sepakati migrasi
kompatibilitas forward-only. Tambahan file per slot memerlukan pembaruan
manifest terkoordinasi sebelum SQL dibuat. Database produksi tidak diperiksa
atau diubah oleh tugas baseline ini.

## QA lokal terisolasi

Prasyarat: Linux, Bash, Docker Compose, akses socket lokal Docker (atau
`sudo -n docker`), JDK21/Gradle wrapper, Node/npm, curl, openssl, flock.
Port 25432 dan 29000 harus bebas. Tidak ada fallback ke `ftth_test`.

```sh
scripts/warehouse/test-environment.sh up
scripts/warehouse/test-environment.sh check
scripts/warehouse/qa.sh server --tests '*WarehouseEnvironmentIT*' --rerun-tasks --no-parallel
scripts/warehouse/qa.sh stop
scripts/warehouse/test-environment.sh down
```

Runner membuat `.omo/runtime/warehouse-test.env` milik user dengan mode0600.
Jangan source, cetak, atau commit file ini. Namespace Compose memuat hash
worktree dan token acak; container, network, volume, dan kedua database
memiliki marker. Endpoint dipublikasikan hanya pada 127.0.0.1.
`warehouse_owner` adalah pemilik migrasi NOSUPERUSER dengan BYPASSRLS untuk
backfill lintas tenant; `warehouse_app` bukan owner, bukan anggota owner,
NOSUPERUSER/NOCREATEROLE/NOCREATEDB/NOBYPASSRLS. Extension dipasang admin
hanya pada database task-owned. Aplikasi mendapat DML, bukan DDL.

Semua override konfigurasi dari shell ditolak sebelum akses DB. Runner
mengekspor datasource Spring dan Flyway secara terpisah setelah validasi.
Override inline lama pada `SchedulingSpringContextIT` juga mengikuti
`SPRING_DATASOURCE_URL`; ini penting karena properti annotation Spring test
berprioritas lebih tinggi daripada environment. Fallback lama di test tersebut
tetap hanya untuk eksekusi di luar runner, bukan jalur warehouse QA.
Gradle dan lifecycle lingkungan menggunakan satu lock per worktree.
`down` hanya menghapus container/network milik task dan mempertahankan volume.
Startup yang diinterupsi membersihkan container task, sehingga `up` dapat
diulang dengan env/volume yang sama. Marker tidak cocok harus diselidiki,
bukan diatasi dengan menghapus volume lama atau melonggarkan validator.
`stop` tetap dapat berjalan sesudah `down`: cleanup lokal memeriksa file env
dan identitas PID tanpa memerlukan koneksi DB yang sudah dihentikan.

`web-test FILTER`, `web-check`, dan `kmp` mengikuti command plan. KMP gagal
secara eksplisit hingga modul materials tugas42 tersedia. Browser juga
gagal eksplisit hingga config/spec tugas31 dan isolasi adapter mutasi eksternal
tersedia; tidak ada hasil browser palsu atau backend produksi yang dipakai.
Profil `application-warehouse-e2e.yml` tugas31 harus mematikan adapter mutasi
perangkat/notifikasi eksternal tanpa mengganti layanan warehouse/customer/WO
atau storage dengan mock. Runner menolak browser sebelum profil ini ada.
Sesudah tersedia, runner memakai metadata `server/build/warehouse/boot-jar-path.txt`
dari task bootJar (bukan wildcard JAR), port17880/14188 dengan proxy eksplisit,
JSON health backend/proxy, laporan Playwright nonzero pada desktop/mobile,
serta cleanup process group dengan PID/start-time/marker yang tercatat.
Readiness wajib menyertakan health contributor `components.warehouse.details`
dengan `database=warehouse_e2e`, `user=warehouse_app`, dan `marker` dari
`WAREHOUSE_ENVIRONMENT_MARKER`; respons generik UP saja tidak diterima.
Contributor task31 harus membaca identitas dari koneksi DB aktif, bukan
sekadar menyalin environment. Detail ini hanya boleh terbuka di profil lokal.
Config Playwright task31 harus mengaktifkan reporter JSON (boleh bersama line),
mengikuti `PLAYWRIGHT_JSON_OUTPUT_NAME`. Laporan hilang, nol test pada salah
satu project, test dilewati, flaky, atau gagal membuat runner gagal.

## 2026-09-07: koreksi verifikasi independen task04

Manifest M02 kini **V174, V174.1, V174.2, V174.3**. V174.2 diperiksa bebas
dan dicadangkan sebelum SQL dibuat. V173/V174/V174.1 tetap byte-identical;
V175-V178 tetap milik task berikutnya. Koreksi forward ini menutup AV-01
(rantai VERIFIED), AV-02 (opening tanpa approval), dan AV-03 (lot serta saldo
parent split), bukan migrasi approval/assignment baru.

Pemeriksaan admission/provenance dan spendability memakai constraint trigger
deferred sehingga receipt dan split atomik diperiksa terhadap keadaan akhir
transaksi. Parent terminal tidak boleh menyisakan saldo VERIFIED positif atau
encumbrance terbuka. Alias claim lama tetap dicadangkan; RETIRED bukan izin stok.
Opening memakai satu extension point DB document/revision-bound yang selalu
menolak sampai task12 memasang pembuktian approval independen yang nyata.

Tenant owner menerbitkan event root saat tenant benar-benar dibuat. Listener
inventory dan IAM sinkron, MANDATORY, fail-fast pada transaksi yang sama;
tidak memakai AFTER_COMMIT/best-effort. JDBC scoped di koneksi Hibernate yang
sama memulihkan GUC asal tanpa mengganti tenant EntityManager di tengah transaksi.
Pengiriman event ganda tidak mempromosikan policy existing atau menaikkan epoch.

## V174.3: kapasitas agregat root lot

V174.3 dicadangkan setelah pemeriksaan slot bebas, sebelum SQL dibuat. Tidak ada
perubahan byte V173/V174/V174.1/V174.2 atau pemakaian slot V175-V178.
Jumlah seluruh root VERIFIED (`parent_segment_id IS NULL`) per tenant/lot tidak
boleh melebihi received_quantity_base. ACTIVE, SPLIT dan RETIRED tetap dihitung;
child hanya mewakili pemecahan root, bukan penerimaan fisik tambahan.

BEFORE INSERT/UPDATE mengunci row lot lama/baru menurut tenant/id, lalu constraint
deferred memeriksa SUM numeric terhadap keadaan akhir transaksi. Mutasi segment
lot memakai READ COMMITTED (READ UNCOMMITTED PostgreSQL ekuivalen); snapshot
transaction REPEATABLE READ/SERIALIZABLE ditolak40001 agar snapshot lama tidak
melewati SUM setelah menunggu writer READ COMMITTED. Caller harus mengulang
seluruh transaksi dengan profil row-lock READ COMMITTED; tidak ada pelemahan
append-only lot hanya untuk memperbarui counter alokasi.

## V174.4: scope tenant saat constraint deferred dieksekusi

V174.4 diperiksa bebas dan dicadangkan sebelum SQL dibuat. Byte/checksum V173
sampai V174.3 tidak berubah; slot V175-V178 tidak digunakan. Constraint kapasitas
menolak scope tenant kosong/berbeda dan lot referensi yang tidak terlihat atau
hilang, bukan melewati validasi ketika RLS menyaring row tersebut.

Guard deferred invoker-rights juga menjaga tenant row untuk segment, lot, asset,
balance, reservation, claim dan usage snapshot. Tidak ada SECURITY DEFINER atau
penonaktifan RLS. Tenant GUC harus cocok saat validasi commit; context yang
dipulihkan sebelum commit diperiksa normal. Staging oleh migration owner setelah
V174.4 juga harus memasang context tenant secara eksplisit, bukan memanfaatkan
BYPASSRLS sebagai pengganti scope transaksi.

## V174.5: scope internal pada setiap validator deferred

V174.5 diperiksa bebas dan dicadangkan sebelum SQL dibuat. V173-V174.4 tetap
byte-identical dan V175-V178 tetap milik task berikutnya. Audit katalog menemukan
20 trigger constraint warehouse DEFERRABLE (selain FK internal PostgreSQL),
menggunakan delapan fungsi. Kapasitas lot dan companion sudah memiliki assertion
internal; enam fungsi lain diperbarui tanpa mengubah logika domain setelahnya:
asset claim, stock provenance, source provenance, origin, segment conservation,
dan usage postings. Semuanya SECURITY INVOKER.

Setiap fungsi memanggil `warehouse_assert_deferred_scope(NEW.tenant_id)` sebagai
statement pertama, sebelum IF/SELECT/loop. Scope bukan bukti yang dapat dipakai
ulang dari companion: `SET CONSTRAINTS ... IMMEDIATE` pada constraint lain tidak
boleh menghilangkan validasi yang masih pending. Pengujian mengisolasi setiap
fungsi dengan mengeksekusi constraint lainnya terlebih dahulu, termasuk data
valid pada scope correct/restored dan penolakan data invalid melalui constraint
aktualnya sendiri. Mutasi source baru sesudah validasi awal diperiksa ulang.

## Reservasi V174.6: metadata command dan delivery task06

Slot V174.6 diperiksa bebas sebelum file SQL dibuat. Patch M02 ini hanya
menambahkan metadata canonical command immutable dan state delivery mutable yang
mereferensikan outbox immutable. Payload/event lama tidak diubah. Semua byte
V173-V174.5 tetap dipertahankan; V175-V178 tetap milik owner berikutnya.
Gate WarehouseSchemaIT wajib dijalankan kembali setelah patch.

## Reservasi V174.7: delivery produksi dan revisi referensi

Slot V174.7 diperiksa bebas sebelum SQL dibuat. Tambahan M02 ini membuat jurnal
observasi provenance milik fulfillment, revision fence WO/roster dan larangan
menghapus state delivery. Tidak mengaktifkan settlement, approval atau assignment
baru. Seluruh byte V173-V174.6 dipertahankan dan gate task04 dijalankan kembali.

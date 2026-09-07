# Kontrak Gudang, Material WO, dan Asal Aset

Status: kontrak publik task 2, bukan implementasi WMS. Dokumen C1-C11 ini
mengikat implementasi berikutnya. Interface tidak memiliki adapter, bean,
fallback, atau default sukses. Tes kontrak membuktikan bentuk API/wire dan arah
dependensi; belum membuktikan transaksi, RLS, perubahan stok, atau deployment.

Tipe publik berada di root module `com.duluin.ftth.<owner>`. UUID adalah referensi
opaque, bukan bukti akses. Tidak ada entity JPA, repository, controller, atau
domain internal yang boleh melintasi API baru. DTO request berbeda dari snapshot,
event, dan binding server. Serialisasi binding server sebagai request dilarang.

## C1. Pemilik Tunggal dan Arah Dependensi

| Pemilik | Otoritas | Kontrak publik |
|---|---|---|
| inventory | Aset fisik, dokumen, ledger, saldo, reservasi, custody, pemakaian, episode assignment | [InventoryMaterialApi](../server/src/main/kotlin/com/duluin/ftth/inventory/InventoryMaterialApi.kt), [InventoryDeploymentApi](../server/src/main/kotlin/com/duluin/ftth/inventory/InventoryDeploymentApi.kt) |
| workorder | Eksekusi, roster aktif, pembatalan, QA; referensi revisi material | [WorkOrderMaterialContextApi](../server/src/main/kotlin/com/duluin/ftth/workorder/WorkOrderMaterialContextApi.kt) |
| customer | Proyeksi layanan/topologi ONU dan episode instalasi historis | [CustomerAssetApi](../server/src/main/kotlin/com/duluin/ftth/customer/CustomerAssetApi.kt) |
| IAM | Pengguna aktif, role/izin/area efektif, epoch otorisasi | [CurrentAuthorityApi](../server/src/main/kotlin/com/duluin/ftth/iam/CurrentAuthorityApi.kt) |
| common | Identifikasi sesi dan protokol fence, bukan penyimpanan otoritas | [AuthorityFence](../server/src/main/kotlin/com/duluin/ftth/common/security/AuthorityFence.kt) |
| network / CPE | Port/topologi / observasi ACS | Tetap melalui API pemilik masing-masing |
| fulfillment | Koordinasi lintas pemilik dan outbox provisioning yang sudah ada | MaterialWorkflowService/Controller disediakan task berikutnya, bukan koordinator kedua |

Dependensi statis: `workorder -> inventory`, `customer -> inventory`,
`inventory -> IAM/common`, `fulfillment -> API publik pemilik`.
Inventory tidak membaca tabel atau memanggil internal customer/workorder/fulfillment.
IAM tidak memanggil inventory untuk scope: `InventoryWarehouseScopeApi` membaca
scope gudang dengan fence IAM yang sedang ditahan. Nama laporan inventory berupa
`WarehouseDocumentLabels.customerLabelSnapshot/workOrderCodeSnapshot` immutable;
detail lintas module yang segar dirakit fulfillment/subscriber360, bukan reverse call.

Inversi wajib:
`InventoryWorkOrderValidationPort.lockAndValidate(DeploymentValidationContext): ValidatedWorkOrderContext`.
Port dan kedua DTO berada di inventory root. Adapter task berikutnya dimiliki
workorder, menginjeksi repository workorder dan layanan otoritas, **bukan**
InventoryDeploymentApi, WarehousePostingService, atau MaterialWorkflowService.
Ini satu-satunya jalur consume-time untuk memvalidasi WO di inventory.

`WorkOrderMaterialContextApi.read(id)` adalah snapshot, bukan izin mutasi.
`lock(id, expectedRevision, authority)` mewajibkan transaksi aktif, fence lebih
awal, row lock WO, dan pemeriksaan revisi. Lock bertahan sampai commit/rollback.
Context mengizinkan customer/subscription/order/visit null untuk pekerjaan jaringan.
Tidak boleh merekayasa pelanggan supaya materialless/preventive WO bisa berjalan.

## C2. Identitas, Dimensi, dan Kuantitas

`assetId` tetap ID `inventory_serialized_asset` lama. Retur/reuse tidak membuat
aset fisik baru. Serial kanonis = trim + uppercase locale-independent; MAC
diparse menjadi 12 hex uppercase dan ditampilkan dengan titik dua. Normalisasi
bersama berada di common (value object task 3). Unik kanonis
per tenant tetap mengikat setelah arsip/disposal; benturan tidak memilih pemenang.

`WarehouseTracking = SERIAL|LOT|BULK`, `WarehouseBaseUnit = EA|MM`.
Perangkat serial selalu 1 EA. Nilai internal adalah signed Long dengan aritmetika
checked; jumlah fisik tidak negatif. Wire `quantityBase` adalah string integer,
bukan JSON number; panjang tampilan adalah string metre dengan 0-3 digit pecahan.
Tolak overflow, pecahan EA, nonfinite, dan presisi lebih dari 3, bukan dibulatkan.
`WarehouseQuantity("82500", MM, "82.500", M)` berarti 82.500 m, bukan 82.500 unit.
Konversi paket menyimpan rasio integer positif per receipt; unit SKU tidak berubah
setelah posting. GIS hanya estimasi dan tidak pernah mengubah stok.

Implementasi nilai task 3:

- `StockQuantity` berisi `quantityBase: Long` nonnegatif dan `StockUnit` immutable.
  Factory `of`/`parseBase`/`each`/`metres` satu-satunya konstruksi; operasi tambah,
  kurang, kali checked dan menolak campuran EA/MM. Stok kurang melempar
  `ConflictException`; input/overflow melempar `ValidationException`. Tidak ada
  floating point atau pembulatan. Nol adalah saldo sah, bukan perangkat serial.
- Metre menerima `[0-9]+(\.[0-9]{1,3})?`, integer base `[0-9]+`. Tidak ada tanda,
  eksponen, whitespace, digit Unicode, koma, atau presisi ekstra. Leading zero
  diterima sebagai nilai sama. `toMetres` dan keluaran wire MM selalu tiga digit
  pecahan (`82500 -> "82.500"`); masukan wire boleh 0-3 digit. Konversi eksplisit
  `WarehouseQuantity.toStockQuantity` membandingkan unit dan kedua nilai; mismatch
  menghasilkan `MALFORMED_REQUEST`, tidak memilih salah satu nilai diam-diam.
- `ReceiptConversion` menyimpan numerator/denominator positif asli, tidak
  direduksi pada snapshot. Maknanya base-unit per paket; `toBase` dan `toPackages`
  menuntut pembagian tepat, membatalkan faktor sebelum checked multiply agar
  hasil sah tidak gagal karena intermediate overflow. Jumlah paket integer;
  pecahan paket tidak diterima. Unit diperoleh dari snapshot SKU receipt, bukan
  katalog terkini. DTO `ReceiptConversionSnapshot` dikonversi eksplisit di
  `inventory.application.StockValueConversions`, bukan menjadi model domain.
- `StockUnitDefinition` adalah pasangan tracking/unit immutable; SERIAL hanya EA
  dan `parseQuantity` satu aset serial harus tepat 1. LOT/BULK boleh EA/MM.
  Saldo beberapa aset adalah agregat EA, bukan kuantitas satu aset. Task 4 dan
  posting berikutnya wajib menyimpan snapshot ini serta menolak perubahan
  tracking/unit SKU setelah posting; task 3 tidak mengklaim enforcement DB.
- Common `SerialIdentity`/`MacIdentity` menyimpan `raw` persis masukan dan
  `canonical` untuk equality/hash/claim. Serial menggunakan `Locale.ROOT`;
  tidak menghapus tanda di tengah serial atau menafsirkan serial sebagai MAC.
  MAC menerima 12 hex tanpa separator, enam pasangan dengan colon/dash (boleh
  campuran), atau tiga kelompok empat hex bertitik. Whitespace luar ditrim;
  separator berulang/salah posisi, whitespace dalam, dan non-hex ditolak.
- `StockIdentity` menggunakan kedua nilai common dan tepat 1 EA. Customer
  `Onu.create` memakai codec serial yang sama, tetap dengan batas format lama.
  Inventory registration/read dan ONU rehydrate tidak diubah: case/separator/raw
  historis tetap tersedia. Nilai kanonis **bukan** bukti claim tenant unik atau
  resolusi collision. Migrasi/persistence berikutnya wajib menyimpan raw secara
  terpisah dan menegakkan claim tanpa memilih pemenang dari benturan lama.

`WarehouseStockDimension` selalu dipakai bersama tenant autentikasi: SKU,
stockIdentityId, lot bila ada, location, custodian, condition, legalOwner.
Condition `SERVICEABLE|QUARANTINE|DAMAGED|SCRAP`, owner `ISP|CUSTOMER|UNKNOWN`
terpisah dari reservasi. Tersedia = fisik serviceable milik ISP di bin eligible
dikurangi encumbrance terbuka. Transit, picked, provisional installation,
quarantine, customer RMA, dan UNKNOWN bukan stok ISP tersedia.

stockIdentityId wajib untuk setiap serial, reel/cut/remnant MM, dan bulk-lot EA.
Permintaan MM `continuousCut=true` secara default. Dua remnant 10 m tidak memenuhi
satu cut 15 m tanpa revisi demand eksplisit. Split reel 1000 m menjadi 100+900 m
mematikan parent spendable dan membuat children serta `StockSegmentLineage`
dengan total sama dalam transaksi yang sama; reservasi mengikuti child yang tepat.
Pemakaian 82.5 m dari issue 100 m menyisakan child remnant 17.5 m, bukan reel utuh.
`reservedUnpickedBase + reservedPickedBase` satu encumbrance; pick tidak mengurangi
dua kali, dispatch mengonsumsi encumbrance sambil memindahkan fisik.

## C3. Posting, Lock, dan Idempotensi

WarehousePostingService task berikutnya menjadi satu otoritas posting; tidak ada
insert leg alternatif dari durable fulfillment atau peta in-memory produksi.
Satu transaksi menyimpan revisi dokumen, header/leg immutable, saldo checked,
delta reservasi, custody, usage fact, original response, dan outbox. Transfer
berpasangan OUT/IN sama base unit; consume masuk sink eksplisit, tidak memotong
gudang asal lagi. Reserve/release adalah event availability, bukan IN/OUT fisik.
Baris saldo nol dibuat di bawah UNIQUE dimensi sebelum arithmetic/lock sehingga
race missing-row tidak menjual aset terakhir dua kali.

Urutan `WarehouseLockStage` berlaku global:

1. TENANT_CUTOVER: shared fence, bandingkan expected cutover epoch.
2. CURRENT_AUTHORITY: shared IAM fence, reload pengguna/izin/area/scope gudang.
3. WORK_ORDER_CONTEXT: lock WO, validasi actor, aktivitas, customer, action, cancel,
   serta referensi revisi. Beberapa WO dikunci menurut ID deterministik.
4. INVENTORY_DOCUMENT_ASSIGNMENT: lock dokumen/authorization/assignment dan validasi
   revisi inventory sebenarnya; beberapa ID dalam urutan deterministik.
5. STOCK_DIMENSION: key dimensi terurut deterministik, termasuk stockIdentityId.

`assertHeld()` harus gagal di luar transaksi asal atau setelah fence dilepas;
handle tidak serializable credential, tidak disimpan di outbox atau HTTP. Tidak
boleh melepas fence saat hanya selesai membaca. Lock berakhir dengan commit lokal,
tidak menunggu BNG/perangkat. Deadlock/serialization/revision conflict dipetakan 409.
Customer boleh membaca identitas sebelum port, tetapi tidak mengambil row lock
atau mengubah ONU yang mendahului urutan ini.

Setiap mutasi menerima header `Idempotency-Key` sebagai
`WarehouseMutationMetadata.idempotencyKey`, bukan body tenant/actor/hash.
Existing resource wajib `expectedRevision`; epoch dipasok server dari context atau
operation tersimpan, tidak dari browser. Server menghitung hash kanonis termasuk
revisi referensi stabil, bukan lookup mutable pada saat replay.
UNIQUE `(tenant, namespace, key)` dan `(tenant, document, businessAction, documentRevision)`
mencegah retry key sama maupun double posting key berbeda. Namespace berasal dari
metode bernama, bukan parameter bebas. Payload berbeda untuk key sama =
IDEMPOTENCY_CONFLICT. Replay mengembalikan original status/body immutable dari
`WarehouseOperationReceipt`, termasuk setelah response HTTP hilang pasca-commit.

Operation mengikat actor asli dan resource scope. **Otorisasi sekarang diperiksa
sebelum mengembalikan replay**. Actor sama dengan sesi baru yang masih berizin
boleh replay; actor lain atau actor revoked tidak menerima payload lama (403 atau
404 tak dapat dibedakan). Tidak ada silent fallback/empty success.
Posted identity, unit/cost/policy snapshots, dan audit append-only; koreksi berupa
dokumen kompensasi tertaut. Inbox receipt dan efek consumer commit bersama.
`WarehouseDocumentEvent` membawa tenant/event/operation/document/revision/time,
bukan instruksi bebas untuk memilih efek atau otoritas baru.

## C4. State Dokumen

Enum di `WarehouseDocumentContracts.kt` adalah nilai wire, bukan ordinal.

| Jenis | Transisi yang diizinkan oleh layanan pemilik berikutnya |
|---|---|
| Master | ACTIVE -> ARCHIVED hanya tanpa saldo/referensi terbuka |
| Receipt | DRAFT -> RECEIVED_IN_INSPECTION -> PUTAWAY -> CLOSED |
| Transfer | DRAFT -> DISPATCHED -> PART_RECEIVED -> RECEIVED atau DISCREPANCY |
| Demand | DRAFT -> SUBMITTED -> PART_RESERVED/RESERVED -> PART_ISSUED/ISSUED -> SETTLING -> CLOSED |
| Issue | DRAFT -> PICKED -> DISPATCHED -> PART_RECEIVED/RECEIVED |
| Return | DRAFT -> DISPATCHED -> RECEIVED_IN_INSPECTION -> ACCEPTED/REPAIR/SUPPLIER_RETURN/SCRAP |
| Count | DRAFT -> COUNTING -> SUBMITTED -> APPROVED -> POSTED atau RECOUNT_REQUIRED |

Demand CANCELLED hanya bila belum diissue. Tahap PART boleh dilompati bila kuantitas
terpenuhi penuh. Status tidak menggantikan quantitative line totals. Receipt boleh
melewati inspection terpisah hanya jika kebijakan SKU eksplisit mengizinkan;
barang reject tetap quarantine/supplier-return. Remainder transfer tetap IN_TRANSIT.
Inspection receipt bukan putaway/serviceable otomatis. Repair mencatat custody
vendor, referensi, hasil, dan inspected return; aset baru hanya jika perangkat
fisiknya memang berbeda.

Reservasi kedaluwarsa `max(submittedAt+24h, (scheduledEndAt ?: scheduledAt)+24h)`;
bila tidak terjadwal, submittedAt+24h. Perpanjangan perlu dispatcher dan audit.
Auto-expiry hanya unissued/unpicked. Picked wajib unpick eksplisit sebelum tersedia.
Handover mencatat sender/receiver dan accepted/rejected/missing. Izin pekerjaan
biasa boleh membolehkan issuer=receiver, tetapi tidak menggantikan independent
exception approval. Count hanya memotret revisi dimensi yang diamati; movement
intervening = COUNT_STALE/recount, diperiksa lagi saat approval dieksekusi. Loss,
scrap, adjustment perlu persetujuan independen terikat dokumen, bukan saldo negatif.

## C5. Material WO, Pemakaian, QA, dan Settlement

Lifecycle WO tidak diganti. Inventory menyimpan BOM/demand snapshot versioned;
workorder menyimpan referensi revisi terkait eksekusi/QA. `MaterialRevisions`
memisahkan workOrderRevision, planRevision, useRevision, settlementRevision.
`materialMode=NONE` wajib alasan dan lines kosong; MATERIAL_REQUIRED tanpa alokasi
tidak sukses. Template hanya default, SKU/substitusi harus eksplisit dan berizin.
Supply parsial/backorder tidak mengaku fulfilled. Sumber boleh gudang berizin atau
stok teknisi bernama dengan alokasi yang dapat ditelusuri.

`reportUse` memposting fisik dan immutable use revision **sebelum QA**, hanya dari
issue line yang sudah acknowledged dan tidak melebihi sisanya. Device deployment
harus lewat InventoryDeploymentApi dalam transaksi assignment+episode, bukan
reportUse umum sebagai jalur kedua. Konservasi per base unit:

`issued = physicallyUsed + returned + transferredOut + disposed + stillAccountable`.

QA menyetujui frozen use/installation snapshot, tidak consume lagi. Reject/resubmit
membuat revisi/kompensasi, bukan menganggap perangkat terpasang sudah kembali.
Efek material tunggal `SETTLEMENT_VERIFY` menunjuk posting yang sudah ada.
MaterialSettlementSnapshot mengikat revisi, posting IDs, residual, dan receipt.
Service boleh finalize setelah bukti used-material disetujui meski retur sisa
masih terbuka. Settlement CLOSED hanya jika seluruh residual ditangani sah.
Installation, QA, provisioning, dan settlement merupakan empat status terpisah.
Efek subscription/provisioning/order/visit diturunkan server hanya dari link dan
action eksplisit; kumpulan efek kosong tidak mengaktifkan fallback legacy.

Reassignment WO tidak memindahkan custody. Custodian lama boleh retur/handover
barang yang masih ditanggung, tetapi tidak report-use baru setelah assignment
dicabut. Reallocation request memilih target WO/demand, bukan movement target
bebas; owner mengunci kedua WO dan memvalidasi targetPlanRevision. Perubahan
custodian tetap membutuhkan physical acknowledgement.

## C6. Assignment, LOAN/SALE, dan Episode Pelanggan

Origin aset baru harus RECEIPT atau OPENING_BALANCE fisik yang disetujui. Serial
lookup/scan/discovery bukan authority assignment. `authorize` membaca WO dan
issue dari server, menyimpan `DeploymentBinding` dengan tenant, actor, asset,
stockIdentity, issue line, WO, customer, purpose, ownership intent, operation ID,
workorder/material/issue/asset revisions, authority/cutover epochs. REPLACE/REMOVE
mengikat previousAssignmentId dan previousAssignmentRevision, bukan mencari
assignment lama yang kebetulan aktif pada saat consume.

`DeploymentAuthorizationRef.authorizationId` adalah locator opaque, **bukan bearer
permission**. Consume wajib reload binding di transaksi lokal, cutover+current
authority fence, lalu panggil `lockAndValidate` sebelum lock inventory/ONU atau
menulis. Port mengecek assignment actor aktif, action/customer, status/cancel dan
revisi WO/material reference, mengembalikan ValidatedWorkOrderContext terikat
authorization/purpose/revisi/epoch. Port tidak menyatakan issue/asset sudah valid:
itu otoritas inventory, bukan workorder. Inventory kemudian membandingkan issue/material/asset
revision miliknya di bawah lock. Hasil context lama tidak boleh dipakai ulang
pada transaksi lain. Missing port binding harus menggagalkan dependency injection.

CustomerAssetApi.install/replace/remove mengambil authorizationId, expected
customer revision, dan topology opsional; tidak menerima serial untuk mint stock,
asset assignment bebas, tenant, atau actor. Path customerId wajib cocok binding.
Inventory.consume dan episode customer commit bersama. Customer generic equipment
boleh `topology=null`/`onuId=null`. Replace menutup episode lama dan membuka episode
baru dengan relasi old/new immutable; perangkat lama masuk recovery custody,
bukan available. REMOVE mencatat removal nyata sebelum return; topology detach
saja tidak restock. Delete customer/ONU dengan active assignment/open custody
ditolak; histori retired/anonymized sesuai retention, tidak cascade menghapus jejak.

LOAN default, title tetap ISP dan mempunyai recovery obligation saat terminasi.
SALE awalnya intent; hanya AcceptedAssetHandover dengan asset/WO/customer/evidence
dan waktu server yang mentransfer title CUSTOMER. QA tidak memindahkan title;
tidak otomatis membuat invoice/pajak/payment/refund. RMA barang terjual tetap
CUSTOMER dan tidak boleh diissue ulang sebagai ISP stock. Reacquisition memerlukan
independent approval dan bukti title transfer sebelum inspeksi dapat merilis stok.

`RETURN_CUSTOMER_RMA` hanya ke customer asli, mengikat original assignment/customer,
repair case+revision, serta return handover. Ini bukan sale/BYOD baru; issueLineId
dan issueRevision boleh null khusus RMA/removal yang dibuktikan purpose-bound,
bukan bypass untuk INSTALL/REPLACE. RMA membutuhkan prior warehouse origin,
ownershipMode sesuai episode asal dan title CUSTOMER tetap. Purpose biasa wajib
acknowledged issue lengkap. Unknown legacy hanya readable/operational; retrieval
ke quarantine tidak membuktikan origin/title. Pindah customer/availability baru
memerlukan rekonsiliasi privileged, bukan flag `legacy` di HTTP.

Semua jalur OnuService, CustomerApiService.provisionOnu, discovery, scheduler dan
import harus akhirnya melewati gate consume yang sama. Customer tidak memanggil
fulfillment; discovery boleh memanggil orchestration API fulfillment yang tidak
mengimpor monitoring. Endpoint lama `/api/inventory/serialized/{id}/installed-onu`
nantinya menolak 409 USE_WORKORDER_ASSET_WORKFLOW, tidak forward ke fulfillment.
Kontrak task 2 tidak mengubah endpoint lama atau mengklaim bypass sudah ditutup.

Episode memakai interval waktu server `[startedAt, retiredAt)`. Reuse customer B
membuat ONU baru, bukan reparent histori A. `attributeObservation` menggunakan
serial+observedAt+device/path context. Query ini server-only, bukan timestamp
otoritatif dari request bebas. receivedAt tidak ada dalam query: customer owner
mengambil receipt clock sendiri. Poll menggunakan server clock; collector harus
authenticated dan lolos skew future 5 menit / age 72 jam. Sampel A terlambat hanya
masuk A, tidak mengubah live status/alarm B. Ambiguous/untrusted/unmatched tetap
unassigned dengan alasan typed. ACS/CPE mengikat episode/assignment revision,
membuang akses snapshot customer lama dan menunggu Inform/readback baru setelah
episode baru mulai. Tidak memindahkan cache/telemetry lama ke pelanggan baru.

## C7. Otorisasi Saat Ini dan Persetujuan Independen

SessionIdentity hanya user/tenant/session identification; sessionId nullable bila
JWT lama tidak punya ID sesi. Klaim JWT tidak menjamin izin masih berlaku.
CurrentAuthorityApi.lockCurrent reload enabled user, roles/permissions/areas di
bawah shared transaction fence. Tidak ada hasil sukses untuk disabled user.
InventoryWarehouseScopeApi.currentUnderFence reload scope gudang dengan fence
yang sama. `AuthorityScope.Restricted(emptySet())` berarti nol akses; hanya
Unrestricted berarti tidak dibatasi. DTO CurrentAuthority hanya hasil server,
bukan input browser. Platform admin tetap tunduk pada independent-approval invariants.

Disable user, role grants, assignment user-role/area, scope gudang/delegation
mengambil exclusive `lockForChange()` dan incrementEpoch dalam transaksi mutasinya.
Epoch harus naik pada commit; rollback membatalkan perubahan dan increment.
Original JWT setelah revocation harus ditolak pada consume/replay. Transaksi yang
sudah commit sebelum revocation tidak dibatalkan retroaktif. Tidak mengambil
cutover/stock lock setelah authority fence jika melanggar urutan global.

Katalog tiga bagian `module.resource.action` menambahkan persis:

- `inventory.sku.{view,manage}`, `inventory.receipt.{view,manage}`.
- `inventory.request.{view,manage}`, `inventory.issue.{view,manage}`.
- `inventory.transfer.{view,manage}`, `inventory.return.{view,manage}`.
- `inventory.count.{view,manage}`, `inventory.provenance.{view,manage}`.
- `inventory.report.view`, `inventory.cost.view`.

Location/item/custody dan seluruh approval family lama tetap. Field action
memerlukan izin field WO **dan** current assignment **dan** custody sendiri;
exception approver hanya memerlukan approval.view untuk antrean, bukan item.view.
Scope mencakup warehouse dan area WO. Semua tabel nantinya immutable tenant_id,
FORCE RLS USING/WITH CHECK, composite tenant FK, indeks dan UNIQUE bisnis;
test role non-owner/NOBYPASSRLS wajib, bukan superuser.

ApprovalSourceRequest hanya documentId+expectedRevision. Policy server versioned
menentukan requester, nilai/currency, tier, expiry, hash dan effect target.
Requester/custodian/counter maupun delegation mereka tidak self-approve.
Missing approver -> INDEPENDENT_APPROVER_REQUIRED; unknown cost yang diperlukan
threshold -> COST_BASIS_REQUIRED; bukan dianggap nol. Receiving/issue in-policy
cukup izin biasa, exception perlu approval. Idle draft boleh expire tanpa stok.
Audit append-only/redacted; evidence menggunakan secured object storage, bukan
public upload. Tidak ada request yang memilih approver/tier/hash/movementId.

## C8. HTTP, Body Eksak, Error, dan Kompatibilitas

Prefix baru `/api/v1/warehouse`; resource: `/skus`, `/locations`, `/suppliers`,
`/receipts`, `/stock`, `/assets`, `/lots`, `/material-requests`, `/issues`,
`/transfers`, `/returns`, `/counts`, `/adjustments`, `/approvals`, `/replenishments`,
`/reports`, `/provenance`, `/settings`. POST create draft, PUT draft dengan revisi,
POST transisi bernama; GET list/detail/history. Page default 25, max 100; tolak
page negatif/size di luar 1..100. Stable sort memakai secondary ID; filter
SKU/serial/location/status/date tenant-scoped. Nama enum case-sensitive, bukan ordinal.

Decoder endpoint baru wajib set Jackson 3 `FAIL_ON_UNKNOWN_PROPERTIES` dan
`EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS`; tidak menerima unknown enum sebagai null
atau default. Tes memakai konfigurasi ini secara eksplisit. Task 2 tidak mengubah
mapper endpoint legacy secara global. Boundary task berikutnya menambahkan
validasi quantity/revision/conditional-field sesuai C2/C5/C6; DTO bukan domain
validator atau bukti authorization.

Di bawah `/api/work-orders/{id}/materials`, fulfillment menjadi host controller
berikutnya. ID WO berasal dari path. Semua body hanya field berikut; field lain
termasuk authority fields dan nested unknown fields ditolak 400:

| Route / metode API | Body request |
|---|---|
| PUT plan / replacePlan | `{expectedRevision, materialMode, reason, lines:[{skuId, quantityBase, baseUnit, continuousCut=true}]}` |
| POST submit-request/reserve/release/dispatch | `{documentId, expectedRevision}` |
| POST pick | `{documentId, expectedRevision, lines:[{demandLineId, stockIdentityId, quantityBase, baseUnit}]}` |
| POST acknowledge | `{documentId, expectedRevision, lines:[{issueLineId, acceptedBase, rejectedBase, missingBase, reason}]}` |
| POST report-use | `{expectedRevision, planRevision, lines:[{issueLineId, stockIdentityId, quantityBase, baseUnit}]}` |
| POST return | `{documentId, expectedRevision, returnLocationId, lines:[{issueLineId, stockIdentityId, quantityBase, baseUnit}], reason}` |
| POST reallocate | `{documentId, expectedRevision, targetWorkOrderId, targetPlanRevision, lines:[{issueLineId, stockIdentityId, quantityBase, baseUnit}], reason}` |
| POST settlement-check | `{expectedRevision, planRevision, useRevision}` |
| Deployment authorize (internal owner API) | `{expectedRevision, assetId, issueLineId, purpose, ownershipMode=LOAN, previousAssignmentId=null, repairCaseId=null}` |
| Deployment consume | `{authorizationId, expectedRevision}` |
| Customer install/replace/remove | `{authorizationId, expectedRevision, topology:{odpId, portNumber, installRxPowerDbm} or null}` |
| Handover acceptance | `{assignmentId, expectedRevision, evidenceId}` |
| Approval source | `{documentId, expectedRevision}` |

expectedRevision targets plan for replacePlan, current use snapshot for reportUse,
settlement for settlement-check, specified document for document commands,
asset for authorize, authorization for consume, assignment for handover, customer
aggregate for CustomerAssetApi. Revisions start at zero before the first snapshot;
zero is not permission to replace missing required material. Referenced plan and
use revisions must agree with the locked snapshot. Quantities remain strings;
server derives sender/receiver/customer/actor/scope from persisted links.

Methods return WarehouseOperationReceipt (immutable original HTTP status/body),
MaterialSettlementSnapshot, DeploymentAuthorizationRef, AssetAssignmentRef, or
CustomerAssetEpisode as declared in root APIs; read methods return typed snapshots
or WarehousePage. Ordinary authorization/mutation failure throws
WarehouseContractException containing WarehouseError `{code,message}`. HTTP
adapters must map it without swallowing failure or leaking private IDs. Message
is actionable/localizable, code stable. Anonymous 401 UNAUTHENTICATED; malformed
400 MALFORMED_REQUEST; forbidden 403 FORBIDDEN; missing/inaccessible 404 NOT_FOUND.
Business conflicts are 409: INSUFFICIENT_STOCK, STALE_REVISION, SOURCE_NOT_VERIFIED,
WRONG_CUSTODIAN, ACTIVE_ASSIGNMENT_EXISTS, APPROVAL_REQUIRED, COUNT_STALE,
IDEMPOTENCY_CONFLICT, STALE_AUTHORITY, CUTOVER_REQUIRED, STALE_CUTOVER,
USE_WORKORDER_ASSET_WORKFLOW, INDEPENDENT_APPROVER_REQUIRED, COST_BASIS_REQUIRED,
CURRENCY_MISMATCH. No mutation can return empty success after one of these failures.

`/api/inventory` read compatibility remains a projection. Legacy stock quantities
are serialized integer COUNTS only, never MM or truncated bulk. Legacy
MaterialConsumptionApi.forCustomer is deprecated **for new consumers** by this
contract; its preserved CustomerMaterialFactRef.quantity stays Int with historical
meaning. No default conversion guesses missing units. MaterialConsumptionApiV2
returns CustomerMaterialFactV2 with WarehouseQuantity, stockIdentityId, useRevision,
postingId and optional compensation link. Subscriber360 migrates later. Internal
CustomerAssetEpisode and PortalCustomerAsset are separate; portal contains no
warehouse bins, purchase cost, custodian, internal approval notes, raw evidence/GPS.
Keyboard/camera scan supports exact lookup and manual entry; scanning never commits.

## C9. Informasi UI dan State Operasional

`Gudang & Logistik` menjadi kelompok terpisah, bukan bagian Jaringan:
`/warehouse`, `/warehouse/catalog` (SKU/location/supplier), `/warehouse/stock`
(positions/assets/lots), `/warehouse/receipts`, `/warehouse/requests`,
`/warehouse/transfers`, `/warehouse/returns` (inspection/repair/RMA),
`/warehouse/counts`, `/warehouse/approvals`, `/warehouse/reports`,
`/warehouse/settings`. Material WO pada detail WO, `/my-materials` di Lapangan,
Perangkat & Material beserta provenance/ownership/history di pelanggan.

Halaman mendatang wajib loading/empty/denied/pending/validation/conflict-retry/
success/partial-state dan tindakan sesuai scope/role. Empty setup memandu location
-> SKU -> receive. Nama SKU/model/serial/location/customer/WO, bukan UUID-only.
Gunakan Fluent atoms/molecules dan web/DESIGN.md; bukan redesign unrelated UI.
Tidak ada halaman/controller/UI task 2 yang diklaim sudah tersedia.

## C10. Admission dan Cutover Tanpa Kehilangan Data

Manifest [warehouse-migrations.md](warehouse-migrations.md) menentukan versi
M01-M06; tidak mengganti nomor Flyway yang sudah diterapkan. Boot-time schema
expansion terpisah dari per-tenant DATA ADMISSION. Semua migrasi harus boot meski
legacy serial/MAC collision atau unit unknown. Simpan ID/raw value/link asli,
`warehouse_admission=LEGACY_UNRESOLVED`, nullable canonical/unit candidates.
App-created stock/assignment wajib VERIFIED; hanya migration-owner boleh staging.
VERIFIED partial constraints tidak boleh menghalangi preserved legacy boot.

Identity claim selalu UNIQUE `(tenant, identityType, canonicalValue)` dengan
LEGACY_RESERVED|CONFLICT|ADMITTED|RETIRED, kandidat memuat semua source rows.
LEGACY_RESERVED/CONFLICT/RETIRED tetap memblokir receipt/assignment identitas itu;
verified partial index saja tidak cukup. Rekonsiliasi berbukti di bawah fence
secara atomik mempromosikan identity, opening posting, source links, admission.
Unknown installed ONU hanya provenance record, tidak phantom receipt/stock.
Opening balance memerlukan evidence, cutoff, source snapshot dan independent
approval, bukan purchase palsu. Unit unknown tidak masuk availability.

InventoryTenantCutoverApi.read tidak menganggap missing policy ENFORCED.
`lockForCommand(expectedEpoch, operationClass)` memakai shared fence; class operasi
ditentukan entry point server, bukan HTTP. `lockForTransition` exclusive menunggu
in-flight writes. Handle transition sendiri tidak mengubah state atau memberi
izin posting; implementasi task 4 memeriksa syarat berikut:

| State | Allowlist |
|---|---|
| LEGACY | CONTROL_PLANE dan MIGRATION_REPORT dengan izin masing-masing |
| VALIDATING | Control-plane/report; PROVENANCE_RESOLUTION, MIGRATION_APPROVAL, MIGRATION_BASELINE terikat batch, CUTOVER_FINALIZATION dengan provenance.manage dan independent approval terkait |
| ENFORCED | Ordinary stock/assignment dengan izin/scope/invariant normal; legacy effects lama tidak boleh direplay menjadi posting baru |

LEGACY -> VALIDATING mengambil exclusive fence, drain/pause atau mencatat pending
legacy effect IDs, dan menetapkan snapshot watermark. Finalisasi memeriksa baseline,
tidak ada active collision/unit uncertainty dalam admitted baseline, tidak ada
in-flight legacy effects dan UNIQUE baseline business identity, kemudian ENFORCED
dengan epoch increment. Paused command melintasi cutover gagal STALE_CUTOVER,
bukan menulis dua baseline. Semua writer lama/baru dan fulfillment consumer ikut
fence sebelum lock lain. Task 2 hanya mendeklarasikan protokolnya.

Tenant baru setelah activation epoch diinisialisasi ENFORCED dengan empty stock
atomik melalui tenant-created event/owner API, bukan dependensi tenancy -> inventory.
Existing empty tenant harus validated-empty cutover eksplisit. Tenant A boleh
ENFORCED saat B masih VALIDATING; unresolved historical/provenance-only records
boleh tetap staged/excluded, reads/telemetry tetap beroperasi. Tidak ada destructive
dedup atau fake origin. Rollback feature flag mematikan mutasi baru, tidak membuka
lagi serial-only writer atau mengembalikan riwayat Flyway.

## C11. Biaya dan Batas Komersial

Supplier price/currency opsional; snapshot cost melekat receipt line/lot/serial.
FIFO deterministik receipt time lalu ID, override perlu alasan berizin. Unknown
legacy price bukan nol. ReceiptCostSnapshot menyimpan totalMinor (numerator) dan
costBasisQuantityBase positif (denominator), keduanya integer string. Per-use
cost dihitung proporsional exact rational/BigDecimal; HALF_UP hanya pada baris
laporan tampilan. Retur mempertahankan numerator/denominator asli, tidak mengira
harga per metre sebagai per mm. Total dikelompokkan currency, unknownLineCount
terpisah; tidak ada FX implisit atau mixed-currency grand total. Policy approval
punya currency eksplisit; mismatch/unknown memblokir value-based decision.

Min/max replenishment mengurangi commitment, menghitung outstanding supply sekali,
dan hanya membuat saran/internal request. Supplier/document reference didukung;
purchase ordering penuh, AP, payment, GL, manufacturing, invoice/refund otomatis
di luar scope. Laporan operational WO cost bukan accounting valuation ledger.

## Bukti Kontrak dan Gate Implementasi

Jalankan melalui lingkungan task-owned:

```sh
scripts/warehouse/test-environment.sh up
scripts/warehouse/qa.sh server --tests '*WarehouseContractTest*' --rerun-tasks --no-parallel
scripts/warehouse/qa.sh server --tests '*ModularityTests*' --no-parallel
scripts/warehouse/qa.sh stop
scripts/warehouse/test-environment.sh down
```

WarehouseContractTest memakukan public signatures, enum/error fixture, identity
vs authority, revisi binding, field authority terlarang, precision wire shape,
permission families, dan kegagalan startup consumer tanpa required binding.
MaterialWarehouseContractTest memakukan seluruh named material actions, nested
handover request, privacy portal, dan legacy count vs V2 length. ModularityTests
tetap tidak berubah dan memverifikasi root-only dependency tanpa cycle.
BootJar/javap membuktikan artefak executable membawa kontrak yang sama.

Belum diuji oleh task 2: arithmetic/normalization task 3; schema/RLS/cutover task 4;
posting/replay/concurrency/current-authority implementation task 5-6; approval,
field workflow, assignment+episode dan HTTP decoder/controller task berikutnya.
Binding harus wajib/non-null ketika consumer ditambahkan, bukan optional provider,
`@ConditionalOnMissingBean` no-op, atau default empty allocation. Legacy defaults
baru dihapus ketika adapter konkretnya tersedia; kontrak baru tidak memakainya.

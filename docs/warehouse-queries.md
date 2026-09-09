# Saldo, aset, lot, dan jejak gudang

Task09 menyediakan query operasional dari ledger/proyeksi durable task05 dan
origin penerimaan task08. Tidak ada mutation, rebuild-on-read, cache otoritatif,
reservasi baru, export, atau UI dalam jalur query.

## Endpoint

Semua route baru memakai prefix `/api/v1/warehouse` dan current permission
`inventory.item.view`. JWT mengidentifikasi actor; IAM memuat ulang izin/area di
bawah fence transaksi pada setiap request. Scope gudang, area dan site efektif
self/ancestor tetap berlaku. Tidak ada scope berarti nol data, bukan seluruh tenant.

| GET | Hasil |
| --- | --- |
| `/stock` | Page ringkasan per SKU/unit, physical/reservedUnpicked/reservedPicked/available dan status/condition/owner buckets |
| `/stock/positions` | Page dimensi SKU/identity/lot/location/custodian/condition/owner/status |
| `/stock/positions/{id}` atau `/stock/{id}` | Posisi berdasarkan ID proyeksi durable |
| `/stock/positions/{id}/history` atau `/stock/{id}/history` | Page jejak immutable identitas posisi |
| `/stock/unknown` | Page saldo/aset staged dan title UNKNOWN; perlu `inventory.provenance.view` juga |
| `/assets` dan `/assets/{id}` | Page/detail satu physical asset ID yang sama dengan registry lama |
| `/assets/{id}/history` | Movement legs, keputusan inspection, reservation snapshots dan material facts yang benar-benar tersimpan |
| `/assets/lookup?value=...` | Kontrak scan task07 tetap, termasuk normalisasi dan penolakan ambiguity tenant-wide |
| `/lots` dan `/lots/{id}` | Page/detail lot, origin, received quantity, cost berizin dan pemeriksaan konservasi |
| `/lots/{id}/segments` | Page root/CUT/REMNANT/BULK, parent, children dan state ACTIVE/SPLIT/RETIRED |
| `/lots/{id}/segments/{segmentId}` | Detail piece terikat lot, bukan lookup lintas lot |
| `/lots/{id}/history` | Page ledger/fakta seluruh piece dalam lot yang dapat diakses |

Page default0/size25, maksimum100. Filter exact `skuId`, canonical `serial`,
`locationId`, `status`, `condition`, `owner`, serta `from` inklusif/`until`
eksklusif. Tanggal harus berpasangan, ISO instant, urutan benar, maksimum366 hari.
Tanpa tanggal, pagination tetap membatasi response dan seluruh histori dapat
ditelusuri per page. Bukan endpoint export tak berbatas.

Sort `name|createdAt|id`, direction `asc|desc`, secondary ID ascending. History
default `createdAt` dan hanya menerima `createdAt|id`. Pada page segmen, name
adalah kind dan status berarti ACTIVE/SPLIT/RETIRED. Filter waktu memakai waktu
penerimaan/pembuatan pada asset/lot/segment, updated_at pada posisi, dan waktu
event pada history. Unknown/repeated parameter, UUID/enum/integer/tanggal invalid,
page negatif, dan size di luar batas menghasilkan400 MALFORMED_REQUEST.

## Kuantitas dan availability

Semua jumlah baru memakai object `{quantityBase,baseUnit,displayQuantity,displayUnit}`.
Base quantity adalah string integer; EA ditampilkan sebagai EA, MM sebagai M
dengan tepat3 pecahan (`82501 MM -> 82.501 M`). Agregat SQL memakai numeric exact,
tidak float atau Int. Status/condition/owner buckets adalah string integer dalam
baseUnit SKU yang sama. Tidak menjumlahkan EA dan MM menjadi satu angka.

Physical adalah jumlah posisi ledger, termasuk posisi sink CONSUMED untuk
konservasi; status bucket memisahkan pemakaian dari gudang. Available hanya posisi
VERIFIED dari piece ACTIVE, SERVICEABLE/ISP, status AVAILABLE, master ACTIVE dan
issue-eligible, dengan kind custody yang cocok. Kurangi jumlah reservation OPEN
unpicked+picked tepat sekali. Expiry timestamp tidak otomatis menghapus fakta
reservasi; task10 yang akan menjalankan release/expiry. Pick tidak mendebit fisik
atau membuat encumbrance kedua.

Transit, issued/consumed, quarantine, customer-owned, unknown title/admission/unit
tidak tersedia. Staged tidak masuk ringkasan normal; `/stock/unknown` menyimpan raw
quantity/serial yang berizin dan `available=false`. Unknown unit tetap null,
bukan ditebak EA. Harga optional receipt tetap UNKNOWN, bukan nol atau harga SKU
terkini; query tidak menciptakan aturan harga baru sebagai otoritas stock.

## Privasi dan histori

Cost hanya dipilih dan diserialkan bila current `inventory.cost.view` tersedia.
Tanpa izin, field cost/numerator/denominator/currency tidak ada, bukan placeholder
null. Supplier private reference, raw evidence, storage key dan payload operation
tidak dipilih untuk DTO ini. Origin menunjuk document/line/code/kind dan immutable
`customerLabelSnapshot`/`workOrderCodeSnapshot`, tanpa import/call customer atau WO.

Internal position/detail boleh membawa location/custodian. PortalCustomerAsset
tetap DTO terpisah dengan field lama; token portal tidak diterima pada route operator.
Hidden/foreign ID mengembalikan404 NOT_FOUND yang sama dengan missing. Timeline
hanya menyertakan lokasi yang terlihat. Origin tersembunyi tidak membuka detail
supplier/cost melalui backlink. Lot-level totals/tree tidak disajikan jika ada
piece atau movement di lokasi tersembunyi: jumlah asal tidak boleh membocorkan
stok pihak lain melalui total conservation.

Timeline tidak merekayasa event dari status saat ini. Receipt OUT/IN, inspection,
reservation/pick snapshots dalam outbox, dan installed/returned material facts
memakai ID, waktu, revisi dan reference durable. Timestamp diserialkan UTC dengan Z.
Root yang SPLIT tidak dihitung lagi sebagai piece aktif. Detail lot memeriksa
received vs roots, terminal/active leaves, parent vs children dan kapasitas piece
vs physical projection. `conservation.consistent=false` mengungkap histori/proyeksi
tidak konsisten; query tidak memperbaiki atau menyembunyikannya. Children ID pada
detail dibatasi100; page `/segments` menyediakan seluruh node dengan parent link.

## Snapshot dan kompatibilitas

CurrentAuthority menggunakan transaksi READ COMMITTED yang sama dengan task06.
Sesudah fence authority, setiap response dibentuk satu SELECT PostgreSQL agar
items/count/totals/tree/facts berasal dari committed snapshot yang sama. Query
memiliki statement timeout20 detik; tidak mengunci stock atau menunggu proses
eksternal. Tidak ada changeset/migration baru untuk task09; V173/V174.x tetap identik.

`GET /api/inventory/stock` tetap array `{skuId,locationId,quantities:{STATUS:Int}}`
yang hanya menghitung serialized assets. MM maupun bulk EA tidak pernah masuk
field count, termasuk bulk3 miliar EA. Scope/current authority sekarang berlaku
juga di HTTP stock lama. Header Link menunjuk successor version, body tidak berubah.
Item, lookup, InventoryAssetRef dan portal field sets dikunci compatibility tests;
consumer legacy internal lain tidak dialihkan ke jumlah panjang.

## Bukti task09

- Baseline compatibility sebelum production edit:2 lulus. Failing-first stock
  HTTP2 gagal, asset/lot HTTP2 gagal karena route belum tersedia.
- Exact `scripts/warehouse/qa.sh server --tests '*WarehouseQueryIT*' --rerun-tasks --no-parallel`
  final dua kali: **12 test,0 gagal,0 skipped** per run (5m44s dan5m33s).
- Cakupan gabungan task01-09: **741 test,0 gagal,0 skipped**, tiga kelompok
  non-overlap melalui runner yang sama: authority/master/receipt/network/Modularity244,
  schema/posting230, contract/quantity/parser/legacy/environment/query267.
- Monolithic command awal mencapai limit runner1800s tanpa hasil akhir. Itu
  **bukan PASS**; seluruh cakupannya dijalankan ulang dalam kelompok di atas,
  tanpa menaikkan timeout, menghapus test, cache, atau skip.
- Skenario posting:1000m menjadi917.5m warehouse+82.5m consumed. Reservation300m
  berupa200m unpicked+100m picked menghasilkan617.5m available, bukan517.5m.
  Lima tree node konservatif; transit/quarantine/customer/unknown tidak available.
- Query selama receive dipause setelah update saldo melihat before-state3 kali,
  kemudian complete after-state. Observer memakai warehouse_app; migration-owner
  sengaja tidak dapat melihat wait_event role aplikasi. Kegagalan fixture awal
  disimpan, bukan ditutupi retry.
- SIGKILL/fresh JVM menjaga JSON totals/timeline/tree identik dan page nama
  duplikat25+5 tanpa kehilangan/duplikasi. Corrupt balance999 vs receipt1000
  dilaporkan inconsistent dan tetap999 sesudah query, tanpa rebuild.
- Clean `:server:clean :server:bootJar --no-build-cache --rerun-tasks --no-parallel`
  sukses. SHA256 `6f86a785292f28368ada2d5c22d02dc8053718b944dbec2c0519d721508a45d1`.
- Packaged JAR melalui socket HTTP, private MinIO dan warehouse_app non-owner:
  actual receipt/inspect/putaway menghasilkan available900000MM+8EA dan
  quarantine100000MM+2EA, sesuai SQL. Cost lot500000/1000000 IDR,5 immutable
  asset events,3 reel nodes; no-cost field absent, no-scope404, anonymous/portal401.
  SIGKILL/restart mengembalikan JSON yang sama.
- SMTP health tanpa kredensial menghalangi readiness probe pertama; hanya health
  layanan mail eksternal dimatikan pada probe terisolasi. Validator/RLS/warehouse
  service produksi tidak dilonggarkan. Probe source/log diarsipkan dan runtime dihapus.
- Zero-match sengaja menghasilkan exit1 melalui nonzero-test guard. Temporary
  schema, query trigger/function dan child DB connections berakhir0/0/0/0.
  Container/network milik task dihentikan; volume tetap dipertahankan.

XML/log/JSON dan notepad tersimpan di `.omo/evidence/warehouse-workorder-asset-provenance/task-9/`
dan `.omo/notepads/task-9.md`, tidak ikut git. CodeGraph/LSP tidak tersedia pada
lane ini; compiler, Spring HTTP, PostgreSQL dan packaged runtime adalah bukti.

## Koreksi AV9: filter memiliki objek dan waktu sendiri

AV9-01: tanggal list/detail lot hanya memfilter `inventory_lot.received_at`.
Seleksi piece memakai `dimension_filtered_positions` untuk SKU/serial/location/
status/condition/owner, tanpa `updated_at`. Query posisi tetap menambahkan filter
`position.updated_at` sendiri. Predicate bernama memisahkan lot received, asset
created, segment created, position updated dan history event time; caller tidak
mewariskan tanggal dari tabel lain melalui page posisi. Lot tanpa posisi positif
atau dengan piece/histori di luar scope tetap tidak ditampilkan.

AV9-02: filter serial pada kedua alias position-history mengikat stockIdentityId
ke aset serial kanonis yang unik sebelum memilih timeline. Codec task03 tetap
trim/uppercase. Matches, claims dan candidates diperiksa tenant-wide sebelum
visibility; duplikat tersembunyi atau claim konflik tidak memilih aset yang kebetulan
terlihat. Asset-history menggunakan guard yang sama. Serial salah/asing/ambigu/
tersembunyi dan serial pada posisi non-serialized menghasilkan generic404 NOT_FOUND,
bukan200 berisi history lain. Tanpa parameter serial, perilaku query ID tetap.
Tanggal history hanya memfilter waktu event, bukan timestamp posisi sekarang.

Bukti koreksi dari baseline4a370f39:

- Failing-first HTTP: lot dalam received interval menghasilkan200/total0 setelah
  inspect/split/putaway. Serial salah menghasilkan200 dengan event aset target;
  hidden duplicate juga menghasilkan200. Regression kini menghasilkan1/404/404.
- Matrix lot mencakup3 posisi dengan update sebelum/di dalam/sesudah interval,
  dimensi positif/negatif, hidden piece dan tanpa posisi; filter tanggal posisi
  tetap membedakan ketiga waktu tersebut.
- Exact WarehouseQueryIT dua kali dengan rerun/no-parallel: **15/0/0** per run
  (7m05s dan7m10s). Gabungan tanpa overlap **746/0/0**:244 authority/master/receipt/
  network/Modularity,230 schema/posting,272 contract/quantity/legacy/query.
  Modularity terpisah **3/0/0**. Tidak ada migrasi atau availability math yang diubah.
- Packaged baseline: received09:30:35.491300Z, until09:30:35.864Z, updates09:30:36.629101Z
  dan09:30:36.918979Z menghasilkan total0; serial mismatch200/five events.
  Packaged sesudah koreksi: received10:31:28.867055Z, until10:31:29.464Z,
  updates10:31:30.305485Z dan10:31:30.592543Z menghasilkan total1; serial mismatch404.
  Semua waktu pada2026-09-09. SQL dan HTTP direkam sebagai warehouse_app non-owner.
- Matching serial dengan posisi diupdate satu hari setelah interval tetap
  mengembalikan2 receipt events. SIGKILL/fresh JVM mempertahankan exact JSON untuk
  date-filtered lot, matching/mismatching serial history, totals, tree dan pagination.
- Clean no-cache bootJar sukses; SHA256
  `e67b98ea32a6c26bb4634a5185a32f7a91efe4bcb82832f496e2717707b24850`.

Evidence koreksi: `.omo/evidence/warehouse-workorder-asset-provenance/task-9/corrections/`.
Probe sumber diarsipkan, runtime/proof object dihapus. Namespace sementara,
app connections, pause triggers dan functions berakhir0/0/0/0; volume dipertahankan.

# Master gudang melalui HTTP

Task07 menyediakan setup master, bukan receiving atau workflow stok task08+.
API lama `/api/inventory` tidak mendapat writer baru dan mempertahankan bentuk
array/count baca yang ada.

## Endpoint dan izin

| Resource | Baca | Create/update/archive |
| --- | --- | --- |
| `/api/v1/warehouse/skus` | `inventory.sku.view` | `inventory.sku.manage` |
| `/api/v1/warehouse/locations` | `inventory.location.view` | `inventory.location.manage` |
| `/api/v1/warehouse/suppliers` | `inventory.receipt.view` | `inventory.receipt.manage` |
| `/api/v1/warehouse/assets/lookup?value=...` | `inventory.item.view` | Tidak ada mutasi |

Untuk ketiga master: POST collection membuat ACTIVE/revision0 (201), GET collection
membaca page, GET `/{id}` membaca detail, PUT `/{id}` mengganti field master dengan
`expectedRevision`, dan POST `/{id}/archive` menerima hanya `{"expectedRevision":0}`.
Tidak ada DELETE atau reopen. PUT dan archive berhasil mengembalikan200 serta
snapshot dengan revision naik satu.

Semua mutasi wajib header `Idempotency-Key`, 1-240 karakter ASCII tanpa whitespace.
Revisi/enum salah, field tidak dikenal, duplicate JSON property, scalar coercion,
dan body lebih dari16384 karakter ditolak400. Tidak menerima tenant, actor, state,
hash, canonical identity, permission atau authority epoch dari client.

Kode master adalah `[A-Z0-9][A-Z0-9._-]{0,63}`; nama tidak kosong, maksimal200
karakter tanpa whitespace luar. Nama Unicode diperbolehkan, kode Unicode ditolak.
Field SKU: code/name/category/model, tracking SERIAL/LOT/BULK, baseUnit EA/MM,
allowedOwnershipModes LOAN/SALE, inspectionRequired, minimumQuantityBase (string
integer nonnegatif). SERIAL hanya EA. Minimum adalah nilai default per-SKU;
aturan replenishment per-lokasi dan workflow pengadaan bukan bagian task07.
Supplier: code/name/contactReference; tidak membuat PO, invoice, AP atau GL.

Lokasi: code/name/kind/parentLocationId/siteId/areaId/custodianId/issueEligible.
Kind mengikuti registry: WAREHOUSE/BIN/VEHICLE/TECHNICIAN/CUSTOMER_SITE/QUARANTINE/
LOST/DISPOSED/TRANSIT. BIN wajib parent; parent hanya WAREHOUSE/BIN dalam area yang
sama. TECHNICIAN wajib custodian aktif. Issue eligible hanya warehouse/bin/vehicle/
technician. Self/cycle dan hierarki lebih dari31 ancestor ditolak. Site dan area
harus cocok; reference site/area/user asing tidak dapat digunakan.

## Setup tenant kosong

1. Daftar `/api/signup`, lalu login `/api/auth/login`.
2. Buat area `/api/areas`; beri administrator area itu melalui
   `PUT /api/users/{id}/access`, mempertahankan roleIds-nya.
3. Buat root WAREHOUSE dengan areaId tersebut, lalu BIN dengan parentLocationId.
4. Buat SKU dan supplier. Receiving disediakan task08, bukan scan atau setup master.

Contoh body root:

```json
{"code":"MAIN","name":"Gudang utama","kind":"WAREHOUSE","areaId":"<area UUID>","issueEligible":true}
```

CurrentAuthority task06 memperlakukan area kosong sebagai nol akses, bukan akses
seluruh tenant. Location manager boleh membuat root dalam area yang diizinkan;
creator mendapat scope lokasi baru secara atomik di bawah exclusive IAM fence dan
epoch increment. Grant parent yang ada mewariskan akses anak sesuai scope task06.
Perubahan hierarchy/custodian/area/kind/eligibility diblokir bila referensi masih
terbuka. SKU/supplier adalah direktori tenant, bukan proyeksi stok atau custody.

## Query dan replay

Page default0/size25, maksimum100; page negatif, sort/direction di luar allowlist
ditolak400. `search`, `code`, dan `name` berupa substring case-insensitive literal,
bukan SQL wildcard. `state=ACTIVE|ARCHIVED`, `sort=code|name`, `direction=asc|desc`.
Urutan secondary selalu ID ascending. Response: items/page/size/totalElements.
Lokasi dan lookup dibatasi tenant, scope gudang dan area saat ini.

Scan menerima serial atau MAC/manual scanner string. Codec task03 melakukan trim
dan normalisasi; serial tidak diubah menjadi MAC. Serial/MAC asing atau di luar
scope sama-sama404 NOT_FOUND. Hanya provenance.view dapat melihat candidate legacy
dengan `legacyUnresolved=true`; hasil ambigu tidak memilih salah satu aset. Tidak
ada identity claim, receipt, movement, atau assignment yang dibuat oleh lookup.

WarehouseCommandService menjalankan master melalui CONTROL_PLANE, diperbolehkan
di LEGACY/VALIDATING/ENFORCED. ORDINARY_STOCK tetap ENFORCED-only. Mutation dan
receipt disimpan dalam transaksi yang sama di inventory_operation dan
inventory_command_identity, tanpa dokumen stok palsu atau outbox stok.
Namespace master terikat kind/action; ledger menggunakan master resource binding
dan document_id null khusus namespace tersebut. Receipt internal memakai resource
master sebagai locator; HTTP mengirim originalStatus/originalBody yang tersimpan.

Izin, actor asli, warehouse/area scope dan cutover epoch dicek sebelum replay.
Payload berubah mengembalikan409 IDEMPOTENCY_CONFLICT; revocation tidak membuka body
lama. Same-key concurrent create mengembalikan satu original response.

Archive diblokir oleh saldo positif/unresolved, reservasi terbuka, dokumen terbuka,
plan, unit/asset/lot origin references dan anak lokasi aktif. Guard DB menolak
reference baru ke master arsip dan perubahan unit/tracking setelah posting.
Policy konservatif mempertahankan master yang masih menunjuk origin historis;
tidak menghapus bukti agar master bisa diarsipkan.

Error stabil:400 MALFORMED_REQUEST,401 UNAUTHENTICATED,403 FORBIDDEN,
404 NOT_FOUND,409 STALE_REVISION/IDEMPOTENCY_CONFLICT/SOURCE_NOT_VERIFIED/
STALE_CUTOVER. Tidak ada fallback sukses kosong.

## Bukti task07

- Failing-first WarehouseMasterIT:2 test,1 expected failure,0 skipped.
- Exact `scripts/warehouse/qa.sh server --tests '*WarehouseMasterIT*' --rerun-tasks --no-parallel`
  dua kali:12 test,0 failure,0 skipped setiap run.
- Gabungan `--tests '*Warehouse*' --tests '*Inventory*Test*' --tests '*ModularityTests*'`
  dengan rerun/no-parallel:445 test,0 failure,0 skipped, termasuk seluruh schema
  upgrade/restart, RLS, posting, authority dan concurrency task01-06.
- Clean `:server:bootJar --no-build-cache --rerun-tasks --no-parallel`: sukses.
- Probe MockMvc terpisah memakai signup/login/area/access/master HTTP nyata di
  warehouse_test sebagai warehouse_app. DB akhir:1 SKU,2 lokasi,1 supplier,
  6 operation,0 movement,0 document; tenant ENFORCED. Replay identik, payload409,
  query200, scan unknown404 dan archive200. Probe dihapus setelah hasil disimpan.
- M02 V174.8/V174.9 ditambahkan manifest-first. Semua versi sebelumnya tetap.
- LSP/CodeGraph tidak tersedia pada lane ini; compiler dan Spring/PostgreSQL nyata
  menjadi gate. Warning compiler historis tidak diubah.

Arsip XML/manual berada di `.omo/evidence/warehouse-workorder-asset-provenance/task-7/`
dan sengaja tidak masuk git. Hasil di atas adalah baseline implementasi awal;
koreksi dan gate terbaru berikut menggantikan klaim strict decoding/reference awal.

## Koreksi verifikasi AV7

AV7-01: Jackson3 memerlukan aturan coercion eksplisit untuk Textual dan Integer,
bukan hanya `ALLOW_COERCION_OF_SCALARS=false`. Mapper khusus warehouse menolak
number/boolean ke string, float/exponent/string ke revision, duplicate property,
unknown field dan enum angka. `minimumQuantityBase` tetap JSON string;
`expectedRevision` wajib token integer JSON, sehingga `0.9`, `0.0`, `0e0` dan
`"0"` ditolak400. Mapper legacy tidak diubah. Matriks HTTP205 masukan invalid
mencakup create/update/archive ketiga master dan membuktikan nol perubahan data.

AV7-02: M02 V174.10 menambahkan FK `(tenant_id,site_id)` ke site dengan key
tenant/id, serta guard area site dan parent/site. Site area change dan delete
mengambil lock owner; network menanyakan `SiteUsageProbe` miliknya yang
diimplementasikan inventory, tanpa dependensi network ke internal inventory.
Referensi gudang termasuk arsip harus dipindahkan sebelum site dihapus/diubah
areanya. SQL FK/trigger tetap menjadi penjaga akhir jika layanan owner dilewati.

Inventory memakai `SiteReferenceApi` untuk lock dan snapshot site yang segar.
Detail/list/search/lookup memeriksa site dan area ancestor, selain scope gudang.
Replay memeriksa **snapshot lokasi sekarang dan snapshot original response**:
memindahkan lokasi dari siteA ke siteB tidak memberi izin membocorkan receipt lama
setelah siteA dihapus atau pindah ke area yang tidak lagi dapat diakses.

FK dipasang NOT VALID untuk mempertahankan reference historis yang terlanjur
dangling sebelum koreksi. Write baru ditegakkan; reference lama tidak konsisten
tetap tersimpan tetapi tidak ditampilkan/replay. Tidak ada pembersihan atau
rekonsiliasi historis otomatis. NULL siteId yang sah tidak berubah.

AV7-03: satu query lookup mematerialisasi matches, claims dan candidates pada
lingkup tenant **sebelum** filter visibility. CONFLICT, beberapa kandidat sumber,
raw/canonical yang tidak konsisten dan hasil ambigu menghasilkan404 NOT_FOUND
yang sama dengan missing/foreign/hidden. Unique candidate baru diperiksa terhadap
scope warehouse/area/site dan izin provenance; tidak ada collision count di body.
Uji SERIAL/MAC mencakup satu/dua/nol lokasi terlihat, multiple candidate tanpa flag
CONFLICT, single candidate ber-flag CONFLICT, malformed dan unique visible/hidden.

Gate koreksi awal, sebelum perbaikan inheritance transitif berikut:

- Exact WarehouseMasterIT dua kali: **53 test,0 failure,0 skipped** setiap run.
- Gabungan warehouse/inventory + NetworkEndToEndIT + ModularityTests:
  **501 test,0 failure,0 skipped**, termasuk seluruh task01-07 dan schema gates.
- Gate terfokus schema/site/network/Modularity sebelum tambahan original-replay:
 154 test lulus;8 race memakai transaksi nyata, latch dan observasi lock PostgreSQL,
 tanpa sleep untuk menentukan pemenang. SQLSTATE FK23503 dan invariant23514 diuji.
- Clean no-cache bootJar final sukses; V174.10 dan kelas owner guards ada di JAR.
- Live server: POST supplier melalui socket tanpa membaca response; commit
 dikonfirmasi sebagai warehouse_app, server di-SIGKILL, lalu proses baru replay
 exact body201. DB tetap1 operation/1 supplier. Disable actor melalui HTTP membuat
 original token replay403 tanpa body lama. Probe memakai warehouse_test non-owner,
 NOSUPERUSER/NOBYPASSRLS; tidak memanggil layanan produksi.

Evidence koreksi: `.omo/evidence/warehouse-workorder-asset-provenance/task-7/corrections/`.
Seluruh migrasi sebelum V174.10 tetap byte-identical. V175+ tetap milik task lain.

## Inheritance transitif V174.11

Site efektif adalah himpunan site non-null pada **self dan seluruh ancestor**:
nol site berarti tidak terikat site (scope area/gudang tetap berlaku), satu site
berarti inherited, lebih dari satu berarti tidak konsisten. NULL bukan pemutus
inheritance. Rantai NULL-NULL-NULL, A-NULL-NULL, NULL-A-NULL dan A-NULL-A sah;
A-NULL-B dan seluruh turunannya tidak sah, termasuk leaf dengan siteId NULL.

M02 V174.11 menambah `inventory_location_topology_fence` dengan FORCE RLS dan
revisi per tenant. Statement insert/update mengambil fence sebelum row locks;
command HTTP mengambilnya sesudah cutover/IAM dan sebelum membaca scope/row/site.
Revisi fence menutup write skew dari snapshot REPEATABLE READ/SERIALIZABLE yang
stale dengan40001. Site owner tetap memakai row lock/FK/usage guard V174.10.

Constraint deferred memeriksa keadaan akhir node yang berubah dan setiap
descendant: site efektif unik, area sama, site/area cocok, tanpa cycle, maksimum31
ancestor (32 node termasuk self). Validator sebenarnya memeriksa current tenant
scope saat dipanggil dan saat commit; hasil validasi awal tidak menjadi credential
untuk mengganti GUC setelahnya. HTTP menjalankan validator yang sama setelah save
untuk memetakan konflik ke409, tanpa menghabiskan deferred check saat commit.
Perubahan banyak row yang konsisten pada akhir transaksi tetap sah.

Detail/list/search/replay/lookup menghitung site efektif independen dari siteId
node yang diminta. Histori A-NULL-B-NULL yang sudah committed sebelum174.11 tetap
tersimpan, tetapi conflict node dan null leaf sama-sama404/excluded. Tidak ada
backfill destruktif, pemilihan salah satu site, atau pelonggaran AV7-01/03.

Bukti final inheritance:

- Failing-first10 kasus:4 inheritance sah lulus,6 kegagalan menunjukkan raw commit,
  perubahan ancestor/cycle, HTTP status, dan visibility leaf historis.
- Exact WarehouseMasterIT dua kali: **72 test,0 failure,0 skipped** setiap run.
- Gabungan tasks01-07/schema/network/Modularity: **526 test,0 failure,0 skipped**.
  Keluarga deferred LOCATION ditambahkan ke seluruh selective tenant-scope gates.
- 4 race SQL/HTTP memakai barrier dan observasi blocking, tanpa sleep: contender
  SQL23514 atau HTTP409, konflik ancestry final0. Snapshot stale40001; batas31/32
  dan coherent multi-row replacement turut diuji.
- Live JAR/non-owner PostgreSQL: raw A-NULL-B-NULL ditolak23514 pada commit,
  kedua row rollback; HTTP A-NULL-B409. A-NULL-NULL tetap200 dan exact201 replay
  sesudah SIGKILL/restart. Supplier response-loss tetap1 operation/1 supplier;
  actor revoked403 tanpa original body.
- Clean no-cache bootJar berhasil; V174.11 dan reader terbaru ada di artefak.
  V174.10 dan seluruh migrasi sebelumnya tidak berubah; V175+ tidak digunakan.

Evidence terbaru: `.omo/evidence/warehouse-workorder-asset-provenance/task-7/inheritance/`.

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
dan sengaja tidak masuk git. Uji restart master menggunakan fondasi schema restart;
belum ada fault injection khusus restart proses di tengah master HTTP response.

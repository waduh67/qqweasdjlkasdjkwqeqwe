# Picking dan pengeluaran material WO

Task14 mengaktifkan picking, unpick dan dispatch melalui `WarehouseIssueService`
milik inventory. Fulfillment hanya memperoleh konteks terkunci dari workorder dan
memanggil `InventoryIssueApi`; stok tetap ditulis oleh `WarehousePostingService`.
Task15 acknowledgement, pemakaian, pemasangan dan settlement **belum diaktifkan**.

## Prasyarat dan otorisasi

- WO aktif, penugasan teknisi aktif, plan terbaru SUBMITTED, demand terikat, dan
  reservasi OPEN yang belum dipick. Revisi WO/plan/demand/reservasi/segmen wajib cocok.
- Picker memerlukan `inventory.issue.manage`, `inventory.request.manage` dan akses
  baca WO. Tidak perlu memberikan izin edit WO hanya untuk picking. Master SKU dan
  scope warehouse/area/site diperiksa menggunakan authority terkini, bukan JWT saja.
- Read/reprint memerlukan `inventory.issue.view`, `inventory.request.view` dan
  akses WO serta lokasi sumber saat ini. Replay tidak mengembalikan body milik
  actor lain atau setelah akses lokasi dicabut.
- Sediakan lokasi ACTIVE berkode `WO_TRANSIT`, jenis TRANSIT, melalui API master.
  Lokasi ini menjadi tujuan dispatch. Tidak ada auto-create stock/lokasi atau
  fallback ke custody teknisi jika setup transit belum ada.
- Receiver dipilih server secara deterministik dari penugasan aktif WO yang
  persisted. Tidak ada receiver/sender/tenant/actor/authority dalam body HTTP.
  Penggantian assignment sebelum dispatch membuat issue lama stale.

Urutan transaksi: cutover dan current-authority fence, konteks WO, topology dan
dokumen inventory terurut, kemudian lot/identitas/dimensi stok. Saat reel dipotong,
reservasi unpicked lain pada parent dipindahkan secara struktural ke remnant tanpa
mengubah WO, jumlah demand, lokasi atau custody mereka. Dokumen terkait dikunci
terurut; revision reservasi tersebut maju sehingga scanner lama ditolak.

## HTTP

Semua POST memerlukan `Idempotency-Key`. DTO ketat menolak unknown fields,
duplicate JSON keys, numeric quantity dan coercion. Prefix:
`/api/work-orders/{workOrderId}/materials`.

| Method | Suffix | Fungsi |
| --- | --- | --- |
| POST | `/pick` | Pilih exact reservation/stock identity; hasil satu issue PICKED |
| POST | `/unpick` | Batalkan seluruh picked bundle issue secara eksplisit |
| POST | `/dispatch` | Dispatch seluruh picked bundle; partial demand harus eksplisit |
| GET | `/issues/{issueId}/slip` | Payload immutable transisi issue terkini |

Contoh pick satu potong100m:

```json
{
  "expectedRevision": 1,
  "workOrderRevision": 1,
  "demandRevision": 2,
  "lines": [{
    "reservationId": "00000000-0000-0000-0000-000000000001",
    "expectedRevision": 0,
    "stockIdentityId": "00000000-0000-0000-0000-000000000002",
    "stockRevision": 0,
    "quantityBase": "100000",
    "baseUnit": "MM"
  }]
}
```

`expectedRevision` luar adalah revisi plan; `expectedRevision` dalam adalah revisi
reservasi. IDs/revisi berasal dari summary material dan endpoint task10
`GET /api/v1/warehouse/material-requests/allocations/{workOrderId}`. Maksimum100
pilihan per command. Serial memakai1EA per identitas. `scan` opsional harus cocok
dengan serial kanonis identitas yang dipilih; scan bukan perintah reserve baru.
Duplikat, identitas salah/asing, unit salah, stock quarantine/customer-owned atau
terminal, expiry, dan over-pick ditolak tanpa durable writes.

Contoh dispatch atau unpick:

```json
{
  "issueId": "00000000-0000-0000-0000-000000000003",
  "expectedRevision": 1,
  "workOrderRevision": 1,
  "planRevision": 1,
  "demandRevision": 3,
  "partial": false,
  "reason": "Serah terima material WO"
}
```

Di sini `expectedRevision` adalah revisi issue. `partial:true` menyatakan bahwa
bundle yang didispatch belum memenuhi seluruh kebutuhan tersisa. Tanpanya,
partial issue ditolak; backorder tidak pernah dihitung sebagai barang terkirim.
Unpick dapat dilakukan oleh petugas berizin setelah reassignment/cancellation;
ini tidak memberikan izin issue baru pada assignment yang sudah dicabut.

## Kuantitas dan identitas

- Serial pick tidak mengubah lokasi, custody, saldo fisik atau physical legs.
  Unpicked menjadi picked; jumlah encumbrance tetap satu.
- Cable demand default continuousCut. Pick100m dari parent1000m menghasilkan
  CUT100m dan REMNANT900m, parent SPLIT, dengan lokasi/custody/lot/provenance sama.
  Total fisik tetap1000m; ID reservasi100m asal dipindahkan ke child100m.
- Partial pick membagi encumbrance yang sama antara cut dan remnant. Residual row
  mempunyai allocation link durable ke demand/plan/origin yang sama, bukan reserve
  bisnis tambahan. Tidak ada debit dua kali atau penggabungan remnant fiktif.
- Unpick mengembalikan picked menjadi unpicked pada identitas yang sama. Potongan
  kabel tetap terpisah. Barang dapat dipick ulang atau direlease; unpick sendiri
  bukan release seluruh komitmen demand. Picked tidak kedaluwarsa otomatis.
- Dispatch mengonsumsi tepat picked pada reservation dan memposting OUT warehouse
  serta IN transit dengan status IN_TRANSIT. Tidak ada posisi positif simultan
  pada source dan destination untuk identitas serial/cut yang sama.
- Transit memakai custody warehouse transit, **bukan teknisi**. Tidak ada accepted
  quantity/RECEIVED yang dibuat. Task15 baru memindahkan transit ke custody teknisi.
- Summary material dan allocation supply memakai requested = unpicked + picked +
  issued + backorder. Reserve berikutnya tidak memesan lagi jumlah yang sudah issued.

Substitusi memakai alur revisi plan task13: sumber plan line/SKU eksplisit,
replacement ACTIVE dengan tracking/base unit kompatibel, `inventory.request.override`
dan reason wajib. Release/unpick kewajiban sebelumnya terlebih dahulu. Picking
tidak menerima SKU pengganti tersembunyi; snapshot issue mempertahankan original SKU,
replacement dan reason dari plan, dengan permission override picker diperiksa lagi.

## Slip, replay dan persistensi

Slip berisi kode stabil, WO/customer, revisi sumber demand/plan/WO, sender/receiver
bernama, label SKU/lokasi, serial/lot, identitas dan quantity exact. `dimension` pada
line adalah dimensi sumber picking. Data tidak dibangun ulang dari nama master
terbaru. Reprint transisi yang sama membaca payload tersimpan yang identik.

Unpick menyimpan event/operation serta `inventory_issue_unpick`, tanpa menghapus
snapshot PICKED atau membuka kembali dokumen historis untuk diedit. Read slip
sesudah unpick menampilkan payload UNPICKED tersimpan; issue itu tidak bisa
didispatch. Repick menerbitkan issue baru. Replay command lama tetap mengembalikan
receipt asli command tersebut setelah pemeriksaan authority terkini.

V175.11 menambah `inventory_issue_snapshot`, `inventory_issue_line`,
`inventory_issue_unpick` dan `inventory_demand_supply_snapshot.issued_base`.
Composite tenant FKs, FORCE RLS, append-only guards dan binding ke posting/demand/
reservation berlaku. Manifest dicadangkan lebih dahulu. Semua predecessor dan
V176+ tidak berubah. SHA256 V175.11:
`44b2c4ca8e1b761db52d0c87d8865d115c9388cad30334180d9b4a63c23e1a46`.

## Bukti verifikasi

Exact command berikut lulus dua kali: **13 test, nol failure/error/skipped**,
10m43s dan7m38s:

```sh
scripts/warehouse/qa.sh server --tests '*WarehouseIssueIT*' --rerun-tasks --no-parallel
```

Gabungan owner task1-14 dijalankan dalam batch serial bounded: schema/Modularity132,
foundation/posting/concurrency/planning300, master/query87, receipt93,
policy/approval65, material69, reservation/planning29, issue13, lalu owner-context/
Modularity4. Setelah deduplikasi test yang berulang: **786 test unik lulus**.
Run reservation selesai dengan BUILD SUCCESSFUL10m5s meskipun tool menampilkan
timeout10menit; laporan29 test lengkap dan tidak ada proses tertinggal. Run gagal
awal tetap dicatat dan tidak dijadikan bukti PASS.

Clean `:server:clean :server:bootJar --no-build-cache --rerun-tasks --no-parallel`
lulus42s. JAR yang dipakai manual:
`43cf4444d34a590e616705e96e979caca40e84f9154ac9c76f4ebdd570f8b3a3`.
CodeGraph/LSP tidak tersedia; bukti memakai compiler, Spring HTTP dan PostgreSQL.

Manual packaged HTTP memakai schema baru pada warehouse_test dengan warehouse_app
non-owner/NOSUPERUSER/NOBYPASSRLS. Signup, roles/assignee, customer, master,
receipt1000m+10serial, putaway, WO, plan, demand dan reserve seluruhnya melalui API
pemilik. Sesudah pick: fisik1000000MM/10EA, picked100000MM/10EA; physical serial
legs tetap40. Sesudah unpick/repick/dispatch: BIN900000MM, transit100000MM+10EA,
11 reservation DISPATCHED dengan encumbrance0, satu split dan satu ISSUE movement.
Technician custody0 dan received issue0. SIGKILL/restart replay serta slip identik.

Suite juga membuktikan shared-parent/last-serial races, same-key replay/new-key
conflict, scanner error, spoofed authority, partial demand, current scope revocation,
reassignment/cancellation, dirty state, immutable label reprint dan tiga JVM dengan
response loss + SIGKILL. Evidence lokal:
`.omo/evidence/warehouse-workorder-asset-provenance/task-14/`.

Cleanup menghentikan process HTTP/JVM milik task, menghapus schema manual dan
container/network test. Volume PostgreSQL/MinIO dipertahankan; runtime, build,
secret dan evidence lokal tidak di-commit.

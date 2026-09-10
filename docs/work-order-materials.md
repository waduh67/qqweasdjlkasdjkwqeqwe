# Perencanaan material WO

Task13 menambahkan perencanaan dan demand, bukan lifecycle WO kedua. Fulfillment
mengorkestrasi kontrak publik workorder dan inventory dalam satu transaksi lokal.
Picking fisik, dispatch, acknowledgement, pelaporan penggunaan dan settlement
melalui route material belum diaktifkan oleh task ini.

## Pemilik dan penguncian

- `WorkOrderMaterialContextApi` dimiliki workorder. Snapshot berisi tipe/action,
  revisi WO, tautan pelanggan/langganan/order, area dan penugasan aktif.
- Revisi material bukan milik workorder. Inventory menyimpan revisi plan,
  snapshot master, demand, reservasi dan fakta posting.
- Urutan command: cutover tenant, current IAM authority, baris WO, lalu dokumen
  dan stok inventory. `lock` membutuhkan transaksi aktif dan expected WO revision.
- Reassignment, perubahan detail dan cancellation tetap memakai engine WO lama
  dan reference-row/revision fence task6. Reassignment tidak memindahkan custody.
- Dispatcher memakai izin WO update/assign. Teknisi membutuhkan izin field dan
  penugasan aktif saat ini. Mutation juga membutuhkan `inventory.request.manage`;
  pilihan SKU membutuhkan `inventory.sku.view`. Hak JWT lama tidak cukup.
- Baca membutuhkan akses WO saat ini dan `inventory.request.view`. Scope reservasi
  tetap diperiksa oleh pemilik inventory, termasuk saat membaca alokasi lama.

Tidak ada dependensi inventory ke implementation workorder/fulfillment. Adapter
workorder hanya menggunakan kontrak inventory/IAM/customer publik. Pekerjaan
standalone `REPAIR` tanpa pelanggan dipetakan ke action `NETWORK`; `PREVENTIVE`
juga tidak membutuhkan pelanggan atau langganan palsu.

## HTTP

| Method | Route | Hasil |
| --- | --- | --- |
| GET | `/api/work-orders/{id}/materials` | Snapshot plan, template default dan total demand/material |
| GET | `/api/work-orders/{id}/materials/history?page=0&size=25` | Versi plan, state submission dan demand ID; size 1..100 |
| PUT | `/api/work-orders/{id}/materials/plan` | Versi plan baru, tanpa mengedit snapshot sebelumnya |
| POST | `/api/work-orders/{id}/materials/submit-request` | Bekukan plan dan buat satu demand yang terikat versi |
| POST | `/api/work-orders/{id}/materials/reserve` | Reservasi task10, termasuk pasokan parsial/backorder |
| POST | `/api/work-orders/{id}/materials/release` | Release eksplisit seluruh reservasi unpicked plan aktif |
| GET/PUT | `/api/v1/warehouse/material-templates/{workType}/{action}` | Template aktif/publikasi versi baru |

Semua mutation membutuhkan `Idempotency-Key`. Body tidak menerima tenant, actor,
customer, assignee, authority, alokasi stok, hash atau effect target. Unknown field,
numeric quantity, duplicate JSON key dan coercion ditolak. `expectedRevision`
adalah revisi plan, bukan revisi demand. Revisi demand dan pilihan alokasi diperoleh
server di bawah lock. `workOrderRevision` adalah expected revision pemilik WO.

Contoh manual plan awal:

```json
{
  "expectedRevision": 0,
  "workOrderRevision": 0,
  "materialMode": "MATERIAL_REQUIRED",
  "reason": null,
  "lines": [
    {
      "skuId": "00000000-0000-0000-0000-000000000001",
      "quantityBase": "100000",
      "baseUnit": "MM",
      "continuousCut": false
    }
  ]
}
```

`lines:null` atau omitted pada MATERIAL_REQUIRED secara eksplisit mengambil
template aktif untuk tipe/action WO. Array manual tidak pernah ditimpa template.
Array kosong pada MATERIAL_REQUIRED ditolak. Maksimum 100 baris; semua duplicate
SKU ditolak karena kontrak sekarang belum memiliki identitas purpose terpisah.
Identitas baris adalah UUID server yang tetap untuk satu versi plan dan nomor
baris, termasuk pada replay.

`NONE` membutuhkan reason nonblank dan tanpa baris. Submit NONE membekukan
deklarasi tanpa membuat demand fisik kosong. Plan yang belum dibuat terlihat
sebagai `plan:null`, revisi0 dan demand DRAFT, bukan deklarasi NONE implisit.

Submit/reserve memakai body berikut; release juga wajib reason:

```json
{"expectedRevision": 1, "workOrderRevision": 0, "reason": null}
```

`quantityBase` adalah string integer exact: EA untuk unit, MM untuk panjang.
Tidak ada pembulatan float. Substitution baris memakai objek `substitution` dengan
`originalPlanLineId`, `originalSkuId`, dan `reason`; membutuhkan
`inventory.request.override`, sumber pada plan sebelumnya dan unit/tracking
kompatibel. Snapshot menyimpan kode/nama/revisi master asli dan penggantinya.

Template PUT menerima `expectedRevision` dan `lines`. Publikasi membutuhkan
`inventory.request.manage`, `inventory.sku.view` dan `workorder.order.assign`.
Versi immutable, baris SKU/unit mempunyai composite FK, dan current pointer
berpindah atomik. SKU pada template aktif tidak dapat diarsipkan.

## Riwayat, supply dan replay

- Setiap penggantian membuat plan ID/revisi baru dan snapshot immutable, termasuk
  plan yang belum submitted. History tidak membaca ulang nama master mutable.
- Reservasi harus direlease sebelum penggantian. Picked membutuhkan unpick
  eksplisit melalui kontrak task10; task13 tidak melakukan silent release.
- Plan dengan issued/usage facts tidak dapat diganti. Koreksi berikutnya harus
  memakai workflow delta tersendiri, bukan menghapus fakta lama.
- Penggantian memanggil `InventoryApprovalInvalidationApi` untuk pending unposted
  approval dokumen WO di bawah lock. Status menjadi STALE, tanpa keputusan atau
  posting; terminal approval dan efek historis tidak berubah.
- Summary menggabungkan baris plan/demand, reservasi durable dan ledger fisik:
  requested, reservedUnpicked, reservedPicked, issued, physicallyUsed, returned,
  transferredOut, disposed, stillAccountable dan backorder. Remnant yang tetap pada
  posisi/custody semula tidak dianggap terpakai/ditransfer.
- Ledger/fakta tanpa demand/issue lineage yang dapat diverifikasi menghasilkan
  conflict, bukan angka nol atau sukses kosong. Tidak ada biaya privat di DTO ini.
- Receipt command material menyimpan canonical payload/hash, actor, epoch,
  resource, revision dan body asli atomik dengan perubahan. Replay memeriksa
  current authority dan WO lebih dahulu; key lain dengan expected plan lama
  tidak membuat plan/demand kedua.

Route `/pick`, `/dispatch`, `/acknowledge`, `/report-use`, `/return`, `/reallocate`
dan `/settlement` tetap conflict409 untuk pemanggil yang dapat membaca WO. Nilai
QA/provisioning/settlement di summary task13 bukan izin untuk menjalankan efek
task14-18. Approval demand/effect owners berikutnya tetap harus menyediakan
binding aktual sebelum suatu keputusan dapat memposting.

## Migrasi dan verifikasi

Manifest diperbarui sebelum V175.8 dan V175.9 dibuat. Seluruh predecessor tetap
byte-identical; V176+ tidak disentuh. Hash SHA256:

| Migration | SHA256 |
| --- | --- |
| V175.8 | `0e81d6a61f60e77a390731d495ad8e875ce94d04f5a1b8d6e0bfe18b88aaf891` |
| V175.9 | `dd300b99c171951220ff2ee60e62dceefa4dc8882d3f355482e1bddc67a468e9` |

Gate exact dijalankan dua kali: masing-masing34 test, zero failure/skipped:

```sh
scripts/warehouse/qa.sh server --tests '*WorkOrderMaterialsIT*' --rerun-tasks --no-parallel
```

Regresi task1-13 dijalankan sebagai tiga command bounded/sequential:422 test
foundation/schema/posting/concurrency/contracts,206 master/receipt/query/reservation,
dan99 approval/policy/material. Total727, zero failure/skipped, termasuk
ModularityTests dan M03/schema gates. WO context adapter juga diuji langsung.

Clean artifact:

```sh
./gradlew :server:clean :server:bootJar --no-build-cache --rerun-tasks --no-parallel
```

Build berhasil37s. SHA256 bootJar yang digunakan untuk packaged HTTP:
`168a9945a7abd10d7817eb6a85694c63b2ca2c1bbf7c3cd33d9a80739ed6f110`.

Manual packaged HTTP memakai schema baru pada `warehouse_test`, application role
`warehouse_app` non-owner/NOSUPERUSER/NOBYPASSRLS, tanpa SQL seed material plan.
Signup, area, master, receipt/putaway, customer dan WO dibuat melalui HTTP.
Template lalu manual revision menghasilkan120000mm requested,60000 reserved dan
60000 backorder; SQL menunjukkan satu reservation60000 dan satu demand. SIGKILL
lalu restart memberi exact replay; release, bounded history, empty-required400,
future409 dan network NONE tanpa customer juga diuji. SMTP health eksternal
dimatikan hanya pada harness lokal karena tidak ada kredensial SMTP.

Evidence lokal berada di `.omo/evidence/warehouse-workorder-asset-provenance/task-13/`.
Test posted-fact projection memakai posting owner task5 dengan dokumen fixture,
bukan implementasi HTTP picking/usage task14-16. Invalidation pending/terminal
dibuktikan melalui owner port dengan approval receipt task12 yang nyata.

Cleanup selesai: process/schema manual dihentikan/dihapus, `qa.sh stop` berhasil,
dan `test-environment.sh down` hanya menghapus container/network milik task.
Volume PostgreSQL/MinIO dipertahankan; runtime, evidence dan secrets tidak di-commit.

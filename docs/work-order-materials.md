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
workorder hanya menggunakan kontrak inventory/IAM/customer publik. Customer dan
subscription adalah tautan nullable yang independen dari action. `REPAIR` tetap
`REPAIR` dengan maupun tanpa customer; tidak ada inferensi `NETWORK` dari null.

| Tipe WO saat ini | Action/default template |
| --- | --- |
| PSB | INSTALL |
| REPAIR | REPAIR |
| MIGRATION | REPLACE |
| DISMANTLE | REMOVE |
| PREVENTIVE | PREVENTIVE |

Template `REPAIR/NETWORK` tetap dapat dikonfigurasi tetapi tidak dipilih oleh
heuristik customer; penggunaannya memerlukan discriminator eksplisit dari pemilik
WO yang belum disediakan tipe WO saat ini. Template `REPAIR/RETURN_CUSTOMER_RMA`
juga dapat dipublikasikan secara durable tanpa mengubah default REPAIR atau
mengaktifkan workflow fisik RMA. Tidak ada customer/subscription palsu.

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
- Outer reserve/release replay memanggil `InventoryReservationApi.replay` sebelum
  mengembalikan body tersimpan. Pemilik reservasi memuat canonical command asli
  dan menjalankan ulang jalur otorisasi replay yang sama dengan `execute`, termasuk
  source allocation/location, warehouse/site/area scope dan izin action saat ini.
  Tidak ada rekonstruksi alokasi dari plan terbaru atau validasi scope duplikat
  yang lebih lemah. Izin request view/manage diperiksa sebelum outer response.
- SUBMITTED membutuhkan tepat satu submission/deklarasi. MATERIAL_REQUIRED
  membutuhkan satu demand tenant/WO/plan/revisi yang sama, seluruh line SKU/unit/
  quantity/tracking/continuousCut cocok, dan initial demand-line revision0.
  Revisi header demand dapat maju melalui reservasi tanpa menulis ulang line.
  NONE membutuhkan declaration dengan demand null, reason dan nol line.
- V175.10 memeriksa final state secara deferred, dengan tenant assertion internal
  pada validator, bukan companion trigger saja. Submission dapat dibuat sebelum
  atau sesudah update state plan dalam transaksi yang akhirnya lengkap. Validator
  yang sama dipanggil summary/history; missing/ambiguous binding menghasilkan
  `409 SOURCE_NOT_VERIFIED`, bukan requested/backorder nol. Data lama tidak direpair.

Route `/pick`, `/dispatch`, `/acknowledge`, `/report-use`, `/return`, `/reallocate`
dan `/settlement` tetap conflict409 untuk pemanggil yang dapat membaca WO. Nilai
QA/provisioning/settlement di summary task13 bukan izin untuk menjalankan efek
task14-18. Approval demand/effect owners berikutnya tetap harus menyediakan
binding aktual sebelum suatu keputusan dapat memposting.

## Migrasi dan verifikasi

Manifest diperbarui sebelum V175.8, V175.9 dan koreksi V175.10 dibuat. Seluruh predecessor tetap
byte-identical; V176+ tidak disentuh. Hash SHA256:

| Migration | SHA256 |
| --- | --- |
| V175.8 | `0e81d6a61f60e77a390731d495ad8e875ce94d04f5a1b8d6e0bfe18b88aaf891` |
| V175.9 | `dd300b99c171951220ff2ee60e62dceefa4dc8882d3f355482e1bddc67a468e9` |
| V175.10 | `88e9e481dc51c759d43f01407e115705463a6a159cd1cf2080f4d73c3f6c3407` |

Gate exact sesudah koreksi AV13 dijalankan dua kali: masing-masing69 test,
zero failure/skipped (8m51s dan8m34s):

```sh
scripts/warehouse/qa.sh server --tests '*WorkOrderMaterialsIT*' --rerun-tasks --no-parallel
```

Regresi task1-13 dijalankan bounded/sequential:422 test foundation/schema/posting/
concurrency/contracts,87 master/query,93 receipt,26 reservation,65 approval/policy
dan69 material. Total762 test unik, zero failure/skipped, termasuk ModularityTests
dan M03/schema gates. Batch master/receipt/query/reservation gabungan sempat mencapai
batas30menit sebelum laporan final; run terinterupsi tidak dihitung sebagai lulus,
dan seluruh keluarga tersebut diulang dalam batch lebih kecil yang lulus.

Clean artifact:

```sh
./gradlew :server:clean :server:bootJar --no-build-cache --rerun-tasks --no-parallel
```

Build koreksi berhasil43s. SHA256 bootJar yang digunakan untuk packaged HTTP:
`b8f4231f13276f50dbe168f482b3aef2fdd49f49f7fc45786518dd2561dc35e4`.

Manual packaged HTTP memakai schema baru pada `warehouse_test`, application role
`warehouse_app` non-owner/NOSUPERUSER/NOBYPASSRLS, tanpa SQL seed material plan.
Signup, area, master, receipt/putaway, customer dan WO dibuat melalui HTTP.
Template lalu manual revision menghasilkan120000mm requested,60000 reserved dan
60000 backorder; SQL menunjukkan satu reservation60000 dan satu demand. Scope
operator dicabut: summary, task10 owner replay dan outer replay semuanya404.
SIGKILL/restart tetap404; setelah scope dikembalikan, replay byte-identical.
REPAIR tanpa customer memilih11000, bukan template NETWORK22000. Publication RMA,
release, bounded history, empty-required400, future409 dan NONE tanpa customer lulus.

Harness awalnya boot pada175.9, membuat plan melalui HTTP, lalu mereproduksi raw
`warehouse_app` UPDATE SUBMITTED tanpa submission yang berhasil COMMIT. Setelah
upgrade175.10 tanpa repair/disable trigger, GET summary/history plan itu409.
Percobaan UPDATE yang sama pada plan baru setelah upgrade gagal saat commit dengan
SQLSTATE23514 `warehouse_material_submission_ck`. SMTP health eksternal dimatikan
hanya pada harness lokal karena tidak ada kredensial SMTP.

Evidence lokal berada di `.omo/evidence/warehouse-workorder-asset-provenance/task-13/`.
Koreksi verifier disimpan pada subdirektori `av13/`, termasuk raw SQLSTATE dan
observasi upgrade/corruption. Counterexample verifier tetap didokumentasikan;
gate34 lama bukan bukti bahwa ketiga bug tersebut tidak pernah ada.
Test posted-fact projection memakai posting owner task5 dengan dokumen fixture,
bukan implementasi HTTP picking/usage task14-16. Invalidation pending/terminal
dibuktikan melalui owner port dengan approval receipt task12 yang nyata.

Cleanup selesai: process/schema manual dihentikan/dihapus, `qa.sh stop` berhasil,
dan `test-environment.sh down` hanya menghapus container/network milik task.
Volume PostgreSQL/MinIO dipertahankan; runtime, evidence dan secrets tidak di-commit.

# Reservasi material WO

Task10 menyediakan `WarehouseReservationService`, API internal
`InventoryReservationApi`, serta facet `InventoryMaterialReservationApi` yang
diwarisi `InventoryMaterialApi`. Task13 tetap memiliki pembuatan/submission plan
dan orkestrasi UI WO. Task14 tetap memiliki pemotongan fisik dan dispatch.
Tidak ada receipt, customer, issue atau pergerakan fisik palsu untuk reservasi.

Task14 sekarang menyediakan picking fisik dan dispatch melalui route material WO;
lihat [warehouse-issues.md](warehouse-issues.md). Route pick/unpick task10 tetap
merupakan transisi encumbrance, bukan penerbitan slip atau pengakuan custody.

## Prasyarat dan kontrak

Demand `inventory_document` harus `DEMAND`, sudah `SUBMITTED`, dan memiliki WO,
plan revision serta submitted timestamp. Plan harus `SUBMITTED` dan
`MATERIAL_REQUIRED`. Setiap line demand cocok dengan nomor line, SKU, unit,
kuantitas dan continuous-cut pada plan. Binding hilang/salah ditolak; tidak
dibuat plan otomatis sebagai fallback. Uji menggunakan fixture plan/demand
durable lewat role aplikasi, bukan implementasi sementara task13.

Validasi memisahkan empat mode, tanpa melepas pengecekan actor/scope terkini:

- `NEW_ALLOCATION`: reserve baru dan target reallocation wajib memakai plan
  submitted yang masih menjadi otoritas terbaru. Adanya draft lebih baru juga
  menolak alokasi baru dari plan lama; bukan memilih plan lama diam-diam.
- `BOUND_LIFECYCLE`: pick/unpick/release/extend, sumber reallocation dan expiry
  memvalidasi binding reservation/document/plan yang benar-benar persisted.
  Draft plan baru tidak menghapus kewajiban picked yang sudah ada.
- `HISTORICAL_READ`: membaca binding submitted historis, dengan izin serta
  scope WO/lokasi/site saat ini, tanpa persyaratan plan paling baru.
- `REPLAY`: verifikasi current authority dan immutable binding sebelum
  mengembalikan response asli. Replay diselesaikan sebelum query kandidat stok.

`POST /api/v1/warehouse/material-requests/{id}/{reserve|release|pick|unpick|extend|reallocate}`
memerlukan `Idempotency-Key`. Body minimal reserve:

```json
{"expectedRevision":1,"workOrderRevision":0,"planRevision":1}
```

`lines` opsional berisi `demandLineId`, `partialQuantityBase`, dan pilihan
`stockIdentityId`. Pilihan identitas selalu memerlukan
`inventory.request.override` dan `reason` tidak kosong. Tanpa pilihan, FIFO
berdasarkan lot received_at atau timestamp posting receipt serial, lalu ID.
Izin reserve/release adalah `inventory.request.manage`; pick/unpick juga
`inventory.issue.manage`; extend/reallocate juga `workorder.order.assign`.
Tidak ada approver atau izin yang diterima dari body/JWT sebagai otoritas akhir.

Mutasi existing allocation memerlukan `allocations` dengan `reservationId`,
`expectedRevision`, `quantityBase` dan alasan. Release mengharuskan kuantitas
remaining unpicked persis, picked nol, serta reservation milik demand itu.
Picked harus di-unpick eksplisit terlebih dahulu. Extend memakai `expiresAt`
yang lebih besar dari waktu DB dan expiry sebelumnya serta kuantitas total persis.

Reallocate memerlukan satu allocation sumber serta `target` yang berisi
`documentId`, `expectedRevision`, `workOrderRevision`, `planRevision`, dan
`demandLineId`. Release sumber dan reserve target berkomit dalam transaksi yang
sama; target shortage/stale membatalkan keduanya. Custody tidak berubah.

Facet material owner memakai `MaterialDocumentRequest.reservation` dengan body
reservation lengkap di atas. Expected revision luar dan dalam harus sama dan WO
parameter harus pemilik dokumen. Facet ini dapat didelegasikan oleh orchestrator
task13 tanpa memasang implementasi palsu untuk dispatch/settlement/use.

## Kuantitas dan concurrency

Eligibility mengikuti task9: VERIFIED ACTIVE segment, SKU ACTIVE, unit/tracking
cocok, AVAILABLE SERVICEABLE ISP, lokasi ACTIVE issue-eligible serta warehouse,
area, effective-site scope terkini. Total open unpicked + picked dikurangi sekali.
Serialized EA dialokasikan satu per identitas; bulk EA tetap membawa lot/identity.

MM continuous-cut default true tidak boleh menyambung remnants. Dua 10m tidak
memenuhi satu 15m. Partial continuous hanya memakai satu piece yang cukup untuk
jumlah partial eksplisit; tambahan allocation untuk line tersebut tetap pada
piece yang sama. Demand non-continuous 100m / stock60m menyimpan reserved60m,
backorder40m dan PART_RESERVED. Shortage nol menyimpan SUBMITTED dengan flag
`shortage=true`, snapshot kuantitatif dan event, bukan RESERVED palsu.

Cutover dan current-authority fence didahulukan; kedua WO dan dokumen dikunci
urut global. Lock lot/stock mengikuti urutan task5 agar tidak membalik urutan
posting fisik. Hanya reserve baru menjalankan perencanaan kandidat, dibatasi SKU,
unit, warehouse/area/effective-site scope, jumlah kebutuhan tersisa dan pilihan
piece eksplisit. Continuous-cut mencari satu piece pertama yang cukup; kebutuhan
fungible memilih prefix FIFO yang cukup. Pending deduction antar-line SKU sama
mencegah dua line menghitung kapasitas piece yang sama dua kali.

Sesudah lock, hanya balance IDs yang terpilih dibaca ulang; perubahan dimensi,
revision atau kapasitas yang tidak cukup menghasilkan conflict, bukan oversell.
Maksimum100 demand lines/selection/mutation dan2000 allocation entries per command.
Kebutuhan serial melampaui cap ditolak400 sebelum query kandidat; fragmentasi
yang benar-benar memerlukan lebih banyak entries juga ditolak, tidak dipotong
diam-diam menjadi shortage palsu. Jumlah total stok tenant bukan batas operasi.
Transaksi dibatasi30s.

Release/unpick/pick/extension dan replay tidak mengenumerasi supply. Origin dan
dimensi diambil dari reservation links yang sudah ada. Reallocation memakai
posisi sumber eksak dan hanya memvalidasi kebutuhan target; expiry memilih due
reservation langsung, bukan mencari kandidat baru untuk seluruh SKU.

## Expiry dan pembacaan

Expiry awal adalah max(submitted+24h, scheduledEnd/scheduledAt+24h). Model WO
persisted saat ini hanya mempunyai scheduledAt; adapter tidak mengarang end.
`ReservationWorkOrder` membawa kedua timestamp untuk kontrak owner. Tanpa jadwal,
gunakan submitted+24h. Semua keputusan due memakai clock PostgreSQL.

`WarehouseReservationExpirySchedule` mengikuti `ftth.scheduling.enabled` dan
delay `ftth.warehouse.reservation-expiry-delay` (default PT1M). Worker membatasi25
transaksi per tenant/tick, memakai fence authority sistem dan row locks. Tidak
ada principal/JWT palsu. Operation expiry mencatat actor asal dokumen dan alasan
`SYSTEM: unpicked reservation expiry`. Mixed row kehilangan hanya unpicked dan
tetap OPEN dengan picked; row tanpa picked menjadi EXPIRED. Restart/node kedua
menemukan tidak ada due quantity lagi, bukan menerbitkan event duplikat.

`GET /api/v1/warehouse/material-requests/allocations/{workOrderId}` memerlukan
`inventory.request.view`, WO/warehouse/area/site scope terkini dan mengembalikan
links berikut revision allocation/reservation/document/plan/source. Terminal
allocations tetap terlihat sebagai history. Demand sah tanpa stock boleh
mengembalikan list kosong pada endpoint ini; API fulfillment menolak keadaan
tanpa allocation atau binding dengan error eksplisit.

`InventoryApi.fulfillmentAllocations` tidak lagi stub. Field lama dipertahankan,
dengan `reservation` versioned sebagai tambahan. Legacy `quantity` hanya integer
count EA yang representable; MM/bulk melebihi Int memakai null dan quantityBase
string pada detail reservation. `customerId` null untuk WO standalone, bukan UUID
palsu. Legacy consume-on-approval menolak semua reservation links: reservasi bukan
acknowledged issue. Perubahan ini tidak mengimplementasikan physical issue/use.

Setiap link juga membawa `demandSupply`: `requestedBase`, `reservedUnpickedBase`,
`reservedPickedBase`, `totalReservedBase`, `backorderBase`, `baseUnit`,
`demandState`, `documentRevision`, `planRevision`, `snapshotId` dan `operationId`.
Ini adalah total **line demand**, dari snapshot supply immutable milik revision
dokumen yang dikunci, bukan hasil menjumlahkan allocation yang kebetulan terlihat.
Nilai line yang sama dapat berulang pada beberapa links dan tidak boleh dijumlah
ulang oleh consumer. Field reserved pada link sendiri tetap quantity reservation
tersebut. Snapshot terkini yang hilang/mismatched gagal eksplisit, bukan fallback
ke snapshot lama atau nullable legacy count.

Mulai task14, `demandSupply.issuedBase` membawa jumlah yang sudah didispatch.
Konservasi supply menjadi requested = unpicked + picked + issued + backorder.
Planner mengurangi issued sebelum reserve tambahan; barang yang sudah dikirim
tidak muncul sebagai backorder baru dan tidak dapat direservasi untuk kedua kali.
PART_ISSUED tetap kuantitatif saat reserve/release berikutnya. Kolom issued
ditambahkan forward-only pada V175.11, tanpa menulis ulang snapshot historis.

Contoh100m/60m: requested100000, unpicked60000, picked0, totalReserved60000,
backorder40000, PART_RESERVED. Pick20m mengubah unpicked40000/picked20000 tetapi
total/backorder tetap. Expiry kemudian memberi unpicked0/picked20000,
totalReserved20000/backorder80000 dan PART_RESERVED. Release seluruh encumbrance
memberi reserved0/backorder100000 dan SUBMITTED.

M02 V174.14 menambah immutable `inventory_reservation_allocation` dan
`inventory_demand_supply_snapshot`, composite tenant FKs/FORCE RLS, origin binding
guard, open-only uniqueness serta transisi demand sesudah release/expiry.
Row terminal tidak dibuka kembali; reservasi ulang membuat allocation baru.
Seluruh byte migrasi terdahulu dan slot V175+ dipertahankan.

## Verifikasi

`scripts/warehouse/qa.sh server --tests '*WarehouseReservationIT*' --rerun-tasks --no-parallel`
meliputi PostgreSQL non-owner, HTTP/internal owner API, race, replay, current-role
revocation, continuous/partial, release/reallocation, expiry dan restart JVM.
Evidence lokal task10 merekam packaged JAR + dua node HTTP: physical legs tetap4,
reserve60000/backorder40000, sesudah expiry picked20000/available40000 dan satu
RESERVATION_EXPIRED. Schema proof sementara dibersihkan; volume test dipertahankan.

Hasil gate sesudah koreksi verifier: dua run exact masing-masing26 test, nol
failure/skipped. Gabungan task1-10, schema/posting/query dan Modularity dieksekusi
dalam dua batch serial non-overlap untuk menjaga batas30 menit runner:423+333
=756 test pada106 XML suites unik, nol failure/error/skipped. Clean
`bootJar --no-build-cache --rerun-tasks --no-parallel` berhasil. LSP dan CodeGraph
tidak tersedia; bukti memakai compiler Kotlin, PostgreSQL dan runtime.

Proof JAR baseline mereproduksi409 setelah draft baru,400 saat supply2001
identities dan field total yang hilang. JAR koreksi dengan fixture sama memberi
200 untuk lifecycle/read/replay; reserve baru dari plan stale tetap409. Seluruh
2001 serial diterima/putaway melalui API task8. Kedua node sesudah restart setuju
tentang snapshot, masing-masing due demand hanya punya satu expiry event, dan
physical legs tetap8008 sebelum/sesudah availability commands. Koreksi ini tidak
menambah atau mengubah migration maupun mengimplementasikan orkestrasi task13.

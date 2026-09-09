# Reservasi material WO

Task10 menyediakan `WarehouseReservationService`, API internal
`InventoryReservationApi`, serta facet `InventoryMaterialReservationApi` yang
diwarisi `InventoryMaterialApi`. Task13 tetap memiliki pembuatan/submission plan
dan orkestrasi UI WO. Task14 tetap memiliki pemotongan fisik dan dispatch.
Tidak ada receipt, customer, issue atau pergerakan fisik palsu untuk reservasi.

## Prasyarat dan kontrak

Demand `inventory_document` harus `DEMAND`, sudah `SUBMITTED`, dan memiliki WO,
plan revision serta submitted timestamp. Plan harus `SUBMITTED` dan
`MATERIAL_REQUIRED`. Setiap line demand cocok dengan nomor line, SKU, unit,
kuantitas dan continuous-cut pada plan. Binding hilang/salah ditolak; tidak
dibuat plan otomatis sebagai fallback. Uji menggunakan fixture plan/demand
durable lewat role aplikasi, bukan implementasi sementara task13.

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
posting fisik. Keputusan FIFO dilakukan terhadap kandidat eligible yang dibaca
ulang sesudah lock. Pemilihan kandidat dan lock acquisition bukan dua otoritas
stok. Maksimum100 demand lines/selection/mutation dan2000 kandidat; kelebihan
ditolak, bukan dipotong diam-diam menjadi shortage palsu. Transaksi dibatasi30s.

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

Hasil gate task10: dua run exact masing-masing13 test, nol failure/skipped.
Gabungan task1-10, schema/posting/query dan Modularity:743 test, nol
failure/skipped. Run awal gabungan menemukan daftar izin exact task02 belum
memuat izin override baru; daftar tetap exact setelah penambahan kode izin.
Clean `bootJar --no-build-cache --rerun-tasks --no-parallel` berhasil. LSP dan
CodeGraph tidak tersedia; bukti memakai compiler Kotlin, PostgreSQL dan runtime.

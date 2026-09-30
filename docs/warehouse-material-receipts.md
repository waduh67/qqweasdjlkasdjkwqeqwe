# Penerimaan Material Teknisi

Task15 mengaktifkan tindakan penerima yang eksplisit. Dispatch task14 tetap hanya
memindahkan barang ke WO_TRANSIT; tidak ada penerimaan otomatis. Fulfillment
`MaterialReceiptService` mengunci konteks WO lewat API pemilik, lalu memanggil
`InventoryMaterialReceiptApi`. Semua gerakan ditulis `WarehousePostingService`.
Tidak ada pemakaian, settlement, return, handover atau pemasangan pelanggan baru.

## HTTP dan Otoritas

`POST /api/work-orders/{id}/materials/acknowledge` memerlukan `Idempotency-Key`,
izin `workorder.order.field`, assignment WO aktif, serta actor yang sama dengan
receiver snapshot issue. Revisi WO dan issue harus cocok. Tenant, actor, receiver,
customer, tujuan custody, movement dan approver tidak diterima dari body.
Unknown fields, duplicate JSON keys, coercion dan numeric quantity ditolak.

Siapkan satu lokasi ACTIVE jenis TECHNICIAN dengan `custodianId` penerima melalui
API master. Lokasi tujuan dipilih server; konfigurasi kosong atau ambigu ditolak.
Receiver memerlukan scope area WO serta scope transit tersimpan dan lokasi teknisi.
Lokasi tidak dibuat otomatis dan stok teknisi tidak menjadi available warehouse.

```json
{
  "issueId": "00000000-0000-0000-0000-000000000001",
  "expectedRevision": 2,
  "workOrderRevision": 1,
  "evidenceReference": "signed-paper-handover-2026-09-11",
  "lines": [{
    "issueLineId": "00000000-0000-0000-0000-000000000002",
    "stockIdentityId": "00000000-0000-0000-0000-000000000003",
    "baseUnit": "MM",
    "acceptedBase": "60000",
    "missingBase": "40000",
    "rejectedBase": "0",
    "reason": "Sisa belum diserahkan"
  }]
}
```

`stockIdentityId` menunjuk identitas pada snapshot dispatch, bukan SKU, serial
teks pengganti, atau identitas pilihan bebas. Untuk SERIAL EA, `serial` wajib
sama persis dengan serial dispatch. Setiap identitas diterima paling banyak sekali.
Kuantitas string berupa bilangan bulat basis EA/MM yang muat dalam Long.

## Kuantitas, Lineage dan Discrepancy

- Issue wajib DISPATCHED atau PART_RECEIVED. Total diterima dan sisa disimpan pada
  receipt immutable dan divalidasi terhadap posting, bukan disimpulkan dari status.
- Terima60 dari100m menghasilkan60m SERVICEABLE/ISSUED milik receiver dan40m
  IN_TRANSIT. Source transit adalah dimensi dispatch persisted, bukan lookup ulang
  kode WO_TRANSIT. Ledger OUT/IN dan revisi issue berada dalam satu transaksi.
- Mesin posting memindahkan piece MM utuh. Partial handover memerlukan satu split
  atomik60/40 dengan parent dispatch yang sama. Remainder berikutnya dipindahkan
  utuh tanpa split kedua atau penggabungan remnant. Snapshot menyimpan identitas
  dispatch, identitas sumber aktual, revision, child diterima dan child sisa.
- Request boleh memilih subset line. Setidaknya satu kuantitas accepted positif
  diperlukan; discrepancy-only command belum diaktifkan.
- `missingBase`/`rejectedBase` adalah observasi pada acknowledgement tersebut,
  bukan loss posting atau disposisi final. Keduanya harus muat di sisa transit dan
  memerlukan reason. Nilainya tidak mengurangi transit, menambah availability,
  atau menutup issue. Penerimaan sisa kemudian mempertahankan observasi lama.
  Penyelesaian kehilangan/penolakan tanpa penerimaan fisik adalah boundary tugas
  return/disposition berikutnya, bukan mutasi tersembunyi pada task15.
- Setelah semua line sepenuhnya diterima, state RECEIVED. Kolom lama
  `inventory_document_line.accepted_base` tetap bagian snapshot pick yang beku;
  sumber penerimaan task15 adalah `inventory_material_receipt_line.accepted_base`
  dan `totals` receipt, bukan perubahan retroaktif snapshot task14. Task16 harus
  memakai sumber penerimaan ini saat menambahkan boundary pemakaian.

## Receipt dan Replay

Response menyimpan receiptId, postingId, server time, nama/id penerima, evidence
reference, snapshot issue asli, line receipt dan total kumulatif per line.
`evidenceReference` adalah referensi bukti serah-terima (misalnya dokumen bertanda
tangan), bukan URL download publik, data upload atau authority token.

`GET /api/v1/warehouse/my-material-receipts/{receiptId}` mengembalikan snapshot
tersimpan milik actor. Reassignment tidak mengubah custody dan tidak menghalangi
receiver lama membaca receipt sendiri bila permission/scope masih berlaku.
Receiver baru tidak memperoleh receipt atau stok receiver lama. Assignment lama
tidak dapat dipakai untuk acknowledgement baru atau replay command WO.

Same key, actor dan payload mengembalikan status/body tersimpan persis, termasuk
setelah restart. Perubahan payload/revisi konflik. Current authority dan scope
tetap diperiksa sebelum replay/private read; revoked JWT tidak mengambil body.

## Persistensi dan Bukti

V175.14 menambah header/line append-only, FORCE RLS USING/WITH CHECK, composite
tenant FKs, sealed transaction insertion, quantity constraints, serta deferred
binding ke operation/movement dan lifecycle issue. Setiap validator memeriksa
scope tenant sendiri, termasuk saat constraint dijalankan secara selektif.
SHA256: `391da11be6b5704402b02d1d47d2f02ffe3027c2b07ea49251d797e318595e5c`.
V175.13 dan seluruh predecessor tetap byte-identical; V176+ tidak dipakai.

Exact `WorkOrderMaterialReceiptIT` lulus dua kali29 tests. Concurrency4 dan
isolation5 juga lulus; schema/contracts/Modularity, task14 issue35, task10
reservation26, task13 material serta bounded regression menghasilkan907 tests
unik (869 predecessor dan38 task15), nol failure/error/skipped pada final runs.
Satu batch terlalu besar dihentikan timeout30menit dan diulang sebagai batch
lebih kecil; run timeout tidak dihitung sebagai PASS.

Clean no-cache bootJar dan HTTP packaged dengan warehouse_app non-owner lulus.
Manual: dispatch100m+2serial; terima60m+1serial; replay tanpa gerakan; wrong actor,
overreceipt dan revoked scope ditolak; sisa menghasilkan RECEIVED; dua SIGKILL
dan restart mempertahankan kedua body asli. Evidence lokal berada pada
`.omo/evidence/warehouse-workorder-asset-provenance/task-15/` dan tidak di-commit.

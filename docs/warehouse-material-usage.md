# Pemakaian fisik material WO

Task16 mencatat pemakaian terukur sebelum QA. Fulfillment hanya mengoordinasikan
API publik workorder, IAM dan inventory. Inventory memiliki dokumen USAGE,
posting, snapshot, fakta material dan sisa custody. Tidak ada konsumsi saat QA.

## HTTP

- `POST /api/work-orders/{id}/materials/report-use`, dengan `Idempotency-Key`.
- `GET /api/v1/warehouse/my-material-usage/{id}` membaca body snapshot asli milik
  teknisi, dengan izin field dan scope custody terkini.

Contoh pemakaian 82.500m dari receipt teknisi 100m:

```json
{
  "expectedRevision": 0,
  "planRevision": 1,
  "workOrderRevision": 2,
  "materialMode": "MATERIAL_REQUIRED",
  "evidenceReference": "pengukuran-lapangan-82.500m",
  "lines": [
    {
      "receiptId": "00000000-0000-0000-0000-000000000001",
      "issueLineId": "00000000-0000-0000-0000-000000000002",
      "stockIdentityId": "00000000-0000-0000-0000-000000000003",
      "quantityBase": "82500",
      "baseUnit": "MM"
    }
  ]
}
```

Revisi contoh harus diganti dengan revisi otoritatif. `stockIdentityId` adalah
identitas `accepted` dari receipt task15, bukan identitas transit atau parent
warehouse. Sumber kuantitas adalah `inventory_material_receipt_line.accepted_base`;
`inventory_document_line.accepted_base` task14 tetap nol dan tidak memberi izin.
Discrepancy, missing dan rejected bukan stok yang dapat dipakai.

Pemakaian pertama membutuhkan WO aktif, penugasan aktif teknisi, izin
`workorder.order.field`, scope lokasi custody, serta plan SUBMITTED yang cocok.
Tenant, actor, customer, custodian, posting dan destination selalu berasal dari
authentication dan state pemilik. Unknown field, duplicate key, duplicate line,
unit tidak cocok, nol, negatif, overflow dan quantity fractional ditolak.
Memulai pekerjaan dapat menaikkan revisi WO tanpa mengganti plan sumber. Command
tetap memakai revisi WO terkini; tipe, action, customer dan penugasan harus cocok
dengan konteks yang sah, sementara revisi plan/receipt historis tetap immutable.

Administrator menyiapkan lokasi aktif berkode `CONSUMED` melalui master warehouse.
Command tidak menerima lokasi tujuan. MM dipotong atomik menjadi child consumed
dan child REMNANT dengan lineage parent yang sama: 100000 = 82500 + 17500 MM.
Remnant tetap ISSUED pada teknisi; tidak menjadi availability. EA fungible memakai
jumlah unit exact dan tidak memerlukan split. Tidak ada penggabungan remnant.

## Snapshot dan replay

- Satu operasi canonical menyimpan key, actor, resource WO, payload/hash, epoch,
  waktu server, status dan body asli. Same-key/same-payload mengembalikan byte asli
  setelah pemeriksaan current authority. Key berbeda tidak mengulang use revision.
- Usage menyimpan revisi WO/plan/receipt, issue dan plan-line, acknowledged identity,
  requested/acknowledged/used/residual exact, posting/fact linkage dan bukti.
- Penulisan dokumen, consumed posting, remnant, fakta, snapshot dan receipt command
  adalah satu transaksi. Kegagalan di tengah tidak meninggalkan efek parsial.
- Snapshot/fakta tidak dapat diubah atau dihapus. Task16 menyediakan use revision1;
  pelaporan berikutnya ditolak. `/correct-use` adalah boundary tertutup untuk
  command koreksi delta/kompensasi eksplisit dengan alasan dan prior revision.
  Material consumed tidak dianggap kembali secara diam-diam.
- Reassignment tidak memindahkan stok: teknisi lama tetap memiliki sisa dan dapat
  membaca receipt sendiri jika izin/scope masih berlaku, tetapi tidak bisa use baru.
- Penolakan dan pengiriman ulang hasil WO tidak mengubah consumed balance,
  usage revision, fakta maupun posting.

`materialMode=NONE` hanya valid untuk plan NONE yang sudah submitted, reason
nonblank dan array lines kosong. Snapshot NONE mempunyai nol posting dan nol fakta.
MATERIAL_REQUIRED tanpa line bukan deklarasi NONE.

`evidenceReference` adalah referensi bukti opaque, bukan instruksi atau URL yang
dieksekusi. `networkReferenceLabel` opsional adalah label immutable, bukan ID
topology dan tidak memberikan binding atau provisioning. Customer nullable hanya
berasal dari WO/plan. Standalone WO tidak membuat customer palsu.

## Batas tugas

Semua SERIAL fail closed pada report-use. Task20 memiliki assignment/deployment;
tidak ada pemasangan perangkat, penulisan ONU atau jalur konsumsi serial kedua.
Flag fakta material `installed` yang sudah ada berarti material fisik terpakai,
bukan assignment perangkat. Settlement task17, return/transfer/closure task18,
scrap, customer topology, provisioning, UI dan mobile tetap di luar task16.

Migrasi forward V175.15-V175.20 mengikuti manifest. Tabel baru FORCE RLS,
append-only dan composite tenant references; validator deferred memeriksa tenant
di dalam fungsi serta exact receipt/posting/fact/snapshot binding. V175.14 dan
seluruh predecessor tidak diubah.

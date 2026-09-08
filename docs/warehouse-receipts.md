# Penerimaan, inspeksi, dan putaway

Task08 menyediakan penerimaan pemasok melalui HTTP, bukan purchase order/AP/GL.
Draft tidak menciptakan stok. Receive menciptakan origin VERIFIED dan physical
posting ke karantina; inspeksi tidak membuat barang AVAILABLE. Hanya putaway
atas bagian diterima atau bypass kebijakan SKU eksplisit yang merilis stok.

## Endpoint

Seluruh endpoint biasa memakai `/api/v1/warehouse/receipts` dan izin terkini
`inventory.receipt.view` untuk baca atau `inventory.receipt.manage` untuk mutasi.
Warehouse/area/site scope berlaku pada source, inspeksi dan posisi barang kini.
Identitas tenant/actor berasal dari sesi; izin dimuat ulang di bawah fence IAM.

| Metode | Path | Input / hasil |
| --- | --- | --- |
| POST | collection | Draft pemasok;201, revision0, tanpa stok/claim |
| PUT | `/{id}` | Ganti draft dengan expectedRevision;200 dan revision+1 |
| GET | collection | Page receipt dalam scope |
| GET | `/{id}` | Snapshot intake, pieces, total inspected/putaway dan bukti keputusan |
| GET | `/{id}/history` | Operation/action/revision/waktu immutable berurutan |
| POST | `/{id}/receive` | `{expectedRevision}`; paired source OUT dan quarantine IN |
| POST | `/{id}/inspect` | `{expectedRevision,lines:[...]}`; keputusan exact per piece |
| POST | `/{id}/putaway` | `{expectedRevision,destinationLocationId,lines:[...]}` |
| POST | `/{id}/attachments` | Multipart `file` dan `expectedRevision`;201, revision+1 |
| GET | `/{id}/attachments/{evidenceId}` | Download privat melalui aplikasi |

Semua mutasi memakai `Idempotency-Key` ASCII tanpa whitespace, panjang1..240.
Transisi wajib expectedRevision integer JSON, bukan string/float/exponent/null.
Decoder menolak field unknown/duplicate, scalar coercion, enum angka dan trailing
JSON. Body JSON maksimal131072 karakter; draft maksimal100 input lines dan500
physical lines setelah ekspansi serial. Tidak menerima state, tenant, actor,
canonical identity, hash, approval atau total hasil inspeksi dari client.

Page default0/size25, maksimum100. `status`, `skuId`, serial kanonis exact,
`locationId` source/inspection, `from` inklusif dan `until` eksklusif adalah filter.
Sort `createdAt|externalReference`, direction `asc|desc`, secondary ID ascending.
Page negatif, size/sort/direction invalid dan rentang tanggal terbalik ditolak400.

## Draft dan snapshot

Body draft:

```json
{
  "supplierId": "<supplier UUID>",
  "externalReference": "SURAT-JALAN-001",
  "sourceLocationId": "<RECEIPT_SOURCE UUID>",
  "inspectionLocationId": "<QUARANTINE UUID>",
  "lines": [
    {
      "skuId": "<cable SKU UUID>",
      "quantityBase": "1000000",
      "lotCode": "REEL-001",
      "conversion": {"numerator": "1000000", "denominator": "1", "packageQuantity": "1"},
      "cost": {"totalMinor": "500000", "currency": "IDR"}
    },
    {"skuId": "<ONU SKU UUID>", "quantityBase": "2", "serials": [{"serial": "ONU-1"}, {"serial": "ONU-2", "mac": "AA:BB:CC:DD:EE:FF"}]}
  ]
}
```

Source adalah master ACTIVE bernama `RECEIPT_SOURCE`, kind TRANSIT; inspection
adalah ACTIVE QUARANTINE yang tidak issue-eligible. Source bukan saldo pemasok
negatif. SKU/supplier/lokasi harus aktif dan source/inspection harus dalam scope.
Master yang masih direferensikan tidak boleh diarsipkan; boundary terkini dicek
kembali sebelum transisi, bukan hanya dipercaya dari snapshot draft.

Quantity memakai string integer base unit SKU:1000000 MM tepat1000m, bukan count.
SERIAL hanya EA; jumlah serial unik harus sama dengan actual quantity. Set serial
dipecah menjadi physical origin lines1EA, dengan `inputLineNumber` asal dipertahankan.
Serial/MAC raw dipertahankan; codec common menentukan canonical claim tenant-wide
sebelum filter visibility. Reserved/conflict/retired claim menolak intake atomik,
termasuk seluruh line sebelumnya pada receipt yang sama.

LOT/BULK membutuhkan lotCode, MM membentuk measured reel. Ratio konversi positif
dan pembagian harus tepat; numerator/denominator asli tidak direduksi. Snapshot
conversion pada kelompok SERIAL tetap pada intake, bukan dianggap ratio per1EA.
Cost opsional menyimpan totalMinor/currency dan basis actual quantity positif.
Setiap serial/cut mempertahankan basis kelompok/lot asal untuk proporsi exact;
unknown bukan nol, harga SKU terkini tidak mengganti snapshot. GET cost memerlukan
`inventory.cost.view`; original mutation response tidak membawa cost.

## Inspeksi dan putaway

Inspection line: `lineId`, `stockIdentityId`, `baseUnit`, `acceptedBase`,
`rejectedBase`, `evidenceId`, `reason`, serta optional
`rejectedDisposition=QUARANTINE|SUPPLIER_RETURN`. Satu piece per origin line per
command; bagian lain dapat diproses lewat command/revisi berikutnya.

Total accepted+rejected harus positif dan tidak melampaui piece yang belum
diinspeksi. Partial MM/lot memecah parent atomik menjadi accepted/rejected/pending
children melalui posting task05. Parent SPLIT tidak spendable. Serial disposition
selalu tepat1EA. Keputusan, bukti dan linkage output tidak dapat diubah.
Accepted tetap QUARANTINE dan tidak tersedia sampai putaway.

Putaway line: `lineId`, `stockIdentityId`, `quantityBase`, `baseUnit`.
Destination wajib bin ACTIVE issue-eligible yang berada dalam scope terkini.
Piece harus milik ISP, custody inspection yang sama, condition/status QUARANTINE,
serta accepted atau snapshot SKU `inspectionRequired=false`. Rejected tidak boleh
memakai bypass. Partial putaway membuat cut+retained remainder dengan lineage sama.
State tetap RECEIVED_IN_INSPECTION selama ada bagian pending/accepted belum
ditempatkan; menjadi PUTAWAY setelah seluruh bagian non-rejected ditempatkan.
Rejected tetap quarantine/supplier-return; task08 tidak menjalankan retur pemasok.

## Bukti privat dan saldo awal

Attachment menerima PNG/JPEG/PDF1..15728640 byte dengan MIME dan signature yang
cocok. Metadata tenant/document/hash/type/size immutable; bytes diverifikasi pada
upload, inspection dan download. Response hanya ID, bukan key, secret atau URL
publik. Download memakai attachment disposition, no-store dan nosniff. Rollback
upload membersihkan objek; SIGKILL di celah S3/DB dapat menyisakan orphan privat
yang tidak pernah menjadi evidence sah. Tidak ada kebijakan purge baru task08.

Route terpisah `POST /api/v1/warehouse/opening-balances/requests` menerima multipart
`request` JSON `{migrationReference,sourceSnapshot,cutoff}` dan `file` bukti.
Cutoff wajib string ISO instant, tidak boleh future. Izin provenance.manage,
cutover control-plane dan bukti MIME/size diperiksa terlebih dahulu. Karena
approval durable task12 belum tersedia, input sah selalu409
INDEPENDENT_APPROVER_REQUIRED. Tidak menyimpan receipt pemasok palsu, approval,
identitas, stok ataupun draft opening yang mengaku disetujui.

## Transaksi dan verifikasi

Controller memasuki WarehouseCommandService; physical legs/splits/balance/custody
hanya melalui WarehousePostingService. Draft, attachment dan inspeksi tanpa split
menyimpan operation/revisi tanpa movement palsu. Payload/key berbeda409; replay
memeriksa actor asli, current authority/scope dan epoch sebelum original body.
Semua efek receive rollback bersama saat identitas collision atau transaksi putus.
Legacy `/api/inventory` read tetap array/count; registry internal lama bukan receipt
API dan tidak dipakai untuk admission baru.

M02 V174.12 ditambahkan manifest-first. V173-V174.11 tetap byte-identical; V175+
tidak dipakai. Tiga tabel receipt memakai composite tenant references dan FORCE RLS.

- Failing-first: draft404, receive404, inspection404 dan attachment404 teramati
  sebelum handler ditambahkan; legacy read compatibility lulus sejak baseline.
- Exact `scripts/warehouse/qa.sh server --tests '*WarehouseReceiptIT*' --rerun-tasks --no-parallel`
  dua kali:20 test,0 gagal,0 skipped per run.
- Gabungan Warehouse/Inventory/NetworkEndToEnd/Modularity:546 test,0 gagal/skipped.
- Clean `:server:clean :server:bootJar --no-build-cache --rerun-tasks --no-parallel` sukses.
- Real child JVM: lost HTTP response lalu SIGKILL/restart mengembalikan original
  receipt dengan satu posting; killed transaction sebelum commit meninggalkan
  nol identity/stock/operation, retry menghasilkan satu posting.
- Packaged JAR lewat HTTP dan private MinIO:1000000MM+10EA diterima; available
  akhir900000MM+8EA, quarantine100000MM+2EA,3 posting/11 inspection/12 disposition.
  Cost lot500000/1000000 IDR tetap; anonymous object403 dan proxy download200.
- Guard zero-match gagal nyata, bukan sukses0 test. Probe schema/trigger/function
  dan child connections akhir0. Evidence ada di `.omo/evidence/warehouse-workorder-asset-provenance/task-8/`, tidak di git.

Probe runtime telah dihapus; `qa.sh stop` dan `test-environment.sh down` hanya
menghapus proses/container/network milik task, volume tetap dipertahankan.
Compiler/Spring/PostgreSQL dipakai sebagai gate lane ini, bukan LSP/CodeGraph.

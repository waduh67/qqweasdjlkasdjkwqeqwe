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
Completion dihitung pada inspeksi maupun putaway, lalu diperiksa kembali terhadap
disposition dan ledger durable sebelum commit. Jika semua barang ditolak tanpa
putaway, state menjadi CLOSED. Inspeksi terakhir dapat mengakhiri receipt tanpa
movement tambahan atau permintaan putaway kosong.
Rejected tetap quarantine/supplier-return; task08 tidak menjalankan retur pemasok.

## Bukti privat dan saldo awal

Attachment menerima PNG/JPEG/PDF1..15728640 byte yang benar-benar dapat diparse.
PDFBox3.0.8 (Apache License2.0) berjalan non-lenient: header/end framing, xref,
trailer, objects dan content stream diperiksa, tanpa rendering atau eksekusi.
Keberhasilan load PDFBox saja tidak cukup: lexer independen memeriksa seluruh
rentang file, objek langsung/tidak langsung dan grammar instruksi content stream.
PDF terenkripsi, form interaktif/JavaScript/action/embedded-file dan konstruksi aktif lain
ditolak. Batas100 halaman,50000 xref,100000 objects/tokens,16MiB content per halaman
dan64MiB content total mencegah input tak berbatas. Gambar didecode ImageIO dengan
batas25 juta pixel/10000 per dimensi, CRC/chunk PNG dan marker/end JPEG lengkap;
truncation, trailing payload dan warning decoder ditolak. Error parser selalu
400 MALFORMED_REQUEST tanpa detail internal, termasuk pada bukti saldo awal.

Metadata tenant/document/hash/type/size immutable; bytes diverifikasi pada upload,
inspection dan download. V174.13 menambahkan content revision dan SHA256 snapshot
intake, terpisah dari revision dokumen. Attachment merekam binding konten ini;
replace draft mengubah binding, sedangkan receive/attachment/inspection tidak.
Bukti lama setelah replace gagal409 dan tidak membuat disposition. Bukti valid
dapat dipakai ulang pada beberapa inspeksi tanpa stale karena revision operation.
Evidence historis sebelum binding tersedia tidak ditebak dari intake terbaru:
tetap tersimpan dengan binding null, tidak boleh mengotorisasi inspeksi baru.

Response hanya ID, bukan key, secret atau URL publik. Download memakai attachment
disposition, no-store dan nosniff. Setelah rollback atau completion UNKNOWN,
reconciler membuka transaksi REQUIRES_NEW dengan tenant yang sama. Lock dokumen
menunggu transaksi asal settle sebelum memeriksa metadata durable. Hanya absence
yang terkonfirmasi menghapus objek. Metadata committed mempertahankan objek;
DB/lock tak dapat diperiksa mempertahankan objek privat dan mencatat warning
reconciliation dengan evidenceId/reason, tanpa key/payload/secret. SIGKILL seluruh
JVM sebelum callback tetap membutuhkan rekonsiliasi orphan operasional; bukan izin
blind-delete. Tidak ada kebijakan purge atau approval baru task08.

MultipartException termasuk MaxUploadSizeExceededException ditangani sebelum
pemilihan controller khusus prefix warehouse. Batas service15MiB maupun container
20MiB menghasilkan envelope400 `{code,message}` yang sama, bukan ProblemDetail.

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

M02 V174.12 dan koreksiV174.13 ditambahkan manifest-first. V173-V174.12 tetap
byte-identical pada koreksi ini; V175+
tidak dipakai. Tiga tabel receipt memakai composite tenant references dan FORCE RLS.

Bukti implementasi awal sebelum koreksi AV8:

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

## Bukti koreksi AV8

- Failing-first: stale intake proof200 menjadi409/no inspection; tiga completion
  failures menjadi terminal;11 malformed file cases yang sebelumnya201 menjadi400;
  backend termination orphan dan21MiB ProblemDetail berhasil direproduksi sebelum fix.
- Exact WarehouseReceiptIT dengan `--rerun-tasks --no-parallel` dua kali:
  **47 test,0 gagal,0 skipped** per run. Termasuk child-JVM SIGKILL/restart,
  normalized serial races, real MinIO, actual servlet upload dan fault PostgreSQL.
- Gabungan Warehouse/Inventory/NetworkEndToEnd/Modularity:
  **574 test,0 gagal,0 skipped**. WarehouseSchemaIT mencakup ketiga tabel receipt;
  upgrade sampai174.13, termasuk preservation evidence174.12 tanpa binding palsu.
- Clean no-cache bootJar sukses; artefak membawa PDFBox3.0.8, V174.13 dan resolver/
  reconciler produksi, tidak membawa test-only restart launcher.
- Packaged HTTP/MinIO/PostgreSQL: main receipt tetap available900000MM+8EA,
  quarantine100000MM+2EA dan cost500000/1000000 IDR. Stale proof409/no disposition;
  final reject600/400/600 menjadi PUTAWAY revision6; semua reject CLOSED revision3.
- Valid PDF434 byte didownload identik secara privat (proxy200, anonymousS3403).
  Fake PDF opening400, valid opening409.15MiB+1/21MiB mendapat code/message400.
- Terminate backend setelah object put: HTTP500, metadata/operation/revision0/0/0,
  object0 setelah fresh confirmation. Lost response sesudah commit mempertahankan
  object/metadata dan exact replay; unsettled row lock mempertahankan object dan
  warning METADATA_UNAVAILABLE hingga absence dapat dikonfirmasi.

Evidence koreksi tersimpan di `.omo/evidence/warehouse-workorder-asset-provenance/task-8/corrections/`
dan tidak ikut commit. No-cache JAR SHA256:
`12e3a9db9ec40ea36ebb162e7961b9abaa2beda31ef5afe122d63e9108224750`.

## Kelengkapan sintaks PDF AV8-3

Koreksi lanjutan menutup dua kasus yang masih lolos pada19e0de8c: HTML mentah
di antara objek dan xref dengan startxref yang disesuaikan, serta keyword/operand
tidak sah di content stream. Tidak ada blacklist substring HTML/script dan tidak
ada sanitasi/rewrite bytes bukti.

- `PdfLexicalCursor` memerlukan string/hex/array/dictionary lengkap, escaping PDF
  sah, angka tanpa koersi permisif dan batas nesting. Teks literal seperti
  `(<html><script>...</script></html>) Tj` tetap sah di dalam text object.
- `PdfFileTopology` berjalan sekuensial atas bytes asli. Header, span indirect
  objects, tabel/stream xref, trailer, startxref dan EOF harus mencakup file;
  hanya whitespace/comment PDF diperbolehkan di antaranya. Panjang stream eksplisit
  atau referensi integer menentukan span binary, bukan pencarian substring endobj.
- Entry xref harus menunjuk header dengan object number/generation yang sama;
  object stream members, offset/index, Size, sentinel dan rantai Prev/hybrid diperiksa.
  Span tak terdaftar, overlap, startxref salah dan footer duplikat ditolak.
  Classic xref, compressed object/xref streams dan incremental update diuji positif.
- `PdfOperatorSyntax` mencakup seluruh keyword standar pada PDFBox OperatorName,
  dengan arity/type operand, warna, path/text/graphics/marked-content state dan
  batas nesting. Unknown operator hanya diabaikan di dalam BX/EX yang sah;
  known operator tetap divalidasi. Operand sisa atau struktur tak tertutup ditolak.
- Inline image dictionary/data/EI dan sample/pixel budget divalidasi. Page content,
  Form XObject, tiling pattern dan Type3 glyph diperiksa tanpa rendering. Resource
  lookup menolak XObject tak bertipe dan mempertahankan inheritance form yang sah.
- Semua resource limit, larangan active content, MIME/size, binding intake, replay,
  lifecycle dan cleanup transaksi dari koreksi sebelumnya tetap berlaku. Tidak ada
  perubahan dependency atau migration pada koreksi sintaks ini.

Bukti terbaru:

- Fixture raw-gap474 byte/startxref311 tetap SHA256
  `0d1beb189093fb5f730111816aae48930920eb62f570f90ea1df72017c457b84`.
  Before: upload201, inspection200, opening409. After: ketiganya400
  MALFORMED_REQUEST. Upload menghasilkan metadata/revision0/0; revalidasi bukti
  historis menghasilkan inspection/disposition0/0 dan tidak menaikkan revision.
- Content-stream HTML fixture SHA256
  `cc6718a94ce883662172fef79be9ef1b9cf1a48c8372096f842c84f4f9ec0948`
  ditolak400 di upload/opening/inspection/download. Bukti lama yang malformed
  tidak dapat melewati revalidasi hanya karena metadata/hash-nya cocok.
- 12 fixture positif: empty, text, literal HTML, vector, comments, inline image,
  CRLF inline image, Flate inline image, compatibility, form/image, inherited form,
  incremental PDF. Seluruhnya upload201/download200 byte-identical; opening409.
- Exact WarehouseReceiptIT dua kali: **65 test,0 gagal,0 skipped** setiap run.
  Parser-focused gate:70 unit +18 HTTP =88 lulus. Gabungan tasks1-8/schema/network/
  Modularity: **662 test,0 gagal,0 skipped**. Clean no-cache bootJar sukses.
- Packaged HTTP + private MinIO + warehouse_app/PostgreSQL mengulang kedua fixture,
  matrix positif dan seluruh AV8 probe. Main stock tetap available900000MM+8EA,
  quarantine100000MM+2EA, cost500000/1000000 IDR. No public upload atau script execution.

Artefak terbaru SHA256:
`9972da33fbaa156c2807fbd829a1a6cc31fb896dd063f1fad0fdd87b28fc7f69`.
Evidence dan fixture bytes berada di
`.omo/evidence/warehouse-workorder-asset-provenance/task-8/pdf-syntax/` (tidak di git).
Fixture/probe runtime telah dihapus; schema/trigger/function/child connection
sementara0/0/0/0. Container/network milik task dihentikan, volume dipertahankan.

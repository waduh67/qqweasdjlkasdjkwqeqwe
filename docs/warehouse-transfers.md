# Transfer gudang

Transfer memindahkan barang melalui custody transit milik dokumen. Dispatch
mengurangi posisi asal dan menambah transit; penerimaan baru menambah posisi
tujuan. Draft tidak memindahkan atau mereservasi stok. Setiap perpindahan memakai
posting, pasangan leg dan riwayat operasi warehouse yang sama.

## Memilih posisi asal

`POST /api/v1/warehouse/transfers` menerima `sourceLocationId`,
`destinationLocationId`, `transitLocationId`, `receiverId`, `reason` dan `lines`.
Setiap line berisi `stockIdentityId`, `quantityBase` sebagai string integer,
`baseUnit` (`EA` atau `MM`), serta `sourceBalanceId` opsional.

Ambil `sourceBalanceId` dari `id` hasil
`GET /api/v1/warehouse/stock/positions`. ID ini harus cocok dengan tenant,
identitas barang dan lokasi asal. Jika satu barang memiliki beberapa posisi,
pilih posisi yang dimaksud secara eksplisit. Contohnya, stock opname dapat
menyisakan80 unit AVAILABLE dan20 unit LOST di lokasi yang sama; pemilihan80 unit
tersedia tidak boleh ikut memindahkan20 unit hilang. Seleksi ambigu tanpa ID
posisi ditolak409 `SOURCE_NOT_VERIFIED`.

Request lama yang tidak menyertakan `sourceBalanceId` tetap dapat memilih posisi
tunggal. Field yang tidak diberikan tidak ditambahkan sebagai null ke payload
kanonis, sehingga hash replay lama tetap sama. ID posisi tidak memberi izin:
scope dan hak akses terkini tetap diperiksa sebelum mengambil stok atau replay.

## Dispatch dan penerimaan

- `POST /transfers/{id}/dispatch` menerima `expectedRevision`.
- `POST /transfers/{id}/receive` menerima `expectedRevision`, `evidenceReference`
  dan `lines` berisi `lineId`, `quantityBase`, `baseUnit`.
- `GET /transfers/{id}` dan `/history` memperlihatkan jumlah dikirim, diterima,
  masih transit dan diselesaikan lewat penanganan selisih.

Semua path di atas memakai prefix `/api/v1/warehouse`. Mutasi membutuhkan
`Idempotency-Key`; revisi atau payload berbeda dengan kunci lama ditolak.
Dispatch hanya oleh pengirim, penerimaan hanya oleh penerima yang terikat.
Keduanya harus masih mempunyai izin dan scope lokasi yang relevan. Pembatalan
setelah dispatch dan penerimaan melebihi sisa ditolak tanpa perubahan stok.

Untuk barang bulk, beberapa transfer boleh memakai identitas barang dan lokasi
transit yang sama. Penerimaan dan penyelesaian selisih memilih dimensi yang
terikat pada ID dokumen, custodian, kondisi dan pemiliknya. Saldo pengiriman lain
tidak digunakan sebagai pengganti. Potongan kabel tetap memakai identitas fisik
hasil split; perangkat berserial mempertahankan assetId dan asal biayanya.

## Selisih penerimaan

`POST /transfers/{id}/discrepancy` menerima `expectedRevision`, `action`
(`LOST` atau `REJECTED`), `destinationLocationId`, `reason` dan
`evidenceReference`. Pelaporan ini belum memindahkan sisa transit. Respons
menyertakan `resolutionDocumentId` untuk permintaan approval ADJUSTMENT melalui
API approval yang ada.

Pengirim, penerima dan delegasi mereka tidak dapat menjadi pemberi persetujuan
independen atas sisa tersebut. Efek approval memindahkan jumlah sisa yang tepat
ke LOST atau QUARANTINE dengan title yang sama. Barang ditolak tetap tidak
tersedia meski kemudian ditransfer lagi ke lokasi yang boleh mengeluarkan stok.

Alokasi WO yang sudah di-issue harus memakai alur handover/residual WO dengan
tautan kewajibannya. Transfer umum tidak menghapus tanggung jawab teknisi.

## Verifikasi

Jalankan suite `WarehouseTransferIT*` dan `WarehouseCountIT` pada lingkungan
terisolasi. `qa.sh wave5` menjalankan penerimaan parsial, selisih independen,
interaksi stock opname/transfer dan replay setelah restart melalui HTTP nyata.
Lihat `warehouse-wave5-verification.md` untuk prosedur dan lokasi hasil.

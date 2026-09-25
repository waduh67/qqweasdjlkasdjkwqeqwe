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

## Mengubah draft tersimpan

`PUT /api/v1/warehouse/transfers/{id}` menerima `{expectedRevision,draft}`;
`draft` memakai bentuk lengkap request create. Hanya pengirim dengan izin dan
cakupan terkini yang boleh mengubah draft sebelum dispatch. Lokasi asal, tujuan,
transit, penerima, alasan dan seluruh daftar barang diganti secara atomik pada
revisi berikutnya. Server memeriksa kembali posisi dan ketersediaan barang.
Perubahan draft tidak membuat pergerakan, reservasi atau penyesuaian saldo.

Layar detail menyediakan **Ubah draft transfer**. Draft dengan penerima nonaktif
tetap terlihat dalam cakupan pemiliknya agar penerima dapat diganti; dispatch dan
penyimpanan tetap membutuhkan penerima aktif yang cocok dengan tujuan. Form
memuat posisi tersimpan secara eksplisit. Draft lama tanpa ID posisi meminta
pemilihan kembali, bukan menebak posisi yang mungkin sudah berubah.

Kunci edit yang sama mengembalikan respons awalnya, termasuk setelah dispatch.
Kunci berbeda dengan revisi lama menghasilkan409 dan layar meminta muat ulang.
Riwayat create/edit lama tetap utuh dan setiap snapshot disaring berdasarkan
lokasinya sendiri sebelum paginasi. Detail dibaca di bawah fence topologi yang
sama dengan edit sehingga referensi barang tidak tercampur dengan revisi lain.
Transfer yang sudah dikirim tidak dapat diedit.

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

## Daftar dan referensi tampilan

`GET /api/v1/warehouse/transfers` menerima `page` (mulai0), `size` (1–100),
`state`, `locationId`, `skuId`, `serial`, `from`, `until`, dan `query` (pencarian
kode literal, maksimal200 karakter). SKU dan serial harus cocok pada baris barang
yang sama. Serial dicocokkan tepat setelah kanonisasi, bukan pencarian awalan.
Lokasi mencakup asal, transit, tujuan, dan tujuan penanganan selisih. Tanggal
membatasi waktu pembuatan transfer, bukan waktu penerimaan terakhir: `from`
inklusif, `until` eksklusif, wajib berpasangan dan maksimal366 hari. Parameter
kosong, berulang, tidak dikenal, atau tidak valid menghasilkan400. Filter layar
memakai tanggal lokal operator dan kembali ke halaman pertama saat diterapkan.
Hasilnya halaman `{items,page,size,totalElements}`. Setiap item berisi
`{transfer,references}`; bentuk `transfer` sama dengan respons operasi lama.
`GET /transfers/{id}/details` mengembalikan bentuk yang sama untuk satu dokumen.

`GET /transfers/{id}/history/page?page=0&size=25` mengembalikan halaman operasi
asli, revisi terbaru lebih dahulu, dengan total hasil yang dihitung server.
Endpoint lama `/history` mempertahankan respons array dan urutan revisi menaik;
kini juga menerima `page`/`size`, default25 dan maksimal100, sehingga pembacaan
riwayat tidak mengambil seluruh dokumen tanpa batas.

`references` memuat nama lokasi, pengirim/penerima, SKU dan serial/lot dari data
saat ini. Nama ini bukan snapshot historis. Respons mutasi, GET lama dan riwayat
operasi tetap memakai kontrak aslinya; mengganti nama SKU tidak mengubah balasan
tersimpan atau memengaruhi kecocokan stok pada dispatch.

Daftar membatasi lokasi asal, transit, tujuan, dan tujuan penanganan selisih
sebelum menghitung hasil atau mengambil halaman. Untuk transfer yang sudah
dikirim, penerima harus masih aktif dan tujuan teknisi/vehicle harus masih cocok
dengan penerima. Draft tetap dapat dibaca untuk memperbaiki penerima. Detail dan riwayat
juga memeriksa cakupan tujuan penanganan selisih. Izin `inventory.transfer.view`
mencukupi untuk membaca referensi dokumen; hasil tidak menyertakan biaya atau
profil IAM selain ID, nama dan status aktif yang diperlukan untuk memilih penerima.

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

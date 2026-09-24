# Permintaan dan pengeluaran material

Buka **Gudang & Logistik → Permintaan & Pengeluaran** untuk mencari work order
berdasarkan kode atau judul. Daftar dan seluruh transaksi mengikuti izin, area,
serta cakupan gudang akun yang sedang masuk.

## Persiapan

1. Buat barang, pemasok, gudang/bin, karantina, dan batas penerimaan di katalog.
   Catat penerimaan dan penempatan agar barang tersedia di bin.
2. Buat WO pada halaman **Work Order**, pilih **Area pekerjaan**, dan tugaskan
   teknisi aktif. Area harus berada dalam cakupan akun.
3. Pada detail permintaan, buka **Persiapan pengeluaran**. Gunakan lokasi transit
   WO yang sudah tersedia, atau **Siapkan transit WO** untuk membuat lokasi
   TRANSIT aktif berkode `WO_TRANSIT`. Pengelola akses memberikan cakupan bin
   asal dan transit kepada petugas terkait.

## Rencana dan permintaan

**Susun rencana material** memilih nama barang dan jumlah kebutuhan. Kabel diisi
meter, paling banyak tiga angka desimal; perangkat diisi jumlah unit bulat.
**Harus satu potongan utuh** mencegah pemenuhan satu kebutuhan dari gabungan
potongan kabel. **Salin template** menyalin baris yang ditampilkan untuk ditinjau
sebagai rencana eksplisit.

Pekerjaan yang tidak membutuhkan material memakai pilihan **Tanpa material** dan
alasan. Simpan lalu ajukan rencana tersebut. Rencana kosong yang belum disusun
bukan deklarasi tanpa material.

Simpan rencana, kemudian **Ajukan permintaan**. Menyimpan maupun mengajukan
rencana belum memindahkan stok. Revisi rencana tetap dapat dibaca pada riwayat.
Mengganti SKU pada baris rencana yang sudah ada memerlukan izin substitusi,
alasan, dan barang dengan pelacakan serta satuan yang kompatibel. Rencana dengan
reservasi atau barang yang sudah dikirim terkunci: batalkan persiapan dan lepas
reservasi terlebih dahulu; pengiriman yang sudah terjadi memerlukan alur koreksi.

## Reservasi dan kekurangan

**Cadangkan otomatis** mencoba memenuhi sisa kebutuhan menurut FIFO. Dialog
menampilkan sisa yang hendak dicadangkan. Bila stok kurang, jumlah yang belum
terpenuhi tetap terlihat sebagai **Kekurangan**.

**Reservasi sebagian / pilih stok** memilih baris dan jumlah tertentu, misalnya
60 m dari permintaan100 m. Pilihan serial atau potongan tertentu membutuhkan
izin override dan alasan; kosongkan identitas untuk FIFO. Jumlah fisik tetap
sama saat reservasi dibuat.

**Lepas reservasi** mengembalikan bagian yang belum disiapkan menjadi tersedia
untuk permintaan lain. Barang yang terikat slip aktif harus melalui **Batal
siapkan** pada slip terlebih dahulu.

## Siapkan, kirim, dan cetak

1. **Siapkan barang** memilih serial atau potongan yang dicadangkan, lokasi,
   dan jumlahnya. Pindai serial bila digunakan; hasil pindai harus cocok dengan
   unit yang dipilih. Enter pada kolom pindai tidak mengirim transaksi.
2. Tinjau dan konfirmasi persiapan. Kabel dapat dipotong saat langkah ini.
   Barang tetap berada di gudang. Slip mencatat nama pengirim, penerima,
   barang, jumlah, identitas, dan revisi rencana.
3. Buka slip pada daftar tersimpan. Periksa nama penerima, isi catatan, lalu
   centang konfirmasi penerima. Bila pengiriman belum memenuhi sisa kebutuhan,
   konfirmasi **Kirim sebagian** juga wajib.
4. **Kirim barang** memindahkan barang dari gudang ke transit WO. Jumlah diterima
   teknisi belum bertambah. Lihat **Stok & Perangkat → Dalam transit** untuk
   saldo fisik transit.
5. **Cetak slip** membaca ulang slip dengan izin terkini sebelum membuka dialog
   cetak browser. Revisi slip adalah revisi kejadian yang dicatat; revisi dokumen
   dapat lebih baru setelah penerimaan teknisi.

**Batal siapkan** membatalkan seluruh persiapan pada slip. Reservasi kembali
belum disiapkan, sedangkan potongan kabel fisik tetap terpisah. Menyiapkan ulang
membuat slip baru; slip pembatalan tetap ditemukan setelah memuat ulang halaman.

Daftar slip menampilkan jumlah disiapkan, dikirim, dan benar-benar dikonfirmasi
diterima per barang. Jumlah dikirim dikurangi diterima bukan selalu saldo transit:
penanganan kehilangan atau pengecualian dapat menyelesaikan barang yang tidak
pernah diterima. Gunakan halaman stok untuk saldo fisik dan riwayat untuk
kejadian bisnisnya.

## Izin dan perubahan bersamaan

Workbench gudang memerlukan izin lihat permintaan dan lihat WO. Perencanaan serta
pengajuan memerlukan kelola permintaan dan izin ubah/penugasan WO. Pemilihan barang
memerlukan izin lihat SKU. Persiapan/pengiriman memerlukan kelola pengeluaran dan
kelola permintaan; UI juga memerlukan izin baca slip untuk meninjau hasilnya.
Slip substitusi memerlukan izin override. Petugas gudang dapat melakukan picking
atas WO yang dapat dibaca tanpa diberikan izin mengubah WO.

Jika alokasi atau revisi berubah, **Muat ulang dokumen** mengambil keadaan baru
untuk ditinjau. Pilihan lama tidak otomatis dikirim ulang dengan revisi tebakan.
Jika koneksi terputus dan hasil transaksi belum diketahui, **Coba transaksi yang
sama** menggunakan isi dan referensi transaksi yang sama sampai hasilnya jelas.

Kontrak teknis: [rencana material](work-order-materials.md),
[reservasi](warehouse-reservations.md), [slip pengeluaran](warehouse-issues.md),
[penerimaan teknisi](warehouse-material-receipts.md).

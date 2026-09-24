# Stock opname

Buka **Gudang & Logistik → Stock Opname**. Halaman membutuhkan
`inventory.count.view`; membuat dan mencatat membutuhkan `inventory.count.manage`.
Pemilihan lokasi baru juga membutuhkan `inventory.location.view`.
Daftar hanya memuat dokumen yang dibuat sendiri atau menugaskan pengguna sebagai
penghitung, dalam cakupan lokasi saat ini. Filter kode, status, lokasi, SKU, serial
lengkap dan tanggal diterapkan sebelum paginasi.

1. Pilih satu lokasi, alasan, posisi barang dan penghitung aktif untuk setiap posisi.
   Satu posisi hanya boleh muncul sekali; maksimal 100 posisi. Dokumen selalu
   mencakup sebagian lokasi sesuai posisi yang dipilih.
2. Pembuat menyimpan draft lalu **Mulai penghitungan**. Draft belum mengubah stok.
3. Setiap penghitung mencatat hasil fisik posisi yang ditugaskan, keterangan, dan
   referensi lembar hitung. Meter menerima maksimal tiga desimal; unit berupa
   bilangan bulat. Nol diperbolehkan. Tidak ada jumlah awal yang diisi otomatis.
4. Hasil yang disimpan menjadi catatan tetap per posisi dan putaran. Penghitung
   hanya melihat hasilnya sendiri; pembuat melihat seluruh hasil dokumennya.
5. Setelah semua posisi dicatat, pembuat **Ajukan hasil hitung**. Hasil tanpa
   selisih langsung dibukukan tanpa pergerakan stok. Selisih menunggu persetujuan
   independen melalui tautan dokumen persetujuan.
6. Bila stok berubah selama hitung, respons `COUNT_STALE` mewajibkan **Muat ulang
   dokumen**, lalu pembuat **Mulai hitung ulang**. Seluruh posisi dihitung lagi;
   hasil putaran lama tetap tersimpan. Tidak ada penimpaan hasil atau percobaan
   otomatis dengan revisi baru.

Angka stok buku hanya tersedia bagi `inventory.approval.view` setelah dokumen
`SUBMITTED`, `APPROVED`, atau `POSTED`, melalui tombol perbandingan. Pilihan posisi
dan detail untuk penghitung tidak membawa angka fisik, kapasitas, cadangan, biaya,
atau jumlah pembanding di respons HTTP. Alur ini tidak memanggil halaman stok/lot.

Semua aksi ditinjau sebelum dikirim. Jika koneksi terputus sesudah pengiriman,
**Coba transaksi yang sama** mempertahankan isi dan kunci transaksi. Konflik yang
diketahui memerlukan muat ulang dan peninjauan ulang.

Pembacaan baru pada `/api/v1/warehouse/counts`:

- `GET /workbench`: daftar bernama, filter `page,size,state,locationId,skuId,serial,query,from,until`.
- `GET /positions`: pilihan tanpa jumlah; `locationId` wajib, dengan `page,size,skuId,serial,query`.
- `GET /locations/{locationId}/counters`: penghitung aktif yang memiliki izin dan cakupan, `page,size,query`.
- `GET /{id}/details`: identitas posisi dan nama petugas tanpa jumlah pembanding.
- `GET /{id}/review/details`: perbandingan pengajuan, khusus izin persetujuan.
- `GET /{id}/history/page`: hasil tetap terbaru, pembatasan penghitung sebelum jumlah total/paginasi.

Halaman default 25, maksimal 100; filter tidak dikenal, berulang atau kosong ditolak.
Tanggal awal/akhir harus diisi bersama, berurutan, maksimal 366 hari. Semua respons
baru memakai `Cache-Control: no-store`. Riwayat lama `GET /{id}/history` tetap berupa
array urutan awal, kini dibatasi default 25/maksimal 100. Kontrak mutasi tetap sama.

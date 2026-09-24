# Verifikasi transfer dan stock opname

`scripts/warehouse/qa.sh wave5` membangun JAR server bersih lalu menjalankan dua
JVM secara berurutan di lingkungan warehouse lokal. Jalankan `test-environment.sh
up` lebih dahulu dan pegang host QA lock sepanjang up, verifikasi, stop, dan down
sesuai `docs/warehouse-migrations.md`. Tidak ada argumen tambahan untuk mode ini.

Skenario dimulai dari signup tenant publik dan membuat area, pengguna, hak akses,
lokasi, supplier, SKU serta stok lewat API penerimaan/putaway. Tidak ada SQL seed
stok. Tenant uji memakai nama acak dan tetap berada di volume QA yang dimiliki
runner. Akun uji ini hanya ditujukan untuk lingkungan lokal terisolasi.

- Transfer100.000m menerima60.000m, mempertahankan40.000m transit, lalu menerima
  sisanya. Overreceipt dan pembatalan setelah dispatch ditolak.
- Transfer terpisah menyelesaikan sisa hilang40.000m lewat persetujuan independen;
  sisa itu tidak tersedia untuk dikeluarkan lagi.
- Stock opname perangkat berserial dan barang bulk tidak menampilkan jumlah
  buku kepada petugas penghitung. Jumlah yang sama tidak menciptakan pergerakan.
- Selisih100→80 unit memerlukan persetujuan independen. Transfer10 unit setelah
  pengamatan membuat approval berikutnya basi; recount menyimpan pengamatan lama.
- JVM baru melakukan login ulang, memainkan ulang respons operasi yang asli,
  menolak payload berbeda dengan kunci sama, lalu membandingkan seluruh snapshot
  stok, dokumen dan riwayat yang dicatat sebelum restart.

Runner menolak port yang sedang dipakai, menunggu health database/disk/ping,
dan menghentikan hanya JVM miliknya. Hasil per fase, log server dan SHA256 JAR
ada di `.omo/runtime/wave5-http/`. State replay privat dihapus saat cleanup;
token login tidak disimpan. Output dan runtime tidak boleh di-commit. Catatan
hasil verifikasi yang sudah benar-benar dijalankan ada di notepad continuation.

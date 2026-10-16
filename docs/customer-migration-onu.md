# ONU pelanggan migrasi

Pelanggan yang sudah terpasang dapat membawa nomor seri ONU saat migrasi, atau dihubungkan setelah data pelanggan dibuat.

1. Pastikan ONU muncul dalam hasil deteksi OLT.
2. Buka **Pelanggan → pelanggan migrasi → Perangkat → Hubungkan ONU terpasang**.
3. Cari nomor seri, OLT, atau PON. Pilih perangkat yang digunakan pelanggan.
4. Tinjau identitas perangkat, lalu pilih **Hubungkan**.

Form migrasi manual menyediakan langkah **Perangkat** yang opsional. CSV menerima kolom opsional `onu_serial`; nilainya harus sama dengan nomor seri pada hasil deteksi, termasuk jika identitas yang dilaporkan OLT berbeda dari label vendor. Pratinjau CSV menampilkan nomor seri tersebut. Pelanggan yang sudah diimpor dapat dihubungkan dari detailnya tanpa mengimpor ulang.

Pengaitan ini mencatat hubungan pemantauan untuk layanan yang sudah ada. Tidak ada PSB baru, work order pemasangan, penerimaan atau pengeluaran stok, perubahan RADIUS, maupun tanggal pemasangan yang dikarang. Riwayat aset gudang tetap terpisah. Kepemilikan dan asal barang belum dinyatakan terverifikasi.

## Kontrak implementasi

- `POST /api/onboarding/migrations/{customerId}/onus` menerima `serialNumber`. Pengulangan pasangan pelanggan/serial mengembalikan hubungan yang sama.
- Migrasi manual dan CSV dapat menyertakan `onuSerial` / `onu_serial`. Pembuatan pelanggan dan pengaitan ONU berlangsung atomik per baris.
- Pengaitan memerlukan `customer.onu.assign`, `monitoring.provisioning.view`, serta akses area pelanggan. Izin CSV diperiksa saat unggah dan commit.
- Episode ONU berstatus admission `MIGRATED`, tanpa aset atau assignment gudang. Fungsi database khusus menyimpan sumber deteksi dan mencadangkan identitas; jalur admission gudang umum tetap dilindungi.
- Nomor seri yang sudah diklaim pelanggan atau gudang ditolak. Pengaitan tidak memindahkan kepemilikan perangkat yang sudah tercatat.
- Pemantauan dimulai saat hubungan dicatat. Sampel sebelumnya tidak diatribusikan secara retrospektif. OLT/PON harus cocok dengan sumber yang dipilih sampai topologi ODP dicatat. Data ACS juga harus berasal dari periode hubungan ini.
- Test utama: `CustomerMigrationOnuIT`, `CustomerMigrationIT`, `CustomerCsvBatchTest`, `CustomerOnuPanel.test.tsx`, dan `customer-migration.spec.ts` (Chromium/Firefox, desktop/mobile).

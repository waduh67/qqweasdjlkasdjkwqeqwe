# Migrasi pelanggan dan akun RADIUS

Di **Pelanggan → Migrasi pelanggan**, pilih cakupan:

- **Pelanggan + akun RADIUS**: pilihan awal untuk pengguna yang dapat mengelola akun jaringan. Pelanggan, langganan, akun jaringan, dan kaitan ONU jika diisi disimpan bersama per pelanggan. Jika salah satu gagal, seluruh perubahan untuk pelanggan tersebut dibatalkan.
- **Data pelanggan saja**: mencatat layanan yang sudah ada. Akun RADIUS dapat disiapkan kemudian dari detail pelanggan menggunakan kredensial yang tersimpan.

Keduanya tersedia untuk input manual, CSV, dan pengambilan akun MikroTik. Impor tidak membuat work order pemasangan. Status ditangguhkan tetap dipertahankan, termasuk pada akun RADIUS. Tanggal penagihan mengikuti pilihan operator dan nilai setiap baris CSV.

## CSV

Template dan ekspor memakai `username` dan `password`. File lama dengan `mikrotik_username` dan `mikrotik_password` masih diterima. Gunakan satu nama kolom untuk setiap nilai; menggabungkan nama baru dan alias lama dalam satu file ditolak agar kredensial tidak tertukar.

Untuk akun RADIUS, isi `username`, `router_name`, dan `package_name`; PPPoE/Hotspot juga memerlukan `password`. Nilainya adalah kredensial pelanggan yang sudah digunakan. Koneksi statis membutuhkan `framed_ip`. Jenis koneksi harus tersedia pada paket. Pratinjau menampilkan username dan BRAS, tanpa password. Ekspor pelanggan selalu mengosongkan password.

CSV diproses di latar belakang setelah ditinjau. Baris gagal tidak membatalkan baris lain yang berhasil. Membuka kembali URL impor menampilkan cakupan dan hasil yang tersimpan. Mengulangi permintaan yang sama tidak membuat akun kedua. Impor ulang data pelanggan yang sudah terdaftar tidak mengubahnya menjadi migrasi lengkap; gunakan **Siapkan RADIUS** pada pelanggan tersebut.

## Operasional

Akun yang berhasil dibuat masuk antrean provisioning RADIUS. Pengaturan autentikasi router dan akun lokal MikroTik tidak diubah oleh impor. Kesiapan jaringan harus diperiksa melalui hasil provisioning; keberadaan akun aplikasi saja belum membuktikan router telah menggunakan RADIUS.

API lama tetap memakai mode data saja bila mode tidak dikirim. Klien baru memilih `ALREADY_INSTALLED_RADIUS`, atau `prepareRadius=true` untuk input manual. Mode disimpan bersama batch CSV, dan izin `bng.access.manage` diperiksa saat unggah serta commit. Worker memakai konteks tenant dari batch; tidak memerlukan sesi pengguna yang masih aktif. Mode baru menggunakan kolom yang sudah tersedia, tanpa migrasi skema.

Pemulihan pekerjaan: branch `feat/migration-radius-20261017`, bukti pengujian dan persiapan akun Fiberlab berada di `.omo/runtime/migration-radius/` yang diabaikan Git. Jangan memasukkan CSV pelanggan, token, atau password ke commit.

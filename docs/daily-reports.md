# Laporan pagi dan sore

Teknisi NE dan FO membuka **Operasional → Laporan Pagi / Sore** (`/daily-reports`). Pilih **Kirim pagi** atau **Kirim sore**, isi catatan pekerjaan, lampirkan foto opsional, lalu tinjau dan kirim. Sesudah terkirim, **Revisi** menyimpan versi baru. Pengiriman dan perubahan hanya tersedia pada tanggal hari ini di Asia/Jakarta; laporan hari libur tetap dapat dikirim.

Catatan wajib 1–5000 karakter setelah spasi awal/akhir dibuang. Maksimal lima foto JPEG/PNG asli, masing-masing 5 MiB dan 25 juta piksel. Server memeriksa signature, ukuran, decoding, serta hash objek setelah unggah. Foto baru pada revisi menggantikan daftar foto pada versi terbaru; foto versi sebelumnya tetap tersedia dalam riwayat. Jika unggah atau transaksi gagal, laporan tidak tersimpan dan objek baru dibersihkan dengan pemeriksaan metadata.

Owner, Admin dan Manager dengan `field.report.all` melihat kelengkapan tim. Teknisi dengan `field.report.view` hanya melihat dirinya; `field.report.submit` wajib untuk mengirim. Admin/Manager/owner tidak dapat mengirim sebagai teknisi, dan izin langsung pada pengguna biasa tidak memberikan akses tim atau hak pelaporan teknisi. Tenant dan pengguna berasal dari sesi, bukan form.

Tanggal kelengkapan dan bulan riwayat mempunyai filter terpisah. Direktori teknisi tetap lengkap saat salah satu teknisi dipilih. Kelengkapan menghitung pagi dan sore sekali, serta jumlah teknisi yang belum mengirim kedua slot pada hari kerja. Hari libur tidak menambah jumlah belum lengkap. Riwayat memakai pagination dan menampilkan versi terakhir; **Revisi …** membuka seluruh versi, waktu server, catatan dan foto privat. Merevisi slot yang sama tidak menambah angka kehadiran atau laporan KPI.

## Penyimpanan dan API

Migrasi `V218` menambahkan projection unik tenant/teknisi/tanggal/slot, ledger revisi immutable, serta ledger foto dengan FORCE RLS dan foreign key tenant. Revisi berurutan dan projection harus sesuai ledger. Setiap pengiriman memakai `Idempotency-Key` UUID dan `expectedRevision`. Percobaan ulang dengan isi yang sama mengembalikan versi asli, termasuk bila versi berikutnya sudah dibuat; isi berbeda dengan kunci yang sama ditolak.

| Endpoint pada `/api/v1/field/reports` | Fungsi |
| --- | --- |
| `GET /completeness?date=YYYY-MM-DD&technicianId=` | Kelengkapan dan direktori sesuai akses |
| `GET ?month=YYYY-MM&technicianId=&page=&size=` | Riwayat terbaru; page mulai nol |
| `POST` | Multipart date, slot MORNING/EVENING, notes, expectedRevision, photos berulang |
| `GET /{id}/revisions` | Versi terbaru sampai pertama |
| `GET /{id}/revisions/{revision}/photos/{photoId}` | JPEG/PNG terautentikasi, private/no-store/nosniff |

Foto atau laporan milik teknisi/tenant di luar akses menghasilkan 404. Bulan riwayat mendatang dan tanggal kelengkapan mendatang ditolak. Tenant dengan pembatasan langganan tetap dapat membaca tetapi tidak mengirim.

Tes backend `DailyReportIT` mencakup kirim/revisi/replay, foto privat, rollback unggah, kelengkapan libur, dan pembatasan peran. Browser `operational-kpi.spec.ts` menjalankan alur NE, FO, dan owner pada desktop serta ponsel dengan tema terang/gelap.

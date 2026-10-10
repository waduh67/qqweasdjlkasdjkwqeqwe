# Client B2B dan Visit Bulanan

Admin membuka **Lapangan → Client B2B** (`/b2b/clients`), memilih **Tambah client B2B**, mengisi nama, alamat, kontak/PIC, target 1–31 visit per bulan, status, dan Teknisi NE. Pilihan teknisi hanya memuat akun aktif dengan role bawaan Teknisi NE tanpa role lain, serta bukan owner atau admin platform. Formulir selalu ditinjau sebelum disimpan.

Teknisi NE membuka **Lapangan → Visit B2B** (`/b2b/visits`). Daftar hanya memuat penugasan teknisi tersebut pada bulan yang dipilih. **Laporkan visit** tersedia untuk client aktif pada bulan berjalan. Catatan wajib diisi, maksimal 5.000 karakter, disertai 1–5 foto JPEG/PNG asli, maksimal 5 MiB per foto dan 25 juta piksel per gambar. Hari operasional mengikuti Asia/Jakarta. Foto hanya dibaca melalui endpoint terautentikasi oleh admin yang memiliki izin atau pelapor.

Admin membuka **Lapangan → Rekap B2B** (`/b2b/reports`) untuk melihat jumlah visit dihitung, target, sisa, minggu tanpa visit, riwayat, dan foto. Klik nama client untuk rincian minggu atau **Buka** pada bukti untuk catatan dan foto. Pencarian client memfilter rekap; riwayat tetap mencakup semua client pada bulan tersebut.

## Aturan bulan dan riwayat

- Client baru berlaku pada bulan dibuat. Nama, alamat, kontak, penugasan, target, dan status disalin ke snapshot bulan tersebut.
- Perubahan client berlaku bulan berikutnya. Snapshot dan penugasan bulan berjalan tetap; teknisi baru mendapat tugas mulai bulan berikutnya. Bulan yang terlewat tetap dibuat berdasarkan pengaturan yang berlaku saat itu.
- Satu client hanya menghitung satu visit per hari. Laporan tambahan pada hari yang sama disimpan dengan label **Tambahan**. Target bersifat bulanan; tidak ada kuota mingguan.
- Minggu berjalan Senin–Minggu dan dipotong pada batas bulan. Minggu aktif tanpa visit baru ditandai setelah berakhir. Client nonaktif tidak menghasilkan minggu terlewat.
- Bulan mendatang ditolak. Bulan sebelum client dibuat tidak berisi client tersebut.
- Client hanya dapat dihapus sebelum memiliki visit. Sesudahnya, nonaktifkan untuk bulan berikutnya agar riwayat tetap tersimpan.
- Revisi client mencegah penimpaan perubahan bersamaan. Kunci pengiriman dan payload tetap dipertahankan saat mencoba ulang; kunci yang sama dengan payload berbeda ditolak.

## Role tenant lama dan baru

Startup menjalankan `TenantDefaultRoleBackfillRunner` untuk seluruh tenant selain tenant platform, termasuk tenant suspended. Role bawaan **Admin**, **Manager**, **Teknisi NE**, dan **Teknisi FO** dipastikan ada. Tenant baru mendapat role yang sama serta gudang bawaan melalui onboarding yang sudah ada. Role lama dan assignment pengguna tidak diganti otomatis; admin dapat menugaskan role baru melalui **Pengguna**.

Migrasi `V212` menambahkan penanda fitur `B2B_V1`. Pada startup pertama fitur ini, Admin memperoleh `b2b.client.view` dan `b2b.client.manage`; Teknisi NE memperoleh `b2b.visit.view` dan `b2b.visit.report`. Penanda diterapkan sekali per role, sehingga perubahan izin oleh tenant sesudahnya tidak ditimpa pada restart berikutnya. Benturan nama menghasilkan suffix `(Bawaan …)` tanpa mengubah role buatan tenant. Owner tetap menggunakan identitas owner yang sudah tersimpan.

Backfill produksi berjalan setelah versi ini dideploy dan aplikasi dimulai. Pengujian lokal memakai database dan penyimpanan QA terisolasi.

## API dan penyimpanan

Semua endpoint berawal `/api/v1/b2b`, memerlukan sesi tenant dan izin saat ini. Tenant serta pelapor berasal dari sesi; input tidak menerima tenant ID. Semua tabel B2B memakai composite foreign key tenant dan FORCE RLS. Snapshot bulan, visit, bukti, dan hasil perintah merupakan riwayat immutable.

| Endpoint | Fungsi |
| --- | --- |
| `GET /technicians?q=&page=&size=` | Pilihan Teknisi NE dengan pagination |
| `GET /clients?q=&page=&size=` | Client dan pengaturan bulan ini/berikutnya |
| `POST /clients` | Membuat client; `Idempotency-Key` UUID wajib |
| `PUT /clients/{id}` | Mengubah pengaturan bulan berikutnya; kunci UUID wajib |
| `DELETE /clients/{id}?expectedRevision=` | Menghapus client tanpa visit |
| `GET /reports?month=YYYY-MM&q=&page=&size=` | Rekap bulanan sesuai akses |
| `GET /visits?month=YYYY-MM&clientId=&page=&size=` | Riwayat bulanan sesuai akses |
| `POST /clients/{id}/visits` | Multipart `notes`, `photos` berulang; kunci UUID wajib |
| `GET /visits/{visit}/photos/{id}` | Bukti privat, `private, no-store`, `nosniff` |

Input client: `name`, `address`, `contact`, `technicianId`, `target`, `active`, `expectedRevision`. Revisi client baru harus nol. Pagination memakai page mulai nol dan size 1–200. Foto disimpan dengan prefix tenant dan diverifikasi SHA-256 sebelum commit; rollback menghapus objek hanya jika metadata masih cocok. Batas multipart aplikasi 26 MB untuk menampung lima foto 5 MiB beserta overhead. Reverse proxy deployment juga harus menerima ukuran request tersebut.

## Verifikasi

Jalankan melalui environment QA milik repository:

```sh
scripts/warehouse/test-environment.sh up
scripts/warehouse/qa.sh server --tests '*TenantDefaultsIT' --tests '*B2BMonthlyIT'
scripts/warehouse/qa.sh web-test b2b.test.ts
scripts/warehouse/qa.sh browser b2b.spec.ts
```

Tes backend mencakup role/backfill idempotent, tenant isolation, snapshot dan bulan terlewat, pergantian penugasan, validasi bukti, replay perintah, dan laporan bersamaan. Tes browser mencakup Admin membuat client, Teknisi NE mengirim dua laporan dengan satu visit dihitung, Admin membaca foto privat, dan empat role bawaan pada desktop serta ponsel.

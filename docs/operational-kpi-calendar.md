# KPI teknisi dan kalender kerja

Port alur Gudang Laravel sampai commit `45d9d13` (16 Oktober 2026). Menu **Operasional → KPI Teknisi** tersedia di `/kpi`; dashboard juga memiliki tab **KPI Teknisi**. Filter bulan, tim NE/FO, dan teknisi menentukan grafik, tabel, serta rata-rata yang sama. Pilih nama atau **Detail** untuk melihat empat indikator dan data pendukungnya. Bulan mendatang, bulan kosong, dan bulan sebelum Januari 2000 ditolak.

Owner, Admin, dan Manager yang mempunyai `kpi.team.view` dapat melihat tim. Teknisi aktif dengan role bawaan NE atau FO hanya melihat dirinya dan memerlukan `kpi.technician.view`, termasuk jika diberikan izin tim. Pengguna biasa dengan izin langsung tidak berubah menjadi penyelia. Pengguna dengan dua role teknisi masuk tim FO; pengguna yang juga memiliki role nonteknisi tidak termasuk direktori teknisi.

## Perhitungan

Semua tanggal operasional memakai Asia/Jakarta. Target memakai seluruh hari kerja dalam bulan, termasuk tanggal yang belum berjalan. Minggu selalu libur; Sabtu libur secara bawaan. Hari libur tersimpan mengurangi target. Laporan hari libur tetap tersimpan tetapi tidak menambah kehadiran maupun capaian laporan.

| Indikator | NE | FO |
| --- | --- | --- |
| Kehadiran | Tanggal kerja dengan minimal satu laporan / hari kerja | Sama |
| Laporan | Slot pagi dan sore unik / dua kali hari kerja | Sama |
| Pekerjaan | Visit dihitung, dibatasi target masing-masing client, lalu dijumlahkan / jumlah target | 100% dikurangi jumlah WO overdue / batas jumlah overdue |
| Hasil | Client baru pada bulan dibuat untuk teknisi awal / target satu aktivasi | Poin pekerjaan selesai / target poin |

Skor indikator dibatasi 0–100%; KPI teknisi adalah rata-rata empat indikator. Rata-rata tim hanya mencakup baris pada filter. Tanpa hari kerja atau target visit, indikator tersebut bernilai 100%. Target bawaan FO adalah 15 poin per bulan dan batas empat WO overdue.

Kategori jenis WO: **Tanpa poin** (0), **PSB** (1), **Project** (3). Jenis dan kategori disalin saat WO dibuat. Perubahan master tidak mengubah WO lama. Migrasi `V217` mengisi kategori PSB untuk snapshot historis PSB dan tanpa poin untuk jenis lain, termasuk hasil perintah yang dapat dicoba ulang. Payload dan hash perintah lama dipertahankan.

WO pending masuk overdue setelah batas hari tanpa aktivitas pada pengaturan Material & WO. Mengubah judul, catatan, atau metadata tidak menggeser aktivitas. Bukti dan progres operasional menggesernya. Riwayat overdue dibaca dari snapshot perintah; pekerjaan yang kemudian selesai tetap dihitung overdue pada bulan ketika terlambat, sekali per WO per teknisi. WO blocked tidak menambah overdue. Poin selesai memakai teknisi dan waktu pada ledger penyelesaian.

## Pengaturan owner

**Setelan Gudang** (`/warehouse/settings`) memiliki tab **Material & WO**, **Kalender kerja**, dan **KPI**. Tab kalender mengatur Sabtu, tahun kalender, hari libur manual, ubah nama/tanggal, hapus, serta sinkron sumber. Tab KPI mengatur batas 1–1000 WO dan target 1–10000 poin. Semua perubahan ditinjau dahulu dan memakai revisi serta kunci pengiriman. Pengaturan terbaru berlaku juga saat menghitung bulan sebelumnya.

Kalender mengambil hari libur dari `tanggalans.com/kalender-{tahun}/`. Data harus berisi dua belas bulan yang valid untuk tahun yang diminta. Akses awal mencoba sinkron saat kalender belum pernah diperbarui. Sumber yang gagal ditampilkan sebagai pesan; kalender tersimpan tetap tersedia. Perubahan manual, pemindahan lintas tahun, dan penghapusan bertahan setelah sinkron ulang. Sinkron tahunan berjalan 1 Januari pukul 00.10 WIB untuk tenant aktif selain platform, dengan transaksi terpisah tiap tenant.

Migrasi `V216` menyimpan pengaturan, kalender dan ledger perintah dengan FORCE RLS. Backfill role menerapkan penanda `FIELD_KPI_V1` dan `FIELD_REPORT_V1` sekali untuk role bawaan tenant lama maupun baru. Pencabutan izin sesudahnya tidak ditimpa ketika aplikasi dimulai ulang; assignment pengguna tetap dikelola melalui Pengguna.

## API dan verifikasi

Endpoint terautentikasi: `GET /api/v1/field/kpi?month=YYYY-MM&team=NE|FO&technicianId=`, `GET|PUT /api/v1/field/settings`, `GET /api/v1/field/calendar/{year}`, `POST /calendar/{year}/holidays`, `DELETE /calendar/{year}/holidays/{date}` dan `POST /calendar/{year}/sync` di bawah prefix field. Mutasi memerlukan `Idempotency-Key` UUID; input memakai `expectedRevision`. Owner dan izin saat ini diverifikasi server. Kunci yang sama dengan payload berbeda menghasilkan konflik.

Jalankan tes melalui environment QA repository:

```sh
bash scripts/warehouse/qa.sh server --tests '*WorkCalendarTest' --tests '*WorkCalendarIT' --tests '*TechnicianKpiTest' --tests '*TechnicianKpiIT' --tests '*WorkOrderKpiTest' --tests '*WorkOrderKpiIT'
bash scripts/warehouse/qa.sh browser operational-kpi.spec.ts
```

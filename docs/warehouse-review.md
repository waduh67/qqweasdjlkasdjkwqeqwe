# Review alur gudang

Gunakan tenant uji baru dengan server, PostgreSQL non-owner, dan object storage nyata.
Login sebagai tiap petugas sesuai perannya. Hasil yang tampil harus berasal dari
transaksi tersimpan, bukan mock API atau isian stok melalui SQL.

## Demo numerik

Siapkan satu reel kabel 1.000 m dan sepuluh ONU dari penerimaan/inspeksi/putaway.
SKU ONU memakai pelacakan serial, satuan EA, kategori ONU, dan mode LOAN/SALE yang
diizinkan. Buat pelanggan, lokasi tujuan, WO pemasangan, teknisi, dan pemeriksa QA.

| Tahap | Kabel tersedia di gudang | ONU tersedia | Fakta lain |
| --- | ---: | ---: | --- |
| Penerimaan dan putaway selesai | 1.000,000 m | 10 | Semua sumber berasal dari dokumen penerimaan |
| Dispatch 100 m dan satu ONU | 900,000 m | 9 | 100 m + satu ONU di transit |
| Teknisi mengakui penerimaan | 900,000 m | 9 | Custody teknisi; transit selesai |
| Pakai 82,500 m dan pasang satu ONU | 900,000 m | 9 | 82,500 m consumed; sisa 17,500 m; satu ONU pelanggan aktif |
| Kirim sisa, gudang menerima dan menginspeksi | 917,500 m | 9 | Tidak ada kabel tersisa pada teknisi |
| Handover dan QA selesai | 917,500 m | 9 | Total fisik tetap; QA tidak mendebit stok lagi |

Periksa episode pelanggan, ID ONU nyata, sumber issue, kepemilikan LOAN, tanda tangan,
hasil QA, dan laporan stok. Receipt/reservation/replay tidak boleh mengubah jumlah
akhir. Jalur SALE mempunyai handover kepemilikan yang berbeda dengan LOAN.

## Variasi yang wajib diperiksa

- Jalankan swap unit pinjaman, bongkar/recovery, inspeksi dan reset, lalu pakai unit
  lama pada pelanggan B. ID aset fisik sama; riwayat tertutup A tetap terlihat.
  Skenario dua pemasangan aktif berakhir dengan delapan ONU tersedia, bukan sembilan.
- Lepas unit yang sudah dijual, kirim perbaikan, verifikasi reset, lakukan RMA dan
  pengakuan penerima, lalu pasang kembali pada pelanggan asal. Hak tetap pelanggan.
- Kembalikan unit teknisi yang belum dipasang, termasuk serial dengan variasi huruf.
  Petugas gudang harus menerima dan menginspeksi sebelum unit tersedia lagi.
- Uji transfer terpisah: dari 100 m, terima 60 m lalu selesaikan selisih 40 m
  melalui pemeriksa independen. Jangan campur tenant ini dengan contoh numerik.
- Penghitung terbatas mencatat hasil tanpa melihat angka buku. Pemeriksa meminta
  perbaikan; stok tidak berubah. Pada hitungan lain, lakukan penerimaan transit
  setelah hitung dimulai: pengajuan lama harus stale, lalu hitung ulang menyimpan
  kedua putaran dan hanya membukukan hasil yang sah.
- Buat pelanggan/ONU memakai versi historis, upgrade database yang sama, lakukan
  cutover dengan saldo awal nol melalui dua petugas, kemudian restart aplikasi.
  Pelanggan dan ONU lama tetap ada; asal yang belum terbukti tidak menjadi stok baru.
  Tenant hasil upgrade membuat katalog kabel melalui UI dan membacanya setelah
  restart; stok tetap nol. Runner juga menjalankan preflight baca saja pada kedua
  tenant hasil upgrade, termasuk penolakan versi, satuan, dan status yang salah.

Periksa layar 1280 px, 375 px, dan tampilan tablet 768 px; tema terang/gelap;
loading, kosong, gagal, konflik, dan hak akses ditolak. Riwayat di panel pelanggan
perlu digulir agar episode yang diperiksa benar-benar terlihat pada screenshot.

## Menjalankan gate pada checkout lokal

Prasyarat: JDK 21, Docker Compose pada daemon lokal, Node/npm, Python 3,
`jq`, `curl`, `flock`, dan Chromium Playwright beserta dependensi sistemnya.
Checkout harus memuat histori Git untuk aplikasi V172 dan seluruh versi V175 yang
dipatok runner. Jangan menjalankan beberapa checkout QA pada port yang sama sekaligus.

```bash
set -euo pipefail
npm --prefix web ci
(cd web && npx playwright install chromium)
scripts/warehouse/test-environment.sh up
cleanup() {
    result=$?
    trap - EXIT
    scripts/warehouse/qa.sh stop || result=64
    scripts/warehouse/test-environment.sh down || result=64
    exit "$result"
}
trap cleanup EXIT
scripts/warehouse/test-environment.sh check
scripts/warehouse/qa.sh server
scripts/warehouse/qa.sh web-check
scripts/warehouse/qa.sh kmp
for spec in setup receiving provenance issue returns exceptions warehouse-empty-tenant customer-assets draft-expiry; do
    scripts/warehouse/qa.sh browser "$spec.spec.ts"
    # Simpan laporan/spec sebelumnya secara privat sebelum runner berikutnya.
done
scripts/warehouse/legacy-browser.sh
```

Dependensi sistem Chromium harus tersedia sebelum gate browser dijalankan. Pada
Ubuntu yang didukung Playwright, `npx playwright install --with-deps chromium`
dapat memasangnya. Pada Arch, gunakan paket distro untuk library yang dilaporkan
hilang; VPS pengembangan ini memerlukan `alsa-lib`. Jangan menjalankan pemasang
dependensi Ubuntu pada Arch.

`qa.sh server` menjalankan tes historis proyeksi V175.21 → V175.22, kemudian tujuh
tes upgrade fulfillment/deployment/title/revisi episode/discovery dari schema V175 yang sesuai,
lalu seluruh tes server terbaru. Ketujuh tes memakai aplikasi historis yang dipatok
dan menerapkan seluruh rantai migrasi terbaru. Semua migrasi yang sudah ada pada
versi historis dibandingkan byte demi byte dengan checkout kini.
Setiap fixture migrasi memakai database terpisah dengan schema `public`; schema
tambahan dalam database QA bersama tidak cukup karena migrasi lama menyebut nama
`public` secara eksplisit. Runner memeriksa bahwa fungsi database QA utama tetap sama.
Tesnya ada di `server/src/historicalTest`; tidak diabaikan atau ditandai skipped.
Untuk mengulangnya, pakai `qa.sh projection-upgrade` dan `qa.sh historical-upgrades`.
Kedua gate wajib berhasil; focused `qa.sh server --tests ...` hanya menjalankan kelas
pada source set server terbaru. Dua tes upgrade draft transfer/count menyiapkan
perintah HTTP dengan proses aplikasi lama yang dipatok, menutup proses itu,
kemudian memigrasikan dan membuka database yang sama dengan aplikasi sekarang.
Tes backfill terpisah memakai proses lama pada schema 178.11 untuk membuat draft
transfer dan rencana material dengan tanggal lama, mendatang, dan nonfinite.
Tes itu memeriksa batas tenggat, penantian kunci tabel sebelum migrasi, data sumber
yang tetap utuh, dan replay respons asli setelah aplikasi sekarang dibuka.

Spec `draft-expiry` membuat draft lewat UI dan membuka editornya sebelum tenggat.
Fixture QA menetapkan kebijakan singkat lewat role pemilik database, lalu menunggu
waktu database sebenarnya. Simpan yang terlambat harus ditolak, dan muat ulang
harus menampilkan alasan kedaluwarsa, baris asli, riwayat dan pilihan membuat draft
baru. Tidak ada penggantian jam aplikasi atau pembuatan stok lewat SQL.

Setiap browser spec wajib mempunyai tes berhasil di `warehouse-desktop` dan
`warehouse-mobile`, tanpa skipped, flaky, retry otomatis, atau tes kosong.
`qa.sh` memeriksa JSON readiness dari backend yang dibangun sendiri, identitas
database/role/marker, dan hasil Playwright. HTML fallback atau port aplikasi lain
tidak memenuhi gate. Semua container dan PID harus milik checkout QA tersebut.
Perintah `down` mempertahankan volume; file env dan bukti mentah ada di `.omo/runtime`.
Pada `up` pertama, runner membangun MinIO dari source rilis yang dipatok checksum;
build Go yang dingin dapat memerlukan beberapa menit. PostgreSQL memakai digest
Timescale yang dipatok. Detail dependency dan arsip CI ada di
[panduan CI](warehouse-ci.md).

Di macOS, jalankan kompilasi target native melalui workflow `mobile-materials`
atau tugas Gradle yang sama:

```bash
./gradlew :mobile:app:compileKotlinIosArm64 :mobile:app:compileKotlinIosSimulatorArm64 \
  verifyMobileModuleGraph --no-daemon --no-parallel --max-workers=2
```

Keberhasilan kompilasi native tidak mencakup login perangkat nyata, lifecycle OS,
Keychain/KeyStore, izin kamera/lokasi, distribusi aplikasi, atau pengujian hardware GPON.

## Membaca hasil

Untuk preflight baca saja pada tenant yang mempunyai SKU uji, gunakan
`scripts/warehouse/preflight.sql`. Konfigurasikan koneksi PostgreSQL ke database
yang ditinjau dengan role aplikasi non-owner melalui `PGHOST`, `PGPORT`,
`PGDATABASE`, `PGUSER`, dan file password privat `PGPASSFILE`. Isi `REVIEW_TENANT`
dan `REVIEW_SKU` dari tenant/SKU yang benar-benar sedang diperiksa:

```bash
psql -X -v ON_ERROR_STOP=1 \
  -v "tenant_id=$REVIEW_TENANT" -v "sku_id=$REVIEW_SKU" \
  -v expected_version=178.12 -v expected_cutover=ENFORCED -v expected_unit=MM \
  -f scripts/warehouse/preflight.sql
```

Contoh ini memeriksa SKU kabel MM setelah cutover. Untuk SKU perangkat, satuan yang
ditinjau adalah EA. Pilih status LEGACY/VALIDATING hanya ketika sedang memeriksa
fase tersebut; itu bukan izin menjalankan operasi gudang biasa. Tenant yang belum
mempunyai SKU diperiksa melalui laporan rekonsiliasi/saldo awal nol, bukan membuat
SKU fiktif untuk memenuhi probe ini. Script memakai transaksi read-only, memeriksa
RLS/role, versi, satuan dan status, lalu menampilkan hitungan dan melakukan rollback.
Exit nonzero harus diselidiki sebelum melanjutkan; jangan mengubah harapan agar
menyamarkan data yang tidak cocok. Flyway tetap harus memvalidasi seluruh checksum.

Pada fixture terisolasi, jalankan ulang dengan versi salah, unit salah, dan status
cutover salah. Masing-masing harus gagal dengan alasan yang sesuai. Jangan merusak
data bisnis atau checksum migrasi untuk melakukan pemeriksaan negatif tersebut.

Catat commit, hash JAR/migrasi, waktu, jumlah tes per suite/project, dan sumber
data historis. Arsipkan laporan lengkap secara privat sebelum focused rerun;
laporan terakhir saja tidak membuktikan seluruh suite pernah lulus. Screenshot
yang dibagikan harus sudah diperiksa bebas token, kredensial, dan data pelanggan nyata.

Periksa ulang setelah perbaikan yang menyentuh alur bersangkutan. Commit yang
berbeda dapat memakai bukti terdahulu hanya bila seluruh input relevannya identik
dan kesetaraannya dicatat. Checklist belum selesai atau angka tes nol bukan PASS.
Rilis mengikuti gate CI serta persetujuan merge/deploy yang berlaku pada repository.

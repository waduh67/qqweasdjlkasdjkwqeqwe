# Operasi gudang, material WO, dan perangkat pelanggan

## Alur baru untuk tenant baru

Tenant yang dibuat melalui pendaftaran atau **Platform → Tenants** langsung
memakai alur gudang baru dengan stok kosong. Role **Admin**, **Manager**,
**Teknisi NE**, dan **Teknisi FO**, serta **Gudang Utama**, dibuat otomatis.
Owner memiliki akses penuh dalam tenant; Admin dan petugas lain tetap memakai
izin, area, dan cakupan gudang yang diberikan. Tidak ada akun teknisi atau stok
contoh yang dibuat.

Owner menyiapkan area, pengguna dan akses lokasi. Di **Barang, Gudang & Pemasok**,
tetapkan area Gudang Utama, tambahkan barang dan pemasok, lalu berikan akses
gudang kepada petugas. Barang kabel memakai meter dengan maksimal tiga desimal
dan disimpan sebagai MM utuh; perangkat memakai EA utuh.

| Halaman | Pekerjaan | Hasil tersimpan |
| --- | --- | --- |
| Penerimaan | Pilih gudang, barang, jumlah, dan identitas nyata; tinjau lalu simpan | Stok langsung tersedia, tanpa putaway terpisah |
| Transfer | Pilih gudang asal dan tujuan serta jumlah yang tersedia; tinjau lalu simpan | Stok berpindah langsung antara kedua gudang |
| Permintaan Material | Teknisi mengajukan kebutuhan sendiri; Admin meninjau jumlah dan pemetaan barang | Persetujuan belum mencadangkan atau memindahkan stok |
| Permintaan Material → penerimaan/penyerahan | Terima pengadaan sebagian atau serahkan stok gudang kepada teknisi | Jumlah diterima/terpenuhi tercatat; material langsung masuk pegangan teknisi |
| Retur Material | Teknisi mengajukan sisa; Admin menerima atau menolak dengan alasan | Barang tetap pada teknisi sampai penerimaan sah dibukukan |
| Stock Opname | Admin memuat snapshot buku, mencatat jumlah fisik, lalu meninjau hasil | Selisih dibukukan sekali dan jejak hitung tetap tersimpan |
| Stok & Riwayat | Pilih barang lalu baca saldo, Lokasi & Pemegang, atau Riwayat | Saldo gudang dan pegangan teknisi terlihat terpisah |

Owner mengatur persetujuan Manager opsional dan batas keterlambatan melalui
**Setelan Gudang**. Kebijakan yang berubah berlaku untuk permintaan baru;
permintaan berjalan mempertahankan kebijakan yang digunakan saat diajukan.

### Tampilan dan pekerjaan teknisi

Teknisi NE/FO membuka **Work order** untuk pekerjaan yang ditugaskan kepadanya.
Detail pekerjaan menampilkan instruksi, slot foto bernama, material miliknya,
catatan dan riwayat. Teknisi mengunggah foto nyata, mengisi pemakaian material
yang terukur dan catatan penyelesaian, meninjau, lalu menyelesaikan pekerjaan.
Penyelesaian mendebit material sekali dan tidak memerlukan persetujuan QA lama.
Kendala wajib mempunyai catatan; lanjutkan pekerjaan setelah kendala selesai.
Sisa material dikembalikan melalui **Retur Material**.

Operator memilih satu teknisi NE atau FO, jenis pekerjaan dan instruksi melalui
form WO. Pekerjaan otomatis dari meja bantuan atau pemeliharaan masuk sebagai
pekerjaan yang perlu ditinjau dan ditugaskan. Pergantian teknisi tidak memindahkan
material yang masih dipegang teknisi lama. Foto dan perintah dari penugasan lama
tidak dapat dipakai untuk menyelesaikan penugasan baru.

Jika respons penyimpanan hilang, gunakan pilihan untuk mencoba transaksi yang
sama: payload dan kunci dipertahankan agar stok tidak dibukukan dua kali.
Konflik revisi yang sudah diketahui memerlukan pembacaan dan tinjauan ulang.
Tampilan teknisi tersedia di web responsif dan aplikasi Android yang sudah
berhasil dibangun sebagai APK debug. Host iOS juga sudah ditambahkan. Panduan
menjalankan aplikasi dan bukti build ada di [panduan mobile](mobile.md). Uji
perangkat Android dan kompilasi/link/runtime iOS belum teramati di host ini.

### Owner di platform

**Platform → Tenants** menampilkan nama, email dan status owner. Panel owner
memungkinkan admin platform memilih owner aktif dan mengganti password owner.
Reset password mengakhiri sesi lama; password tidak ditampilkan dalam riwayat
audit. Tenant platform tidak mempunyai kontrol owner tenant.

### Memindahkan tenant lama

Owner membuka **Gudang → Perpindahan Gudang**. Mulai perpindahan menghentikan
dokumen baru pada alur lama. Selesaikan dokumen, reservasi, WO dan pemenuhan lama,
serta rekonsiliasi asal stok, identitas dan pemegang yang dilaporkan sebagai
penghambat. Aktivasi keamanan **ENFORCED** dan pemilihan alur **REFERENCE**
merupakan pemeriksaan yang terpisah.

Setelah kesiapan tidak mempunyai penghambat, tinjau aktivasi alur baru dan
simpan alasan. Server memeriksa ulang epoch dan hash tinjauan sebelum aktivasi.
Stok terverifikasi memakai ledger yang sama; tidak ada impor salinan stok.
Respons mulai perpindahan yang tidak pasti harus dipastikan melalui pembacaan
status sebelum mengirim perintah baru. Aktivasi yang tidak pasti dapat dicoba
kembali dengan transaksi yang sama.

**Arsip Gudang** menyediakan penerimaan, bukti unduhan, transfer, lot, serial,
posisi dan riwayat lama sesuai izin baca masing-masing. Arsip tidak mengubah
dokumen. Posisi stok menunjukkan ledger saat ini, bukan snapshot aktivasi.
Panduan berikut menjelaskan operasi lama yang masih diperlukan selama draining.

## Panduan operasi lama

Gudang menyimpan identitas fisik, kuantitas, lokasi, pihak yang memegang barang,
kondisi, dan kepemilikan. WO menyimpan penugasan dan keputusan QA. Halaman pelanggan
menyimpan episode pemasangan perangkat. Ketiganya terhubung melalui dokumen nyata;
menyelesaikan WO tidak otomatis mengembalikan sisa material atau mengonsumsi barang lagi.

## Siapkan tenant dan petugas

1. Buat area di **Administrasi → Area**, lalu tetapkan area petugas melalui
   **Pengguna → Aksi baris → Akses**. Operasi gudang memerlukan area eksplisit dan
   cakupan lokasi yang sesuai. Label akses area lama pada halaman pengguna tidak
   menggantikan pemeriksaan cakupan gudang.
2. Buka **Gudang → Ringkasan Gudang**. Siapkan SKU, pemasok, gudang/bin, lokasi transit,
   karantina, dan lokasi teknisi. Pilih kategori **ONU** atau **ONT** untuk perangkat
   yang harus mempunyai catatan ONU jaringan. Pelacakan serial saja tidak menentukan kategori.
3. Pada pengaturan gudang, beri petugas akses lokasi yang diperlukan dan atur
   kebijakan persetujuan. Pemeriksa independen harus berbeda dari pembuat dokumen.
   Akses halaman persetujuan tidak mengharuskan akses ke seluruh halaman stok.
4. Tetapkan area pelanggan dan WO. Untuk pelanggan lama tanpa area, sunting
   **Pelanggan → Aksi sel → Edit → Area pelanggan** melalui akun yang masih berhak
   melihat pelanggan tersebut, sebelum membatasi akun itu ke area tertentu.

| Petugas | Pekerjaan | Batas utama |
| --- | --- | --- |
| Admin tenant | Master, peran, area, cakupan, kebijakan | Izin admin tidak menggantikan cakupan lokasi atau pemeriksa independen |
| Petugas gudang | Penerimaan, putaway, reservasi, pick, dispatch, retur/inspeksi | Barang harus berasal dari dokumen dan posisi yang memenuhi syarat |
| Teknisi | **Material Saya**, pengakuan penerimaan, penggunaan, pemasangan, pengembalian | Hanya penugasan dan custody yang sah; material transit belum boleh digunakan |
| Pemeriksa | Persetujuan selisih, write-off, saldo awal | Memeriksa dokumen dalam cakupan; tidak menyetujui pekerjaan sendiri |
| QA WO | Bukti penyelesaian, handover, review material | Menilai fakta penggunaan yang sudah tersimpan; tidak mengulang debit stok |

Rincian izin tersedia pada [pengaturan](warehouse-policy.md),
[master](warehouse-masters.md), dan [persetujuan](warehouse-approvals.md).

## Penerimaan sampai pemakaian

Di penerimaan, buat dokumen dengan SKU, kuantitas, serial atau lot/reel, serta
referensi pemasok. Konfirmasi penerimaan, catat pemeriksaan, lalu lakukan putaway
ke bin yang sesuai. Barang ditolak atau masih dikarantina belum menjadi stok tersedia.
Biaya yang tidak diketahui tetap tidak diketahui; jangan mengisi nol untuk menutup kekurangan data.

Pada bagian **Material** WO, susun kebutuhan dan ajukan. Reservasi mengikat
ketersediaan tanpa memindahkan barang. Picking memilih serial atau potongan kabel
yang nyata; unpick melepaskan pilihan sebelum dispatch. Dispatch memindahkan barang
ke transit. Teknisi masuk dengan akunnya sendiri dan mengakui jumlah yang diterima
di **Material Saya**. Selisih harus dijelaskan dan diselesaikan melalui dokumennya.

Catat pemakaian kabel yang terukur dan pasang perangkat dari sumber yang sudah
diakui. Form meter menerima maksimal tiga desimal; satu unit perangkat tidak boleh
dipecah. Sistem menyimpan meter sebagai milimeter utuh: 82,500 m berarti 82.500 MM.
Koreksi pemakaian tambahan memakai delta positif. Barang yang kembali secara fisik
memakai pengembalian, bukan pemakaian negatif.

Sisa dikirim ke karantina tujuan, diterima petugas lain, lalu diinspeksi. Stok baru
tersedia kembali setelah kondisi dan kepemilikannya memenuhi syarat. Penutupan
material memeriksa sisa, transit, inspeksi tertunda, dan kewajiban lain secara terpisah
dari status teknis, QA, serta provisioning jaringan.

Contoh lengkap dan angka per tahap ada pada [panduan review](warehouse-review.md).
Rincian: [penerimaan](warehouse-receipts.md), [issue](warehouse-issues.md),
[pengakuan teknisi](warehouse-material-receipts.md),
[pemakaian](warehouse-material-usage.md), dan [sisa material](warehouse-material-lifecycle.md).

## Perangkat pelanggan, penggantian, dan RMA

Pemasangan memilih unit gudang yang memenuhi syarat, bukan mengetik serial bebas.
Serial observasi dicocokkan setelah spasi tepi dibuang dan huruf dinormalisasi;
ejaan asli serta bukti transaksi sebelumnya tetap disimpan. Discover/auto-provision
juga harus memenuhi aturan sumber yang sama. Menemukan perangkat di jaringan
tidak membuat stok atau bukti pembelian.

| Alur | Hasil kepemilikan dan riwayat |
| --- | --- |
| LOAN | Unit tetap milik ISP, dengan episode pelanggan dan kewajiban pengembalian |
| SALE | Serah-terima mencatat perpindahan hak ke pelanggan; bukan BYOD |
| Swap/bongkar | Episode lama ditutup, unit dilepas ke proses recovery; belum otomatis tersedia |
| Inspeksi/reset/reuse | Unit ISP yang layak dapat dipasang ke pelanggan lain dengan ID fisik yang sama dan episode baru |
| Repair/RMA barang terjual | Unit tetap milik pelanggan dan kembali melalui penerima yang diotorisasi untuk pelanggan asal |

Retur barang milik pelanggan tidak boleh masuk stok ISP hanya karena berada di gudang.
Perbaikan, reset, penyerahan RMA, dan pengakuan penerima mempunyai bukti masing-masing.
SKU serial tanpa kategori ONU/ONT dapat memakai RMA tanpa membuat ONU jaringan.

Riwayat pelanggan A tetap pada episode A setelah perangkat dipakai pelanggan B.
Telemetry dan pengamatan ACS yang terlambat tidak dipindahkan ke pelanggan baru.
Monitoring mengikuti episode yang benar, termasuk ketika sumber historis belum terverifikasi.
Lihat [pemasangan](warehouse-customer-installation.md),
[serah-terima](warehouse-asset-handover.md), [penggantian](warehouse-asset-replacement.md),
dan [retur/perbaikan](warehouse-returns.md).

## Selisih, hitung fisik, dan pemulihan transaksi

Transfer gudang memisahkan dispatch dari penerimaan. Penerimaan sebagian
menyisakan kewajiban transit; pemeriksa independen menyelesaikan selisih berdasarkan
bukti. Kehilangan, scrap, dan adjustment harus memakai dokumen dan keputusan yang
diizinkan. Ledger yang sudah dibukukan tidak disunting langsung.

Stock opname mencakup posisi yang dipilih, dengan penghitung yang ditugaskan.
Penghitung tidak diberi angka buku untuk disalin. Mulai hitung tidak membekukan
seluruh operasi fisik: bila revisi stok berubah, pengajuan ditolak sebagai stale.
Muat ulang dokumen dan pilih **Mulai hitung ulang**. Catatan putaran lama tetap ada.
**Kembalikan untuk perbaikan** menghasilkan `REWORK_REQUIRED`, tanpa membukukan selisih.

Jika koneksi terputus setelah konfirmasi, gunakan **Coba transaksi yang sama**.
Isi dan kunci transaksi dipertahankan agar hasil committed dapat ditemukan kembali.
Untuk konflik revisi yang sudah diketahui, muat ulang dan tinjau data terbaru sebelum
membuat transaksi baru. Status offline tidak berarti perubahan sudah tersimpan.

Draft yang tidak dilanjutkan mempunyai tenggat, dengan masa berlaku bawaan tujuh
hari. Setelah kedaluwarsa, rincian dan riwayatnya tetap dapat dibaca, tetapi draft
tidak dapat disimpan, diajukan, atau dibukukan lagi. Buat draft atau usulan baru
dari sumber yang sesuai. Membuka halaman atau mencoba ulang transaksi lama tidak
memperpanjang tenggat. Menyimpan perubahan yang diterima pada draft penerimaan,
transfer, atau stock opname sebelum hitung dimulai memperbarui tenggatnya.
Transaksi yang sudah masuk proses fisik tidak dibatalkan oleh kedaluwarsa draft.
Lihat [masa berlaku draft](warehouse-draft-expiry.md) untuk rincian tiap alur.

Pembatalan WO meminta unpick bila perlu. Reassign tidak memindahkan custody.
Teknisi lama masih mempunyai kewajiban atas barang yang dipegangnya dan harus
mengembalikan atau menyerahkannya melalui alur resmi. Menolak QA tidak menambah stok.

## Cutover, cadangan, dan rilis

Tenant baru dimulai dengan gudang aktif dan kosong. Tenant lama memakai
**Rekonsiliasi Data Lama**: periksa sumber, unggah bukti, putuskan tiap kasus,
ajukan saldo awal, minta persetujuan independen, lalu finalisasi aktivasi.
Kasus riwayat saja dan saldo awal nol tidak boleh membuat unit atau biaya fiktif.
ID pelanggan/ONU, serial mentah, dan sejarah lama tetap dipertahankan.

Versi migrasi dan aturan checksum ada pada [manifest migrasi](warehouse-migrations.md).
Versi tertinggi yang dikemas pada 2026-10-09 adalah **211**. File yang sudah diterapkan
tidak boleh diedit. Ambil cadangan database dan object storage yang konsisten,
verifikasi latihan restore pada lingkungan terisolasi, lalu catat identitas image
dan hasil preflight sebelum rilis. Migrasi membutuhkan role pemilik; aplikasi
tetap memakai role non-owner, `NOSUPERUSER`, `NOBYPASSRLS`.

Pemulihan sesudah cutover memakai koreksi forward atau restore cadangan terverifikasi
dalam maintenance window. Binary downgrade yang tidak memahami schema dan bukti
baru bukan rollback yang didukung. Jangan menghapus volume, mengubah stok langsung
lewat SQL, mengubah checksum Flyway, atau menonaktifkan validator untuk memaksakan boot.
Detail [rekonsiliasi](warehouse-provenance.md), [backup](backup.md),
dan [deploy](../deploy/DEPLOY.md) harus dibaca bersama hasil gate rilis yang aktual.

Penghapusan tenant melalui admin platform hanya tersedia bila tenant tidak mempunyai
riwayat terlindungi. Dokumen gudang, identitas legacy, dan catatan permanen tidak
dihapus lewat aksi ini; server menolak sebelum menghapus data apa pun. Gunakan
**Suspend** untuk menghentikan tenant sambil mempertahankan riwayatnya. Tenant kosong
tetap dapat dihapus, meskipun tenant lain mempunyai riwayat pada tabel yang sama.
Konfigurasi masa berlaku draft saja tidak menghalangi penghapusan tenant kosong.
Konfigurasi itu serta baris kontrol otorisasi dan cutover ikut hilang hanya ketika
baris tenant dihapus; baris kontrol tenant aktif tidak dapat dihapus langsung.

Bukti foto, tanda tangan, dan file pemeriksaan bersifat privat. Gunakan unduhan
yang memeriksa izin dan integritas. Pertahankan aturan retensi dan legal hold;
jangan mempublikasikan trace browser, respons login, dump database, atau file env.
Berbagi laporan QA cukup dengan hitungan, hash sumber, dan screenshot yang diperiksa.

Kode GPON mempunyai [bukti dokumentasi dan fixture offline](gpon-profile-evidence.md).
Itu tidak menyatakan sertifikasi perangkat fisik. Kontrak material KMP dan kompilasi
iOS juga tidak menyatakan aplikasi native telah diuji pada perangkat atau dirilis.

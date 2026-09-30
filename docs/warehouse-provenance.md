# Rekonsiliasi data gudang lama

## Melalui halaman gudang

Buka **Gudang & Logistik → Rekonsiliasi Data Lama** (`/warehouse/provenance`)
dengan izin `inventory.provenance.manage` dan akses ke seluruh sumber batch.
Tenant baru langsung menampilkan gudang aktif dengan saldo kosong. Tenant lama
menampilkan jumlah tiap sumber, benturan identitas, satuan yang belum terbukti,
dan pekerjaan lama yang tertunda. Jumlah nol tetap ditampilkan.

1. Pilih **Mulai pemeriksaan gudang**, lalu periksa dampaknya sebelum mengonfirmasi.
2. Buka setiap kasus. Baca serial, MAC, lokasi, dan kuantitas asli; unggah bukti
   PDF, PNG, atau JPEG. Pilih bukti yang mendukung keputusan kasus. Unduhan selalu
   memakai pemeriksaan akses dan integritas file.
3. Tentukan apakah sumber hanya menyimpan riwayat, menjadi calon saldo awal,
   merupakan duplikat sumber yang sudah dibuktikan, atau merupakan efek tertunda
   yang perlu dibatalkan. Calon stok memerlukan SKU, satuan yang terbukti, dan
   kepemilikan ISP. Jumlah berasal dari catatan asli, bukan isian stok baru.
4. Buka **Saldo awal & aktivasi**. Tinjau masalah dan kuantitas per satuan, pilih
   lokasi pemeriksaan, lalu simpan usulan. Saldo awal nol memerlukan pernyataan
   pemeriksaan kosong. Usulan tersimpan dapat ditemukan kembali setelah halaman
   dimuat ulang.
5. Buka tautan persetujuan pada usulan. Pembuat mengajukan dokumen; pemeriksa
   independen menyelesaikan seluruh tahap kebijakan. Pembuat, pengunggah bukti,
   dan pihak yang memutuskan kasus tidak boleh menyetujui batchnya sendiri.
6. Kembali ke **Saldo awal & aktivasi** dan muat ulang. Aktivasi tersedia setelah
   seluruh pemeriksaan final lolos. Tinjau jumlah saldo, pembatalan, dan identitas
   yang tetap dicadangkan sebelum mengonfirmasi. Hasil aktivasi tetap terbaca
   setelah keluar atau memuat ulang halaman.

Setelah saldo awal dibukukan, kasus dan bukti menjadi riwayat baca saja. Jika
respons suatu tindakan tidak sampai, gunakan coba ulang pada konfirmasi yang
sama agar perintah tersimpan diperiksa kembali. Persetujuan yang belum selesai
atau kasus bermasalah harus diselesaikan sebelum aktivasi.

Migrasi V177 menyimpan bukti data lama sebelum ada penerimaan stok ke sistem baru.
ID, serial/MAC mentah, kuantitas, hubungan pelanggan/ONU, dan riwayat pergerakan
tetap ada. Kuantitas tanpa satuan yang terbukti tidak berubah menjadi EA atau MM.
Snapshot tidak membuat pembelian, biaya, saldo tersedia, atau penugasan perangkat.

Sebelum batch dibuka, laporan membaca sumber terkini melalui proyeksi baca milik
inventory dan pelanggan. GET tidak menyimpan snapshot. Ketika batch dimulai,
penguncian eksklusif menunggu penulis lama selesai, kemudian hash laporan dicek
kembali. Perubahan sumber membuat perintah kedaluwarsa; operator perlu membaca
ulang laporan sebelum mengirim perintah baru.

Di cutoff, sumber yang tidak berubah memakai ID kasus lama. Versi yang berubah
disimpan sebagai kasus baru dengan hash dan ID stabil; versi lama tetap utuh.
Sumber tambahan harus ikut manifest, sedangkan proyeksi yang sudah tidak ada
tidak dimasukkan sebagai saldo saat ini. Setelah batch dibuat, daftar kasus dan
jumlah sumber memakai manifest yang dibekukan. Snapshot ONU berasal dari port
milik pelanggan dan tetap memerlukan cakupan area terkini.

`inventory_provenance_case` memuat snapshot terpisah untuk aset, saldo, ONU,
tombstone, pergerakan, kaki pergerakan, efek fulfillment, dan fakta material lama.
V177.5 menambah checkpoint dan outbox fulfillment lama yang belum memakai snapshot
persetujuan material. Payload, pesan error, dan identitas worker tidak disalin.
Hash SHA-256 dihitung database. Akun aplikasi hanya dapat membacanya. Kontak,
alamat, kredensial pelanggan, dan telemetry tidak disalin ke snapshot kasus.

API laporan membutuhkan `inventory.provenance.manage` serta akses ke seluruh
lokasi, area WO, dan area pelanggan yang dirujuk snapshot tenant tersebut. Pemeriksaan
berlaku sebelum jumlah atau baris dikembalikan, termasuk setelah hak dicabut.
Area pelanggan diperiksa modul pelanggan terhadap data saat ini. Operator yang
hanya mencakup sebagian sumber tidak mendapat laporan tenant yang menyesatkan.

| API | Hasil |
| --- | --- |
| `GET /api/v1/warehouse/provenance` | Status cutover, hash snapshot, sepuluh jumlah sumber termasuk nol, konflik identitas, saldo tanpa satuan, pergerakan/checkpoint/outbox tertunda, batch |
| `GET /api/v1/warehouse/provenance/cases` | Kasus dengan bukti asli, klaim identitas, nama lokasi/pelanggan; `page`, `size` 1–100, dan `sourceTable` opsional |
| `GET /api/v1/warehouse/provenance/cases/{id}` | Satu kasus dari tenant yang sama |
| `POST /api/v1/warehouse/provenance/batches` | Mulai VALIDATING atau rekam manifest untuk tenant yang sudah VALIDATING |
| `POST /api/v1/warehouse/provenance/batches/{batch}/cases/{case}/evidence` | Unggah bukti untuk snapshot kasus dalam batch |
| `GET /api/v1/warehouse/provenance/batches/{batch}/cases/{case}/evidence` | Daftar bukti dengan `page` dan `size` 1–100 |
| `GET /api/v1/warehouse/provenance/batches/{batch}/cases/{case}/evidence/{id}` | Unduh melalui pemeriksaan hak terkini dan verifikasi isi file |
| `POST /api/v1/warehouse/provenance/batches/{batch}/cases/{case}/resolutions` | Usulkan keputusan kasus dengan bukti dan revisi yang ditinjau |
| `GET /api/v1/warehouse/provenance/batches/{batch}/cases/{case}/resolutions` | Riwayat keputusan, terbaru dahulu; `page` dan `size` 1–100 |
| `GET /api/v1/warehouse/provenance/batches/{batch}/review` | Manifest pemeriksaan, hash, dan masalah yang perlu diselesaikan |
| `POST /api/v1/warehouse/provenance/batches/{batch}/opening` | Segel usulan saldo awal untuk persetujuan independen |
| `GET /api/v1/warehouse/provenance/batches/{batch}/opening` | Temukan kembali usulan yang tersimpan; `page`, `size` 1–100, lokasi pemeriksa dibatasi cakupan terkini |
| `GET /api/v1/warehouse/provenance/batches/{batch}/opening/{id}` | Usulan saldo awal yang disegel |
| `GET /api/v1/warehouse/provenance/batches/{batch}/finalization` | Kesiapan finalisasi, jumlah sumber/saldo/pembatalan, dan bukti finalisasi bila sudah selesai |
| `POST /api/v1/warehouse/provenance/batches/{batch}/finalization` | Finalisasi saldo awal yang sudah disetujui dan aktifkan ENFORCED secara atomik |

Pembukaan batch memakai `Idempotency-Key` dan body
`{"expectedEpoch":0,"expectedPreservationHash":"<hash laporan aktual>"}`.
Epoch dan hash wajib berasal dari laporan yang ditinjau. Perintah mengambil
penguncian eksklusif tenant sebelum otorisasi dan perubahan lain. Peralihan dari
LEGACY mencatat watermark serta ID pergerakan lama yang belum APPLIED. Manifest
batch dihitung database; klien tidak dapat memilih sebagian kasus atau mengirim
jumlah nol. Manifest tidak berubah setelah dibuat.

Jika tenant sudah VALIDATING tanpa manifest, API memakai batch ID, epoch, dan
watermark yang sudah tercatat. Pengulangan kunci dan body oleh aktor yang sama
mengembalikan respons tersimpan setelah memeriksa hak terkini. Kunci dengan body
berbeda, aktor berbeda, epoch kedaluwarsa, atau batch kedua ditolak.

Unggahan bukti memakai multipart `request` berisi `expectedEpoch`,
`expectedCaseHash`, dan `label`, serta tepat satu `file`. PDF, PNG, dan JPEG
divalidasi isinya dengan batas 15 MiB. Label tidak boleh mengandung karakter
kontrol. Bukti hanya dapat ditambah selama VALIDATING dan harus cocok dengan
kasus yang tercakup manifest. Metadata tidak dapat diubah; unggahan baru mendapat
ID sendiri. Kunci idempotensi yang diulang tetap terikat aktor dan isi semula.

File disimpan di ObjectStorage privat. Ukuran, tipe, dan SHA-256 diperiksa setelah
unggahan dan saat unduhan/replay. Respons tidak memuat key atau URL penyimpanan.
Jika transaksi gagal, pembersihan menunggu transaksi batch selesai lalu memeriksa
metadata dalam transaksi baru. File yang sudah committed atau belum dapat
dipastikan hasilnya tetap disimpan. Hanya key yang cocok dengan tenant, batch,
kasus, dan ID bukti tersebut yang boleh dibersihkan.

Usulan keputusan memakai `Idempotency-Key`, `expectedEpoch`, `expectedCaseHash`,
`expectedResolutionRevision` (nol sebelum usulan pertama), `kind`, `reason`, dan
`evidenceIds` berisi 1–10 bukti dari kasus dan batch yang sama. File diperiksa
kembali. Setiap perubahan membuat revisi baru; respons perintah lama tetap dapat
diulang oleh aktor aslinya dengan hak terkini dan isi yang sama.

| Keputusan | Makna |
| --- | --- |
| `BASELINE_STOCK` | Calon saldo awal dengan `stock: {skuId, sourceUnit, legalOwner}`; kepemilikan harus terbukti ISP |
| `PROVENANCE_ONLY` | Simpan sebagai riwayat yang tidak masuk saldo tersedia |
| `DUPLICATE` | Hubungkan lewat `duplicateCaseId` ke usulan aset fisik yang sama, tanpa menghapus baris lama |
| `CANCEL_PENDING` | Ajukan rekonsiliasi pergerakan, checkpoint, atau outbox lama yang belum selesai untuk tinjauan batch |

Kuantitas calon saldo dihitung dari snapshot, bukan angka bebas dari klien.
Aset serial memakai ID fisik lama dan satu EA. Saldo tanpa satuan memerlukan
pernyataan EA, MM, atau M; M dikonversi tepat ×1000 ke MM dengan batas Long.
Satuan yang sudah diketahui tidak boleh ditafsirkan ulang. Lokasi asal dan SKU
harus aktif dalam tenant yang sama. Aset terpasang, milik pelanggan, atau saldo
yang sudah mewakili aset serial tidak boleh dimasukkan lagi sebagai stok tersedia.
Revisi SKU/lokasi serta revisi usulan duplikat ikut dicatat untuk tinjauan berikutnya.
Usulan pembatalan belum mengubah status pergerakan lama.

Checkpoint APPLIED dan hasil rekonsiliasi manual tetap merupakan riwayat; replay
tidak mengulang efeknya. Checkpoint atau outbox yang belum selesai wajib memakai
usulan `CANCEL_PENDING`, dengan bukti masing-masing kasus. Pengiriman ulang efek
lama tanpa snapshot persetujuan yang sah berakhir di rekonsiliasi, tanpa posting.
Hasil `MANUAL_RESOLVED` tetap terminal walaupun worker mengirim pesan lagi.
Penulisan checkpoint, status efek, antrean, dan pengakuan worker mengambil fence
cutover pada transaksi masing-masing. Laporan tenant memakai fence eksklusif agar
sumber baru tidak muncul di antara pemeriksaan cakupan dan penghitungan jumlah.

Platform operator dapat memeriksa referensi lokasi yang sudah tidak ada di tenant
tersebut sebagai snapshot saja. Nama/data lokasi tenant lain tidak dikembalikan,
dan referensi itu tidak dapat dipakai untuk calon saldo awal. Operator biasa tetap
memerlukan cakupan seluruh sumber yang valid sebelum mendapat laporan.

Usulan keputusan belum mengaktifkan stok. Usulan saldo awal disegel dengan hash
pemeriksaan, lokasi pemeriksa, referensi migrasi, dan alasan. Persetujuan menggunakan
alur persetujuan gudang biasa dengan jenis OPENING_BALANCE. Semua tingkat pemeriksa
harus menyetujui tanpa mengarang nilai pembelian. Pembuat batch, pengusul, penyelesai
kasus, dan pengunggah bukti tidak dapat menyetujui hasilnya sendiri. Persetujuan akhir
membukukan saldo awal melalui pemilik posting tunggal, mempromosikan aset asli,
serta mencatat bukti pembatalan efek lama dalam transaksi yang sama. Tenant tetap
VALIDATING; operasi stok biasa belum dibuka.

Setelah persetujuan selesai, baca endpoint finalization. Kirim `Idempotency-Key`
bersama `expectedEpoch`, `openingDocumentId`, `expectedReviewHash`, dan `reason`.
Epoch, dokumen, dan hash harus berasal dari hasil pemeriksaan tersebut. Finalisasi
memeriksa posting independen, bukti file asli, event dan inbox persetujuan,
pembatalan efek lama, reservasi identitas terkini, serta kecocokan stok tersedia
dengan seluruh baris saldo awal. Penguncian eksklusif tenant mendahului otorisasi
terkini dan penguncian batch. Bukti finalisasi dan perubahan epoch ke ENFORCED
disimpan bersama; rollback membatalkan keduanya.

Respons menyimpan jumlah sumber termasuk nol, total saldo per satuan dasar, jumlah
pembatalan, identitas yang tetap dicadangkan, aktor, alasan, dan waktu finalisasi.
Jika respons hilang, ulangi kunci dan body yang sama. Respons asli dikembalikan
setelah pemeriksaan hak terkini walaupun epoch telah berubah. Kunci sama dengan
isi atau aktor berbeda ditolak. Jangan mengganti kunci untuk mengatasi respons
yang belum pasti. Riwayat kasus, bukti, dan usulan tetap dapat dibaca setelah
finalisasi; dokumen yang telah dibukukan tidak membuka kembali rekonsiliasi.

Data historis dan perangkat lama yang belum terbukti tetap staged, dikecualikan
dari stok baru, dan identitasnya tetap menghalangi penerimaan yang bentrok. Serial,
ID aset/ONU, hubungan pelanggan, serta nilai mentah tidak dihapus. Tenant baru
kosong memakai inisialisasi atomik ENFORCED; tenant lama kosong memerlukan saldo
awal nol dengan pemeriksaan dan persetujuan independen. Jalur nol tidak membuat
SKU, lot, baris stok, kuantitas, atau biaya fiktif. M06 memvalidasi referensi hanya
pada baris VERIFIED sehingga data lama dengan referensi yang belum lengkap tetap
bisa dibaca dan tidak menggagalkan boot seluruh tenant.

Verifikasi dasar ada di `WarehouseMigrationITBoot`, `WarehouseMigrationITQuery`, `WarehouseMigrationCaptureIT`,
`WarehouseMigrationEvidenceIT`, `WarehouseMigrationResolutionIT`, dan
`WarehouseMigrationInventoryTest`. Tes bukti
menggunakan HTTP serta MinIO sungguhan, kehilangan respons, penghentian backend
database setelah file ditulis, dan penguncian transaksi yang belum terselesaikan.
Fixture upgrade dimulai sebelum V173 dengan
serial/MAC bentrok dan ONU lama, lalu memuat semua migrasi paket. Setup metadata
area pada fixture bukan bukti UI rekonsiliasi; perjalanan UI tetap menjadi
pemeriksaan task43/45. `WarehouseMigrationOpeningApprovalIT` mencakup persetujuan,
finalisasi, rollback, replay konkuren, tenant kosong, dan pemeliharaan identitas lama.

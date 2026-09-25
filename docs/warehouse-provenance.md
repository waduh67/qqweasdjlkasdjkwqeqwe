# Rekonsiliasi data gudang lama

Migrasi V177 menyimpan bukti data lama sebelum ada penerimaan stok ke sistem baru.
ID, serial/MAC mentah, kuantitas, hubungan pelanggan/ONU, dan riwayat pergerakan
tetap ada. Kuantitas tanpa satuan yang terbukti tidak berubah menjadi EA atau MM.
Snapshot tidak membuat pembelian, biaya, saldo tersedia, atau penugasan perangkat.

`inventory_provenance_case` memuat snapshot terpisah untuk aset, saldo, ONU,
tombstone, pergerakan, kaki pergerakan, efek fulfillment, dan fakta material lama.
Hash SHA-256 dihitung database. Akun aplikasi hanya dapat membacanya. Kontak,
alamat, kredensial pelanggan, dan telemetry tidak disalin ke snapshot kasus.

API laporan membutuhkan `inventory.provenance.manage` serta akses ke seluruh
lokasi dan area pelanggan yang dirujuk snapshot tenant tersebut. Pemeriksaan
berlaku sebelum jumlah atau baris dikembalikan, termasuk setelah hak dicabut.
Area pelanggan diperiksa modul pelanggan terhadap data saat ini. Operator yang
hanya mencakup sebagian sumber tidak mendapat laporan tenant yang menyesatkan.

| API | Hasil |
| --- | --- |
| `GET /api/v1/warehouse/provenance` | Status cutover, hash snapshot, delapan jumlah sumber termasuk nol, konflik identitas, saldo tanpa satuan, pergerakan tertunda, batch |
| `GET /api/v1/warehouse/provenance/cases` | Kasus dengan bukti asli, klaim identitas, nama lokasi/pelanggan; `page`, `size` 1–100, dan `sourceTable` opsional |
| `GET /api/v1/warehouse/provenance/cases/{id}` | Satu kasus dari tenant yang sama |
| `POST /api/v1/warehouse/provenance/batches` | Mulai VALIDATING atau rekam manifest untuk tenant yang sudah VALIDATING |
| `POST /api/v1/warehouse/provenance/batches/{batch}/cases/{case}/evidence` | Unggah bukti untuk snapshot kasus dalam batch |
| `GET /api/v1/warehouse/provenance/batches/{batch}/cases/{case}/evidence` | Daftar bukti dengan `page` dan `size` 1–100 |
| `GET /api/v1/warehouse/provenance/batches/{batch}/cases/{case}/evidence/{id}` | Unduh melalui pemeriksaan hak terkini dan verifikasi isi file |
| `POST /api/v1/warehouse/provenance/batches/{batch}/cases/{case}/resolutions` | Usulkan keputusan kasus dengan bukti dan revisi yang ditinjau |
| `GET /api/v1/warehouse/provenance/batches/{batch}/cases/{case}/resolutions` | Riwayat keputusan, terbaru dahulu; `page` dan `size` 1–100 |

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
| `CANCEL_PENDING` | Ajukan pembatalan pergerakan lama yang belum APPLIED untuk tinjauan batch |

Kuantitas calon saldo dihitung dari snapshot, bukan angka bebas dari klien.
Aset serial memakai ID fisik lama dan satu EA. Saldo tanpa satuan memerlukan
pernyataan EA, MM, atau M; M dikonversi tepat ×1000 ke MM dengan batas Long.
Satuan yang sudah diketahui tidak boleh ditafsirkan ulang. Lokasi asal dan SKU
harus aktif dalam tenant yang sama. Aset terpasang, milik pelanggan, atau saldo
yang sudah mewakili aset serial tidak boleh dimasukkan lagi sebagai stok tersedia.
Revisi SKU/lokasi serta revisi usulan duplikat ikut dicatat untuk tinjauan berikutnya.
Usulan pembatalan belum mengubah status pergerakan lama.

Platform operator dapat memeriksa referensi lokasi yang sudah tidak ada di tenant
tersebut sebagai snapshot saja. Nama/data lokasi tenant lain tidak dikembalikan,
dan referensi itu tidak dapat dipakai untuk calon saldo awal. Operator biasa tetap
memerlukan cakupan seluruh sumber yang valid sebelum mendapat laporan.

Usulan keputusan belum mengaktifkan stok. Persetujuan independen, posting saldo
awal, dan finalisasi ENFORCED masih tahap berikutnya di task43. Guard yang menutup
operasi tersebut tetap berlaku. Tenant baru kosong
tetap memakai inisialisasi atomik ENFORCED yang sudah tersedia; tenant lama kosong
memerlukan jalur validasi dan persetujuan tersendiri.

Verifikasi dasar ada di `WarehouseMigrationITBoot`, `WarehouseMigrationITQuery`,
`WarehouseMigrationEvidenceIT`, `WarehouseMigrationResolutionIT`, dan
`WarehouseMigrationInventoryTest`. Tes bukti
menggunakan HTTP serta MinIO sungguhan, kehilangan respons, penghentian backend
database setelah file ditulis, dan penguncian transaksi yang belum terselesaikan.
Fixture upgrade dimulai sebelum V173 dengan
serial/MAC bentrok dan ONU lama, lalu memuat semua migrasi paket. Setup metadata
area pada fixture bukan bukti UI rekonsiliasi; perjalanan UI dan restart sesudah
admission tetap menjadi pemeriksaan task43/45.

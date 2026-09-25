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

Pembukaan batch belum mengaktifkan stok. Rekonsiliasi berbukti, persetujuan
independen, posting saldo awal, dan finalisasi ENFORCED masih tahap berikutnya
di task43. Guard yang menutup operasi tersebut tetap berlaku. Tenant baru kosong
tetap memakai inisialisasi atomik ENFORCED yang sudah tersedia; tenant lama kosong
memerlukan jalur validasi dan persetujuan tersendiri.

Verifikasi dasar ada di `WarehouseMigrationITBoot`, `WarehouseMigrationITQuery`,
dan `WarehouseMigrationInventoryTest`. Fixture upgrade dimulai sebelum V173 dengan
serial/MAC bentrok dan ONU lama, lalu memuat semua migrasi paket. Setup metadata
area pada fixture bukan bukti UI rekonsiliasi; perjalanan UI dan restart sesudah
admission tetap menjadi pemeriksaan task43/45.

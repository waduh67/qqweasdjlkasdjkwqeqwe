# Pembelian, pinjaman client, dan garansi vendor

Alur ini tersedia saat workflow gudang aktif dalam mode REFERENCE. Menu Pembelian,
Pinjaman client, dan Garansi vendor memakai ledger gudang yang sama dengan stok
teknisi. Website referensi hanya dibaca; implementasi dan verifikasi dilakukan lokal.

## Pembelian

Teknisi mengajukan request pengadaan, Admin meninjau jumlah, lalu Manager mengambil
keputusan bila pengaturan mengharuskannya. Admin dapat mencatat pesanan pembelian
dari request yang disetujui atau membuat pesanan tersendiri. Pesanan menyimpan
pemasok, tanggal, referensi, barang, jumlah, dan harga satuan opsional.

Membuat pesanan belum menambah stok. Penerimaan dilakukan per baris dan boleh
sebagian, termasuk daftar serial/MAC untuk perangkat. Sisa pesanan dapat dibatalkan.
Pesanan terkait request menahan sisa jumlah request yang belum diterima agar tidak
dipesan atau diterima dua kali. Penerimaan memperbarui pesanan dan request dalam
transaksi yang sama. Pembayaran dan pembukuan tidak termasuk alur ini.

## Pinjaman client

Nama client/peminjam dan kontak diisi bebas. Pilih gudang, barang, jumlah, tanggal
pinjaman, dan batas pengembalian opsional. Admin meninjau jumlah; persetujuan
Manager mengikuti kebijakan yang ditangkap saat pengajuan. Perubahan kebijakan
berikutnya berlaku pada pengajuan baru.

Sesudah disetujui, petugas memilih stok fisik sesuai jumlah persetujuan dan mencatat
bukti penyerahan. Stok tetap milik ISP, dengan lokasi/custody client dan status
ISSUED. Berita acara menggunakan snapshot penyerahan awal yang tetap sama setelah
retur; izin diperiksa ulang sebelum cetak.

Pengembalian dapat sebagian. Barang baik kembali ke stok tersedia. Barang rusak
masuk karantina dan otomatis membuat klaim garansi. Serial fisik harus sesuai
pinjaman. Perpindahan sebagian barang nonserial membentuk identitas terpisah untuk
barang yang pindah dan sisanya, termasuk jumlah kabel dalam milimeter.

## Garansi vendor

Klaim berasal dari retur rusak, kemudian dikirim ke pemasok dengan referensi dan
bukti. Hasil vendor dapat diperbaiki, tidak diperbaiki, ditolak, atau diganti.
Penggantian perangkat membutuhkan serial baru; serial awal tetap tersimpan dalam
riwayat pinjaman. Barang dari vendor masuk karantina sampai inspeksi selesai.
Perangkat serial yang dinyatakan layak harus dikonfirmasi sudah direset sebelum
masuk stok tersedia. Barang yang tidak layak tetap di karantina.

## Akses dan kontrak

- Pembelian: warehouse.request.view / warehouse.request.receive.
- Pinjaman: warehouse.loan.view / warehouse.loan.manage; tinjauan juga memerlukan
  warehouse.request.review, keputusan Manager memerlukan warehouse.request.approve.
- Garansi: warehouse.warranty.view / warehouse.warranty.manage.

Peran bawaan Admin/Manager memperoleh izin sesuai tugasnya. Peran kustom dapat
diatur melalui IAM. Setiap daftar, detail, command, cetak, dan ringkasan dibatasi
tenant dan akses gudang. Command menyimpan actor, kebijakan/epoch, revisi, payload
kanonis, dan movement. Replay memakai kunci yang sama; perubahan payload dengan
kunci yang sama ditolak. Mutasi fisik dan dokumen disimpan atomik.

API berada di /api/v2/warehouse/purchases, /loans, /warranties, dan
/client-operations/overview. Migrasi V225 menambah dokumen, command, hubungan movement,
serta validator deferred tanpa mengganti ledger lama.

## Verifikasi lokal

Gunakan lingkungan QA terisolasi yang disediakan repository:

    bash scripts/warehouse/test-environment.sh up
    bash scripts/warehouse/qa.sh server --tests '*WarehouseClientOperationsIT' --tests '*ReferenceRequestIT' --tests '*ReferenceWarehouseIT' --no-parallel

Tes frontend:

    cd web
    npm test -- --run src/pages/warehouse/WarehouseClientOperations.test.tsx --maxWorkers=2
    npm run build

Wrapper QA memeriksa marker database, pemisahan owner/app, RLS, dan layanan lokal.
Di Windows, jalankan wrapper dalam Linux dengan socket Docker dan checkout milik
Linux agar pemeriksaan kepemilikan/mode file tetap berlaku.

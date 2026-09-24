# Kehilangan, scrap, dan koreksi stok

Disposisi retur menerima satu potong material atau satu perangkat milik ISP
dari dokumen retur yang berada dalam inspeksi. Dokumen kehilangan memakai tindakan `LOSS`; scrap memakai `SCRAP`
dan harus didahului inspeksi `DAMAGED`.

## Pengajuan dan keputusan

1. Baca revision retur dan identitas fisiknya melalui API retur.
2. Buat `POST /api/v1/warehouse/dispositions` dengan `Idempotency-Key`.
   Isi `sourceDocumentId`, `expectedRevision`, `stockIdentityId`, `quantityBase`,
   `baseUnit`, `destinationLocationId`, `action`, `reason`, dan `evidenceReference`.
   Kuantitas harus sama persis dengan potongan retur: contoh 17,5 m adalah
   `"17500"` dengan unit `MM`; satu perangkat adalah `"1"` dengan unit `EA`.
   Tujuan harus lokasi `LOST` untuk kehilangan atau `DISPOSED` untuk scrap.
3. Gunakan ID dokumen baru dan revision 0 untuk
   `POST /api/v1/warehouse/approvals/request`.
4. Pemeriksa independen memutuskan melalui API approval. Pengaju, pihak yang
   dikecualikan kebijakan, dan delegasinya tidak dapat menyetujui sendiri.

Pengajuan belum mengubah stok. Persetujuan memindahkan barang satu kali ke
tujuan, menyimpan bukti keputusan, dan melanjutkan riwayat retur tanpa debit
fisik kedua. Retur kemudian berstatus `LOST` atau `SCRAP`. Riwayat barang,
assignment pelanggan yang telah ditutup, dan nilai penerimaan asal tetap ada.

`GET /api/v1/warehouse/dispositions/{id}` membaca status terkini. Daftar pada
`GET /api/v1/warehouse/dispositions` mendukung `page`, `size`,
`sourceDocumentId`, dan `action`. Scope lokasi diperiksa sebelum paginasi.
Pengulangan mutasi memakai kunci yang sama dan payload identik; respons awal
dikembalikan setelah akses terkini diperiksa. Gunakan GET untuk status terbaru.

## Sumber, biaya, dan kewajiban

Pengajuan membutuhkan `inventory.custody.manage`, `inventory.return.manage`,
dan `inventory.approval.request`, beserta akses WO dan semua lokasi terkait.
Pembacaan membutuhkan `inventory.custody.view`; keputusan memakai izin approval.
Barang dengan assignment aktif atau reservasi terbuka tidak dapat dibuang.

Barang milik pelanggan atau berstatus kepemilikan tidak diketahui ditolak.
RMA yang sudah dijual memerlukan alih kepemilikan tersendiri sebelum dapat
diperlakukan sebagai barang ISP.

Nilai untuk kebijakan diambil dari numerator biaya, basis kuantitas, dan mata
uang penerimaan asal. Biaya kosong menghasilkan `COST_BASIS_REQUIRED` ketika
kebijakan membutuhkan nilai. Mata uang berbeda menghasilkan `CURRENCY_MISMATCH`;
tidak ada konversi otomatis atau biaya nol pengganti.

Untuk residual WO, kuantitas yang pernah dikembalikan tetap tercatat sebagai
`returnedBase`. Disposal yang disetujui menambah `settledReturnBase` untuk
residual itu sekali saja. Penutupan kewajiban tidak memindahkan stok lagi.
Penolakan approval mempertahankan sumber dan meminta pengajuan baru dengan
bukti yang diperbaiki.

## Koreksi disposisi retur

Buat `POST /api/v1/warehouse/dispositions/{id}/compensations` dengan kunci baru.
Input berisi `expectedRevision`, `expectedReturnRevision`, `destinationLocationId`,
`reason`, dan `evidenceReference`. Sumber harus posting LOSS/SCRAP yang sudah
berhasil, seluruh barang masih berada pada posisi hasil disposisi, dan belum
pernah dikompensasi. Kewajiban material yang sudah ditutup memerlukan koreksi
lifecycle terlebih dahulu.

Draft baru menjalani kebijakan `ADJUSTMENT` dan persetujuan independen. Keputusan
berhasil membuat satu `REVERSAL` yang menunjuk movement asli, lalu mengembalikan
barang ke karantina dengan kondisi `QUARANTINE`. Posting lama tetap tersimpan.
Kuantitas kembali menjadi kewajiban terbuka sampai inspeksi baru menerimanya.
Perangkat harus diperiksa dan di-reset sebelum dikeluarkan lagi. Koreksi lama
tidak dapat membatalkan pemasangan berikutnya untuk pelanggan lain.

## Kehilangan perangkat pinjaman yang belum dipulihkan

`POST /api/v1/warehouse/asset-losses` mengajukan kehilangan perangkat dengan
penugasan aktif, serah-terima `LOAN`, dan kepemilikan ISP. Isi `assignmentId`,
`sourceHandoverId`, `expectedRevision` penugasan, `expectedTitleRevision`,
`expectedWorkOrderRevision`, `destinationLocationId`, `reason`, dan `evidenceId`.
Bukti harus merupakan revisi tanda tangan yang tersimpan dan dapat diverifikasi
pada WO pemasangan. Penggantian bukti mengikuti aturan koreksi bukti WO dan
mempertahankan bukti serah-terima asal. Kuantitas ditetapkan dari perangkat asli:
satu `EA`. Tujuannya lokasi `LOST` yang aktif.

Pengajuan memerlukan `inventory.custody.manage`, `inventory.approval.request`,
akses WO/bukti dan kedua lokasi. Gunakan ID draft dan revision 0 untuk meminta
approval seperti disposisi retur. Kebijakan yang dipakai adalah `LOSS`, dengan
biaya penerimaan asli dan pemeriksa independen. Penugasan, posisi perangkat,
bukti, revisi WO, dan episode diperiksa lagi ketika keputusan dijalankan.
Pemulihan fisik atau perubahan sumber membuat approval lama `STALE`.

Persetujuan mencatat satu movement LOSS, mengakhiri penugasan dan episode ONU,
dan mengantrekan perubahan provisioning dalam satu transaksi lokal. Kewajiban
pinjaman asal tetap menjadi riwayat dan terhubung ke bukti kehilangan yang
disetujui. Kegagalan pengiriman provisioning tetap terlihat terpisah dari fakta
kehilangan. Riwayat instalasi dan telemetry lama mempertahankan pelanggan asal.
Permintaan ini tidak membuat catatan pembongkaran atau retur fisik.

`GET /api/v1/warehouse/asset-losses/{id}` membaca status terbaru; daftar mendukung
`page` dan `size`, dengan scope lokasi sebelum paginasi. DTO publik tidak memuat
biaya, nama/alamat pelanggan, atau object key bukti. Perangkat SALE milik pelanggan
ditolak. API kompensasi disposisi retur tidak menerima dokumen ASSET_LOSS.

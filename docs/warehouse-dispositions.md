# Kehilangan dan scrap barang retur

Implementasi task 28 sedang diverifikasi. Alur saat ini menerima satu potong
material atau satu perangkat milik ISP dari dokumen retur yang berada dalam
inspeksi. Dokumen kehilangan memakai tindakan `LOSS`; scrap memakai `SCRAP`
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

Pengajuan koreksi sedang diverifikasi pada `POST /api/v1/warehouse/dispositions/{id}/compensations`.
Input berisi `expectedRevision`, `expectedReturnRevision`, `destinationLocationId`,
`reason`, dan `evidenceReference`. Pengajuan hanya menyimpan draft yang menunjuk
posting asli; pelaksanaan reversal melalui approval masih dikerjakan. Kehilangan
di luar retur inspeksi juga belum tersedia pada API ini.

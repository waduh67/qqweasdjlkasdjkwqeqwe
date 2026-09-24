# Retur dan inspeksi gudang

Retur memakai dokumen baru yang menunjuk asal fisiknya. Retur material menunjuk
residual WO yang sudah diterima; retur perangkat menunjuk pembongkaran assignment
yang sudah disaksikan. Penerimaan dan inspeksi tidak menghapus riwayat issue,
pemakaian, kepemilikan, pelanggan, atau episode ONU.

## API yang tersedia

Mutasi intake/inspeksi/servis di bawah membutuhkan `Idempotency-Key` dan izin
`inventory.return.manage`. Pembacaan membutuhkan `inventory.return.view`.
Area serta lokasi karantina awal dan lokasi saat ini diperiksa pada setiap
permintaan, termasuk pengulangan permintaan lama. Token atau kunci lama tidak
mengabaikan pencabutan scope. Hasil mutasi memuat revision yang digunakan oleh
perintah berikutnya; perubahan bersamaan menghasilkan konflik, bukan retry
dengan revision tebakan.

| Metode dan path | Fungsi |
| --- | --- |
| `POST /api/v1/warehouse/returns` | Menerima asal `MATERIAL_RESIDUAL` atau `ASSET_REMOVAL` |
| `POST /api/v1/warehouse/returns/{id}/inspect` | Mencatat ukuran, kondisi, bukti dan tujuan inspeksi |
| `POST /api/v1/warehouse/returns/{id}/repair-dispatch` | Menyerahkan perangkat hasil inspeksi ke custody vendor |
| `POST /api/v1/warehouse/returns/{id}/repair-receive` | Menerima perangkat yang sama kembali ke karantina |
| `GET /api/v1/warehouse/returns` | Daftar retur dan total dokumen dalam scope pengguna |
| `GET /api/v1/warehouse/returns/{id}` | Membaca kondisi dokumen terkini |
| `GET /api/v1/warehouse/returns/{id}/history` | Membaca revision dokumen secara berurutan dan berpaginasi |

Daftar menerima `page` (mulai0), `size` (1–100, default25), serta filter `origin`,
`state`, `locationId`, `skuId`, `stockIdentityId`, dan `owner`. Urutan daftar adalah
waktu intake terbaru, lalu ID sebagai pembeda. Total dan halaman dihitung setelah
scope lokasi/area diterapkan. Riwayat menerima `page` dan `size` dengan default
100 serta urutan revision naik; parameter di luar batas ditolak.

Intake berisi `origin`, `sourceDocumentId`, `quarantineLocationId`, dan
`evidenceReference`. Receiver perangkat harus berbeda dari pelaku pembongkaran.
Penerimaan perangkat memindahkan1EA dari custody pembongkar ke karantina gudang.
Penerimaan residual tidak mendebit atau menerima stok dua kali: posting fisiknya
sudah terjadi pada acknowledgement residual.

Inspeksi berisi `expectedRevision`, `measuredQuantityBase`, `condition`,
`destinationLocationId`, `evidenceReference`, dan `resetConfirmed`. Perangkat
berserial juga menggunakan `observedSerial` dan `resetEvidenceReference`.
Quantity adalah string bilangan bulat dalam satuan dasar:17,5m ditulis `17500`
MM, sedangkan satu perangkat ditulis `1` EA. Inspeksi menerima seluruh potongan
yang terikat sumber; tidak boleh mengarang panjang tambahan atau menggabungkan
asal berbeda. Serial harus cocok dengan aset fisik, bukan hanya model SKU.

## Keputusan inspeksi

- Barang milik ISP dengan kondisi `SERVICEABLE` dapat masuk BIN yang mengizinkan
  issue; dokumennya menjadi `ACCEPTED` dan stoknya `AVAILABLE`.
- Perangkat harus memiliki konfirmasi reset dan referensi buktinya sebelum
  dinyatakan serviceable, termasuk perangkat milik pelanggan.
- Barang milik pelanggan tetap `QUARANTINE` dan tidak menambah stok ISP yang
  tersedia, meskipun perangkat sudah serviceable. Mengganti kondisi bukan
  pemindahan kepemilikan.
- `DAMAGED` atau `QUARANTINE` tetap berada di lokasi karantina. Scrap membutuhkan
  alur disposition dengan otorisasi tersendiri.

Pemakaian ulang perangkat pinjaman membuat assignment dan episode ONU baru untuk
pelanggan berikutnya. Assignment lama tetap berakhir pada waktu pembongkaran;
riwayat pelanggan dan telemetry lama tidak dipindahkan ke pelanggan baru.

Ledger APPLIED menjadi pembuktian posisi setelah recovery. Perubahan langsung
ke aset sekaligus saldo tidak dianggap pergerakan yang sah. Rebuild proyeksi
dijalankan secara atomik dari ledger dan tidak menambah transaksi fisik.

## Servis vendor

Perangkat yang sudah diinspeksi dan masih karantina dapat dikirim ke vendor
aktif. Perintah `repair-dispatch` memuat `expectedRevision`, `vendorId`,
`repairLocationId` berupa lokasi TRANSIT, `vendorReference`, `evidenceReference`,
dan `observedSerial`. Dokumen berubah menjadi `REPAIR`; custody menunjuk vendor
dan kondisi serta pemilik tetap sama. Izin lokasi asal dan tujuan wajib tersedia.

Perintah `repair-receive` memuat `expectedRevision`, `observedSerial`,
`quarantineLocationId`, `result` (`REPAIRED` atau `UNREPAIRED`), `vendorReference`,
dan `evidenceReference`. Serial berbeda ditolak. Hasil vendor dicatat terpisah
dari keputusan inspeksi: barang kembali ke karantina dengan kondisi sebelumnya,
lalu operator melakukan inspeksi dan reset ulang melalui endpoint inspeksi.
Case servis ditutup ketika inspeksi menyatakan serviceable; barang milik pelanggan
tetap tidak tersedia bagi issue ISP. Satu dokumen retur saat ini mendukung satu
case servis. Dokumen dan riwayat memuat detail case pada field `repair`.

## Penutupan material

Retur material yang telah diinspeksi dan diterima mengurangi outstanding WO.
`returnedBase` tetap menyimpan quantity historis; `settledReturnBase` menunjukkan
bagian yang sudah selesai diinspeksi. Field kedua tidak dikirim bila nilainya0.
Retur rusak atau belum diinspeksi tetap menghalangi penutupan. Penutupan menyimpan
seluruh baris sumber dan tidak menambah posting fisik.

Perangkat yang benar-benar dipasang dihitung sekali sebagai pemakaian terhadap
issue asal. Serah terima title tidak menambah pemakaian. Pemasangan ulang RMA
menunjuk handover khusus dan tidak membebankan satu unit lagi pada issue lama.

## RMA kembali ke pelanggan asal

Sesudah case servis perangkat CUSTOMER ditutup oleh inspeksi serviceable, gudang
menyiapkan serah terima untuk teknisi pada WO `REPAIR` pelanggan asal. Pengirim
harus berbeda dari teknisi penerima. Semua langkah menggunakan `Idempotency-Key`.

| Metode dan path | Izin dan hasil |
| --- | --- |
| `POST /api/v1/warehouse/returns/{id}/rma-handover` | `inventory.return.manage`; kirim ke transit |
| `POST /api/v1/warehouse/rma-handovers/{id}/acknowledge` | `workorder.order.field`, teknisi yang ditunjuk; terima custody |
| `GET /api/v1/warehouse/rma-handovers/{id}` | Pengelola retur atau teknisi yang ditunjuk, sesuai scope |
| `POST /api/work-orders/{id}/assets/authorize` | Teknisi WO, izin field dan assign; terbitkan izin tujuan RMA |
| `POST /api/customers/{id}/assets/install` | Konsumsi izin sekali pada pelanggan asal |
| `POST /api/customers/{id}/assets/handover` | Bukti tanda tangan pelanggan; tanpa posting stok/title kedua |

Dispatch memuat `expectedRevision` retur, `workOrderId`, `workOrderRevision`,
`technicianId`, `transitLocationId`, `technicianLocationId`, `observedSerial`,
dan `evidenceReference`. Hasilnya memuat ID handover, pelanggan/assignment asal,
case servis, lokasi dan revision1. Acknowledgement memuat `expectedRevision: 1`,
serial yang sama dan referensi bukti; hasilnya revision2. Stok berpindah ke custody
teknisi dengan status `ISSUED`, kondisi serviceable dan pemilik CUSTOMER.

Authorization menggunakan `purpose: RETURN_CUSTOMER_RMA`, `ownershipMode: SALE`,
`assetId`, `repairCaseId`, `previousAssignmentId` assignment asal,
`issueLineId: null`, serta `expectedRevision` WO saat ini. Izin hanya terbit setelah
handover diterima teknisi. Pemasangan memakai authorizationId yang dikembalikan;
serial, pelanggan, asal fisik dan title ditentukan server. Barang CUSTOMER tidak
menjadi stok ISP yang tersedia. Assignment dan episode ONU lama tetap utuh;
pemasangan kembali membuat episode baru untuk pelanggan asal.

## Status pengembangan

Intake, inspeksi, servis perangkat yang sama, pembacaan berpaginasi, penutupan
material serta pemasangan kembali RMA telah memiliki bukti integrasi PostgreSQL.
Serah terima bertanda tangan RMA menjaga pemilik CUSTOMER dan titleRevision0;
regresi konkurensi lintas pemasangan masih dalam validasi.
Penggantian fisik oleh vendor, reacquisition dengan approval independen,
panduan UI dan bukti packaged HTTP masih menjadi pekerjaan task26/lanjutan.

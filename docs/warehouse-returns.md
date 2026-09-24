# Retur dan inspeksi gudang

Retur memakai dokumen baru yang menunjuk asal fisiknya. Retur material menunjuk
residual WO yang sudah diterima; retur perangkat menunjuk pembongkaran assignment
yang sudah disaksikan. Penerimaan dan inspeksi tidak menghapus riwayat issue,
pemakaian, kepemilikan, pelanggan, atau episode ONU.

## API yang tersedia

Semua mutasi di bawah membutuhkan `Idempotency-Key` dan izin
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
| `GET /api/v1/warehouse/returns/{id}` | Membaca kondisi dokumen terkini |
| `GET /api/v1/warehouse/returns/{id}/history` | Membaca sampai100 revision awal dokumen |

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

## Status pengembangan

Dokumen ini mencatat intake/inspeksi, reuse dan servis vendor atas perangkat yang
sama. Penggantian fisik oleh vendor, pengembalian RMA kepada pelanggan
asal, reacquisition dengan approval independen, daftar berpaginasi, dan
penutupan kewajiban material masih dikerjakan pada task26. Riwayat saat ini
dibatasi100 revision awal; jangan menganggapnya sebagai ekspor audit lengkap.
Panduan UI dan bukti packaged HTTP menyusul sebelum task dinyatakan selesai.

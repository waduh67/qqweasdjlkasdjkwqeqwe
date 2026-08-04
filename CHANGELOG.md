# Changelog

Semua perubahan penting yang layak dicatat pada proyek ini. Format mengikuti
[Keep a Changelog](https://keepachangelog.com/id/1.1.0/); proyek belum memakai
versi rilis (trunk-based di `main`), jadi entri dikelompokkan per tanggal.

## [Belum dirilis]

### 2026-08-04 — Pembayaran manual (transfer / QRIS) + rework UI Paywuz

Bagian dari rework bertahap payment gateway ([`docs/payment-gateway.md`](docs/payment-gateway.md)),
"Langkah 1": mengisi celah saat gateway **nonaktif**/`MANUAL` yang tadinya tak punya instruksi bayar.

**Ditambahkan**
- **Pembayaran manual per-tenant** di halaman Payment Gateway: saklar **Transfer bank**
  (nama bank / nomor rekening / atas nama) dan **QRIS** (unggah gambar). Tampil ke pelanggan
  sebagai panel "Cara bayar" pada tagihan MANUAL yang belum lunas.
- **Object storage untuk gambar QRIS** — byte di MinIO/S3 dengan key `"$tenantId/billing/gateway/qris"`
  (DB hanya simpan `qris_storage_key` + `qris_content_type`). Port `ObjectStorage` dipromosikan dari
  modul `workorder` ke `com.duluin.ftth.common.storage` agar dipakai bersama.
- Endpoint baru: `POST/DELETE/GET /api/billing/gateway-settings/qris` (unggah/hapus/sajikan gambar,
  validasi `image/*` maks 5 MB) dan `GET /api/billing/manual-payment-instructions`.
- Migrasi **V54** — kolom manual di `tenant_payment_gateway` (plaintext, non-rahasia, semantik
  "selalu diganti").
- Paywuz: tampilan **URL webhook per-tenant** (read-only + tombol salin) di halaman setelan.

**Diubah**
- Pilihan metode Paywuz kini **dropdown statis** `QRIS` / `VA` ("Virtual Account (Pilih Bank)")
  + opsi "Default server (QRIS)", menggantikan pemuatan dinamis. Endpoint
  `GET /api/billing/gateway-settings/paywuz-methods` masih ada tapi tak lagi dipakai UI.
- Simpan setelan manual disatukan ke satu alur "Tinjau & simpan": PUT setelan dulu, baru unggah/hapus
  byte QRIS (preview lokal ditahan sampai save).

**Diperbaiki**
- Toggle status/manual yang balik ke *Nonaktif* setelah disimpan dan unggah QRIS yang 404 — akar
  masalah: tabrakan nomor versi Flyway (migrasi manual tak pernah jalan). Migrasi dipindah ke V54.
- Transfer bank tak tersimpan & terasa "auto-submit" setelah unggah gambar — kini unggah gambar
  ditunda ke alur simpan tunggal sehingga edit transfer tak hilang.

**Perkakas**
- `dev.sh` — `up`/`up-all` menunggu Postgres sehat lalu memastikan extension siap sebelum lanjut.

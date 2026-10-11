# Helpdesk dan prasyarat PSB — 11 Oktober 2026

Branch implementasi: `fix/helpdesk-psb-ui-20261011`, berbasis `main` pada
`45cd2aaf8ce30e63b80702dcda519443bec7c831`.

## Perubahan

- Helpdesk memakai command bar, filter langsung, tabel resource satu baris, dan
  pagination server. Ringkasan dipisahkan dari antrean kerja.
- Detail tiket memakai resource blade yang mempertahankan daftar di bawahnya;
  pembuatan work order merupakan blade anak dengan tinjauan sebelum pengiriman.
- Percakapan berupa baris teks dengan waktu dan pengirim. Balasan serta catatan
  eskalasi tidak dihapus saat API gagal. Aksi mengikuti izin dan transisi domain.
- Filter backend `unassigned=true` mengecualikan tiket CLOSED sebelum pagination,
  sesuai angka ringkasan dan arti antrean aktif.
- PSB membedakan kegagalan membaca paket dari ketiadaan paket aktif, menyediakan
  percobaan ulang dan tautan ke Paket Internet, serta memeriksa keempat izin endpoint.

## Diagnosis PSB

Pemeriksaan produksi secara read-only menemukan akun yang dilaporkan adalah tenant
owner dan tenant belum memiliki paket aktif. Tenant owner mendapat semua izin aktif
non-platform melalui assembler autentikasi, terlepas dari daftar izin role Admin.
Pemeriksaan role saja bukan bukti akses efektif. Tidak ada perubahan grant, identitas
owner, harga, maupun data paket. Operator perlu membuat atau mengaktifkan paket
pilihannya agar formulir PSB tersedia.

## Validasi dan pemulihan

- Tes komponen: prasyarat PSB, error/retry, permission gate, filter/pagination,
  draft gagal kirim, eskalasi bertingkat, izin baca, dan transisi/penutupan tiket.
- Playwright: Chromium dan Firefox pada 375/1280 px; jarak command/filter, tabel
  teks biasa, draft balasan, eskalasi, filter tetap tersimpan, identitas dokumen
  tidak berubah saat blade ditutup, serta prasyarat PSB untuk akun non-platform.
- `HelpdeskIT`: 4 tes lulus, termasuk portal → balasan → work order, pembukaan ulang,
  isolasi pelanggan, dan antrean belum ditugaskan sebelum pagination.
- Log dan screenshot lokal: `.omo/runtime/express-psb/` dan
  `.omo/runtime/network-ui/browser-artifacts/`. Data browser merupakan fixture.
- Deploy melalui workflow GitHub Actions `deploy`, setelah CI PR lulus dan merge
  ke main. Workflow mencadangkan database/config serta menyimpan aktivasi pada
  `/opt/ftth/releases/<merge-sha>/activation.json`. Tidak ada migrasi baru.
- Setelah deploy: periksa readiness publik, asset frontend, dan revision image
  server/web terhadap merge SHA. Riwayat status deploy tersedia di GitHub Actions.

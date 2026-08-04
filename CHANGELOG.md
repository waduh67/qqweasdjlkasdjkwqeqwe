# Changelog

Semua perubahan penting yang layak dicatat pada proyek ini. Format mengikuti
[Keep a Changelog](https://keepachangelog.com/id/1.1.0/); proyek belum memakai
versi rilis (trunk-based di `main`), jadi entri dikelompokkan per tanggal.

## [Belum dirilis]

### 2026-08-04 — Area UI khusus Platform admin (SaaS) terpisah dari UI tenant

Platform admin (SaaS super-admin, `user.platformAdmin`) kini punya **shell & dashboard
sendiri** di namespace `/platform/*`, tak lagi bercampur dengan menu operasional tenant.
Murni pekerjaan frontend — tanpa endpoint atau permission baru.

**Ditambahkan**
- **Shell platform** `PlatformLayout` (`web/src/components/PlatformLayout.tsx`): sidebar khusus
  platform (Dashboard, Tenant, Billing Langganan, Server VPN, plus Administrasi Platform:
  Pengguna/Role/Audit) dengan brand "NetOps · Platform" dan pintasan "↗ Tampilan Tenant" untuk
  inspeksi area tenant.
- **Dashboard SaaS** `PlatformDashboardPage` (`web/src/pages/PlatformDashboardPage.tsx`): lean,
  dirakit dari endpoint yang ada — ringkasan portofolio tenant (total/aktif/ditangguhkan), gateway
  aktif & biaya bulanan default, daftar tenant, dan pintasan.
- **Namespace rute `/platform/*`** dengan penjaga `RequirePlatformAdmin`; halaman platform yang ada
  (`TenantsPage`, `PlatformBillingSettingsPage`, `VpnServersPage`, `UsersPage`, `RolesPage`,
  `AuditPage`) dipakai ulang apa adanya, hanya di-mount di path baru.

**Diubah**
- **Landing platform admin** kini otomatis ke `/platform` sesudah login (operator tenant tetap ke
  beranda operasional). Pengalihan hanya saat login — beranda tenant `/` tetap bisa dibuka platform
  admin lewat "Tampilan Tenant" tanpa terlempar balik.
- **Nav tenant** (`Layout`) tak lagi memuat item platform (Tenant, Billing Langganan, Server VPN) —
  item-item itu memang hanya pernah terlihat oleh platform admin. Sebagai gantinya, saat platform
  admin menengok area tenant, sidebar memunculkan pintasan **"Tampilan Platform"** (balik ke `/platform`).
- **Rute platform lama jadi redirect** demi jaga bookmark: `/tenants` → `/platform/tenants`,
  `/platform-billing` → `/platform/billing`, `/vpn-servers` → `/platform/vpn-servers`.

### 2026-08-04 — Langganan SaaS: harga default global + override khusus + self-service tenant

Model harga langganan aplikasi (SaaS) dirapikan menjadi **flat + override** dengan halaman
langganan mandiri sisi tenant. Strategi lengkap: [`docs/saas-subscription.md`](docs/saas-subscription.md).

**Ditambahkan**
- **Harga bulanan default global** di setelan Billing Langganan Platform (satu harga untuk semua
  tenant). Migrasi **V62** — kolom `default_monthly_fee` pada `platform_setting`.
- **Override harga khusus saat onboarding tenant**: form "Onboarding tenant" punya kolom
  "Harga bulanan khusus" (kosong = pakai harga default global). Langganan tenant dibuat otomatis
  saat onboarding (idempotent), plus **backfill** memastikan tiap tenant lama punya langganan
  (tenant `platform` dikecualikan).
- **Halaman "Langganan Aplikasi" sisi tenant** (`/subscription`, izin `billing.subscription.view`):
  tata letak lebar penuh — hero biaya + masa aktif (bar progres periode), pemakaian kosmetik (mis.
  "OLT 10 / Unlimited" — tanpa batas nyata), riwayat tagihan, dan tombol **Perpanjang** mandiri lewat
  gateway aktif (izin `billing.subscription.renew`).
  Endpoint baru `GET /api/subscription` + `POST /api/subscription/renew`.
- **Bayar di muka beberapa bulan sekaligus** (1 / 3 / 6 / 12 bulan): pemilih durasi di halaman
  langganan; `POST /api/subscription/renew?months=N` (1..12) menerbitkan satu tagihan `biaya × N`
  berperiode N bulan, dan saat LUNAS masa aktif memanjang N bulan. Jumlah bulan diturunkan dari
  rentang periode tagihan (tanpa kolom/migrasi baru); `next_invoice_at` dilompatkan agar scheduler
  tak menagih dobel di bulan yang sudah prabayar.

**Diubah**
- **Masa aktif bertambah saat tagihan LUNAS**, bukan saat tagihan terbit. Penerbitan tagihan hanya
  memajukan jadwal tagih berikutnya; pelunasan (webhook gateway / manual) memperpanjang
  `current_period_end` sebulan (menumpuk bila masa aktif belum habis).
- Langganan baru diberi **masa aktif awal sebulan** dengan tagihan pertama terbit menjelang periode
  habis — mencegah tenant baru langsung tertagih/tersuspend oleh scheduler.

**Internal (batas modul)**
- Provisioning langganan saat onboarding dipisah dari `iam` lewat event `TenantOnboardedEvent`
  (bukan panggilan port langsung) — memutus siklus modul `iam → platformbilling → billing → … → iam`
  yang ditegakkan `ModularityTests`.
- Mesin payment gateway `billing` (registry + port + value type) di-expose sebagai **named interface**
  Spring Modulith `gateway` agar `platformbilling` memakainya ulang tanpa menembus enkapsulasi.
- `suspend(id)`/`activate(id)` dipromosikan ke `TenantApi` (kontrak lintas-module) menggantikan akses
  `ManageTenantUseCase` internal tenancy.

### 2026-08-04 — Input SNMP untuk OLT vendor HSGQ

**Diperbaiki**
- Form **tambah OLT** kini menampilkan input SNMP (community string / versi / port) saat vendor
  **HSGQ** dipilih. Sebelumnya seksi SNMP disembunyikan karena asumsi keliru "HSGQ tak berbicara
  SNMP" — padahal HSGQ EPON nyatanya dipolling lewat SNMP (`HsgqEponSnmpAdapter` terdaftar di
  poller). HSGQ kini bersifat **dual-channel**: memilih vendor HSGQ menyalakan SNMP **dan** Web UI,
  keduanya sekaligus.

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

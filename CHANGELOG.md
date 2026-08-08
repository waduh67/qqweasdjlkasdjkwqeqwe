# Changelog

Semua perubahan penting yang layak dicatat pada proyek ini. Format mengikuti
[Keep a Changelog](https://keepachangelog.com/id/1.1.0/); proyek belum memakai
versi rilis (trunk-based di `main`), jadi entri dikelompokkan per tanggal.

## [Belum dirilis]

### 2026-08-08 — Redesign Azure Fase 6: seragamkan aksi tabel ke menu `…` + pratinjau tagihan

**Diubah**
- **Semua tabel data memakai menu aksi per-baris `…` (kebab)** ala tabel Pelanggan,
  menggantikan tombol inline (Hapus/Ubah/Batal, dll.) yang memenuhi kolom. Tercakup:
  Area, Role, BRAS/RADIUS, Server VPN, Monitoring (collector + alarm), Tenant, Akun VPN,
  dan inbox Provisioning ONU. Aksi tetap tergerbang izin — baris tanpa aksi (mis. tenant
  `platform`) tak menampilkan menu.
- **Akun VPN dirampingkan** dari 5 tombol per baris menjadi satu menu `…` (Unduh
  RouterOS/`.ovpn`, Nonaktifkan/Aktifkan, Rotasi password, Hapus).

**Diperbaiki**
- **Klik baris tagihan tak lagi nyasar ke dashboard.** Sebelumnya baris Tagihan
  menembak rute mati `/customers/:id`; kini membuka **Blade pratinjau** seperti tabel
  lain — berisi rincian DPP/PPN/Total, tanggal (periode/terbit/jatuh tempo/dibayar),
  dan riwayat pembayaran.

**Ditambahkan**
- **Cetak / Unduh PDF tagihan** dari menu `…` dan footer pratinjau — merakit HTML
  tagihan dan memanggil cetak browser lewat `<iframe>` tersembunyi (tanpa endpoint PDF
  server). Aksi lain di menu: Catat bayar & Batalkan, sesuai izin.

### 2026-08-07 — Redesign Azure Fase 5: seragamkan header halaman

**Diubah**
- **Semua header halaman memakai `PageHeader`.** Halaman non-tabel dan realm lain
  (dashboard tenant/platform, laporan, audit, monitoring, insiden, area, tugas saya,
  langganan, provisioning, impor CSV/PPPoE, setelan pajak/gateway/billing, PSB ekspres,
  tenant, VPN, server VPN, BRAS/RADIUS) kini memakai komponen `PageHeader`
  (judul + subtitle + slot `actions`) menggantikan `<h1 className="page-title">` lepas —
  header seragam di seluruh konsol. Halaman **detail** (Pelanggan/OLT/Work Order)
  sengaja dipertahankan karena judulnya menyandingkan badge status inline.

### 2026-08-07 — Redesign Azure Fase 4: kontrol form Fluent + validasi

**Ditambahkan**
- **Validasi klien inline pada form.** Isian wajib divalidasi sebelum menembak API —
  galat tampil di bawah field lewat komponen `Field` (`error`/`required`) plus
  `aria-invalid` pada kontrol, dan toast ringkas bila ada yang kosong. Diterapkan sebagai
  eksemplar di form Pelanggan (Nama & Alamat wajib) dan penanda wajib di form Role.
- **Token `--danger`** (merah validasi Fluent, ter-tema terang/gelap) untuk state galat form.

**Diubah**
- **Keadaan kontrol form ala Fluent.** Input/select/textarea **disabled** kini redup +
  kursor terlarang; **checkbox & radio** memakai aksen Azure (`accent-color`) dan tak lagi
  melar 100% lebar; keadaan **tak valid** memberi bingkai + cincin merah (dipicu
  `.field-error` atau `aria-invalid`).

### 2026-08-07 — Penajaman kesetiaan Azure: tab, blade, dialog, sidebar, konteks

Lanjutan redesign Azure — merapikan pola yang belum konsisten dengan Portal.

**Ditambahkan**
- **Dialog in-app `useConfirm`/`usePrompt`.** `DialogProvider` (di `App.tsx`) menyediakan
  `confirm(opts) => Promise<boolean>` & `prompt(opts) => Promise<string | null>` berbasis
  [Modal], menggantikan **semua** `window.confirm`/`window.prompt`/`alert` bawaan browser
  (VPN, Server VPN, Pengguna, Role, langganan Tenant, BNG, Inventory, Provisioning, detail
  Pelanggan, dll). Aksi merusak memakai `danger`.
- **Seksi sidebar bisa diciutkan (dropdown chevron).** Komponen bersama `SidebarNav` merender
  tiap grup berlabel sebagai tombol chevron ala Azure left-nav; status ciut per-seksi disimpan
  di localStorage. Dipakai `Layout` (tenant) & `PlatformLayout` (platform).

**Diubah**
- **Bilah tab jadi underline-tab ala Azure.** Komponen `Tabs` (indikator garis-bawah + hitungan)
  menggantikan `.segment` pada navigasi tab halaman: detail Pelanggan, detail OLT, Inventory
  (Site/OLT/ODC/ODP), dan filter status Provisioning (`DiscoveredOnuInbox`). Kontrol segmented
  kecil (pemilih periode/scope/perangkat) tetap `.segment`.
- **Switcher konteks Platform ↔ Tenant.** `EnvSwitcher` berpindah lewat `useNavigate` eksplisit
  (andal) dan tampil sebagai pemilih direktori/langganan ala Azure (titik status + nama konteks
  + kapsi + chevron, dropdown pilihan).
- **Detail Pelanggan kini flyout (Blade), bukan rute.** Rute `/customers/:id` dihapus; detail
  muncul sebagai Blade dari daftar (lebar 50% di desktop, penuh di tablet/mobile). Tombol
  **Hapus** di detail dibuang. Blade menerima `className` untuk penyetelan lebar.
- **Lebar halaman diseragamkan.** `.settings-page` tak lagi dibatasi `max-width`/`margin auto`
  (mis. `/payment-gateway`, `/platform/billing`) — kini penuh & rata-kiri seperti halaman lain.
- **Font mendekati Azure** — tumpukan `Segoe UI Variable` di depan + `font-optical-sizing: auto`.

### 2026-08-07 — Redesign UI/UX ala Microsoft Azure Portal dengan Fluent UI (Fase 0–3)

Perombakan tampilan & alur kerja agar mencerminkan Azure Portal — bukan sekadar warna,
tapi juga pola interaksi. Dikerjakan bertahap; detail rencana di
[`docs/azure-fluent-redesign.md`](docs/azure-fluent-redesign.md).

**Ditambahkan**
- **Tema Azure + FluentProvider (Fase 0).** `@fluentui/react-components` v9 dengan brand Azure Blue
  (`#0078D4`), tema terang/gelap terjembatani ke `data-theme`/localStorage lewat `ThemeProvider`
  (`web/src/theme/`). Ikon memakai `lucide-react`.
- **Breadcrumb global + kepala halaman (Fase 1).** Komponen `Breadcrumbs` (Fluent Breadcrumb,
  label Indonesia per-segmen) tampil di atas tiap halaman pada `Layout` & `PlatformLayout`;
  komponen `PageHeader` menyeragamkan judul + subjudul.
- **CommandBar ala Azure (Fase 2).** `CommandBar` menaruh aksi primary `+ Tambah` menonjol di
  paling kiri, aksi sekunder (Hapus/Ekspor/Impor/Segarkan) berjajar berkelompok dengan ikon.
  Tombol **Hapus nonaktif sampai ada baris terpilih**.
- **DataGrid multi-select (Fase 2).** `DataTable` kini punya kolom **checkbox** pilih-semua/
  per-baris dan **menu aksi `…`** per baris (Fluent Menu) — dipakai di halaman Pelanggan, Pengguna,
  Paket Internet, dan Inventory (Site/OLT/ODC/ODP). Baris terpilih ditandai aksen kiri.
- **Blade (panel geser kanan) untuk semua form (Fase 3).** Komponen `Blade` (Fluent `OverlayDrawer`)
  menggantikan modal terpusat untuk **semua form buat/sunting**: header berjudul + `X`, body ter-scroll,
  dan **footer sticky** dengan tombol **Simpan** primary di kiri & **Batal** di kanan (konvensi Azure).
  ESC/klik-luar menutup panel; bila form **kotor** diminta konfirmasi dulu agar perubahan tak hilang.
  Ukuran mengikuti kompleksitas (`sm`/`lg`/`full`). Turut disertakan primitif `FormSection` & `Field`.
  Dipakai di Pelanggan, Pengguna (buat + editor Akses), Paket Internet, Inventory (Site/OLT/ODC/ODP +
  uplink ODC), Work Order, BNG/BRAS, Tenant, langganan Tenant, dan Edit OLT. Dialog **konfirmasi** dan
  **panel bayar** (alur aksi, bukan form) tetap sebagai modal.

**Diubah**
- **Sidebar jadi terang ala Azure left-nav** dengan indikator aktif biru Azure, pengelompokan
  seksi, dan status ciut/lebar.
- **Aksi per-baris dipindah dari tombol inline ke menu `…`** (Edit/Hapus/Uplink/Akses/Aktivasi),
  dan tombol Tambah/Ekspor/Impor dari kepala halaman dipindah ke CommandBar.

**Ditambahkan**
- **Undang & kirim ulang undangan pengguna sub-account.** Tenant bisa mengundang admin ke
  sub-account Pivot-nya (email + nama, `POST /v1/sub-merchants/admin`) dan mengirim ulang undangan
  (`POST /v1/sub-merchants/users/resend-invitation`) — keduanya on-behalf lewat `x-submerchant-id`.
  Seksi baru "Pengguna sub-account" di kartu Sub-account Pivot (`PaymentGatewaySettingsPage`),
  di-gate izin `billing.gateway.manage`, muncul setelah sub-account terprovisi.
- **Sistem local payout ke rekening beneficiary bebas.** Seksi "Saldo & Payout": tampilkan saldo
  payout sub-account lalu kirim dana ke bank + nomor rekening tujuan apa pun (pilih bank via
  dropdown channel, nominal rupiah, deskripsi). Server memvalidasi nama pemilik lewat inquiry &
  **WAJIB mengecek saldo sebelum membuat payout** — payout ditolak (`ConflictException`) bila saldo
  tak cukup, tanpa menembak Pivot. Riwayat payout ditampilkan dengan status (Menunggu/Diproses/
  Berhasil/Gagal) + alasan gagal.

**Diubah**
- **Body `POST /v1/payouts` diselaraskan dengan dokumentasi Pivot** — kini array `payouts[]`
  dengan `referenceId`, `amount.value` (rupiah utuh), `description`, dan `inquiryId` (bila ada) atau
  `channelInformation{accountNumber,accountName}`. Withdrawal KYC (`/v1/withdrawals`) tetap memakai
  bentuk flat lama (builder terpisah). Referensi payout dibaca dari `data.uuid` (rekonsiliasi
  callback juga mengenali `uuid`).
- **Saldo payout dibaca dari `GET /v1/payouts/balance?currency=IDR`** (menggantikan
  `/v1/balances?usecase=PAYMENT`); `availableBalance.value` yang berupa string desimal 2-angka
  dibulatkan ke bawah jadi rupiah utuh agar konsisten dengan `amount.value` saat create payout.
  Saldo dibaca on-behalf sub-account tenant begitu terprovisi.

### 2026-08-07 — Simpan profil sub-account Pivot tak lagi inquiry + daftar satu klik + dropdown channel

**Diperbaiki**
- **"Simpan profil" gagal `400` "POST /v1/inquiry-account: Make sure value is fulfilled".** Simpan
  profil dulu memicu inquiry untuk memvalidasi rekening, padahal `inquiry-account` baru bisa jalan
  SETELAH sub-account ada di Pivot — jadi selalu ditolak sebelum tenant sempat mendaftar. Simpan
  profil kini murni menyimpan (channel + nomor rekening) tanpa menembak Pivot; inquiry berjalan
  otomatis best-effort setelah provisioning (`TenantPivotAccount.setPayoutDestination`,
  `TenantPivotAccountService`).

**Diubah**
- **"Daftarkan sub-account" jadi satu klik** — otomatis menyimpan profil bila ada perubahan lalu
  memprovisi, jadi tak perlu lagi menekan "Simpan profil" dulu (yang justru error). "Simpan profil"
  tetap ada sebagai aksi sekunder untuk menyimpan draf. Bila auto-inquiry saat provisioning gagal,
  "Simpan rekening" pasca-provisioning bisa memicu ulang validasi (`PaymentGatewaySettingsPage`).
- **Kode channel bank kini dipilih dari dropdown yang bisa dicari & dikelompokkan per tipe**
  (Bank / E-Wallet / Virtual Account), bukan input bebas — mencegah salah ketik (mis. `MANDIRI`
  vs `MANDIRI_TASPEN`). Daftar channel ditranskrip dari dokumen Pivot "Channel Codes"
  (`data/pivotReference.ts`); `Combobox` menerima prop opsional `groupOf` untuk header grup.

### 2026-08-07 — Rekening payout digabung ke profil sub-account Pivot

**Diperbaiki**
- **Provision sub-account Pivot gagal `400` "channelCode/accountNumber value is fulfilled".**
  Pivot mewajibkan `bankAccount` (channel + nomor rekening) di body `POST /v1/sub-merchants`, tapi
  rekening payout dulu disetel di langkah TERPISAH setelah provisioning — sehingga saat create,
  `bankAccount` kosong dan ditolak.

**Diubah**
- **Rekening payout kini bagian dari profil sub-account, bukan langkah terpisah.** Kode channel +
  nomor rekening diisi bersama identitas/PIC/alamat sebelum "Daftarkan sub-account", divalidasi ke
  bank (`inquiry-account`) saat simpan profil, lalu dikirim sebagai `bankAccount` saat create —
  jadi rekening selalu ada di body create. Kelengkapan profil (`profileComplete`) kini juga menuntut
  rekening payout. Bagian "Rekening payout" hanya muncul pasca-provisioning untuk mengganti rekening
  (`TenantPivotAccount`, `TenantPivotAccountService`, `PaymentGatewaySettingsPage`, `pivotAccount.ts`).

### 2026-08-07 — Default sub-account Pivot pakai dropdown + galat validasi self-diagnosing

**Diperbaiki**
- **Galat validasi Pivot tak menyebut field yang salah.** Pesan yang tampil hanya wrapper
  generik ("The request was invalid, or an error occurred in downstream provider") karena parser
  hanya membaca `message` level atas; field yang gagal sebenarnya ada di `error.details[].message`
  (string validator Go, mis. `Field validation for 'BusinessStructure' failed on the 'required'
  tag`). Parser kini mendahulukan `error.details[]`, lalu `errors[]`, baru `message`/`error`
  generik — sehingga penolakan `POST /v1/sub-merchants` langsung menunjuk field bermasalah
  (`PivotApiClient`).

**Diubah**
- **Default sub-account Pivot (`/platform-billing`) kini dropdown, bukan input bebas.** Field
  yang nilainya harus sama persis dengan daftar Pivot rawan salah ketik (mis. `businessStructure`
  "PT" vs "PERSEROAN TERBATAS", atau MCC yang tak cocok pasangan industrinya). Struktur bisnis,
  industri induk→anak, dan negara bisnis/entitas kini dipilih dari daftar; **MCC terisi otomatis**
  dari anak industri; **district** dipilih lewat combobox pencari atas ~7.200 district (data
  di-*dynamic import* agar tak membebani bundel awal). Data referensi baru: `pivotReference.ts`,
  `pivotDistricts.ts` (`PlatformBillingSettingsPage`).

### 2026-08-07 — Perbaiki provisioning Pivot (400 EOF) & metode pembayaran ke-reset

**Diperbaiki**
- **Provisioning sub-account Pivot gagal `400`.** Penukaran token `POST /v1/access-token`
  dikirim tanpa body → handler Pivot men-decode body kosong dan menolak (`EOF`); setelah diberi
  body, ketahuan field `grantType` (camelCase) wajib diisi. Kini call itu mengirim
  `Content-Type: application/json` + body `{"grantType":"client_credentials"}` (kredensial tetap
  lewat header `X-MERCHANT-ID`/`X-MERCHANT-SECRET`). Pesan galat Pivot juga kini menyebut
  endpoint yang gagal (`Pivot menolak POST /v1/… (400): …`) agar mudah didiagnosis
  (`PivotApiClient`).
- **Metode pembayaran di `/payment-gateway` ke-reset ke "Manual" tiap ada toast.** Objek API
  toast tak di-memo, sehingga tiap notifikasi (mis. simpan profil / galat provision) mengubah
  identitas context dan memicu `useEffect` ber-dep `[toast]` memuat ulang setelan — menimpa
  pilihan "Pivot" yang belum disimpan. API toast kini di-memo (`ui.tsx`), memperbaiki reset ini
  dan fetch-ulang tak sengaja di seluruh halaman lain.

### 2026-08-06 — Migrasi penuh payment gateway ke Pivot

Payment layer dipangkas jadi **Pivot-only** dengan model **"business as platform"**: satu
akun **MASTER** Pivot milik platform menampung semua transaksi, tiap tenant jadi **sub-account**
yang ditagih on-behalf (+ potong fee platform via split routing). Seluruh penyedia lain
(**Xendit/Midtrans/Paywuz**) dan model **BYOK** per-tenant **dihapus**. Ditambah fase payout/
withdrawal untuk menyalurkan dana tenant. Dokumentasi baru: `docs/pivot-overview.md`,
`docs/pivot-sub-account.md`, `docs/pivot-fee-split.md`, `docs/pivot-payout.md`.

**Ditambahkan**
- **Setelan MASTER Pivot** (`pivot_master_config`, migrasi `V70`; singleton PLATFORM-level
  tanpa RLS): kredensial terenkripsi (merchant id/secret + Callback API Key), toggle sandbox,
  fee platform per transaksi (`platform_fee_minor`/`platform_fee_type` FIXED|PERCENTAGE),
  rekening payout platform. Domain `PivotMasterConfig`, service `PivotMasterConfigService`,
  provider `PivotMasterConfigProvider` (`@NamedInterface("gateway")`). Endpoint super-admin
  `GET/PUT /api/platform/pivot-config` (`PivotMasterConfigController`, izin `platform.billing.*`).
- **Sub-account Pivot per-tenant** (`tenant_pivot_account`, migrasi `V71`; tenant-scoped + RLS):
  domain `TenantPivotAccount` (`SubAccountType` NON_KYC/KYC, `SubAccountStatus`,
  `SubAccountKycStatus`, rekening payout + `payout_inquiry_id`). Auto-provisi **NON_KYC** saat
  onboarding lewat `TenantPivotAccountProvisioningListener` (`TenantOnboardedEvent`, AFTER_COMMIT,
  dalam `TenantContext.runAs`). Service `TenantPivotAccountService`, adapter
  `PivotSubMerchantGateway` (`POST /v1/sub-merchants`, `GET /v1/sub-merchants/{uuid}`,
  `POST /v1/inquiry-account`). Endpoint `GET /api/billing/pivot-account`,
  `POST .../provision|refresh|request-kyc|payout-account` (`TenantPivotAccountController`).
- **Payout & withdrawal** (`tenant_payout`, migrasi `V72`; tenant-scoped + RLS): domain
  `TenantPayout` (`PayoutKind` PAYOUT/WITHDRAWAL, `PayoutStatus` PENDING→PROCESSING→SUCCESS/FAILED),
  service `TenantPayoutService`, adapter `PivotPayoutGateway` (`POST /v1/payouts`,
  `POST /v1/withdrawals` on-behalf, `GET /v1/balances`). Nominal eksplisit (belum ada scheduler
  otomatis — follow-up). Endpoint `GET /api/billing/pivot-account/balance|payouts`,
  `POST .../payouts|withdrawals` (`TenantPayoutController`). Rekonsiliasi
  `POST /api/billing/webhooks/{slug}/pivot-payout` (`PivotPayoutWebhookController`, verifikasi
  `X-API-Key` master, idempotent).
- **Split routing fee platform** (`PivotPaymentGateway.splitRouting`): fee (FIXED / PERCENTAGE
  dikonversi ke nominal) dipotong dari hasil tenant ke merchant id master; di-skip untuk charge
  langganan SaaS & saat fee 0 / ≥ nominal.
- **Klien Pivot bersama** `PivotApiClient`: OAuth `POST /v1/access-token` (Bearer ~900 dtk,
  cache per merchant-id), base URL sandbox/prod, header `x-submerchant-id` (on-behalf) &
  `X-REQUEST-ID` (idempotency), galat HTTP → `ConflictException`.

**Diubah**
- **`tenant_payment_gateway` dirampingkan** (migrasi `V69`): kolom `mode`, `api_key`, `secret_key`,
  `webhook_token`, `sub_account_id`, `payment_method` **dibuang**; `provider` dibatasi
  `PIVOT | MANUAL` (CHECK baru). Domain `TenantPaymentGateway` tak lagi menyimpan kredensial —
  hanya metode aktif + konfigurasi pembayaran manual (transfer/QRIS). Controller
  `PaymentGatewaySettingsController` (`/api/billing/gateway-settings`) tanpa field kredensial.
- **Resolusi gateway** `TenantPaymentGatewayResolver`: PIVOT (mode PLATFORM di akun master +
  `x-submerchant-id` + split fee) bila master aktif & sub-account siap (bukan DEACTIVATED/REJECTED),
  else fallback **MANUAL**. Charge Pivot pindah on-behalf sub-account (`PivotPaymentGateway`,
  `POST /v2/payments` REDIRECT); callback verifikasi `X-API-Key` master (status PAID/SETTLED/SUCCESS).
- **Penagihan langganan SaaS** `platformbilling.PlatformGatewayResolver`: charge langsung di akun
  master (`subAccountId=null`, tanpa split → 100% ke platform) via `PivotMasterConfigProvider`;
  menolak jelas bila master belum dikonfigurasi.
- **Callback Pivot direstrukturisasi** dari URL per-tenant-slug (`/api/billing/webhooks/{slug}/pivot`,
  `.../pivot-payout`, `/api/platform/billing/webhooks/pivot`) menjadi **10 endpoint platform per-produk**
  di bawah `/api/platform/pivot/callbacks/*` (Pivot master mendaftarkan satu Callback URL per produk):
  `payment` (dipilah customer vs langganan SaaS via metadata `scope`), `payout`/`withdrawal`/
  `international-payout`/`refund`, `sub-account-registration`, dan `wallet`/`wallets`/
  `wallet-account-linkage-activation`/`wallet-user-activation` (produk wallet tak dipakai → no-op ACK 200).
  Semua verifikasi `X-API-Key` master (constant-time). Setelan Billing Langganan Platform kini
  menampilkan ke-10 URL siap-salin per produk (`web/src/pages/PlatformBillingSettingsPage.tsx`).
- **Dokumentasi** `docs/payment-gateway.md`, `docs/billing.md`, `docs/saas-subscription.md`
  disesuaikan ke model Pivot master+sub-account.

**Dihapus**
- **Penyedia Xendit/Midtrans/Paywuz & model BYOK** (migrasi `V69`): kredensial gateway per-tenant,
  tabel `platform_payment_gateway`, kolom `platform_setting.active_payment_provider`, dan mode
  BYO/PLATFORM per-penyedia. Auto-provision sub-account xenPlatform & endpoint
  `POST /api/billing/platform/gateway/{tenantId}/xendit-subaccount` ikut hilang. Satu-satunya
  gateway kini Pivot; alternatifnya pembayaran manual (transfer/QRIS).
- **Izin `billing.gateway.provision`** (provisi sub-account Xendit PLATFORM) dihapus dari
  `PermissionCatalog`; `PermissionCatalogSeeder` menonaktifkannya otomatis saat startup.

### 2026-08-06 — Peta pusatkan ke lokasi pengguna + kode kabel auto-generate

Dua penyempurnaan alur lapangan. **Peta Jaringan** (`/map`) kini otomatis memusatkan diri
ke lokasi pengguna saat dibuka, dan **kode kabel** tak lagi diisi manual — dibuat otomatis
di backend.

**Ditambahkan**
- **Peta pusatkan ke lokasi pengguna** (`web/src/pages/MapPage.tsx`): saat `/map` dibuka,
  peta meminta izin lokasi lalu memusatkan diri (`flyTo`) ke area pengguna via Geolocation
  API. Bila izin ditolak/gagal, peta tetap di pusat default (Bekasi) tanpa mengganggu.
  Ditambah tombol **"Lokasi saya"** di toolbar untuk memusatkan ulang kapan saja (tampil
  untuk semua peran — geolokasi bukan aksi tulis). Ikon baru `IconCrosshair`.

**Diubah**
- **Kode kabel auto-generate di backend**: saat menarik kabel, `code` kini dibuat otomatis
  sebagai **UUIDv7** (terurut waktu) di `CableService.create` bila frontend tak mengirimnya —
  field **Kode** dihapus dari form tarik kabel (`SaveCablePanel`). Request `code` menjadi
  opsional (`CableRequest`, `SaveCableCommand`). Sekalian memperbaiki bug laten:
  `create` sebelumnya menyimpan `command.code` mentah padahal cek duplikat memakai versi
  ter-normalisasi (trim + uppercase) — kini keduanya memakai kode hasil resolusi yang sama.

### 2026-08-06 — Webhook copyable semua penyedia + bayar ikut penyedia gateway aktif

Dua perbaikan alur pembayaran gateway. Setelan **Payment Gateway** kini memunculkan URL
webhook siap-salin untuk **semua** penyedia (bukan hanya Paywuz), dan tombol **bayar** di
detail pelanggan selalu mengikuti penyedia yang **aktif sekarang** — bukan penyedia saat
tagihan diterbitkan.

**Diubah**
- **URL webhook copyable untuk semua penyedia** (`web/src/pages/PaymentGatewaySettingsPage.tsx`):
  Xendit, Midtrans, Pivot, dan Paywuz kini sama-sama menampilkan field **URL webhook** readonly
  + tombol **Salin** + hint tempat menempel per-penyedia (sebelumnya hanya Paywuz; penyedia lain
  cuma menyebut path di catatan). Catatan panjang per-penyedia diringkas ke panduan kredensial
  saja (path callback tak lagi diulang — sudah diwakili field).
- **Perbaikan slug pada URL webhook**: path memakai **slug** tenant
  (`/api/billing/webhooks/<slug>/<provider>`), bukan `tenantId` (UUID) — selaras dengan
  `BillingWebhookController` yang me-resolve tenant lewat slug. Sebelumnya URL Paywuz memakai
  UUID sehingga callback tak akan cocok.

**Ditambahkan**
- **Bayar mengikuti penyedia gateway aktif** ("regenerate saat bayar"): endpoint baru
  `POST /api/billing/invoices/{id}/recharge` (izin `billing.invoice.manage`) membuat ulang
  tautan bayar sebuah tagihan lewat penyedia yang aktif sekarang. Idempoten bila penyedianya
  sudah sama; tanpa tautan bila penyedia aktif MANUAL; ditolak untuk tagihan lunas/batal.
  Backend: `InvoiceGenerator.refreshCharge`, `ManageInvoiceUseCase.refreshPaymentLink`,
  `InvoiceService`. Frontend: tombol **bayar** di tab Tagihan detail pelanggan
  (`web/src/pages/CustomerDetailPage.tsx`) memanggil endpoint lalu membuka tautan terbaru;
  kini juga muncul untuk tagihan ISSUED/OVERDUE yang belum punya tautan.

### 2026-08-04 — Redesign UI: sidebar gelap, aksen indigo, switcher lingkungan

Penyegaran visual menyeluruh design system in-house "NetOps Console" (`web/src/index.css`)
terinspirasi dashboard SaaS modern. Murni frontend — tanpa endpoint, permission, atau
perubahan kontrak API. Semua ~30 halaman ikut berganti lewat satu titik ungkit (token CSS).

**Diubah**
- **Palet aksen jadi indigo–biru** (`#4f46e5` light, `#818cf8` dark) menggantikan aksen lama;
  seluruh warna dipusatkan pada token `--accent` + turunannya (`--accent-hover/-soft/--focus-ring`,
  gradient logo, box-shadow tombol via `color-mix`) — tak ada lagi nilai warna ter-hardcode.
- **Sidebar permanen gelap** (charcoal-navy) lepas dari tema terang/gelap konten lewat token
  khusus `--sidebar-*`, dengan link aktif ber-rib aksen indigo di tepi kiri.
- **Kartu statistik** dirapikan (buang bilah aksen tebal, pakai titik status halus) dan tabel
  bergaya lebih bersih (header uppercase abu, padding lega).
- **Scrollbar kustom** untuk Chrome (`::-webkit-scrollbar`) & Firefox (`scrollbar-*`), termasuk
  varian terang untuk sidebar gelap.
- **Halaman berbasis formulir** (`/payment-gateway`, `/platform/billing`) kini satu kolom
  terpusat (`.settings-page`, `max-width` + `margin-inline: auto`) — menghapus gutter kosong
  lebar di sisi kanan pada layar besar.
- **Font Plus Jakarta Sans** (Google Fonts, `display=swap`) dengan fallback `system-ui`.

**Ditambahkan**
- **Switcher lingkungan** (`web/src/components/EnvSwitcher.tsx`): pil dropdown di puncak sidebar
  untuk platform admin berpindah antara "Tampilan Platform" ↔ "Tampilan Tenant", menggantikan
  item nav lama. Hanya tampil untuk platform admin.
- **Ikon aksi** pada manajemen pengguna (`/users`): tombol tambah (`+`), akses (kunci),
  aktif/nonaktif (power), dan hapus (trash) kini semuanya bericon; ikon baru `IconTrash`,
  `IconPower`, `IconKey`, `IconCheck` di `web/src/components/icons.tsx`.

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

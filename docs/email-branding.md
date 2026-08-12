# Email: SMTP, merek, dan baris subjek (platform → tenant)

Seluruh tumpukan email aplikasi dalam satu dokumen: dari **sambungan SMTP** mana surat
berangkat, **atas nama siapa** ia terbaca di kotak masuk pelanggan, dan **subjek** apa yang
tertulis di sana. Tiga hal itu dulu terkunci di env dan di konstanta kode; sejak **V97**
semuanya setelan yang bisa diubah admin platform tanpa restart, dan sebagian boleh **ditimpa
per tenant**.

Satu aturan menjelaskan hampir semuanya:

> **Yang diisi tenant menang; yang dikosongkan mewarisi platform; yang tak diisi platform
> jatuh ke bawaan di kode/env.**

Modul: **`notification`**. Tabel platform tanpa RLS, tabel tenant ber-RLS penuh.

---

## Tiga tingkat sumber SMTP

`SmtpSenderFactory.current()` — dipanggil tiap kirim, bukan sekali saat boot:

| # | Sumber | Kapan dipakai |
|---|---|---|
| 1 | baris `platform_email_setting` | `smtp_host` terisi → sender dibangun dari DB |
| 2 | bean Boot dari `spring.mail.*` | host DB kosong **dan** `FTTH_MAIL_HOST` terisi |
| 3 | — (tak ada sender) | dua-duanya kosong → `SmtpEmailDispatcher` catat ke log (`[MAIL/LOG] …`) |

Dua keputusan yang membentuknya:

- **Autokonfigurasi Boot tak cukup lagi.** Beannya hanya lahir bila `spring.mail.host` terisi
  dan nilainya **beku sepanjang umur proses**. Setelan yang bisa diubah dari layar admin
  menuntut sender yang bisa dibangun ulang tanpa restart container.
- **Cache ber-sidik-jari, bukan cache biasa.** `JavaMailSenderImpl` baru tiap email itu boros;
  cache tanpa sidik jari berarti setelan yang baru disimpan tak pernah berlaku sampai
  di-restart — persis masalah yang hendak dihapus fitur ini. Sidik jarinya
  `host|port|username|hash(password)|auth|starttls`; password ikut hanya lewat hash-nya.
- **Tingkat 3 harus bisa dicapai.** `spring.mail.host` SELALU hadir di `application.yml`
  (`${FTTH_MAIL_HOST:}`) walau kosong, jadi `envSender()` menyaring `JavaMailSenderImpl`
  ber-host kosong. Tanpa saringan itu dev yang belum menyetel SMTP menerima "Mail server host
  not specified", bukan email yang tercatat di log.

Timeout SMTP dipasang eksplisit (10 detik connect/read/write): jalur kirim berjalan di dalam
transaksi, dan tanpa timeout utasnya menggantung sampai TCP menyerah sendiri.

---

## Model data (V97)

| Tabel | RLS | Isi |
|---|---|---|
| `platform_email_setting` | ❌ | singleton: SMTP (host/port/user/**password terenkripsi**/auth/STARTTLS), alamat & nama pengirim, logo, warna aksen, footer, tanda tangan, `public_base_url` |
| `platform_email_subject` | ❌ | timpaan subjek level platform — satu baris per pemicu (`UNIQUE`) |
| `tenant_email_setting` | ✅ | timpaan tenant: nama pengirim, alamat balasan, tampilan. **Semua kolom nullable** — null = warisi platform |
| `tenant_email_subject` | ✅ | timpaan subjek tenant, `UNIQUE (tenant_id, trigger)` |

**Kenapa tabel terpisah, bukan satu tabel ber-`tenant_id` nullable?** Kebijakan RLS menyaring
baris ber-`tenant_id` lain, jadi baris bawaan platform justru **tak terlihat** dari sesi tenant
— persis kebalikan dari yang dibutuhkan pewarisan.

**Baris ABSEN ≠ baris kosong.** Tak adanya baris subjek berarti "pakai bawaan"; baris berisi
string kosong ditolak validasi. Password SMTP disimpan sebagai ciphertext `SecretCipher` —
batas enkripsinya di adapter persistence, DB tak pernah melihat aslinya.

**V100** menurunkan pangkat satu kolom: `tenant_email_setting.from_address` di-**rename** jadi
`reply_to_address`. Rename, bukan buang-lalu-tambah — alamat yang sudah diisi tenant memang
alamat mereka sendiri, dan sejak semula resolver sudah memasangnya sebagai `Reply-To` juga.
Alasannya di bagian berikut.

---

## Identitas pengirim: alamat satu tingkat, nama tiga tingkat

`EmailBrandingResolver.forTenant(tenantId)` → `ResolvedEmailIdentity`. Setelah titik ini tak ada
lagi pertanyaan "ini punya tenant atau punya platform".

| Kolom | Rantai |
|---|---|
| `From` (alamat) | **selalu** alamat platform → `ftth.mail.from` (env). Tak ada timpaan tenant |
| `From` (nama) | timpaan tenant → **nama perusahaan tenant** → nama platform |
| `Reply-To` | **hanya** bila tenant mengisi alamat balasannya sendiri |
| logo/warna/footer/tanda tangan | `platform.branding.overriddenBy(tenant.branding)` — per kolom |

**Kenapa alamatnya tak bisa ditimpa.** Relay platform (Brevo) hanya menerima surat dari
pengirim yang sudah **terverifikasi di sisi penyedia**. Alamat berdomain tenant di header `From`
bukan sekadar berisiko masuk spam — suratnya **ditolak sebelum berangkat**, dan yang gagal
adalah seluruh email ISP itu. Karena verifikasinya menuntut akses DNS/kotak masuk per domain,
tak ada jalan menyediakannya lewat layar setelan; jadi kolomnya ditiadakan, bukan divalidasi.
Layar tenant tetap **memajang** alamat platform yang berlaku (terkunci, bukan input mati):
inilah alamat yang dilihat pelanggan, dan menyembunyikannya sama saja membuat operator menebak.

`Reply-To` justru tak diverifikasi penyedia mana pun — di sanalah alamat tenant tetap berguna:
balasan pelanggan mendarat di kotak masuk ISP-nya, bukan di kotak masuk platform. Digabung
dengan nama pengirim yang tetap bisa ditimpa (Brevo tak mempermasalahkan display name selama
alamatnya terverifikasi), surat tetap terbaca sebagai surat dari ISP-nya.

Tingkat tengah pada nama pengirim itu yang penting: tanpanya pelanggan menerima tagihan
internetnya dari nama yang tak pernah ia kenal — lebih mirip penipuan daripada pemberitahuan.

Peringatan sender-terverifikasi/SPF-DKIM di UI kini duduk di layar **platform**, di bawah kolom
alamat pengirimnya — di sanalah alamat yang harus terdaftar di relay itu benar-benar disetel.

`platformOnly()` adalah jalur keempat: surat yang bukan atas nama tenant mana pun (peringatan job
macet, **email selamat datang pendaftaran**). Ia sengaja tak menyentuh repo tenant sama sekali —
pemanggilnya berjalan tanpa `TenantContext`, dan query ber-RLS di sana akan meledak.

---

## Logo lewat endpoint publik

Email berangkat **multipart**: HTML berlogo + teks polos. Logonya disajikan tanpa auth:

```
GET /api/public/email-logo              → logo bawaan platform
GET /api/public/email-logo/{tenantId}   → logo tenant; JATUH ke logo platform bila tak ditimpa
```

- **Harus publik** karena pembacanya bukan browser yang login melainkan klien email pelanggan,
  yang memuat `<img src>` dari kotak masuk tanpa token apa pun. Yang tersaji cuma gambar merek
  yang memang dimaksudkan dipandang setiap penerima surat.
- **Fallback ke logo platform** karena surat yang terlanjur terkirim menyimpan URL bertenant
  selamanya; tenant yang menekan "kembalikan ke bawaan" tak boleh membuat email lamanya
  berlubang gambar.
- **Cache 1 hari** (`Cache-Control: public`). Gmail memuat ulang lewat proxy gambarnya sendiri;
  tanpa cache, satu siaran ke sepuluh ribu pelanggan berarti sepuluh ribu unduhan logo yang sama.

URL absolutnya dirangkai dari `public_base_url` (DB) → `ftth.mail.public-base-url` (env). Bila
tak disetel di mana pun, `logoUrl` = null dan **email tetap terkirim tanpa logo** — banyak klien
email memblokir gambar remote secara bawaan, jadi logo memang cuma hiasan. Sumber base URL yang
sama juga yang memutuskan apakah tautan "Masuk di: …" ikut di email selamat datang.

---

## Baris subjek: tiga tingkat + dua pengecualian

`EmailSubjectResolver` — **hanya subjek** yang dijahit di sini; isi pesannya sudah dirangkai
listener yang menerbitkan peristiwanya. WhatsApp tak mengenal padanan subjek, jadi ini murni
urusan kanal email.

```
timpaan TENANT  →  timpaan PLATFORM  →  DEFAULT_SUBJECTS (konstanta kode)
```

`DEFAULT_SUBJECTS` sengaja publik dan **lengkap** (semua pemicu punya entri): UI menampilkannya
sebagai placeholder supaya operator melihat apa yang akan terkirim bila kolomnya dibiarkan
kosong, dan menambah pemicu tanpa subjeknya gagal keras di test alih-alih diam-diam mengirim
email tanpa judul ke pelanggan sungguhan.

### Siapa boleh menyetel subjek apa

| Pemicu | Platform | Tenant | Bawaan kode |
|---|---|---|---|
| `SUBSCRIPTION_ACTIVATED` | ✅ | ✅ | Layanan internet Anda sudah aktif |
| `SUBSCRIPTION_ISOLATED` | ✅ | ✅ | Layanan internet Anda dinonaktifkan sementara |
| `SUBSCRIPTION_TERMINATED` | ✅ | ✅ | Layanan internet Anda telah dihentikan |
| `INVOICE_DUE_SOON` | ✅ | ✅ | Tagihan internet Anda akan jatuh tempo |
| `INVOICE_OVERDUE` | ✅ | ✅ | Tagihan internet Anda telah melewati jatuh tempo |
| `WORK_ORDER_SCHEDULED` | ✅ | ✅ | Jadwal kunjungan teknisi |
| `INCIDENT_OPENED` | ✅ | ✅ | Pemberitahuan gangguan layanan |
| `MANUAL` | ✅ | ✅ | Pemberitahuan dari penyedia layanan internet Anda |
| `PORTAL_PASSWORD_RESET` | ✅ | ❌ **khusus platform** | Kode pemulihan akun `{isp}` |
| `TENANT_SIGNED_UP` | ✅ | ❌ **khusus platform** | Pendaftaran `{isp}` berhasil — kode ISP Anda |

Dua pemicu terakhir masuk `EmailSubjectResolver.PLATFORM_ONLY` karena keduanya bukan
"pemberitahuan dari ISP kepada pelanggannya" melainkan **surat dari mekanisme aplikasi itu
sendiri**:

- `PORTAL_PASSWORD_RESET` adalah bagian dari jalan masuk pelanggan — ISP yang mengarang
  subjeknya sendiri bisa membuatnya tak lagi terbaca sebagai email keamanan.
- `TENANT_SIGNED_UP` bahkan **belum punya tenant pemilik** saat dikirim.

Penegakannya berlapis, bukan sekadar disembunyikan dari layar: `forCurrentTenant` /
`effectiveForCurrentTenant` **melewati peta tenant** untuk pemicu ini, `TenantEmailSettingsService`
menyaringnya dari view, dan `update()` menolaknya saat sanitasi — klien nakal tak bisa
menyelundupkan timpaan lewat API.

### Token `{isp}`

Satu-satunya token yang dikenali; diganti nama tenant saat surat dikirim, sehingga satu subjek
global tetap terbaca personal di kotak masuk pelanggan.

| Jalur | Nama ISP diambil dari |
|---|---|
| `forCurrentTenant(trigger)` | `tenantApi.findById(TenantContext.tenantId())?.name` |
| `forPlatform(trigger, ispName)` | argumen — jalur tanpa tenant aktif (email selamat datang) |
| `effectiveFor*()` | **tak diganti**: layar setelan harus menampilkan tokennya apa adanya |

`ispName` boleh kosong; `{isp}` lalu **hilang** dari subjek alih-alih tampil mentah.

### Subjek pemulihan password akhirnya benar-benar dipakai

Sebelumnya barisnya bisa disimpan tapi tak pernah terpakai: `NotificationApiService.sendEmail`
memakai subjek kiriman pemanggil dan `PortalPasswordRecoveryService` memaku
`"Kode pemulihan akun ${tenant.name}"`. Perbaikannya membuang sumber kebenaran kedua:
`NotificationApi.TransactionalMessage` **kehilangan field `subject`** (parameter yang diabaikan
diam-diam lebih buruk daripada dihapus), dan subjeknya diturunkan dari `purpose.toTrigger()` —
mencerminkan cabang WhatsApp yang memang sudah begitu. Nama ISP tak hilang: ia kembali lewat
token `{isp}`.

---

## Email selamat datang pendaftaran ISP

`TenantWelcomeEmailListener` — isi utamanya **kode ISP**, karena kode itu kini dipilih server dan
tak pernah diketik pendaftar, sementara layar masuk memintanya setiap kali. Layar sukses
pendaftaran menampilkannya juga, tapi layar itu hilang begitu tab ditutup; email tidak.

```
iam.TenantOnboardingService  ──publish──▶  iam.TenantAdminProvisionedEvent
   (hanya bila adminCreated)                       │  @TransactionalEventListener(AFTER_COMMIT,
notification.TenantWelcomeEmailListener  ◀─────────┘                        fallbackExecution)
```

- **Diterbitkan di `TenantOnboardingService`**, jadi `/signup` maupun `/platform/tenants`
  tercakup tanpa cabang. Syarat `adminCreated == true`: onboarding idempoten terhadap slug, dan
  tenant yang di-`ensure` ulang tak boleh dikirimi "selamat datang" untuk kedua kalinya.
- **`fallbackExecution = true`** karena `onboard` dipanggil dari jalur yang tak selalu
  transaksional; tanpa itu listener diam saja di jalur `/signup`.
- **Merek platform**, bukan merek tenant (`branding.platformOnly()`) — surat dari penyedia SaaS
  kepada ISP barunya, yang pada detik itu memang belum punya logo/warna/pengirim sendiri.
- Isi: sapaan ke nama admin, nama ISP, **kode ISP di barisnya sendiri** (mudah disalin, tak
  tenggelam di tengah kalimat), email admin, dan tautan masuk bila base URL terisi. **Password
  tak pernah disebut ulang** — yang mengetiknya sudah tahu, dan email adalah tempat terakhir
  yang pantas menyimpannya.
- Seluruh badan dibungkus `runCatching { … }.onFailure { log.warn(…) }`: relay SMTP yang mati
  tak boleh membatalkan pendaftaran yang sudah commit.

Pemicunya sengaja **tidak** ditambahkan ke `TRIGGERS` di `web/src/api/notification.ts`, jadi ia
tak muncul di layar template WhatsApp tenant — ia tak punya pelanggan untuk dikirimi apa pun.

---

## Batas modul (Spring Modulith)

**`iam ↛ notification`.** Arah `notification → billing → iam` sudah ada, jadi arah balik menutup
siklus. Karena itu email selamat datang **tidak** dikirim iam lewat `NotificationApi`, melainkan
oleh listener **di dalam `notification`** yang mendengarkan event dari iam — persis pola
`iam → TenantOnboardedEvent → platformbilling` yang sudah dipakai langganan SaaS
([`saas-subscription.md`](saas-subscription.md#batas-modul-spring-modulith)). Eventnya diletakkan
di **base package `iam`** (permukaan publiknya), sebelah `TenantOnboardedEvent`.

**`SmtpSenderFactory` membaca port keluar dari dalam adapter** — memang tak lazim, ditempuh
karena sambungan SMTP adalah detail transport murni yang tak punya urusan dengan use case mana
pun; menyeretnya lewat lapisan application hanya menambah perantara kosong.

---

## Izin & endpoint

| Izin | Untuk |
|---|---|
| `platform.email.view` / `platform.email.manage` | super-admin: SMTP, pengirim, tampilan, subjek platform |
| `notification.settings.view` / `notification.settings.manage` | tenant: timpaan nama pengirim, alamat balasan, tampilan, subjek |

| Endpoint | Izin |
|---|---|
| `GET/PUT /api/platform/email-settings` | `platform.email.*` |
| `POST/DELETE /api/platform/email-settings/logo` · `GET .../logo` | `platform.email.*` |
| `POST /api/platform/email-settings/test` · `GET .../preview` | `platform.email.*` |
| `GET/PUT /api/notifications/email-settings` (+ `/logo`, `/test`, `/preview`) | `notification.settings.*` |
| `GET /api/public/email-logo[/{tenantId}]` | **publik** (klien email tak membawa token) |

---

## Sisi web

- **`/platform/email`** (`PlatformEmailSettingsPage.tsx`) — SMTP, identitas pengirim, URL publik,
  tampilan bawaan, dan kartu subjek per pemicu. Password SMTP **write-only**: yang kembali ke
  layar hanya penandanya. Baris "Pemulihan password portal" & "Pendaftaran ISP baru" diberi
  catatan bahwa keduanya berlaku untuk semua ISP dan tak bisa ditimpa tenant.
- **Pengaturan Notifikasi** (`NotificationSettingsPage.tsx`) — kartu timpaan tenant. Tiap kolom
  menampilkan nilai warisannya sebagai **placeholder**, jadi mengosongkan kolom terbaca sebagai
  "ikut platform", bukan "kosongkan". Satu-satunya yang di luar aturan itu: baris **"Dikirim
  dari"** — teks terkunci berlabel, tanpa kolom sama sekali, karena alamatnya memang tak punya
  timpaan. Pemicu khusus platform tak muncul di sini sama sekali.
- **Pratinjau & kirim email uji** di kedua layar menempuh jalur render dan transport yang **sama
  persis** dengan email sungguhan — pratinjau yang memakai jalur sendiri hanya membuktikan
  dirinya sendiri.

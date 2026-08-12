# Modul `portal` — realm pelanggan (identitas, sesi, self-service)

Pintu pelanggan ke sistem: **masuk tanpa perlu tahu "kode ISP"**, lihat & bayar
tagihan, cetak bukti tagihan, ajukan pindah paket, lapor gangguan, dan pantau status
sambungan sendiri.

Realm ini **terpisah total** dari realm operator. Identitasnya bukan pengguna IAM
melainkan `PortalCustomer` (pelanggan, tanpa RBAC), rantai keamanannya sendiri, dan
tokennya ditandatangani **secret berbeda** — token operator gagal di decoder portal dan
sebaliknya, jadi dua realm tak bisa saling bocor.

`portal` **tak memiliki tabel modul lain**. Ia merangkai kontrak publik `customer`,
`catalog`, `billing`, `bng`, `cpe`, dan `helpdesk` — persis pola `ReportService` /
`Subscriber360Service`. Yang benar-benar miliknya cuma kredensial, sesi, indeks
identitas, dan kode pemulihan. Batas ditegakkan `ModularityTests`.

---

## Rantai keamanan terpisah

```
/api/portal/**   → PortalSecurityConfig   (@Order HIGHEST_PRECEDENCE + 10)
                     decoder HS256 dengan effectivePortalJwtSecret
                     → PortalJwtAuthenticationConverter → PortalAuthenticationToken
                     → PortalTenantContextFilter (pasang TenantContext dari klaim token)
/api/**          → rantai operator (JWT IAM + RBAC)
```

`@Order` lebih tinggi dari rantai utama (yang mencocokkan semua request) supaya rantai
spesifik ini dievaluasi lebih dulu; kalau tidak, Spring Security **menolak konfigurasi
saat startup** karena rantai ini tak akan pernah terpanggil. Pola yang sama dipakai
`CollectorSecurityConfig`.

Secret portal diturunkan otomatis (`SHA-256("$jwtSecret:portal")`) bila
`ftth.security.portal-jwt-secret` tak diisi — jadi isolasi realm berlaku bahkan pada
deploy yang tak pernah menyetel apa pun.

Terbuka tanpa token: `login`, `refresh`, `forgot-password`, `reset-password`. Sisanya
wajib token portal. Access token 15 menit, refresh token 30 hari (`SecurityProperties`),
refresh **dirotasi** tiap dipakai dan disimpan sebagai SHA-256 — DB tak pernah melihat
nilai aslinya.

---

## Masuk tanpa kode ISP

Pelanggan mengetik **satu kotak**: email, nomor HP, **atau** username. Kode ISP tak lagi
diminta — itu hal yang tak pernah dihafal pelanggan, dan sebetulnya bisa disimpulkan
server dari identitasnya.

```
identifier ──PortalIdentifier.candidates()──▶ [email?, phone?, login?]  (bentuk kanonik)
           ──portal_identity (TANPA RLS)────▶ kandidat (tenant, customerId), maks 5
           ──verifikasi BCrypt ke SEMUA kandidat──┐
                                                  ├─ 1 cocok  → langsung masuk
                                                  ├─ >1 cocok → CHOOSE_TENANT
                                                  └─ 0 cocok  → "Login atau password salah"
```

**Kenapa password diperiksa ke semua kandidat lebih dulu?** Satu orang boleh
berlangganan di dua ISP dengan email yang sama — sah. Godaannya adalah langsung
menampilkan daftar ISP dan bertanya "yang mana?", tapi itu menjadikan layar masuk **alat
intip**: mengetik nomor HP orang lain cukup untuk tahu ia pelanggan siapa. Karena itu
pilihan ISP hanya pernah ditawarkan kepada orang yang **sudah membuktikan tahu
passwordnya**.

Konsekuensi yang diterima sadar: bila seseorang memakai email sama di dua ISP dengan
password **berbeda**, ia hanya sampai ke salah satunya — untuk yang lain ia memakai
username ISP itu (identitas yang tak kembar) atau memulihkan passwordnya.

Batas 5 kandidat bukan kosmetik: tiap kandidat = satu verifikasi BCrypt yang memang
sengaja lambat.

Angka dicoba **sebagai nomor HP DAN sebagai username apa adanya** — username PPPoE di
lapangan sering berupa deretan angka yang persis seperti nomor HP; memaksa memilih satu
tafsir pasti mengunci salah satu golongan pelanggan.

### Indeks identitas (`portal_identity`)

| | `user_directory` (operator) | `portal_identity` (pelanggan) |
|---|---|---|
| Keunikan | **global**: 1 email = 1 tenant | **per-tenant**: `UNIQUE (tenant_id, value)` |
| Alasan | staf hanya milik satu ISP | warga boleh berlangganan di dua ISP |

Keduanya **sengaja tanpa RLS**: saat pelanggan mengetik identitasnya, server belum tahu
tenant mana — justru itulah yang sedang dicari, jadi GUC `app.tenant_id` belum bisa
di-set dan RLS akan menyaring habis semua baris. Aman karena tabel ini **hanya
penunjuk**: tak ada password/hash di dalamnya.

Isinya disinkronkan dari kontak pelanggan (`PortalCustomerContactListener` mendengar
`CustomerContactChanged`, `AFTER_COMMIT`) dan dari kredensial saat dibuat/diganti.
Normalisasi ada di **satu tempat** (`PortalIdentifier`) dan dipakai baik saat *menulis*
indeks maupun saat *mencocokkan* ketikan — kalau kedua sisi ini pernah berbeda pendapat,
pelanggan yang sah ditolak tanpa jejak. Backfill SQL `V79` sengaja mencerminkan fungsi
yang sama.

Jalur ber-slug punya **jaring pengaman**: bila indeks tak memuat apa pun tapi ISP-nya
disebut, username dicari langsung ke `portal_credential`. Tanpa itu, satu baris indeks
yang hilang berarti pelanggan terkunci.

---

## Lupa password (OTP 6 digit)

```
POST /auth/forgot-password  →  SELALU 204
  └─ kandidat (maks 3 ISP) → PortalPasswordReset.issue → kode 6 digit
       └─ NotificationApi.sendTransactional (EMAIL / WHATSAPP)   ← BUKAN broadcast
POST /auth/reset-password   →  kode + identitas yang SAMA → password baru
```

| Parameter | Nilai | Alasan |
|---|---|---|
| Panjang kode | 6 digit | cukup pendek untuk didikte lewat telepon |
| Masa berlaku | 15 menit | cukup untuk membuka WA/email, terlalu pendek untuk ditebak paksa |
| Percobaan | 5× | salah ketik wajar dimaafkan; selebihnya bukan pelanggan asli |
| ISP sekaligus | maks 3 | menahan jalur ini dipakai membanjiri WA/email orang lain |

Empat keputusan yang membentuk alur ini:

1. **Permintaan kode selalu "berhasil".** Tak ada jawaban yang membedakan identitas
   dikenal dari yang tidak — juga tidak dalam bentuk tersamar. Halaman ini terbuka untuk
   umum; jawaban yang membedakan menjadikannya alat memetakan basis pelanggan sebuah ISP.
2. **Kodenya tidak lewat riwayat broadcast.** Riwayat itu memang dibuat untuk dibaca
   operator, jadi menuliskan kode ke sana sama dengan menyerahkan kunci akun pelanggan ke
   seluruh staf ISP. Yang tercatat hanya peristiwanya — siapa, kanal apa — tanpa isi.
   (Pemicu `PORTAL_PASSWORD_RESET` tetap ada semata agar ISP bisa **memetakan template
   WhatsApp** yang sudah disetujui Meta/Qontak; penyedia seperti Qontak tak menerima teks
   bebas sama sekali.)
3. **Kode terikat ke identitas yang meminta.** Tanpa ikatan ini, kode yang terbaca orang
   lain (mis. notifikasi muncul di layar terkunci) bisa dipakai atas nama akun mana pun.
4. **Satu pesan untuk semua sebab kegagalan** (kode salah, kedaluwarsa, habis percobaan,
   identitas tak cocok) — membedakannya memberi tahu penebak seberapa dekat ia, dan hanya
   itu yang ia butuhkan.

**Subjek emailnya kini setelan platform, bukan string di kode.** Dulu `"Kode pemulihan akun
${tenant.name}"` dipaku di `PortalPasswordRecoveryService`; sekarang ia diturunkan dari pemicu
`PORTAL_PASSWORD_RESET` lewat `EmailSubjectResolver`, dengan bawaan `"Kode pemulihan akun {isp}"`
— token `{isp}` diganti nama ISP saat kirim, jadi pelanggan yang punya akun di beberapa ISP tetap
bisa membedakan dua email pemulihan di kotak masuknya. Yang boleh mengubahnya **hanya admin
platform** (`/platform/email`): baris ini bagian dari jalan masuk pelanggan, dan ISP yang
mengarang subjeknya sendiri bisa membuatnya tak lagi terbaca sebagai email keamanan — timpaan
tenant untuknya diabaikan, bukan sekadar disembunyikan dari layar. Lihat
[`email-branding.md`](email-branding.md#baris-subjek-tiga-tingkat--dua-pengecualian).

Isi pesan menyebut **nama ISP** (pelanggan bisa punya beberapa akun) dan ditutup
peringatan jangan membagikan kode — penipuan paling umum di jalur ini adalah menelepon
pelanggan sambil mengaku petugas dan meminta kodenya dibacakan.

### Rem anti-tebak

`AttemptThrottle` dipasang di controller, bukan di service:

| Endpoint | Rem |
|---|---|
| `POST /auth/login` | per-IP **dan** per-identitas (kunci = bentuk kanonik, bukan apa yang diketik) |
| `POST /auth/forgot-password` | per-IP, **setiap** panggilan menghabiskan jatah |
| `POST /auth/reset-password` | per-IP |

Kunci rem dinormalkan lebih dulu: tanpa itu `0811-222-333`, `0811222333`, dan
`+62811222333` adalah tiga ember berbeda untuk akun yang sama dan jatah tebakannya
tinggal dikalikan tiga.

Permintaan kode tak punya konsep "gagal", jadi jatahnya dihabiskan tanpa syarat — kalau
tidak, endpoint itu jadi tombol gratis untuk memompa tagihan gateway WA tenant dan
membakar reputasi kirim SMTP platform. Rem penukaran per-IP menutup celah
**penyemprotan kode ke banyak identitas sekaligus**: jatah per-kode saja tak cukup,
karena 6 digit jadi murah bila boleh dicoba tanpa batas lintas akun.

---

## Self-service (`/api/portal/me/**`)

Semua endpoint mengambil `customerId` **dari principal**, tak pernah dari path/query —
sehingga pelanggan mustahil membaca data pelanggan lain.

| Endpoint | Isi |
|---|---|
| `GET /me` | identitas ringkas untuk header |
| `POST /me/password` | ganti password mandiri — **seluruh sesi berakhir**, login ulang |
| `GET /me/profile` | langganan, paket, status |
| `GET /me/billing` | tagihan & riwayat pembayaran |
| `GET /me/payment-methods` | instrumen bayar yang aktif di tenant (VA/QRIS) |
| `POST /me/invoices/{id}/pay` | bayar in-app lewat gateway aktif |
| `GET /me/invoices/{id}/print` | lembar tagihan siap cetak |
| `GET /me/connection` | status sambungan, sesi PPPoE, perangkat |
| `GET /me/plan-options` | paket yang bisa dipilih (`current` menandai yang dipakai) |
| `POST /me/plan-change` | **ajuan** pindah paket |
| `GET/POST /me/tickets/**` | meja bantuan (lihat [`docs/helpdesk.md`](helpdesk.md)) |

Lembar cetak **membawa sendiri** identitas penerbit & penerima alih-alih mengandalkan
klien menempelkannya: yang dicetak lalu disimpan/dilampirkan pelanggan harus lengkap
berdiri sendiri, dan nilainya tak boleh berubah bila tampilan portal berubah. Ia bukti
tagihan, **bukan faktur pajak** — portal tak menyimpan alamat/NPWP tenant.

Ajuan ganti paket **tidak mengubah langganan**. Ia terbit sebagai tiket berkategori
`GANTI_PAKET` agar operator yang memutuskan (harga, prorata, perlu kunjungan atau
tidak); pelanggan menerima nomor tiket yang bisa diikuti di menu Bantuan.

Sesi PPPoE ditampilkan **tanpa rahasia** — password/secret akun akses tak pernah keluar
lewat jalur portal.

---

## Kredensial dikelola operator

| Endpoint (`/api/portal-admin/customers/{id}/credential`) | Izin |
|---|---|
| `GET` | `portal.credential.view` |
| `POST` (provision) · `/reset-password` · `/enable` · `/disable` | `portal.credential.manage` |

Password sementara dibuat dari **alfabet non-ambigu** (tanpa `0/O`, `1/l/I`) sepanjang 10
karakter, supaya bisa dibacakan lewat telepon tanpa salah dengar. Ia hanya pernah muncul
sekali, di respons provisioning.

Login dinormalkan & divalidasi di **domain** (`PortalCredential.normalizeLogin`:
lower-case, 3–64 karakter, huruf/angka/`. _ -`) agar aturannya sama di mana pun
kredensial dibuat — jalur operator maupun ganti-login mandiri. Satu pelanggan paling
banyak satu kredensial.

Akun yang dinonaktifkan ISP dijawab **"Akun portal dinonaktifkan"** — barulah aman
menyebut alasannya, karena yang bertanya sudah terbukti tahu passwordnya.

---

## Skema

| Tabel | RLS | Isi |
|---|---|---|
| `portal_credential` | ✅ | login + hash BCrypt + `disabled_at` (1:1 dengan pelanggan) |
| `portal_refresh_token` | ❌ | sesi; hash SHA-256, dicari sebelum tenant diketahui |
| `portal_identity` | ❌ | indeks identitas → (tenant, pelanggan); **hanya penunjuk** |
| `portal_password_reset` | ❌ | kode OTP ter-hash; baris tak dihapus setelah dipakai (jejak audit) |

Migrasi: `V64` (kredensial + sesi), `V79` (indeks identitas + pemulihan password).

Tiga tabel tanpa RLS itu semuanya dipakai **pra-autentikasi**, saat GUC `app.tenant_id`
belum bisa di-set — pola yang sama dengan `user_directory` (V44). Tak satu pun menyimpan
rahasia yang bisa dipakai (hash saja, dan indeks tanpa hash).

---

## Alur lintas-tenant tanpa `@Transactional`

`PortalAuthenticationService` dan `PortalPasswordRecoveryService` **sengaja tidak**
`@Transactional`: satu permintaan bisa menyentuh beberapa ISP, dan tiap pembacaan ber-RLS
harus dibuka di dalam `TenantContext`-nya sendiri agar koneksinya membawa GUC yang benar.

```
Service lintas-tenant (tanpa transaksi)
  └─ TenantContext.runAs(tenantA) { workerTransaksional.… }   ← REQUIRES_NEW di dalam
  └─ TenantContext.runAs(tenantB) { … }                        ← kegagalan terkurung di sini
```

Workernya `PortalTenantScopedAuthenticator` dan `PortalTenantScopedRecovery`. Kegagalan
satu ISP tak menghentikan yang lain **dan tak terlihat dari luar** — pemanggil tetap
menerima jawaban yang sama persis.

---

## Konfigurasi

| Properti | Bawaan | Guna |
|---|---|---|
| `ftth.security.portal-jwt-secret` | diturunkan dari `jwt-secret` | secret tanda tangan token portal |
| `ftth.security.access-token-ttl` | `PT15M` | umur access token (operator & portal) |
| `ftth.security.refresh-token-ttl` | `P30D` | umur refresh token |
| `spring.mail.host` | kosong | SMTP **platform** untuk pesan transaksional; kosong = hanya dicatat ke log |

---

## Kaitan lintas-modul

```
                 ┌── customer  (profil, kontak, langganan)
                 ├── catalog   (pilihan paket)
web/src/portal ──┤── billing   (tagihan, pembayaran, cetak)
   PortalApp     ├── bng       (sesi PPPoE, status akses)
                 ├── cpe       (perangkat pelanggan)
                 ├── helpdesk  (lapor gangguan, ajuan ganti paket)
                 └── notification (kode pemulihan — jalur transaksional, bukan broadcast)
```

Frontend portal hidup di `web/src/portal/` — aplikasi terpisah dari konsol operator
dengan klien HTTP & konteks auth sendiri. Access token ditahan di memori, refresh token
di `localStorage` dengan kunci sendiri (`ftth.portal.refreshToken`), sehingga sesi
operator dan sesi pelanggan tak pernah bertabrakan di browser yang sama.

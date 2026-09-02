# Changelog

Semua perubahan penting yang layak dicatat pada proyek ini. Format mengikuti
[Keep a Changelog](https://keepachangelog.com/id/1.1.0/); proyek belum memakai
versi rilis (trunk-based di `main`), jadi entri dikelompokkan per tanggal.

## [Belum dirilis]

### 2026-09-01 — Perbaikan build Docker paralel, isolasi cache Gradle, port fleksibel, dan interaksi peta

**Diubah**
- **Grid Core Kabel (12 Kolom) & Meja Splicing (8 Kolom)**: Menata kisi core pada detail kabel (`.core-grid` pada `CableCoreManager`) menjadi 12 kolom per baris sehingga 1 tube (12 core) muat rapi dalam satu baris penuh, serta meja sambungan (`.splice-core-grid` pada `SplicingManager`) menjadi 8 kolom per baris yang mengisi rata seluruh lebar wadah kartu Ujung A & B (100%).
- **Indikator Flag & Ring Seleksi Core/Slot (`.is-selected`)**: Menambahkan badge checklist bulat (`✓`) dan ring kontras berlapis ganda pada kotak core terpilih serta outline tebal 3px pada slot splitter/ODF agar status pilihan terlihat jelas dan mencolok.
- **Migrasi Flyout Detail Kabel ke Drawer Blade (`CablePanel`)**: Memigrasikan `CablePanel` menjadi komponen `<Blade size="full" className="blade-detail">` sehingga meluncur dari kanan dengan tampilan, animasi, dan lebar yang sama persis (`64vw; min-width: 760px`) dengan detail Joint Box, ODC, ODF, dan OLT.
- **Pembersihan Chip Core Kabel (`CableCoreManager`, `SplicingManager`)**: Mengganti komponen `<ToggleButton>` Fluent UI menjadi elemen button native untuk menampilkan nomor core secara presisi di tengah tanpa distorsi ukuran dan tanpa menimpa warna serat, serta menghapus outline hijau statis pada detail kabel.
- **Port HTTP Caddy di lingkungan Lab**: Diubah default-nya ke port `8000` (via `${PORT:-8000}:80`) pada `docker-compose.lab.yml` dan `Makefile`, sehingga tidak bentrok dengan web server host (seperti Apache/Nginx pada port 80/8080). Variabel `BASE` dan setelan CORS server diselaraskan ke `http://localhost:8000`.
- **Port HTTP/HTTPS Caddy di lingkungan Produksi**: Di-parameterisasi menjadi `${FTTH_HTTP_PORT:-80}:80` dan `${FTTH_HTTPS_PORT:-443}:443` pada `deploy/docker-compose.prod.yml` agar fleksibel saat pengujian lokal namun tetap terikat ke port 80/443 secara baku di VPS/CI-CD.
- **Skrip Seeding Lab (`seed-lab.py`)**: Diimplementasikan ulang menggunakan Python 3 tanpa dependensi paket pihak ketiga (`jq`), sehingga `make lab-seed` dapat dijalankan secara mandiri dan andal di berbagai distribusi OS.

**Diperbaiki**
- **Penutupan Dropdown / Combobox Saat Klik di Luar Panel & Area Kosong Sekitarnya (`Combobox`, `MultiCombobox`)**: Menyelaraskan CSS lebar `100%` pada `.combobox .fui-Combobox` dan `.multi-combobox .fui-Combobox` untuk menghilangkan area kosong tak terlihat (*dead zone*), serta memperhalus deteksi outside-click agar hanya mengecualikan klik pada input trigger `.fui-Combobox` dan popup `[role="listbox"]`. Mengganti pembungkus layout `<label className="stack">` menjadi `<div className="stack">` pada berbagai form (SplicingManager, CableCoreManager, WorkOrdersPage, WorkOrderDetailBody) untuk mencegah synthetic click native browser yang membuka ulang input saat mengklik area kosong di sekitarnya. Memperkuat listener penutupan outside-click pada `window` dengan fase capture untuk `pointerdown`, `mousedown`, dan `touchstart`.
- **Posisi Menu Klik Kanan Peta (`AddHereMenu`)**: Mengganti pembungkus Fluent UI `Menu`/`MenuPopover` pada menu "tambah di sini" menjadi elemen absolut langsung (`map-menu`). Menghilangkan *glitch* posisi di mana menu pertama kali muncul di pojok kiri atas (0, 0) karena jeda kalkulasi Floating UI.
- **Dropdown / Combobox Auto-Close pada Klik Pertama**: Menghapus `onFocus` yang memicu toggle ganda internal pada komponen `Combobox` (dipakai pada Meja Kerja Splicing di panel Joint Box, ODF, ODP, ODC), serta membungkus status loading dan opsi kosong dalam elemen `<Option disabled>` yang valid.
- **Docker BuildKit Crash (`frontend grpc server closed unexpectedly`)**: Menghapus direktif eksternal `# syntax=docker/dockerfile:1` dari `web/Dockerfile`, `server/Dockerfile`, `simulator/Dockerfile`, dan `collector/Dockerfile` untuk mencegah crash pada gRPC frontend worker saat build paralel.
- **Gradle Build Cache Lock Contention**: Menambahkan cache ID terisolasi (`id=gradle-server`, `id=gradle-simulator`, `id=gradle-collector`) pada cache mount `--mount=type=cache` di masing-masing Dockerfile, mencegah perebutan lock file journal cache (`/root/.gradle/caches/journal-1.lock`) saat proses build berjalan bersamaan.

### 2026-08-12 — Konsol ACS / TR-069: armada ONT punya halamannya sendiri

**Ditambahkan**
- **Menu `ACS / TR-069`** (di bawah BRAS & RADIUS) dengan dua tab. *Dashboard*: jumlah perangkat
  online/total/offline, rata-rata sinyal RX, aksi cepat (sapuan refresh, ekspor CSV, log
  aktivitas, health check), dan blok informasi server ACS. *Devices*: tabel se-armada dengan
  pencarian serial/SSID/PPPoE/nama pelanggan, saringan status/sinyal/merek, dan ekspor CSV.
  Sampai kini satu-satunya pintu masuk ke fitur ACS adalah tab di dalam detail satu pelanggan —
  ISP tak punya cara melihat armadanya sendiri.
- **Kartu "Setelan ONT (TR-069)"** — daftar nilai yang harus diketik ke halaman ACS di ONT
  (ACS URL, kredensial, connection request, Periodic Inform Interval `300`), tiap baris bisa
  disalin dan ada tombol **"Salin semua"** untuk ditempel ke grup lapangan. Muncul di halaman
  ACS **dan** di tab Ringkasan detail pelanggan, tepat setelah operator mendaftarkan serial ONU —
  momen ketika nilai-nilai itu benar-benar dibutuhkan. Selama ini alamatnya cuma hidup di `.env`
  dan ditanyakan lewat chat; interval `300` (bawaan pabrik ONT `3600`) yang paling sering salah.
- **Izin `cpe.acs.view`**, otomatis diberikan ke role **Teknisi**: cukup untuk melihat informasi
  server ACS + health check, tak cukup untuk melihat armada tenant. Teknisi lapangan bisa membuka
  setelan ONT dari HP-nya tanpa dapat menengok daftar pelanggan. Halaman menyesuaikan diri —
  tanpa `cpe.device.view` strip tabnya tak dirender dan endpoint armada tak dipanggil sama sekali.
- **Kolom SSID & suhu** ikut ditarik saat sinkronisasi ACS (`V102`). Suhu adalah parameter vendor
  (`X_*`) yang tak dibakukan TR-069, jadi **mati secara bawaan**: isi
  `FTTH_CPE_TEMPERATURE_PARAMS` dengan path vendormu, atau biarkan kolomnya `—`.

**Diubah**
- **Tab "CPE" di detail pelanggan kini berlabel "GenieACS"** — nama yang sama dengan yang
  dipakai operator saat bicara soal fiturnya.
- `docker-compose.prod.yml` kini meneruskan `FTTH_CPE_PUBLIC_HOST` dan `FTTH_CPE_CWMP_PORT` ke
  service `server`. Sebelumnya keduanya hanya sampai ke container genieacs, jadi kartu setelan
  ONT akan merender "belum dikonfigurasi" di produksi meski ACS-nya jalan normal.

**Diperbaiki**
- **Penyalinan teks yang gagal diam-diam di `http://` polos.** `navigator.clipboard` hanya ada di
  secure context; pola `navigator.clipboard?.writeText(x).then(...)` yang dipakai halaman BRAS
  memutus seluruh rantai di sana — tombolnya diklik, tak ada yang tersalin, dan tak ada toast
  sukses maupun error. Helper baru jatuh ke `document.execCommand('copy')` dan melaporkan
  kegagalan dengan jujur.

**Catatan keamanan**
- Password ACS & connection-request **dikirim ke browser siapa pun yang punya `cpe.acs.view`**,
  termasuk setiap teknisi. Itu disengaja — nilainya global, diketik ke semua ONT, bukan rahasia
  per-pelanggan — tapi jangan pakai password yang sama dengan apa pun yang lain. Keduanya
  dikecualikan dari ekspor CSV dan dari semua baris log.
- Health check tak pernah membocorkan alamat NBI internal ke browser tenant (pesan error
  dibersihkan jadi kalimat tetap; aslinya hanya ke log server) dan tak pernah mengembalikan
  jumlah device se-ACS — semua angka berasal dari tabel ber-RLS milik tenant.
- "Segarkan Batch" adalah sapuan **berplafon** (`FTTH_CPE_BULK_REFRESH_MAX`, bawaan 50) dengan
  anggaran waktu, bukan "refresh semua": tanpa infrastruktur async, tiap klik menahan satu thread
  servlet + satu koneksi Hikari. Jumlah yang tak sempat disapu dilaporkan apa adanya.

### 2026-08-12 — Alamat pengirim email dikunci ke platform; alamat tenant jadi alamat balasan

**Diubah**
- **Alamat pengirim (`From`) email kini SELALU alamat platform**, tak bisa lagi ditimpa tenant.
  Relay yang dipakai (Brevo) hanya menerima surat dari pengirim yang sudah terverifikasi di
  sisi penyedia, jadi alamat berdomain ISP di header `From` bukan sekadar berisiko masuk spam —
  **seluruh email ISP itu gagal berangkat**. Verifikasinya menuntut akses DNS per domain, yang
  tak mungkin disediakan lewat layar setelan, jadi kolomnya ditiadakan alih-alih divalidasi.
- **Kolom alamat milik tenant turun pangkat jadi "Alamat balasan" (`Reply-To`)** — peran yang
  memang sudah dipegangnya sejak semula. `Reply-To` tak diverifikasi penyedia mana pun, jadi
  balasan pelanggan tetap mendarat di kotak masuk ISP-nya, bukan di kotak masuk platform. Nilai
  yang sudah tersimpan **ikut pindah sendiri** lewat migrasi `V100` (rename kolom, bukan hapus):
  tak ada tenant yang kehilangan setelan.
- **Kartu "Identitas & tampilan email" tenant** menampilkan alamat pengirim platform yang
  berlaku sebagai baris **terkunci** (teks berlabel, bukan input mati — tak ada izin yang bisa
  membukanya), lengkap dengan alasannya. Nama pengirim, logo, warna, footer, tanda tangan, dan
  subjek **tak berubah sama sekali**: relay tak mempermasalahkan nama pengirim selama alamatnya
  terverifikasi, jadi surat tetap terbaca sebagai surat dari ISP-nya.
- **Peringatan sender-terverifikasi/SPF-DKIM pindah ke layar `/platform/email`**, di bawah kolom
  alamat pengirim platform — di sanalah alamat yang harus terdaftar di relay itu benar-benar
  disetel. Di kartu tenant peringatan itu sudah tak punya kolom yang diperingatkannya.

### 2026-08-11 — Pendaftaran ISP tanpa ketik kode, email selamat datang, dan kunci baca-saja saat menunggak

**Ditambahkan**
- **Kode ISP dirakit server** saat pendaftaran mandiri (`/signup`): dari nama ISP
  (`PT Net Media Jaya` → `pt-net-media-jaya`), bernomor bila bentrok (`…-2`, `…-3`). Kolom
  "Kode ISP" hilang dari formulir — ia kunci teknis, bukan pilihan bisnis, dan bentrok kode
  yang muncul sebagai 409 di tengah alur tak bisa diperbaiki pendaftar. Kodenya dikembalikan
  di layar sukses dan **wajib** disimpan: layar masuk staf memintanya setiap kali.
- **Email selamat datang** ke admin ISP baru, berisi kode ISP di barisnya sendiri, email
  admin, dan tautan masuk. Berlaku untuk pendaftaran mandiri **maupun** onboarding dari
  `/platform/tenants`, asalkan admin awalnya memang baru dibuat. Berangkat atas nama merek
  **platform** — pada detik itu tenantnya belum punya logo, warna, atau alamat pengirim.
- **Token `{isp}` di semua baris subjek email**, diganti nama ISP saat kirim: satu subjek
  global tetap terbaca personal di kotak masuk pelanggan.
- **Subjek khusus platform.** "Pemulihan password portal" & "Pendaftaran ISP baru" hanya bisa
  disetel admin platform; timpaan tenant untuknya **diabaikan**, bukan sekadar disembunyikan
  dari layar. Keduanya surat dari mekanisme aplikasi, bukan pemberitahuan ISP ke pelanggannya.
- **`GET /api/subscription/lock`** — tanpa izin khusus (cukup terautentikasi) supaya teknisi
  atau CS yang tak punya izin billing pun tahu kenapa aplikasinya membeku: nominal, jatuh
  tempo, dan umur tunggakan.
- **Dokumentasi baru [`docs/email-branding.md`](docs/email-branding.md)** — satu dokumen untuk
  seluruh tumpukan email: tiga tingkat sumber SMTP, pewarisan platform → tenant, logo publik,
  tabel siapa boleh menyetel subjek apa, dan email selamat datang beserta batas modulnya.

**Diubah**
- **Tenant yang menunggak langganan SaaS kini BACA-SAJA, bukan tak bisa login.** Aturan lama
  men-suspend tenantnya, dan login tenant non-aktif ditolak — tunggakan jadi lubang tanpa jalan
  keluar: ISP yang telat bayar tak bisa masuk sekalipun untuk membayar, dan datanya seolah
  lenyap. Sekarang seluruh konsol tetap terbaca (semua izin `*.view`), aksi tulis ditolak
  **402** ber-`code=SUBSCRIPTION_LOCKED`, tombolnya mati di UI, banner merah menetap, dan
  pengguna diarahkan sekali ke `/subscription`. Membayar langganan tetap boleh — tanpa
  pengecualian itu kuncinya menelan dirinya sendiri. **Portal pelanggan tetap jalan penuh**,
  termasuk membayar tagihan: itu sumber uang yang melunasi langganannya. Pelunasan membuka
  kunci **seketika**, tanpa login ulang. Suspend manual dari `/platform/tenants` tetap kunci
  total dan tak berubah; tenant yang terlanjur tersuspend aturan lama dipulihkan sekali jalan
  saat start-up.
- **`POST /api/signup` tak lagi menerima `slug`.** Field itu dibuang dari payload (balasannya
  tetap membawanya). Galat "Kode ISP sudah dipakai" ikut hilang; yang tersisa hanya bentrok
  **email**, satu-satunya yang bisa diperbaiki pendaftar sendiri.
- **`TransactionalMessage.subject` dihapus dari `NotificationApi`.** Subjeknya kini diturunkan
  module notification dari pemicu pesannya. Sebelum ini baris subjek "Pemulihan password
  portal" bisa disimpan tapi tak pernah terpakai, karena pemanggil selalu mengoper subjeknya
  sendiri — parameter yang diabaikan diam-diam lebih buruk daripada dihapus.
- **Bonus bulan gratis tak lagi menghidupkan tenant yang tersuspend.** Ia membuka kunci
  baca-saja seperti biasa, tapi status SUSPENDED pada tenant kini hanya bisa dipasang tangan
  admin platform — dan bonus bukan alasan untuk membatalkan keputusan itu.

**Diperbaiki**
- **Baris subjek email pemulihan password portal akhirnya benar-benar dipakai.** Sebelumnya
  `"Kode pemulihan akun <nama ISP>"` dipaku di kode, jadi apa pun yang disetel di
  `/platform/email` tak berpengaruh. Nama ISP tak hilang — ia kembali lewat token `{isp}`.

### 2026-08-11 — Bonus bulan langganan gratis untuk tenant

**Ditambahkan**
- **Beri bulan gratis dari panel Langganan** (`/platform/tenants` → ⋯ → Langganan, izin
  `platform.subscription.manage`): pilih 1/2/3/6/12 bulan atau ketik angka sendiri (1–24),
  isi alasan opsional, dan masa aktif tenant bertambah **tanpa ditagih**. Untuk promo,
  kompensasi gangguan, atau memperpanjang masa percobaan.
- Bonus **membebaskan tunggakan** yang ada dan **memulihkan tenant yang tersuspend** —
  tanpa itu scheduler akan men-suspend ulang tenantnya karena tagihan lama dan bonusnya
  jadi percuma. Selama masa bonus tenant tidak ditagih.
- Jejaknya terlihat dua sisi: tagihan `FREE-…` senilai Rp 0 berstatus lunas dengan badge
  **Bonus** di panel super-admin **dan** di halaman `/subscription` milik tenant, ditambah
  entri audit `platform.subscription.granted` berisi jumlah bulan & alasannya.
- Panel Langganan kini juga menampilkan **masa aktif ("aktif s/d")**, bukan cuma tanggal
  tagih berikutnya — tanpa itu efek bonus tak kelihatan.

### 2026-08-11 — Setelan email platform + template & logo yang bisa ditimpa tenant

**Ditambahkan**
- **Layar Setelan Email platform** (`/platform/email`, izin baru `platform.email.view` /
  `platform.email.manage`): server SMTP (host, port, kredensial, auth, STARTTLS), alamat &
  nama pengirim, URL publik aplikasi, tampilan bawaan email (logo, warna aksen, footer,
  tanda tangan), dan baris subjek per pemicu notifikasi. Password SMTP **write-only** —
  tersimpan terenkripsi, yang kembali ke layar hanya penandanya.
- **Timpaan per tenant** di Pengaturan Notifikasi: nama & alamat pengirim, logo, warna,
  footer, tanda tangan, dan subjek per pemicu. Kolom yang dikosongkan **mewarisi**
  setelan platform, dan tiap kolom menampilkan nilai warisannya sebagai placeholder.
- **Email berangkat sebagai HTML berlogo** (multipart: HTML + teks polos). Logo disajikan
  endpoint publik `/api/public/email-logo[/{tenantId}]` — klien email tak punya token, dan
  alamat bertenant tetap menyajikan logo platform setelah tenant menekan "kembalikan ke
  bawaan", supaya surat yang terlanjur terkirim tak berlubang.
- **Pratinjau & kirim email uji** di kedua layar, menempuh jalur render dan transport yang
  sama persis dengan email sungguhan.

**Diubah**
- **Sumber SMTP: baris DB menang, `spring.mail.*` jadi cadangan.** Deploy yang sudah
  berjalan tetap mengirim tanpa disentuh selama host di DB masih kosong; setelan yang
  disimpan dari layar admin langsung berlaku tanpa restart container.
- **Alamat pengirim tenant dipakai apa adanya sebagai `From`, plus `Reply-To`** ke alamat
  yang sama, disertai peringatan SPF/DKIM di UI — balasan pelanggan mendarat di tenant.
- **Nama pengirim tanpa timpaan = nama ISP-nya**, bukan nama platform: pelanggan menerima
  tagihan internetnya dari nama yang ia kenal.
- Baris subjek notifikasi tak lagi dipaku di `NotificationSender`; konstantanya pindah ke
  `EmailSubjectResolver` supaya bisa ditampilkan UI sebagai bawaan yang bisa ditimpa.

**Diperbaiki**
- **SMTP yang tak disetel di mana pun kembali jatuh ke mode catat-ke-log.** `spring.mail.host`
  selalu hadir di `application.yml` (`${FTTH_MAIL_HOST:}`) walau nilainya kosong, sehingga
  Spring Boot tetap membuat pengirim tanpa host — dan pengiriman gagal dengan "Mail server
  host not specified" alih-alih tercatat di log.

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

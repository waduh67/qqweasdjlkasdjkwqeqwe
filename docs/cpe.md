# Modul `cpe` — router/ONT pelanggan lewat GenieACS (TR-069)

Mengelola perangkat di rumah pelanggan: lihat status, ubah WiFi, reboot, factory
reset, diagnostik Ping/Speed, dan upgrade firmware — semuanya dari dashboard,
tanpa operator perlu tahu GenieACS ada.

Aplikasi **bukan** ACS. Yang bicara TR-069 dengan perangkat adalah GenieACS;
modul ini memerintahnya lewat **NBI** (REST) dan menyimpan proyeksi tipis agar
daftar perangkat bisa dirender tanpa memanggil NBI tiap kali.

```
  ONT/router ──TR-069──▶ genieacs-cwmp ──▶ Mongo ◀── genieacs-nbi ◀──REST── server
       ▲                                                                      │
       └──────────── Download firmware ── genieacs-fs ◀────────── task download
```

---

## Batas: apa yang disimpan, apa yang tidak

`CpeDevice` sengaja **tipis** — identitas + atribut yang jarang berubah:

```
CpeDevice (agregat)
├── identitas   genieacsId (_id di ACS, kunci tiap perintah) · serialNumber
├── perangkat   oui · productClass · manufacturer · model · softwareVersion
├── keadaan     ipAddress · lastInformAt · ssid? · temperatureC?
└── tautan      customerId? · onuId?        (uuid polos, tanpa FK — lintas modul)
```

Yang **cepat basi tidak disimpan**: daftar SSID lengkap beserta passphrase, host
yang tersambung, hasil diagnostik. Itu dibaca **langsung dari ACS** saat panel dibuka
(`GET /devices/{id}/live`), sebab menyimpannya cuma menyajikan salinan usang yang
meyakinkan.

`ssid` (SSID jaringan pertama saja) dan `temperatureC` adalah pengecualian sadar: tabel
armada di konsol ACS menampilkan ratusan baris sekaligus, dan memanggil `/live` per baris
berarti ratusan round-trip NBI tiap kali halaman dibuka. Keduanya ikut menumpang ronde
sinkron yang memang sudah berjalan, dengan konsekuensi yang diterima: umurnya sampai
satu `sync-interval`. Panel satu-pelanggan tetap membaca nilai hidup dari `/live`.

**PPPoE sengaja tak disalin ke sini** — modul `bng` sumber kebenarannya, dan salinan di
tabel ini basi dalam hitungan menit. Kolom PPPoE di konsol dirakit saat query.

Migrasi: `V14__cpe.sql` (device + action log), `V18` diagnostik, `V19` firmware,
`V20` aksi manage, `V101` ssid + suhu.

---

## Penautan ke pelanggan = kecocokan serial

Ini satu-satunya jembatan, dan sumber kebingungan paling sering:

```kotlin
// CpeSyncScheduler
val snapshot = bySerial[onu.serialNumber] ?: return@forEach
```

ACS **satu instance untuk semua tenant** — perangkat tak punya sumbu tenant.
Maka daftar device ditarik **sekali** (panggilan NBI global, di luar konteks
tenant), lalu disebar ke tiap tenant: masing-masing hanya mengklaim device yang
serialnya cocok dengan ONU miliknya.

Konsekuensi praktis:

- Serial yang dilaporkan perangkat (`Device.DeviceInfo.SerialNumber`) **harus
  persis sama** dengan `serialNumber` ONU di aplikasi. Beda satu karakter →
  perangkat ada di ACS tapi tak nempel ke pelanggan mana pun.
- ONU EPON diidentifikasi lewat MAC oleh OLT, sedangkan CPE melapor serial
  pabriknya. Kalau keduanya beda, satu pelanggan butuh dua catatan ONU — dan
  di peta bisa muncul dua titik. Samakan kalau bisa.
- Sinkron berjalan tiap `ftth.cpe.sync-interval` (default `PT5M`), jadi
  perangkat baru tak muncul seketika.

Kegagalan dikurung per-tenant (`TenantContext.runAs` + `REQUIRES_NEW`): satu
tenant gagal tak menghentikan yang lain. ACS mati → satu baris
`WARN Tak bisa menarik daftar device dari ACS` per ronde, tak ada stack trace,
fitur lain tak terganggu.

---

## API & izin

| Endpoint | Izin |
|---|---|
| `GET /api/cpe/devices?customerId=` | `cpe.device.view` |
| `GET /api/cpe/devices/{id}` | `cpe.device.view` |
| `GET /api/cpe/devices/{id}/live` | `cpe.wifi.view` |
| `POST /api/cpe/devices/{id}/wifi` | `cpe.wifi.manage` |
| `POST /api/cpe/devices/{id}/reboot` | `cpe.device.reboot` |
| `POST /api/cpe/devices/{id}/diagnostics/ping` | `cpe.diagnostic.run` |
| `POST /api/cpe/devices/{id}/diagnostics/speedtest` | `cpe.diagnostic.run` |
| `GET  /api/cpe/devices/{id}/firmware` | `cpe.firmware.manage` |
| `POST /api/cpe/devices/{id}/firmware` | `cpe.firmware.manage` |
| `POST /api/cpe/devices/{id}/factory-reset` | `cpe.device.manage` |
| `POST /api/cpe/devices/{id}/refresh` | `cpe.device.manage` |

Konsol ACS se-armada (halaman `/acs`) memakai sub-path `/acs` di controller yang sama:

| Endpoint | Izin |
|---|---|
| `GET /api/cpe/acs/server` | `cpe.acs.view` |
| `GET /api/cpe/acs/health` | `cpe.acs.view` |
| `GET /api/cpe/acs/stats` | `cpe.device.view` |
| `GET /api/cpe/acs/devices` | `cpe.device.view` |
| `GET /api/cpe/acs/devices.csv` | `cpe.device.view` |
| `GET /api/cpe/acs/logs` | `cpe.device.view` |
| `POST /api/cpe/acs/refresh-all` | `cpe.device.manage` |

Tiap aksi dicatat ke `cpe_action_log`. `refresh` memaksa connection request ke
perangkat lalu melaporkan "ACS Connect / Not Connect" — cara paling cepat tahu
perangkat masih menyahut atau tidak.

**`cpe.acs.view` sengaja dipisah dari `cpe.device.view`** dan diberikan juga ke role
Teknisi: muatan dua endpoint pertama murni nilai env global (alamat CWMP + kredensial
yang diketik ke ONT), nol data tenant. Teknisi lapangan butuh itu saat mendaftarkan ONT
di rumah pelanggan, tapi tak boleh melihat armada tenant. Di web, halaman `/acs`
menyembunyikan strip tab dan tak memanggil endpoint armada sama sekali saat pengguna
hanya punya izin pertama.

Namanya **wajib** berakhiran `.view`: `AccessChecker.isWrite()` = `!code.endsWith(".view")`,
jadi nama lain (mis. `cpe.acs.health`) akan membuat probe kesehatan dianggap operasi
tulis dan melempar 402 saat langganan tenant terkunci.

Tiga hal yang sengaja **tidak** dilakukan konsol:

- **Tak pernah mengembalikan jumlah device se-ACS.** Hitungan NBI mencakup semua tenant;
  semua angka tile datang dari tabel `cpe_device` yang ber-RLS.
- **Pesan error probe dibersihkan.** Exception `RestClient` menyisipkan URI penuh, jadi
  `http://genieacs-nbi:7557` akan muncul di browser operator tenant. Yang dikirim cuma
  kalimat tetap + nama kelas exception; pesan aslinya hanya ke log server.
- **Password ACS/connection-request tak pernah masuk CSV maupun baris log.**

"Segarkan Batch" (`refresh-all`) adalah sapuan **berplafon**, bukan "semua": hanya
perangkat online, diurut dari yang paling lama tak inform, dibatasi
`ftth.cpe.bulk-refresh-max` dan anggaran waktu `ftth.cpe.bulk-refresh-budget`. Tanpa
infrastruktur async di aplikasi ini, tiap klik menahan satu thread servlet + satu
koneksi Hikari; jumlah yang tak sempat disapu dikembalikan jujur sebagai `skipped`.

`CpeApi` mengekspos `CpeDeviceStatusRef` ke modul lain (dipakai
`subscriber360`): status online **dihitung di server** dari `lastInformAt`
dibanding `ftth.cpe.online-stale-after` (default `PT15M`), bukan disimpan.

---

## Firmware: aplikasi memerintah, GenieACS menyimpan

Berkas firmware tinggal di **GridFS** milik GenieACS, bukan di MinIO kita.
Alurnya:

1. Operator/admin mengunggah berkas ke GenieACS (lihat `deploy/DEPLOY.md`
   Bagian L.4) dengan metadata `fileType`, `oui`, `productClass`, `version`.
2. `GET /firmware` membaca `fs.files` lewat NBI, menyaring
   `fileType == "1 Firmware Upgrade Image"` **dan** cocok `oui`/`productClass`
   perangkat ini — jadi operator tak bisa keliru mengirim firmware model lain.
3. `POST /firmware` menitipkan task `download`. GenieACS merakit URL-nya sendiri
   dari konfigurasi `FS_HOSTNAME`/`FS_PORT`, lalu mengirim **Download RPC** ke
   perangkat lewat connection request.

Karena URL itu dirakit ACS dan dituju oleh **perangkat**, `FS_HOSTNAME` wajib
alamat publik VPS. Salah di sini = daftar firmware tampil normal, tombol jalan,
tapi perangkat gagal mengunduh diam-diam.

---

## Uji Kecepatan (TR-143)

`speedtest` memakai `DownloadDiagnostics`/`UploadDiagnostics` — **perangkat** yang
menarik/mendorong berkas uji, server hanya membaca hasilnya. Alamat berkasnya:

```yaml
ftth.cpe.diagnostics.download-url: ${FTTH_CPE_DIAGNOSTICS_DOWNLOAD_URL:...}
```

Default historisnya `http://speedtest.tele2.net/10MB.zip` — layanan itu **sudah
dimatikan Tele2**. Selama tak diganti ke berkas sendiri, uji kecepatan selalu
gagal. Nama parameter byte terukur berbeda antar model & firmware, jadi gateway
mengambil mana pun yang tersedia alih-alih memaksa satu nama.

---

## Konfigurasi

| Properti | Env | Default |
|---|---|---|
| `ftth.cpe.genieacs.base-url` | `FTTH_CPE_GENIEACS_BASE_URL` | `http://localhost:7557` |
| `ftth.cpe.genieacs.username` | `FTTH_CPE_GENIEACS_USERNAME` | *(kosong)* |
| `ftth.cpe.genieacs.password` | `FTTH_CPE_GENIEACS_PASSWORD` | *(kosong)* |
| `ftth.cpe.genieacs.health-timeout` | `FTTH_CPE_GENIEACS_HEALTH_TIMEOUT` | `PT3S` |
| `ftth.cpe.sync-interval` | `FTTH_CPE_SYNC_INTERVAL` | `PT5M` |
| `ftth.cpe.online-stale-after` | `FTTH_CPE_ONLINE_STALE_AFTER` | `PT15M` |
| `ftth.cpe.diagnostics.download-url` | `FTTH_CPE_DIAGNOSTICS_DOWNLOAD_URL` | *(tele2, sudah mati)* |
| `ftth.cpe.ont.public-host` | `FTTH_CPE_PUBLIC_HOST` | *(kosong)* |
| `ftth.cpe.ont.cwmp-port` | `FTTH_CPE_CWMP_PORT` | `7547` |
| `ftth.cpe.ont.acs-username` | `FTTH_CPE_ONT_ACS_USERNAME` | *(kosong)* |
| `ftth.cpe.ont.acs-password` | `FTTH_CPE_ONT_ACS_PASSWORD` | *(kosong)* |
| `ftth.cpe.ont.connection-request-username` | `FTTH_CPE_ONT_CR_USERNAME` | *(kosong)* |
| `ftth.cpe.ont.connection-request-password` | `FTTH_CPE_ONT_CR_PASSWORD` | *(kosong)* |
| `ftth.cpe.ont.periodic-inform-interval` | `FTTH_CPE_ONT_INFORM_INTERVAL` | `PT300S` |
| `ftth.cpe.temperature-params` | `FTTH_CPE_TEMPERATURE_PARAMS` | *(kosong)* |
| `ftth.cpe.bulk-refresh-max` | `FTTH_CPE_BULK_REFRESH_MAX` | `50` |
| `ftth.cpe.bulk-refresh-budget` | `FTTH_CPE_BULK_REFRESH_BUDGET` | `PT20S` |

Kredensial NBI global (bukan per-tenant) sebab ACS-nya memang satu untuk semua. Blok
`ftth.cpe.ont.*` beda urusan: itu yang **diketik ke perangkat**, dan aplikasi hanya
memajangnya — `public-host` kosong membuat konsol menandai "belum dikonfigurasi" alih-alih
memajang URL palsu. `FTTH_CPE_PUBLIC_HOST` dan `FTTH_CPE_CWMP_PORT` dipakai container
genieacs **dan** service `server`; keduanya harus diteruskan ke dua-duanya.

### Suhu perangkat: parameter vendor, mati secara bawaan

TR-069 tak membakukan suhu — tiap vendor menaruhnya di cabang `X_*` sendiri
(mis. `InternetGatewayDevice.DeviceInfo.X_HW_Temperature`). Karenanya
`ftth.cpe.temperature-params` kosong secara bawaan: projection NBI tak dilebarkan sama
sekali dan kolom TEMP di tabel perangkat tetap `—`.

Mengisinya pun **belum tentu** memunculkan angka: parameter vendor baru ada di pohon
GenieACS kalau skrip provisioning pernah me-refresh objek itu. Anggap kolom TEMP kosong
sebagai keadaan normal, bukan kerusakan.

### Batas skala yang sudah ada sebelumnya

`listDevices()` adalah panggilan NBI **tanpa paging**: seluruh dokumen device ditahan di
memori, lalu dipindai ulang sekali per tenant aktif (`CpeSyncScheduler.syncAll()`) —
O(device × tenant) tiap `sync-interval`. Melebarkan projection (SSID/suhu) hanya menambah
byte per dokumen, bukan jumlah dokumen atau round-trip, jadi tak mengubah bentuk masalah
ini. Kalau armada platform tumbuh sampai puluhan ribu perangkat, yang perlu diperbaiki
adalah paging + indeks serial di sisi ACS, bukan projection-nya.

---

## Keamanan: satu hal yang tak boleh salah

**NBI (7557) tidak punya otentikasi apa pun.** Siapa pun yang bisa menjangkaunya
boleh me-reboot, mem-factory-reset, dan mengubah konfigurasi seluruh router
pelanggan **semua tenant** sekaligus. Di stack prod ia sengaja tak punya
`ports:` — hanya hidup di jaringan internal compose. Jangan pernah dipublikasikan;
kalau butuh akses dari luar, lewat SSH tunnel.

Yang memang harus terbuka cuma yang **dihubungi perangkat**: `7547` (cwmp) dan,
bila memakai upgrade firmware, `7567` (fs). Sebisanya batasi ke rentang IP
jaringan akses pelanggan.

---

## Menjalankan & mencoba

- **Lab lokal**: `make lab` sudah menaikkan GenieACS + satu ONT palsu
  (`genieacs-sim`). Serialnya (`SIM_SERIAL`) sengaja disamakan dengan salah satu ONU
  yang diumumkan simulator OLT, jadi ONU Budi Lab sekaligus ONLINE di OLT **dan**
  punya CPE. Lihat `docs/lab-fullstack.md`.
- **Produksi**: `deploy/DEPLOY.md` **Bagian L** — port, `.env`, cara mengarahkan
  ONT, dan cara mengunggah firmware.

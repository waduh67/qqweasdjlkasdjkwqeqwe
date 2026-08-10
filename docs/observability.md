# Observability — memantau aplikasi terhadap dirinya sendiri

Jangan tertukar dengan modul `monitoring`: yang itu memantau **OLT & ONU pelanggan**.
Yang di sini memantau hal yang jauh lebih sunyi — **belasan pekerjaan latar** (`@Scheduled`)
yang menagih, memoll, menyinkron, menyapu SLA, dan menjatuhkan alarm.

Masalahnya spesifik: kalau salah satu job berhenti, **tak ada layar yang berubah merah**.
Tagihan sekadar tak terbit bulan itu. Sesi PPPoE sekadar tak tercatat. Tiket lewat SLA
sekadar tak diteriakkan. Semuanya tampak persis seperti "tidak ada masalah", dan baru
ketahuan seminggu kemudian lewat keluhan pelanggan — saat kerusakannya sudah harus
dibereskan dengan tangan.

Yang membuatnya lebih licin lagi: pola di seluruh repo ini adalah penjadwal luar yang
menyapu tenant di dalam `runCatching { }.onFailure { log.warn(...) }`. Bagus, karena satu
tenant rusak tak menjatuhkan yang lain — tapi akibatnya kegagalan cuma jadi baris log yang
tak seorang pun baca. Dan bila sebuah job berhenti **dijadwalkan** (kolam utas penjadwal
habis dipakai job yang menggantung), tak ada baris log sama sekali. Cuma kesunyian.

> Panduan operasional (cara menyalakan, apa yang harus dilakukan saat ada yang macet)
> ada di `deploy/DEPLOY.md` **Bagian N**. Dokumen ini menjelaskan **rancangannya**.

---

## Tiga lapis

```
                     ┌───────────────────────────────────────────────┐
   @Scheduled  ──AOP─▶│  JobHealthRegistry  (denyut nadi, in-memory)  │
   (semua job)        └───────────┬───────────────────┬───────────────┘
                                  │                   │
              ┌───────────────────▼──────┐   ┌────────▼─────────────────┐
              │ JobStallWatchdog         │   │ JobHealthMetrics         │
              │ (utas sendiri, tiap 5 m) │   │ (Micrometer gauge/counter)│
              └───────────┬──────────────┘   └────────┬─────────────────┘
                          │ event                     │
        ScheduledJobStalled/Recovered          /actuator/prometheus
                          │                            │
              notification/JobHealthAlertListener   Prometheus + Grafana
                          │                          (profil opsional)
                        email                    +  aturan alert
                                  │
                       GET /api/platform/jobs → halaman "Pekerjaan Latar"
```

| Lapis | Perlu setelan? | Menjawab |
|---|---|---|
| Halaman **Pekerjaan Latar** | tidak, sudah jalan | "sekarang sehat?" |
| **Email peringatan** | `FTTH_ALERT_EMAIL` | "beri tahu saya kalau ada yang mati" |
| **Prometheus + Grafana** | profil `monitoring` (opsional) | "sejak kapan melambat?" |

Yang benar-benar wajib cuma email. Halaman hanya menolong kalau ada yang membukanya, dan
tak ada yang membuka halaman kesehatan server **di hari yang tenang** — padahal justru
hari tenang itulah job-nya diam-diam mati.

---

## Instrumentasi: advisor AOP, bukan panggilan manual

`ScheduledJobHealthConfig` memasang satu advisor atas **seluruh** metode ber-`@Scheduled`.
Tak satu baris pun berubah di penjadwal mana pun.

Kenapa bukan "panggil registry dari tiap job"? Karena yang paling mahal dari pemantauan
adalah **yang lupa dipasang**. Dua belas penjadwal hari ini akan jadi lima belas bulan
depan, dan job yang paling mungkin macet justru yang paling sepi ditulis. Dengan advisor,
"terpantau" jadi sifat bawaan `@Scheduled` di aplikasi ini.

Tiga detail yang kalau salah membuat seluruh mekanisme ini jadi hiasan:

- **`@Role(ROLE_INFRASTRUCTURE)` wajib.** Tanpa aspectjweaver di classpath, pembuat proxy
  yang aktif adalah `InfrastructureAdvisorAutoProxyCreator` — ia **hanya** melirik advisor
  berperan infrastruktur. Advisor biasa akan didaftarkan dengan patuh lalu diabaikan
  diam-diam. Tak ada galat, tak ada peringatan; cuma metrik yang selamanya nol.
- **`Ordered.HIGHEST_PRECEDENCE`** menaruhnya di lapis terluar, jadi durasi yang tercatat
  **termasuk commit transaksi** dan kegagalan commit ikut terhitung sebagai job gagal.
  Advisor di dalam `@Transactional` akan melaporkan sukses untuk ronde yang justru
  di-rollback sesudahnya.
- **Bukan `TaskDecorator`.** Dekorator penjadwal menerima `RunnableScheduledFuture` yang
  identitas metodenya sudah hilang, dan pembungkus penanganan galat Spring berada **di
  dalamnya** — dari sana nama job maupun lemparannya tak akan pernah terlihat.

Instrumentasi **melempar ulang** apa pun yang dilempar job: pemantauan tak boleh mengubah
perilaku yang diamatinya.

### Interval diambil dari Spring, bukan dari anotasi

`ScheduledJobDiscovery` mendaftarkan tiap job **beserta intervalnya** saat
`ApplicationReadyEvent`, sebelum ronde pertamanya sempat berjalan.

Ini penting untuk deteksi macet: tanpa pendaftaran awal, job berinterval panjang (mis.
penagihan 12 jam) baru muncul di daftar setelah ronde pertamanya — persis pada rentang
waktu ketika kita paling ingin tahu ia sudah terjadwal atau belum.

Intervalnya dibaca dari pendaftaran Spring (`ScheduledTaskHolder` → `IntervalTask`), bukan
dari anotasinya: `fixedDelayString` di repo ini semuanya berupa placeholder properti, jadi
hanya Spring yang tahu angka yang **benar-benar berlaku** setelah konfigurasi lingkungan
diterapkan.

Identitas job diambil dari `toString()` tugasnya (`paket.Kelas.metode`) karena Spring
membungkus runnable-nya dalam kelas internal yang tak membuka pembungkusnya — itulah
satu-satunya jalan resmi yang tersisa menuju nama metode aslinya. Kunci registry dibentuk
dari **nama kelas**, bukan objeknya, supaya jalur penemuan (yang cuma punya string) dan
jalur eksekusi (yang punya objek, mungkin sudah ter-proxy CGLIB) bermuara ke entri yang sama.

---

## Registry: sengaja hanya di memori

`JobHealthRegistry` menyimpan per job: jumlah ronde, kegagalan, waktu mulai/sukses/gagal
terakhir, galat terakhir, lama ronde terakhir, dan apakah sedang berjalan.

Tak ada tabel, tak ada Redis — dan itu keputusan sadar. Pertanyaannya selalu **"sejak
proses ini hidup, apakah job X pernah selesai?"**, bukan riwayat historis (untuk itu ada
Prometheus). Setelah restart, umur dihitung sejak boot, jadi aplikasi yang baru naik tak
langsung menyemburkan peringatan palsu.

```
stallAfter = max(interval × stallFactor, stallGrace)
stalled    = sinceSuccess > stallAfter
```

`stallGrace` (bawaan 10 menit) adalah **batas bawah**: tanpa itu job berinterval 10 detik
akan dinyatakan macet hanya karena satu ronde tersendat, dan peringatan berisik selalu
berakhir diabaikan. `stallFactor` bawaan 3 → penerbit tagihan tiap 12 jam diperingatkan
setelah 36 jam tanpa sukses.

Job yang intervalnya tak diketahui (dipicu cron/trigger, atau lambda terdaftar manual)
tetap terpantau — cuma tak punya ambang macet (`stallAfter = null`), jadi ia tak pernah
dinyatakan macet ketimbang dinyatakan macet berdasarkan tebakan.

Galat terakhir **dipangkas 300 karakter**: ia berakhir sebagai label di layar & email, dan
stack trace utuh sudah ada di log tempat kejadiannya.

---

## Penjaga: berjalan di utasnya sendiri

`JobStallWatchdog` memakai `ScheduledExecutorService` satu-utas milik sendiri
(`ftth-job-watchdog`, daemon) — **bukan** `@Scheduled` seperti semua pekerjaan lain di repo
ini.

Itu bukan gaya-gayaan. Kemacetan yang paling mungkin terjadi justru **kolam utas penjadwal
yang habis** dipakai job menggantung (SNMP ke OLT yang tak menjawab, ACS yang diam). Penjaga
yang ikut mengantre di kolam yang sama akan diam persis pada saat ia paling dibutuhkan.
Penjaga yang bisa ikut macet bersama yang dijaganya sama saja dengan tidak ada penjaga.

Yang diterbitkan hanya **peralihan keadaan**:

| Kejadian | Event | Kapan |
|---|---|---|
| sehat → macet | `ScheduledJobStalled(repeated = false)` | seketika |
| masih macet | `ScheduledJobStalled(repeated = true)` | tiap `alertRepeat` (bawaan 6 jam) |
| macet → sehat | `ScheduledJobRecovered` | ronde berikutnya berhasil |

`repeated` dipisah supaya penerima bisa membedakan "baru terjadi" dari "masih terjadi sejak
kemarin". Pengingat ditahan 6 jam dengan sengaja: job yang mati semalaman akan mengirim
ratusan email, dan banjir peringatan selalu berakhir jadi aturan filter di inbox — sesudah
itu peringatan berikutnya tak pernah sampai ke siapa pun.

Peringatan **selalu masuk log** lebih dulu, apa pun nasib emailnya: log adalah satu-satunya
kanal yang tak bergantung pada SMTP, jaringan, atau seseorang yang sudah mengisi
konfigurasi.

### Batas modul: `common` menerbitkan, `notification` mengirim

`common` tak boleh tahu email itu ada, jadi penjaga hanya menerbitkan peristiwa;
`notification/JobHealthAlertListener` yang mengubahnya jadi surat — di tempat SMTP memang
berumah.

Listener itu satu-satunya pemberitahuan di modul `notification` yang **tak ditujukan kepada
pelanggan dan tak terikat tenant mana pun**. Karena itu ia memakai `EmailDispatcher`
platform langsung, bukan `NotificationSender` yang menghormati saklar pemicu tiap tenant:
tak masuk akal bila ISP pelanggan kita bisa mematikan peringatan tentang server kita
sendiri.

Kegagalan kirim **tidak dilempar**. Peristiwanya terbit dari utas penjaga, dan penjaga yang
mati gara-gara SMTP mati adalah cara paling konyol kehilangan pemantauan.

`alertEmail` kosong = peringatan hanya dicatat ke log. Aman untuk dev; di produksi isilah,
karena log yang tak dibaca sama saja diam.

---

## Metrik Prometheus

| Metrik | Jenis | Arti |
|---|---|---|
| `ftth_job_success_age_seconds` | gauge | **umur sukses terakhir** — yang paling berharga |
| `ftth_job_stalled` | gauge | 1 bila dianggap macet |
| `ftth_job_running` | gauge | 1 bila sedang berjalan detik ini |
| `ftth_job_duration_last_seconds` | gauge | lama ronde terakhir |
| `ftth_job_interval_seconds` | gauge | selang terjadwal |
| `ftth_job_runs_total` | counter | ronde yang selesai |
| `ftth_job_failures_total` | counter | ronde yang melempar galat |

Yang paling berguna bukan jumlah eksekusi, melainkan **umur sukses terakhir**: nilai yang
terus menanjak adalah satu-satunya tanda yang muncul ketika sebuah job berhenti bekerja —
kegagalan diam yang tak menghasilkan galat, tak menghasilkan log, dan tak menghasilkan
keluhan sampai beberapa hari kemudian.

`ftth_job_stalled` sengaja dihitung **di sisi aplikasi**, bukan diserahkan ke ekspresi
PromQL: ambangnya bergantung pada interval tiap job (10 detik sampai 12 jam), dan aturan
alert yang menyalin angka-angka itu satu per satu pasti akan ketinggalan zaman.

> **Labelnya `job_name`, BUKAN `job`.** Prometheus memakai `job` untuk nama scrape-config-nya
> sendiri; label aplikasi yang bentrok diganti diam-diam jadi `exported_job` saat diserap.
> Aturan alert yang ditulis dengan `job="…"` akan cocok dengan hal yang sama sekali berbeda —
> kesalahan yang tak menimbulkan galat apa pun, cuma peringatan yang tak pernah menyala.
> Label kedua: `module` (segmen paket setelah `com.duluin.ftth.`).

Pengukur memegang **registry** lalu menengok job lewat namanya tiap kali diambil sampel.
Micrometer menyimpan objek sumber secara lemah — menahan `JobHealth` langsung akan membuat
pengukurnya lenyap tanpa jejak begitu GC lewat.

---

## Menjaga endpoint metrik

`/actuator/prometheus` punya **security chain sendiri** (`@Order(1)`, dipasang sebelum chain
utama) dengan token statis.

Yang menjemput metrik adalah Prometheus, bukan manusia: ia tak bisa masuk, tak bisa
menyegarkan token, dan hidup bertahun-tahun. Bearer JWT kita yang berumur 15 menit sama
sekali tak cocok. Sementara membuka endpoint ini tanpa syarat berarti membagikan bentuk
sistem, nama job, dan volume kerja tiap tenant kepada siapa saja yang menebak URL-nya.

Jalan tengahnya token statis panjang, diterima lewat `Authorization: Bearer <token>` **atau**
header `X-Metrics-Token`, dibandingkan **dengan waktu tetap** (`MessageDigest.isEqual` —
perbandingan string biasa bocor lewat waktu), dan **mati secara bawaan**: `metricsToken`
kosong berarti tertutup rapat, bukan terbuka.

Actuator hanya membuka `health,info,prometheus` (`management.endpoints.web.exposure.include`).

---

## Halaman "Pekerjaan Latar"

`GET /api/platform/jobs` (izin `platform.ops.view`) → `/platform/jobs` di web, menyegarkan
diri tiap 15 detik.

Metrik Prometheus memang lebih lengkap, tapi ia mengandaikan ada Prometheus + Grafana yang
sudah berdiri. Deploy paling kecil kita belum tentu punya itu — dan justru deploy kecil yang
paling sering ditinggal tanpa pengawasan. Satu halaman yang bisa dibuka kapan saja menutup
jarak itu tanpa menambah infrastruktur.

Lintas-tenant dan **tanpa RLS sama sekali**: yang dilaporkan adalah proses server, bukan data
ISP mana pun. Karena itu izinnya `platform.ops.view` — operator tenant tak berkepentingan,
dan nama job bisa membocorkan bentuk dalam sistem tanpa memberi mereka manfaat apa pun.

Durasi dikirim sebagai **detik** (bukan `Duration`): serialisasi ISO-8601 (`PT2H15M`) harus
diurai ulang di browser hanya untuk bisa dibandingkan dan diformat. Pecahan detik
dipertahankan — banyak ronde selesai di bawah satu detik, dan "0" untuk semuanya menghapus
justru perbedaan yang ingin dilihat.

Sengaja **tidak ada tombol "jalankan sekarang"**. Menyuntik ronde tagihan dengan tangan dari
halaman diagnosa adalah cara termudah menerbitkan tagihan ganda.

---

## Konfigurasi

| Properti | Env | Bawaan | Guna |
|---|---|---|---|
| `ftth.observability.metrics-token` | `FTTH_METRICS_TOKEN` | *(kosong)* | token `/actuator/prometheus`; kosong = tertutup |
| `ftth.observability.alert-email` | `FTTH_ALERT_EMAIL` | *(kosong)* | penerima peringatan; kosong = hanya log |
| `ftth.observability.stall-factor` | — | `3` | macet bila sukses terakhir > `interval × faktor` |
| `ftth.observability.stall-grace` | — | `PT10M` | batas bawah ambang macet |
| `ftth.observability.stall-check-interval` | — | `PT5M` | selang ronde penjaga |
| `ftth.observability.alert-repeat` | — | `PT6H` | jeda pengingat "masih macet" |

Semua angka divalidasi di `init` (`stallFactor ≥ 1`, sisanya harus positif): setelan yang
salah harus menggagalkan boot, bukan diam-diam mematikan penjaga.

---

## Lapis luar: Prometheus + Grafana (opsional)

Berkasnya di `deploy/monitoring/` — aktif hanya lewat profil compose `monitoring`, jadi
`up -d` biasa tak menyalakannya.

- `prometheus.yml` — men-scrape `server:8080` lewat jaringan internal compose. Tokennya
  dibaca dari **berkas** (`credentials_file`), bukan variabel lingkungan: Prometheus tak
  mengembangkan env di berkas config, dan menuliskan token mentah di sana akan membuatnya
  ikut masuk Git. Prometheus juga men-scrape dirinya sendiri — berguna untuk membedakan
  "job aplikasi mati" dari "yang memantaunya yang mati".
- `rules.yml` — empat alert: server tak terjangkau, pekerjaan latar macet, sering gagal
  (`increase(ftth_job_failures_total[1h]) > 5`), dan penjadwal kehabisan utas
  (`executor_queued_tasks{name="taskScheduler"} > 0`).
- `grafana/dashboards/ftth-jobs.json` — dasbor yang terisi sendiri.

Aturan `ServerTidakTerjangkau` menangkap justru hal yang **tak mungkin dilaporkan aplikasi**:
ketika seluruh prosesnya mati, tak ada yang tersisa untuk mengirim email. Itulah alasan lapis
ini ada meski email sudah jalan.

Keduanya tak pernah menyentuh internet: Caddy hanya meneruskan `/api/*`, `/swagger-ui*`, dan
`/v3/api-docs*`, sedangkan Prometheus & Grafana terikat ke `127.0.0.1` di VPS — dibuka lewat
terowongan SSH. Konsekuensinya `https://app.contoh.com/actuator/prometheus` **tak akan pernah
menjawab**, juga bagi yang sudah punya Prometheus sendiri.

---

## Uji

| Berkas | Yang dipatok |
|---|---|
| `common/JobHealthRegistryTest.kt` | perhitungan ambang macet, umur sukses, penghitung ronde/gagal |
| `common/JobStallWatchdogTest.kt` | peralihan keadaan & jeda pengingat (jam disuntik, bukan ditunggu) |
| `notification/JobHealthAlertListenerTest.kt` | subjek/isi surat & sikap saat SMTP gagal |
| `ObservabilityIT.kt` | job nyata terdaftar beserta interval yang berlaku, advisor sungguh terpasang (denyut bertambah saat job berjalan), dan `/actuator/prometheus` tertutup tanpa token |

`JobStallWatchdog.check(now)` menerima waktu sebagai parameter justru agar bisa diuji —
menunggu jam dinding hanya akan membuat pengujiannya lambat sekaligus rapuh.

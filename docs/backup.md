# Cadangan & pemulihan — rancangannya

Sistem cadangan punya satu mode kegagalan yang jauh lebih buruk daripada tidak punya
cadangan sama sekali: **cadangan yang tampak sehat tapi tak berisi apa-apa**. Ia
menghasilkan berkas tiap malam, ukurannya wajar, log-nya hijau — dan baru ketahuan hampa
pada hari kita betul-betul membutuhkannya, yaitu hari ketika sudah tak ada jalan mundur.

Seluruh rancangan di sini disusun mengelilingi satu keyakinan itu: **cadangan yang belum
pernah dipulihkan bukan cadangan, cuma harapan.**

> Panduan operasional (menyalakan, memeriksa, melatih pemulihan, memulihkan sungguhan)
> ada di `deploy/DEPLOY.md` **Bagian M**. Dokumen ini menjelaskan **kenapa** bentuknya
> begini.

---

## Bentuknya: dua service di dalam stack

```
docker-compose.prod.yml
├── backup          timescale/timescaledb-ha:pg17   → DB aplikasi (ftth)   02:30
└── backup-radius   postgres:16-alpine              → DB radius            03:00
        │
        ├── entrypoint.sh   penjadwal (tidur → jalan → tidur)
        ├── backup.sh       satu ronde dump + verifikasi + pangkas
        └── restore.sh      pemulihan: latihan (bawaan) atau --replace
                                  ./backup:/opt/backup:ro   ./backups:/backups
```

**Bukan cron di host.** Image Postgres tak membawa cron, dan cron yang dipasang di host
adalah hal yang lupa ikut pindah saat VPS diganti — persis momen ketika cadangan paling
dibutuhkan. Satu proses yang log-nya jatuh ke `docker compose logs backup` jauh lebih mudah
dipantau daripada cron yang diam-diam gagal di dalam container.

**Dua image berbeda** karena dua database berbeda versi mayor (pg17 untuk aplikasi + Timescale/
PostGIS, pg16 untuk radius) — dan klien `pg_dump` tak boleh lebih tua dari servernya. Karena
itu skripnya ditulis dalam **POSIX `sh`**, bukan bash: berkas yang sama harus jalan di Debian
dan di Alpine/busybox.

Jamnya digeser setengah jam supaya dua `pg_dump` tak berebut I/O di VPS yang sama.

Berkasnya ditulis ke **bind mount** `./backups`, bukan named volume — supaya operator bisa
menyalinnya keluar dengan `scp`/`rsync` tanpa mantra `docker cp`. `user: root` + `chmod 600`:
isinya seluruh basis data, jadi memang sepantasnya hanya root yang boleh membacanya.

---

## Gerbang terpenting: role yang mendump harus kebal RLS

```
privileged = SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user
   ≠ t  →  BERHENTI, jangan menghasilkan berkas apa pun
```

Semua tabel ber-tenant memakai `FORCE ROW LEVEL SECURITY`, jadi kebijakan isolasi berlaku
**bahkan untuk pemilik tabel** (role `ftth`). Kebijakannya menyaring
`tenant_id = current_setting('app.tenant_id')`, dan sesi `pg_dump` tak pernah menyetel GUC
itu — hasilnya nol baris.

Dan `pg_dump` **tidak menganggapnya galat**. Ia menulis `.dump` yang tampak wajar, lengkap
dengan skema, exit code 0. Inilah kegagalan hampa yang disebut di awal, dan satu-satunya
cara mencegahnya adalah **menolak di depan**, bukan berdoa. Karena itu container cadangan
menyambung sebagai superuser `postgres` (`POSTGRES_SUPER_PASSWORD`), bukan sebagai role
aplikasi.

Pasangannya: `BACKUP_DB_OWNER` disetel ke role **aplikasi**. Yang mendump superuser, tapi
yang memiliki objek saat dipulihkan tetap `ftth` — kalau tidak, database hasil pemulihan
akan dimiliki `postgres` dan RLS-nya berperilaku lain dari produksi.

---

## Sebuah dump belum jadi cadangan sebelum diverifikasi

Urutan satu ronde:

```
1. dump ke berkas .part          ← nama sementara, belum terlihat sebagai cadangan
2. pg_restore --list             ← arsip terpotong ketahuan DI SINI
3. khusus app: TOC wajib memuat 'TABLE DATA public tenant'
4. mv .part → nama final + chmod 600
5. pg_dumpall --globals-only     ← definisi role level-cluster
6. pangkas yang lebih tua dari retensi
7. tulis last-backup.txt
```

**Menulis ke `.part` dulu** berarti dump yang mati di tengah jalan (disk penuh, container
dibunuh saat deploy) tak pernah menyandang nama cadangan yang sah. Berkas `.part` yang
tertinggal lebih dari sehari dibersihkan ronde berikutnya.

**`pg_restore --list` membaca seluruh daftar isi**, jadi arsip terpotong ketahuan saat itu
juga — bukan nanti, saat kita betul-betul butuh.

**Cek tabel `tenant`** adalah rem kedua untuk kegagalan RLS: tanpa tenant, seluruh database
aplikasi tak berarti apa-apa. Kalau gerbang superuser di atas suatu saat tertembus (mis.
seseorang mengubah `PGUSER` di `.env`), cek ini yang menangkapnya.

Kegagalan apa pun menulis `status=GAGAL` ke `last-backup.txt`, bukan diam. Berkas itu satu
baris-per-fakta (`status`, `at`, `file`, `bytes`, `seconds`, `kept`) supaya bisa dibaca mata
maupun `grep`.

`globals-*.sql` ada karena **role & password level-cluster tak ikut dalam dump satu
database**. Memulihkan ke server kosong tanpa itu menghasilkan objek tanpa pemilik ("role
ftth does not exist"). Di stack kita role dibuat ulang oleh `postgres-init` saat boot
pertama, jadi berkas ini jaring pengaman untuk pemulihan ke **mesin lain**.

---

## Penjadwal yang mengejar ketinggalan

```
saat start:
  belum ada cadangan sama sekali        → jalankan SEKARANG
  cadangan terakhir > BACKUP_STALE_HOURS (26 jam) → jalankan SEKARANG
lalu: tidur sampai BACKUP_AT, jalan, tidur lagi
```

Container ini ikut restart **tiap kali stack di-deploy**. Tanpa pengejaran ketinggalan,
deploy yang kebetulan jatuh tiap hari sebelum jam cadangan akan membuat cadangan **tak
pernah berjalan sama sekali** — dan tak ada yang memberi tahu, karena tak ada yang gagal.

Waktu tunggu selalu dihitung ulang dari jam dinding (`(target - now + 86400) % 86400`), jadi
pergeseran zona waktu/DST paling banter menggeser satu ronde, tak pernah mengakumulasi
galat.

Kegagalan satu ronde **tidak** menjatuhkan penjadwal: ia mencatat "akan dicoba lagi besok"
dan tetap hidup. Penjadwal cadangan yang mati karena satu malam buruk adalah cara paling
sunyi kehilangan seluruh cadangan berikutnya.

Skripnya dipanggil lewat `sh "$HERE/backup.sh"`, bukan dieksekusi langsung: bind mount
membawa bit permission apa adanya dari host, dan satu `chmod -x` yang tak sengaja tak boleh
membuat cadangan berhenti diam-diam.

---

## Pemulihan: latihan dulu, timpa belakangan

`restore.sh` **bawaannya mode latihan** — dipulihkan ke database **baru** di server yang
sama, produksi tak disentuh sama sekali. Menimpa butuh `--replace` **dan** mengetik ulang
nama database.

Bawaan yang aman itu disengaja: satu-satunya cara membuktikan cadangan bisa dipulihkan
adalah dengan benar-benar memulihkannya, dan itu hanya akan rutin dilakukan bila aman
dijalankan di produksi yang sedang melayani pelanggan. Perkakas pemulihan yang menakutkan
adalah perkakas yang tak pernah dicoba sampai keadaan darurat.

Arsip **diperiksa lebih dulu** (`pg_restore --list`) sebelum satu pun perintah dijalankan:
memulihkan setengah jalan lalu baru sadar berkasnya rusak adalah cara terburuk mengetahuinya
— apalagi di mode `--replace`, ketika yang lama sudah telanjur dihapus.

### TimescaleDB harus dibuat diam

```
CREATE EXTENSION postgis, timescaledb
SELECT timescaledb_pre_restore()
pg_restore …
SELECT timescaledb_post_restore()      ← dijalankan APA PUN hasil pg_restore
ANALYZE
```

Tanpa `timescaledb_pre_restore()`, event trigger-nya ikut campur saat chunk hypertable
dibuat ulang dan pemulihannya berantakan. `post_restore` dijalankan tanpa syarat karena
database yang ditinggal dalam mode pre-restore **tak bisa dipakai sama sekali** — gagal
setengah jalan tak boleh berakhir dengan database yang lumpuh permanen.

Extension dipasang **sebelum** data masuk supaya tipe & fungsinya sudah ada saat dibutuhkan.

### Bukti, bukan "selesai"

Setiap pemulihan diakhiri hitungan baris per tabel kunci:

| Target | Tabel yang dihitung |
|---|---|
| `app` | `tenant` `app_user` `customer` `subscription` `invoice` `work_order` `helpdesk_ticket` **`onu_metric`** |
| `radius` | `nas` `radcheck` `radgroupreply` `radusergroup` `radacct` |

`onu_metric` ikut dengan sengaja: ia **hypertable TimescaleDB**, jadi jumlah barisnya
sekaligus membuktikan chunk deret-waktu ikut pulih — bagian yang paling mudah diam-diam
hilang tanpa satu pun pesan galat.

Skrip keluar dengan status bukan-nol bila `pg_restore` melaporkan galat, supaya latihan yang
setengah berhasil tak terbaca sebagai lulus di skrip otomatis.

---

## Yang sengaja TIDAK dicadangkan

| Isi | Alasan |
|---|---|
| Bukti foto WO (volume MinIO) | bukan data yang mustahil dibentuk ulang, dan ukurannya tumbuh tanpa batas — salin terpisah bila dianggap penting |
| Data GenieACS (Mongo) | proyeksi; terbentuk lagi sendiri saat perangkat Inform berikutnya |
| `.env` | **berisi semua secret** — tak boleh berada di folder cadangan yang disalin ke mana-mana |

Yang terakhir adalah risiko yang paling sering diremehkan: kehilangan `.env` sama gawatnya
dengan kehilangan database. Tanpa `FTTH_ENCRYPTION_SECRET` yang **sama persis**, kredensial
SNMP & rahasia VPN di dalam cadangan tak bisa dibaca lagi — datanya utuh, tapi tak berguna.
Simpan salinannya di luar VPS, terpisah dari cadangan database.

Retensi bawaan 14 hari (`BACKUP_RETENTION_DAYS`) memangkas `.dump` **dan** `globals-*.sql`
sekaligus; keduanya tak berguna tanpa pasangannya.

---

## Batas yang tak bisa ditutup skrip ini

Cadangan yang tinggal di mesin yang sama **tak menolong saat mesinnya yang hilang** — disk
rusak, akun ditutup, VPS terhapus. Menyalinnya keluar (`rsync`/`rclone` ke laptop/NAS/object
storage) adalah langkah yang harus dilakukan manusia, dan `DEPLOY.md` M.7 menyebutkannya
sebagai "jangan dilewat" justru karena ia satu-satunya bagian yang tak bisa dipaksakan oleh
kode di dalam stack.

Isinya seluruh data pelanggan ISP. Simpan terenkripsi, dan jangan di folder yang tersinkron
sembarangan.

---

## Konfigurasi

| Env | Bawaan | Guna |
|---|---|---|
| `BACKUP_TZ` | `UTC` | zona waktu jam cadangan |
| `BACKUP_AT` | `02:30` | jam cadangan DB aplikasi |
| `BACKUP_RADIUS_AT` | `03:00` | jam cadangan DB radius (digeser agar tak rebutan I/O) |
| `BACKUP_RETENTION_DAYS` | `14` | lebih tua dari ini dibuang |
| `BACKUP_STALE_HOURS` | `26` | ambang "ketinggalan" saat container start |
| `POSTGRES_SUPER_PASSWORD` | — | **wajib**; dump harus kebal RLS |
| `BACKUP_DB_OWNER` | `FTTH_DB_USER` | pemilik objek saat dipulihkan |

`BACKUP_AT` yang tak berbentuk `HH:MM` **tidak** menggagalkan container — ia dicatat ke log
lalu jatuh ke `02:30`. Setelan salah tak boleh berarti tak ada cadangan sama sekali.

---

## Kaitan dengan ekspor data tenant

Jangan tertukar dengan `GET /api/tenant/export` (offboarding): itu **satu tenant**, berformat
CSV, disunting dari rahasia, dan ditujukan untuk **diberikan kepada pemiliknya**. Cadangan di
sini adalah **seluruh cluster**, format biner `pg_dump`, memuat hash password dan seluruh
tenant sekaligus — ia untuk kita, dan tak boleh keluar ke siapa pun.

Keduanya menjawab pertanyaan berbeda: "bagaimana kalau ISP ini pindah?" versus "bagaimana
kalau servernya hilang?".

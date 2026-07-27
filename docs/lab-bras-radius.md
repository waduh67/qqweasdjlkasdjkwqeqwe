# Lab BRAS/RADIUS — Uji Adapter BNG Sungguhan

Panduan ini menuntun bang menguji modul **BNG** (BRAS/RADIUS) collector dengan
perangkat/servis **nyata**, bukan simulator. Dua adapter yang diuji:

| Adapter | Perangkat | Baca sesi | Kontrol (Disconnect/CoA) |
|---|---|---|---|
| **MIKROTIK** | Mikrotik (RouterOS v7) | REST `/ppp/active` + octet interface | REST: hapus sesi aktif / ubah simple queue |
| **FREERADIUS** | Server FreeRADIUS + Postgres | JDBC baca tabel `radacct` | RFC 5176 DAE (UDP :3799) ke perangkat BRAS |

Ada **dua jalur lab**, pilih sesuai kebutuhan:

- **Jalur A — Mikrotik CHR** (disarankan untuk uji PENUH): sebuah Mikrotik "virtual"
  gratis (Cloud Hosted Router). Menguji poll + Disconnect + CoA end-to-end lewat REST.
- **Jalur B — docker `radius`** (paling cepat): FreeRADIUS + Postgres via docker-compose.
  Menguji jalur BACA adapter FreeRADIUS tanpa perangkat apa pun.

Boleh jalankan salah satu atau keduanya. Untuk uji CoA/Disconnect FreeRADIUS lewat DAE,
gabungkan keduanya: CHR autentikasi ke FreeRADIUS, DAE ditembak ke CHR.

---

## 0. Prasyarat

- App `ftth-server` jalan + login sebagai admin tenant (sudah biasa bang lakukan).
- `ftth-collector` bisa dijalankan dari mesin yang **bisa menjangkau** BRAS/servis lab
  (satu LAN, atau VPS yang sama). Collector konek keluar ke server; tapi ia yang menembak
  REST/JDBC/DAE ke BRAS, jadi jaringan collector→BRAS harus nyambung.
- Untuk Jalur B: Docker + docker-compose (yang sudah dipakai untuk stack lain).

> **Ingat arah koneksi.** Collector → BRAS (REST/JDBC/DAE). Jadi jalankan collector di
> tempat yang bisa "ping" ke IP CHR / ke Postgres lab. Kalau CHR di laptop dan collector di
> VPS Azure, dua-duanya harus saling terjangkau (VPN/port-forward/atau taruh sekalian di VPS).

---

## Jalur A — Mikrotik CHR (uji penuh: poll + Disconnect + CoA)

**CHR (Cloud Hosted Router)** = RouterOS yang jalan sebagai VM. Gratis untuk throughput ≤1 Mbps
(cukup untuk lab), fitur lengkap termasuk REST API v7 dan PPPoE server. Tak perlu beli hardware.

### A.1 Pasang CHR

Pilih salah satu tempat menjalankan VM-nya:

**Opsi 1 — lokal (VirtualBox / VMware, paling gampang):**

1. Unduh image CHR (format sesuai hypervisor, mis. `.vdi` untuk VirtualBox) dari halaman
   Download MikroTik → bagian **Cloud Hosted Router** → versi **7.x stable**.
2. Buat VM baru: tipe *Linux/Other 64-bit*, RAM 256 MB cukup, pasang disk = image `.vdi` tadi.
3. **Jaringan: pakai adapter "Bridged"** supaya CHR dapat IP di LAN yang sama dengan mesin
   collector (lebih mudah dari NAT). Start VM.
4. Login pertama: user `admin`, password kosong (nanti diminta set password).

**Opsi 2 — di VPS Azure (via qemu/KVM):** jalankan image CHR raw pakai qemu. Lebih ribet soal
jaringan bridge; kalau baru pertama, pakai Opsi 1 dulu.

Setelah login, lihat IP yang didapat:

```rsc
/ip address print
```

Catat IP-nya (misal `192.168.1.50`). Itu yang jadi **host** BRAS di app nanti.

### A.2 Konfigurasi RouterOS (tinggal paste)

Buka terminal CHR (Winbox/SSH/console), lalu **tempel skrip berikut**. Skrip ini:
mengaktifkan REST (service `www`), membuat user API khusus, menyiapkan PPPoE server + satu
profil + satu secret pelanggan uji, dan membuat simple queue agar CoA (ubah kecepatan) ada
sasarannya.

```rsc
# ====== SKRIP LAB CHR — BNG FTTH ======
# 1) Aktifkan REST API lewat HTTP (www:80). Di LAN lab, HTTP lebih mulus daripada
#    HTTPS self-signed. (Untuk HTTPS pakai service "www-ssl":443 + apiUseTls=true di app.)
/ip service enable www
/ip service set www port=80

# 2) User khusus untuk app (jangan pakai admin). Ganti password sesukanya.
/user group add name=api policy=api,read,write,test,winbox,ssh,rest-api comment="untuk ftth app"
/user add name=ftth-api group=api password=ApiRahasia123 comment="dipakai collector ftth"

# 3) IP pool + profil PPPoE (alamat yang dibagikan ke pelanggan dial-in).
/ip pool add name=ppp-pool ranges=100.64.0.10-100.64.0.200
/ppp profile add name=ftth-profile local-address=100.64.0.1 remote-address=ppp-pool

# 4) PPPoE server di sebuah interface. GANTI "ether2" ke interface yang menghadap
#    ke sisi pelanggan/klien uji. (Cek nama interface: /interface print)
/interface pppoe-server server add service-name=ftth interface=ether2 \
    default-profile=ftth-profile disabled=no one-session-per-host=yes

# 5) Kredensial pelanggan uji (dipakai untuk dial dari klien).
/ppp secret add name=budi@isp.net password=rahasia123 service=pppoe profile=ftth-profile

# 6) Simple queue bernama SAMA dengan username → jadi sasaran CoA (ubah kecepatan).
#    Adapter mencari queue yang name-nya == username PPPoE.
/queue simple add name=budi@isp.net target=100.64.0.0/24 max-limit=10M/50M comment="rate awal budi"
# ====== SELESAI ======
```

> **Catatan interface (langkah 4).** CHR default punya `ether1` (menghadap ke jaringan/collector)
> dan mungkin `ether2`. PPPoE server harus di interface yang menghadap **klien dial**, bukan yang
> menghadap collector. Kalau bang cuma punya satu interface, tambahkan interface kedua di VM
> untuk sisi klien. Untuk sekadar menguji jalur BACA/kontrol, bang bisa juga dial dari perangkat
> lain di LAN yang sama.

### A.3 Buat satu sesi PPPoE hidup (agar ada yang di-poll)

Adapter membaca `/ppp/active`. Jadi harus ada minimal satu sesi tersambung. Cara termudah:
dial dari klien. Kalau tak ada klien fisik, pakai **RouterOS/CHR kedua** sebagai PPPoE client:

```rsc
# Di CHR/router KEDUA (sisi klien), pada interface yang tersambung ke PPPoE server:
/interface pppoe-client add name=uji-budi interface=ether1 \
    user=budi@isp.net password=rahasia123 disabled=no add-default-route=no
```

Cek di CHR server bahwa sesi muncul:

```rsc
/ppp active print
```

Kalau `budi@isp.net` muncul di situ → siap dibaca app.

### A.4 Daftarkan BRAS di app

Di UI app → halaman **BNG/BRAS** → tambah NAS baru dengan nilai:

| Field di form | Isi untuk CHR |
|---|---|
| Nama | `CHR Lab` (bebas) |
| Vendor | **MIKROTIK** |
| Alamat (host) | IP CHR, mis. `192.168.1.50` |
| API Username | `ftth-api` |
| API Secret/Password | `ApiRahasia123` |
| API Port | `80` (atau kosongkan → default 80 non-TLS) |
| API pakai TLS | **nonaktif** (karena kita pakai `www`/HTTP) |
| CoA Secret | *(kosongkan — Mikrotik pakai REST, bukan DAE)* |
| Aktif | ya |

Tautkan collector yang menjangkau CHR (field collector), lalu simpan.

### A.5 Jalankan collector & verifikasi

Jalankan collector **tanpa** simulator (mode nyata adalah default):

```bash
export FTTH_COLLECTOR_KEY=<api-key-tenant-dari-app>
export FTTH_SERVER_URL=<url-server>          # sesuaikan dengan setup bang
# FTTH_COLLECTOR_SIMULATOR biarkan kosong/false → adapter nyata
./gradlew :collector:run
```

Di log collector harus terlihat: `Vendor BRAS didukung: MIKROTIK, FREERADIUS`.

Verifikasi tiga hal:

1. **Poll** — di halaman pelanggan/sesi app, `budi@isp.net` muncul **online** dengan byte
   naik/turun. (Octet dibaca dari interface dinamis `<pppoe-budi@isp.net>`.)
2. **Disconnect / Reset Login** — klik Reset Login (atau Isolir) pada pelanggan itu. Sesi di
   `/ppp active` CHR harus **hilang** (klien akan reconnect kalau auto-dial). Klik lagi saat
   sudah tak ada sesi = tetap sukses (idempoten, tak error).
3. **CoA / ubah paket** — ubah paket kecepatan pelanggan. Cek di CHR:
   `/queue simple print` → `max-limit` queue `budi@isp.net` berubah (mis. jadi `30M/100M`,
   format `unggah/unduh`).

Kalau tiga-tiganya jalan → adapter MIKROTIK lulus end-to-end. 🎉

---

## Jalur B — docker `radius` (uji jalur baca FreeRADIUS)

Ini paling cepat: satu perintah, tak perlu perangkat. Menguji adapter **FREERADIUS**
membaca sesi hidup dari `radacct`.

### B.1 Nyalakan stack lab

Dari root repo:

```bash
docker compose --profile radius up -d
```

Yang naik:

- **radius-db** — Postgres 16 di **host port 5433** (agar tak bentrok Postgres app di 5432).
  Skema FreeRADIUS + data contoh dimuat otomatis (lihat `docker/radius/initdb/`).
- **freeradius** — server FreeRADIUS 3.2 (auth 1812/udp, acct 1813/udp) mode debug `-X`.
  Hanya perlu kalau bang mau BRAS autentikasi ke sini; untuk jalur baca saja, cukup radius-db.

Cek data contoh sudah termuat (harus ada 1 sesi hidup `budi@isp.net`):

```bash
docker compose exec radius-db psql -U radius -d radius \
    -c "select username, nasipaddress, acctinputoctets, acctoutputoctets from radacct where acctstoptime is null;"
```

### B.2 Daftarkan BRAS di app

Di UI app → BNG/BRAS → tambah NAS:

| Field di form | Isi untuk FreeRADIUS lab |
|---|---|
| Nama | `FreeRADIUS Lab` |
| Vendor | **FREERADIUS** |
| Alamat (host) | IP perangkat BRAS untuk DAE — untuk uji baca saja isi `127.0.0.1` |
| API Username | `radius` (user Postgres) |
| API Secret/Password | `radius` (password Postgres) |
| **API Database (JDBC URL)** | `jdbc:postgresql://<host-db>:5433/radius` |
| CoA Secret | secret DAE perangkat BRAS (mis. `testing123`) — perlu untuk Disconnect/CoA |
| Aktif | ya |

> **Isi `<host-db>`** dengan alamat yang bisa dijangkau collector: `localhost` bila collector
> jalan di mesin yang sama dengan docker; atau IP mesin docker bila collector di tempat lain.
> Port **5433** (port host yang di-map), bukan 5432.

### B.3 Jalankan collector & verifikasi baca

Sama seperti A.5 (mode nyata). Di app, sesi **`budi@isp.net`** harus muncul **online** dengan
~0.9 GB unggah / ~4.5 GB unduh (dari baris `radacct` contoh). Itu membuktikan adapter membaca
`radacct` lewat JDBC dengan benar.

### B.4 (Opsional) uji Disconnect/CoA FreeRADIUS lewat DAE

Adapter FreeRADIUS mengontrol sesi via **RFC 5176 DAE** (UDP :3799) yang ditembak ke
**perangkat BRAS** (bukan ke server FreeRADIUS). Jadi untuk uji ini butuh BRAS sungguhan yang:

1. Autentikasi PPPoE ke FreeRADIUS lab (arahkan RADIUS client BRAS ke IP mesin FreeRADIUS,
   port 1812/1813, secret `testing123`), dan
2. Menerima DAE di port 3799 dengan secret yang sama dengan **CoA Secret** yang diisi di app.

Cara paling mudah: pakai **CHR dari Jalur A** sebagai BRAS-nya. Di CHR:

```rsc
# Arahkan CHR memakai RADIUS eksternal (FreeRADIUS lab) untuk PPPoE + terima DAE.
/radius add service=ppp address=<ip-mesin-freeradius> secret=testing123
/radius incoming set accept=yes port=3799
/ppp aaa set use-radius=yes
```

Lalu di app, isi **host** BRAS FreeRADIUS = IP CHR, **CoA Secret** = `testing123`. Kini
Reset Login/CoA dari app akan: baca sesi dari `radacct` (radius-db) → kirim DAE ke CHR →
CHR putus/ubah sesi. Disconnect-NAK dengan Error-Cause 503 (sesi tak ditemukan) dianggap
sukses (idempoten).

### B.5 Matikan lab

```bash
docker compose --profile radius down
# hapus juga data (skema+seed dimuat ulang saat naik lagi):
docker compose --profile radius down -v
```

---

## Contekan field NasTarget per vendor

Ringkasan cepat field mana dipakai adapter mana (kolom di `NasTarget`):

| Field | MIKROTIK | FREERADIUS |
|---|---|---|
| `host` (Alamat) | IP RouterOS (target REST) | IP BRAS (target DAE) |
| `apiUsername` | user REST RouterOS | user Postgres |
| `apiSecret` | password REST RouterOS | password Postgres |
| `apiPort` | port REST (80 non-TLS / 443 TLS) | — (port ada di URL JDBC) |
| `apiUseTls` | `www`=false, `www-ssl`=true | — |
| `apiDatabase` | — | **URL JDBC**, mis. `jdbc:postgresql://host:5433/radius` |
| `coaSecret` | — (kontrol via REST) | **secret DAE** perangkat BRAS |
| `expectedUsernames` | (opsional) pencocokan | (opsional) pencocokan |

Aturan singkat:
- **MIKROTIK** = semua lewat REST; `coaSecret`/`apiDatabase` tak dipakai.
- **FREERADIUS** = baca lewat JDBC (`apiDatabase` + `apiUsername`/`apiSecret`), kontrol lewat
  DAE (`host` + `coaSecret`).

---

## Pemecahan masalah

| Gejala | Kemungkinan sebab & solusi |
|---|---|
| Log: `Vendor ... belum didukung` | Vendor NAS di app bukan MIKROTIK/FREERADIUS, atau collector jalan mode simulator. Pastikan `FTTH_COLLECTOR_SIMULATOR` tak di-set true. |
| Poll Mikrotik kosong | Belum ada sesi di `/ppp active`. Dial klien dulu (A.3). Cek user API punya policy `rest-api`. |
| Mikrotik error konek REST | Salah port/TLS. Untuk `www` (HTTP) pakai apiPort=80 & apiUseTls=false. Cek `/ip service print` service `www` enabled. |
| CoA Mikrotik "queue tak ditemukan" | Simple queue harus bernama SAMA dengan username PPPoE (A.2 langkah 6). |
| FreeRADIUS poll kosong | Cek `radacct` ada baris `acctstoptime IS NULL` (B.1). Cek URL JDBC pakai port 5433 + host yang benar dari sisi collector. |
| FreeRADIUS DAE `tak menjawab` | BRAS tak menerima DAE di :3799, atau IP `host` salah. Untuk CHR: `/radius incoming set accept=yes port=3799`. |
| DAE `Authenticator tak cocok` | `coaSecret` di app ≠ secret DAE di BRAS. Samakan. |
| Auth FreeRADIUS ditolak: `Realm does not have at least one dot separator` | Kebijakan bawaan `filter_username` menolak realm tanpa titik (mis. `budi@isp`). Pakai realm ber-titik seperti `budi@isp.net` (itu sebabnya user lab pakai `.net`). |

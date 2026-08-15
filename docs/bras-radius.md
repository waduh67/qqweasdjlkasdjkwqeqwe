# BRAS & RADIUS — panduan operator (isi form-nya apa)

Panduan praktis untuk menu **BRAS & RADIUS** di aplikasi: apa arti tiap kolom form,
nilai apa yang diisi, dan cara mengarahkan router ke server RADIUS pusat. Untuk
arsitektur & alasan desainnya, baca [`radius-as-a-service.md`](radius-as-a-service.md).

---

## Model mental (baca ini dulu biar gak bingung)

Platform menjalankan **satu server FreeRADIUS pusat** untuk semua tenant. Kamu **tidak**
memasang FreeRADIUS sendiri. Yang kamu daftarkan di menu ini adalah **router BRAS
milikmu** (Mikrotik/Cisco/dll — mesin yang menutup sesi PPPoE pelanggan) supaya
FreeRADIUS pusat **mau menerima** permintaan auth dari router itu.

Dalam istilah RADIUS: **router-mu = RADIUS _client_**, **FreeRADIUS pusat = RADIUS
_server_**. Client menembak ke server, bukan sebaliknya.

```
[ Router BRAS-mu (client) ]  ── auth/accounting (UDP keluar) ──▶  [ FreeRADIUS pusat (server, punya platform) ]
        ▲                                                                    │
        └───────────── CoA / Disconnect (server → router, RFC 5176 :3799) ◀──┘
```

**Kenapa gak ada vendor "FreeRADIUS" di dropdown?** Karena FreeRADIUS adalah _server_-nya,
bukan _client_. Mendaftarkan "FreeRADIUS" sebagai BRAS/client itu tak masuk akal —
seperti mendaftarkan kantor pos sebagai salah satu alamat rumah yang dikirimi surat.
Dropdown vendor cuma untuk perangkat yang jadi client: **MikroTik, Cisco, Juniper,
Lainnya**. (Vendor secara fungsional hanya penting untuk **MikroTik** — membuka kontrol
sesi live via REST RouterOS; vendor lain dapat auth RADIUS biasa saja.)

---

## Arti tiap kolom form "Tambah BRAS"

| Kolom | Wajib | Arti & nilai yang diisi |
|---|---|---|
| **Nama** | ya | Label bebas untuk kamu sendiri, mis. `BRAS-BKS-01`. Cuma penanda di daftar, tak dipakai untuk matching. |
| **Vendor** | ya | Jenis perangkat client: MikroTik / Cisco / Juniper / Lainnya. Pilih **MikroTik** kalau mau kontrol sesi live (muncul kolom REST API tambahan). |
| **Alamat manajemen** | ya | **IP router-mu sebagaimana dilihat server** — inilah `nasname` di RADIUS. Kalau router ber-IP publik: isi IP publiknya. Kalau di belakang NAT dan join VPN: isi **IP overlay VPN**-nya. FreeRADIUS memakai IP ini (+ shared secret) untuk mengenali & mempercayai request; server memakai IP yang sama sebagai tujuan CoA/Disconnect. |
| **NAS-Identifier** | tidak | Metadata opsional (mis. `cab-bks`). Disimpan & ditampilkan saja — **tak dipakai untuk matching**. Boleh dikosongkan. |
| **Shared Secret RADIUS** | ya | Kata sandi bersama antara router dan FreeRADIUS. Lihat penjelasan lengkap di bawah. Pakai tombol **Generate** biar acak & kuat, lalu **salin** — setelah disimpan tak bisa dibaca lagi. |
| **Kredensial REST API** (User/Password/Port/HTTPS) | hanya MikroTik | Login RouterOS untuk kontrol sesi live via REST (bukan bagian dari RADIUS). Muncul hanya saat vendor = MikroTik. |
| **Aktif** | — | Kalau dicentang, baris client ditulis ke FreeRADIUS (router boleh auth). Non-aktif = baris dicabut (router ditolak) tanpa menghapus datanya. |

> **"Alamat manajemen = IP router client kita?"** — Ya. Persisnya: IP router **sebagaimana
> server bisa menjangkaunya**. IP publik kalau punya; IP overlay VPN kalau di belakang NAT.
> Bukan IP LAN internal yang tak terlihat dari luar.

---

## Apa itu "Shared Secret RADIUS" dan buat apa

**Shared secret** = satu kata sandi rahasia yang **ditaruh di dua tempat yang sama
persis**: di konfigurasi RADIUS client router-mu, dan di baris client-mu pada FreeRADIUS
pusat (ditulis otomatis saat kamu simpan form ini). Fungsinya tiga:

1. **Saling percaya (anti-spoof).** FreeRADIUS hanya melayani request dari IP terdaftar
   yang shared secret-nya cocok. Tanpa secret yang benar, request ditolak — orang lain
   tak bisa menyamar jadi router-mu.
2. **Menyamarkan password pelanggan.** Password PPPoE dalam paket RADIUS di-obfuscate
   (di-XOR) memakai shared secret, jadi tak lewat sebagai teks polos di kabel.
3. **Cek keutuhan balasan.** Response dari server di-hash bareng shared secret, jadi
   router bisa memastikan balasannya asli & tak diubah di tengah jalan.

Di sistem ini shared secret dipakai **dua arah** dengan **satu nilai**:

- **Router → FreeRADIUS**: auth + accounting PPPoE (arah keluar dari router).
- **Server → Router**: CoA/Disconnect (RFC 5176, port 3799) untuk memutus/mengubah sesi
  hidup (mis. isolir nunggak, ganti kecepatan). Secret yang sama dipakai mengesahkan
  perintah ini.

Karena itu: **nilai yang kamu Generate di form harus ditempel identik** di konfigurasi
RADIUS Mikrotik. Beda satu karakter → auth gagal (`Access-Reject`) atau CoA ditolak.
Secret disimpan terenkripsi dan tak pernah ditampilkan ulang — salin dulu sebelum simpan.

---

## Arahkan router ke RADIUS (snippet Mikrotik siap-salin)

Kartu **"Arahkan router ke RADIUS ini"** menampilkan host + port server pusat dan skrip
RouterOS siap-tempel. Bentuknya (ganti `<IP-RADIUS>` & `<SECRET-BRAS>` sesuai punyamu):

```rsc
/ip pool add name=pool-pppoe ranges=10.20.0.2-10.20.255.254
/ppp profile set [find name=default] local-address=10.20.0.1 remote-address=pool-pppoe
/radius add service=ppp address=<IP-RADIUS> secret=<SECRET-BRAS> \
    authentication-port=1812 accounting-port=1813
/radius incoming set accept=yes port=3799
/ppp aaa set use-radius=yes accounting=yes interim-update=5m
```

- Baris 1–2: **alamat untuk pelanggan.** RADIUS pusat mengirim izin login + kecepatan
  paket, **bukan IP** — pool-nya milik router. Ganti rentang dengan blok milikmu; kalau
  server PPPoE-mu memakai profil selain `default`, setel profil itu.
- Baris 3–4: daftarkan server RADIUS pusat + shared secret (yang kamu Generate di form).
- Baris 5: buka penerimaan **incoming DAE (CoA/Disconnect) di 3799** — wajib kalau mau
  server bisa memutus/mengubah sesi dari jauh.
- Baris 6: nyalakan RADIUS untuk AAA PPPoE + accounting (interim tiap 5 menit). **Jangan
  hilangkan `interim-update`**: itu denyut nadi sesi. Tanpa denyut, sesi yang berakhir
  tanpa Acct-Stop (BRAS kehilangan jalur ke RADIUS, router dimatikan paksa) menganga di
  `radacct` dan pelanggannya terbaca "Online" selamanya. Server membuang baris yang
  denyutnya berhenti lebih lama dari `ftth.radius.acct-interim-stale-after` (bawaan 1 jam
  — selalu setel lebih longgar dari interim router).

> **`logged in, 0.0.0.0` lalu `no network protocols running`?** Autentikasinya justru
> sudah berhasil — yang kurang alamat. Profil PPP tanpa `remote-address` menutup sesi
> begitu IPCP gagal, dan di `radacct` terlihat sebagai sesi beruntun ber-`framedipaddress`
> `0.0.0.0` yang start dan stop di detik yang sama. Betulkan baris 1–2 di atas.

> **"Host server RADIUS belum dikonfigurasi platform"?** Itu berarti env
> **`FTTH_RADIUS_PUBLIC_HOST`** belum terisi/terbaca di container `server`. Auth tetap
> jalan, tapi kartu panduan menampilkan placeholder. Isi IP publik VPS di `.env`,
> pastikan `docker-compose.prod.yml` di VPS punya baris passthrough
> `FTTH_RADIUS_PUBLIC_HOST: ${FTTH_RADIUS_PUBLIC_HOST:-}` pada service `server`, lalu
> `docker compose ... up -d server` dan cek `... exec server printenv FTTH_RADIUS_PUBLIC_HOST`.

---

## Tipe layanan lain: Hotspot, DHCP, IP Statis

Snippet di atas mengarahkan **PPPoE**. Model RADIUS-pusat yang sama menegakkan tipe
lain juga — grup rate-limit (`plan:{id}`) & CoA **dipakai ulang apa adanya**, cuma cara
router memicu event auth yang beda. Syaratnya: paket mengizinkan tipe itu (field
`serviceTypes` di **Paket Internet**), lalu buat akun bertipe sesuai di **detail
pelanggan → tab Akses**.

**Hotspot** — auth by username/password (halaman login / MAC-login):

```rsc
/ip hotspot profile set <profil> use-radius=yes radius-interim-update=5m
```

**DHCP & IP Statis** — auth by **MAC** saat perangkat minta lease:

```rsc
/ip dhcp-server set <server> use-radius=yes
/ip dhcp-server network add address=<subnet> gateway=<gw> dns-server=<dns>
```

Di form akun, isi **MAC** (dinormalkan server → jadi identitas + password RADIUS):
- **DHCP** — IP dari pool DHCP router; reservasi `Framed-IP-Address` opsional.
- **IP Statis** — **wajib** isi IP reservasi; server memin IP itu via `radreply
  Framed-IP-Address`, jadi perangkat selalu dapat IP tetap **plus** tetap kena
  rate-limit & CoA.

> **Static murni** (IP diketik manual di router, tanpa DHCP & tanpa auth) **tak punya
> event auth** → **di luar jangkauan RADIUS-pusat**; enforce-nya `simple-queue` di
> router (jalur VPN/collector). Kalau mau IP tetap yang **tetap ke-enforce** lewat
> RADIUS, pakai tipe **IP Statis** (pola DHCP-reservasi) di atas — bukan IP manual.

---

## Jebakan NAT & jaringan (sering bikin gagal)

- **Isi IP RADIUS = IP publik VPS mentah, BUKAN domain di belakang Cloudflare.** RADIUS
  itu **UDP**; Cloudflare hanya proxy HTTP/HTTPS → paket UDP 1812/1813 **tak lewat**.
  Arahkan router ke IP publik VPS langsung.
- **Buka port UDP di firewall/NSG VPS:** `1812/udp` (auth) & `1813/udp` (accounting)
  masuk. Batasi source ke IP router-mu kalau bisa. Di Azure: tambah inbound rule NSG.
- **Router di belakang NAT tanpa IP publik?** Auth/accounting tetap jalan (router
  menembak _keluar_ — NAT ramah untuk arah ini). Yang bermasalah cuma **CoA/Disconnect**
  (server harus menembak _masuk_ ke router). Solusinya: **join VPN** (lihat
  [`vpn.md`](vpn.md)) lalu isi **IP overlay VPN** sebagai *Alamat manajemen*. Tanpa jalur
  balik, perubahan (isolir/ganti speed) baru berlaku saat pelanggan login ulang.
- **Alamat manajemen = tujuan CoA.** Server menembak CoA ke `alamat:3799`. Kalau kamu
  isi IP publik tapi router sebenarnya di-NAT, CoA tak nyampai — pakai jalur VPN.
- Cara server menjangkau router untuk CoA ditandai kolom internal `reachability`
  (`DIRECT`/`VPN`/`COLLECTOR`/`NONE`); rincinya di
  [`radius-as-a-service.md`](radius-as-a-service.md#reachability-coa-per-nas-3-jalur).

---

## Bagaimana "Online/Offline" pelanggan ditentukan

Yang membuat pelanggan berubah jadi Offline di peta & B-ras Check **bukan** ping, melainkan
hilangnya sesi dari accounting RADIUS. Tiga lapis, dari yang paling cepat:

1. **Sesi lenyap dari `radacct`** — router mengirim Acct-Stop saat PPPoE putus, barisnya
   tertutup, dan poll berikutnya (tiap 30 detik) menandai akun itu putus. Ini jalur normal:
   cabut kabel → Offline dalam **±30–60 detik**.
2. **Denyut interim berhenti** — Acct-Stop tak selalu terkirim (BRAS kehilangan jalur ke
   RADIUS, router dimatikan paksa). Baris yang `interim-update`-nya membeku lebih lama dari
   `ftth.radius.acct-interim-stale-after` (bawaan 1 jam) dibuang sebagai bangkai. Karena itu
   `interim-update=5m` di skrip di atas **wajib** ada.
3. **Poll aplikasi sendiri berhenti** — server mati atau radius-db tak terjangkau. Baris sesi
   yang tak diperbarui melebihi `ftth.bng.session-stale-after` (bawaan 3 menit) disajikan
   sebagai putus, supaya layar tidak membeku hijau sementara tak ada yang mengabari.

Semuanya sengaja dibuat "telat-offline daripada salah-offline": satu poll yang terlewat tak
boleh memerahkan pelanggan yang baik-baik saja, tapi pelanggan yang benar-benar mati harus
ketahuan tanpa perlu ada yang menekan Refresh.

---

## Isolir: halaman tagihan, bukan kabel dicabut

Menekan **Isolir** (atau langganan yang jatuh tempo lewat penagihan) **tidak** mencabut
login pelanggan. Yang terjadi: akunnya dipindah ke grup RADIUS `isolir`, lalu sesinya
diputus. Dial berikutnya tetap **berhasil** — hanya saja RADIUS menyambutnya dengan sisa
kecepatan seadanya + keanggotaan address-list `isolir`, dan routermu yang melempar semua
tujuan ke halaman tagihan.

Ini disengaja. Pelanggan yang disambut "PPPoE gagal" hanya akan menelepon CS dan menuduh
jaringanmu rusak; pelanggan yang melihat tagihannya sendiri bisa langsung membayar.

**RADIUS cuma mengisi daftarnya — router yang menentukan artinya.** Tanpa aturan di bawah,
address-list-nya terisi rapi tapi tak ada yang membacanya: pelanggan "terisolir" tetap
browsing seperti biasa, dan tak ada satu pun log yang menunjukkan ada yang keliru. Kartu
**"Halaman isolir (walled garden)"** di form BRAS merakit aturannya siap-salin (alamat
halaman tagihan terisi sendiri, boleh diganti). Bentuknya:

```rsc
/ip firewall address-list add list=isolir-tujuan address=<ALAMAT-HALAMAN-TAGIHAN> comment="halaman tagihan"
/ip firewall filter add chain=forward src-address-list=isolir protocol=udp dst-port=53 \
    action=accept comment="isolir: DNS boleh"
/ip firewall filter add chain=forward src-address-list=isolir protocol=tcp dst-port=53 \
    action=accept comment="isolir: DNS boleh"
/ip firewall filter add chain=forward src-address-list=isolir dst-address-list=isolir-tujuan \
    action=accept comment="isolir: halaman tagihan boleh"
/ip firewall nat add chain=dstnat src-address-list=isolir protocol=tcp dst-port=80 \
    action=dst-nat to-addresses=<IP-HALAMAN-TAGIHAN> to-ports=80 comment="isolir: http dilempar ke halaman tagihan"
/ip firewall filter add chain=forward src-address-list=isolir protocol=tcp dst-port=443 \
    action=reject reject-with=tcp-reset comment="isolir: https ditolak cepat (tak bisa dilempar)"
/ip firewall filter add chain=forward src-address-list=isolir \
    action=reject reject-with=icmp-network-unreachable comment="isolir: sisanya ditutup"
```

- **DNS dibuka duluan.** Tanpa itu browser tak pernah sampai membuka koneksi, jadi tak ada
  apa pun untuk dilempar dan pelanggan cuma melihat "server tak ditemukan".
- **HTTPS ditolak, bukan dilempar.** Mengalihkan port 443 ke server lain memunculkan
  peringatan sertifikat — pelanggan malah yakin jaringannya dibajak. Yang benar-benar
  membuka halaman tagihan adalah **deteksi captive portal ponsel**: ia memakai HTTP polos,
  jadi notifikasi "Masuk ke jaringan" muncul sendiri.
- **Sisanya `reject`, bukan `drop`.** Reject bikin aplikasi gagal seketika; drop bikin ia
  menggantung sampai timeout dan pelanggan menyimpulkan "internet mati" lalu menelepon.
- Nama address-list-nya diatur platform (`FTTH_RADIUS_ISOLIR_ADDRESS_LIST`, bawaan
  `isolir`) dan **wajib sama persis** dengan yang kamu tulis di aturan firewall.
- Sisa kecepatannya `FTTH_RADIUS_ISOLIR_RATE_LIMIT` (bawaan `1M/1M`). Sengaja bukan nol:
  halaman tagihan harus tetap bisa dimuat.

### Memulihkan

Tekan **Pulihkan** (atau pembayaran masuk lewat penagihan) → grup paketnya disinkronkan
ulang, akun dikembalikan ke sana, lalu **sesinya diputus sekali lagi**. Pemutusan itu bukan
basa-basi: keanggotaan address-list menempel pada **sesi**, bukan pada akun, jadi selama
sesi isolirnya belum mati routermu tetap melempar pelanggan ke halaman tagihan betapapun
grup RADIUS-nya sudah benar. Satu kedipan beberapa detik jauh lebih murah daripada
pelanggan yang sudah membayar tapi tetap terkurung.

### Menguji tanpa menunggu tagihan

1. Pastikan aturan di atas terpasang di router, dan BRAS-nya **terjangkau server**
   (kolom rute di form bukan "Tak terjangkau" — lihat [jebakan NAT](#jebakan-nat--jaringan-sering-bikin-gagal)).
2. Detail pelanggan → tab **Akses** → **Isolir**.
3. Dalam ±20 detik sesi PPPoE-nya putus (dua worker: grup dulu, baru pemutusan). CPE dial
   ulang sendiri dan **berhasil** — cek di router `/ip firewall address-list print` :
   IP-nya sudah masuk daftar `isolir`.
4. Dari perangkat pelanggan, buka situs apa pun **berawalan `http://`** → mendarat di
   halaman tagihan. Situs HTTPS gagal seketika (itu memang yang diharapkan).
5. Tekan **Pulihkan** → sesi putus sekali lagi, dial ulang, IP-nya hilang dari daftar
   `isolir`, internet normal.

> **Diisolir tapi internetnya lancar?** Urutan curiga: (a) nama address-list di router beda
> dengan yang dikirim RADIUS; (b) aturan `isolir` kalah urutan dari aturan `accept` yang
> lebih atas di chain `forward` — pindahkan ke atas; (c) sesinya belum benar-benar putus,
> jadi masih memakai otorisasi lama.

> **Diisolir tapi malah tak bisa apa-apa (bahkan halaman tagihan)?** Hampir selalu DNS:
> pastikan dua baris port 53 ada dan berada **di atas** baris `reject` penutup.

---

## Setelah daftar: bikin akun jaringan pelanggan

Mendaftarkan BRAS hanya sekali per router. Akun per-pelanggan dibuat di **detail
pelanggan → tab Akses**: pilih **tipe** (PPPoE/Hotspot pakai username+password;
DHCP/IP Statis pakai MAC — lihat [tipe layanan lain](#tipe-layanan-lain-hotspot-dhcp-ip-statis))
+ paket. Paket (kecepatan/harga) dikelola di modul **Paket Internet** (`catalog`) —
lihat [`catalog.md`](catalog.md). Server yang menulis otorisasi ke RADIUS; kamu tak
menyentuh SQL apa pun.

---

## Ringkas

- Menu ini mendaftarkan **router-mu sebagai RADIUS client**, bukan memasang server RADIUS.
- **Alamat manajemen** = IP router sebagaimana dilihat server (publik / overlay VPN).
- **Shared secret** = satu password bersama di dua sisi; dipakai dua arah (auth + CoA).
- Arahkan ke **IP publik VPS mentah** (bukan domain Cloudflare); buka **1812/1813 udp**
  (+ **3799** di router untuk CoA).
- Router di-NAT → **join VPN**, isi overlay IP; kalau tidak, CoA baru jalan saat login ulang.
- **Isolir tak memutus login** — pelanggan tetap masuk tapi mendarat di halaman tagihan;
  aturan walled-garden-nya harus dipasang di routermu, salin dari kartu di form BRAS.
</content>
</invoke>

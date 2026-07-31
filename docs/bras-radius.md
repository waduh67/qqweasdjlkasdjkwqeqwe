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
/radius add service=ppp address=<IP-RADIUS> secret=<SECRET-BRAS> \
    authentication-port=1812 accounting-port=1813
/radius incoming set accept=yes port=3799
/ppp aaa set use-radius=yes accounting=yes interim-update=5m
```

- Baris 1–2: daftarkan server RADIUS pusat + shared secret (yang kamu Generate di form).
- Baris 3: buka penerimaan **incoming DAE (CoA/Disconnect) di 3799** — wajib kalau mau
  server bisa memutus/mengubah sesi dari jauh.
- Baris 4: nyalakan RADIUS untuk AAA PPPoE + accounting (interim tiap 5 menit).

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
</content>
</invoke>

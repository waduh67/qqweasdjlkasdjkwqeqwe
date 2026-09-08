# Modul `monitoring` — polling OLT & auto-provisioning ONU (isi form-nya apa)

Panduan operator untuk memantau OLT dari server dan menampung ONU yang belum
terdaftar. Bahasa apa adanya: apa yang diisi di form, kenapa, dan jebakan yang
sering bikin "kok gak muncul-muncul".

---

## Model mental

Server kita yang **langsung** nanya ke OLT lewat SNMP — tidak perlu agen/collector
di lokasi ISP. Tiap ~5 menit server:

1. Ambil daftar OLT tiap tenant yang punya IP + community.
2. **Probe** dulu (baca `sysDescr`) buat mastiin nyambung, lalu **walk** tabel ONU
   vendor tsb.
3. Setiap ONU yang **terlihat OLT tapi belum terdaftar** sebagai ONU pelanggan →
   masuk **kotak masuk auto-provisioning** (menu Provisioning).
4. OLT yang tak bisa dihubungi → alarm **OLT_UNREACHABLE**.

Syarat mutlak: **OLT harus reachable dari server** — entah lewat IP publik, atau
lewat VPN overlay kita. Kalau OLT cuma bisa diakses dari LAN ISP dan tak diekspos,
polling server tak akan nyampe (itu skenario collector on-prem yang sengaja belum
dideploy).

Dua "dunia" ONU yang jangan ketuker:
- **ONU terdeteksi** (`discovered_onu`, modul monitoring) = mentahan hasil scan.
  Belum jadi pelanggan. Cuma catatan "ada barang nyala di PON sekian".
- **ONU pelanggan** (`onu`, modul customer) = ONU yang sudah **ditautkan** ke
  pelanggan + dipasang ke port ODP. Ini yang di-monitor redaman/status-nya.

Alur normalnya: barang nyala → nongol di kotak masuk → operator **Terima** →
jadi ONU pelanggan.

---

## Daftarin OLT biar dipolling (menu Inventaris → OLT)

Server cuma polling OLT yang **punya IP manajemen**. Form OLT:

| Field | Isi apa | Catatan |
|---|---|---|
| Site | POP tempat OLT ditaruh | wajib |
| Kode / Nama | identitas OLT | mis. `OLT-CGK-01` |
| **Vendor** | ZTE / HUAWEI / FIBERHOME / NOKIA / HSGQ / OTHER | menentukan adapter SNMP — lihat tabel dukungan di bawah |
| Model | teks bebas | opsional |
| **IP manajemen** | IP yang **reachable dari server** | kosong = OLT ini **tidak** dipolling |
| **SNMP community** | community read (mis. `public`) | wajib biar SNMP jalan |
| **SNMP port** | default `161` | **HSGQ sering di `1161`** — lihat Jebakan |

Begitu OLT disimpan dengan IP + community yang benar, discovery pertama tinggal
nunggu **satu siklus polling (≤5 menit)**. Gak instan.

### Vendor mana yang beneran dipolling

| Vendor | Teknologi | Identitas ONU | Status |
|---|---|---|---|
| **ZTE** | GPON | serial (ZTEG + heksa) | didukung |
| **HUAWEI** | GPON | serial | didukung |
| **FIBERHOME** | GPON | serial | didukung |
| **HSGQ** | EPON | **MAC address** | didukung (tabel enterprise `.50224.3`) |
| **HSGQ-G01ID** | GPON | serial | profil `.50224.3.12` dipilih dari `sysDescr` |
| NOKIA | — | — | **ada di dropdown tapi belum ada adapter** → dilewati diam-diam, **tanpa** alarm |
| OTHER | — | — | monitoring tak didukung, dilewati |

> ⚠️ **NOKIA & OTHER dilewati tanpa bunyi.** Kalau OLT-nya kepilih NOKIA/OTHER,
> polling-nya di-skip senyap — gak ada ONU nongol, gak ada alarm OLT_UNREACHABLE.
> Jangan bingung "kok gak ada apa-apa". Pilih vendor yang ada adapternya.

GPON identitasnya **serial** (4 huruf kode vendor + heksa, mis. `ZTEGC0FFEE01`).
HSGQ EPON tak punya serial GPON — identitasnya **MAC** (mis. `C0FD8465FD12`).
HSGQ-G01ID memakai serial GPON, bukan MAC. Server dan collector memilih profil
yang sama berdasarkan `sysDescr`; profil EPON tetap dipakai untuk perangkat HSGQ
lain sampai keluarga MIB-nya diverifikasi.

## ONU perangkat dan ONU pelanggan

Di detail OLT dari **Inventory**, **Peta**, maupun `/olts/:id`:

- **ONU di OLT** membaca daftar perangkat langsung lewat SNMP, tanpa harus ada
  pelanggan/ODP yang terpasang. Cari serial, nama, atau ONT ID; **Refresh** membaca
  ulang dengan melewati cache. Hasil sukses disimpan di memori browser per OLT
  selama 15 menit sejak diterima, termasuk hasil kosong. Pindah tab atau membuka
  ulang detail memakai hasil yang sama; pembacaan yang masih berjalan juga dibagi.
  Setelah cache kedaluwarsa, pembacaan baru dilakukan saat tab dibuka kembali,
  tanpa polling latar belakang. Cache dibersihkan saat sesi/profil berubah atau
  halaman dimuat ulang penuh. Izin: `network.olt.view` dan `monitoring.provisioning.view`.
- **ONU Pelanggan** tetap menampilkan hubungan pelanggan dan topologi aplikasi.
- **ONU Baru** tetap menjadi kotak masuk untuk menautkan ONU ke pelanggan.

`GET /api/monitoring/olts/{id}/onus` hanya membaca target yang tercatat pada tenant.
Tidak menyimpan atau mengubah konfigurasi OLT, pelanggan, maupun topologi. Hasilnya
snapshot dengan waktu baca aplikasi, bukan stream waktu nyata. Kegagalan membaca
identitas menghasilkan error dan membuang cache sebelumnya. Kegagalan field
tambahan tetap menghasilkan `null` dan peringatan pada respons API, tetapi UI
tidak menampilkan kartu peringatan tersebut. Kolom yang seluruh nilainya kosong
disembunyikan; kolom dengan sebagian nilai tersedia tetap muncul, dengan `—`
untuk sel kosong. Pencarian tidak mengubah pilihan kolom. Serial yang tidak
ditemukan hanya berarti tidak ada pada hasil baca tersebut, bukan bukti pasti
ONU tidak terdaftar di perangkat.

### Peta GPON HSGQ-G01ID

Prefix inventori: `1.3.6.1.4.1.50224.3.12.2.1`.

| Field | Kolom | Penafsiran |
|---|---|---|
| Name | `.2` | teks OLT |
| State | `.3` | 0 Inactive, 1 Active, 2 Disable, 3 Enable, 4 ActiveS, 5 Awake |
| Running state | `.4` | 0 Unknown/initial, 1 Online, 2 Offline |
| Config state | `.5` | 0 Initial, 1 Normal, 2 Fail |
| Serial number | `.15` | empat huruf vendor + delapan heksa |
| Last up time | `.20` | teks jam OLT, tanpa konversi zona waktu |

RX/TX: `1.3.6.1.4.1.50224.3.12.3.1.4` / `.5`, dibagi 100 menjadi dBm.
Join optik hanya memakai indeks `<onuIndex>.0.0`; `.65535.65535` adalah optik
port OLT dan tidak boleh menimpa RX ONU. ONT ID diambil dari byte PON/ONU dalam
indeks, termasuk ONU nomor **0**. Device type, last down time, dan last down cause
belum dipetakan dan tetap `—`; tidak disimpulkan dari kolom lain.

Peta berdasarkan MIB `PARKS-PK700` enterprise 50224 di LibreNMS, commit
`6044667c3ed1c99e83829533fc33233a7943552d`
(`mibs/parks/PARKS-PK700`), ditambah verifikasi `snmpwalk` baca-saja pada
HSGQ-G01ID firmware `IGC_V1.0.10ID_Rel` tanggal 8 September 2026. Serial, nama,
running state, suffix RX dan last-up dikonfirmasi perangkat; enum state/config
dan satuan RX/TX mengikuti MIB. Nilai SNMP tidak dijamin identik dengan web OLT.
Jam OLT yang belum disetel bisa menghasilkan tahun 1970 dan tetap ditampilkan
apa adanya. Tidak ada OID password atau aksi konfigurasi dalam profil ini.

---

## Cara kerja polling (biar paham kalau ada yang aneh)

- Terjadwal server-side, `OltPollingScheduler`, tiap `ftth.monitoring.poll-interval`
  (default **`PT5M`** = 5 menit).
- Kill-switch: `ftth.monitoring.server-poll-enabled` (default **true**). Set `false`
  buat matiin polling total (mis. lagi maintenance).
- Per OLT: probe `sysDescr` → kalau gagal nyambung, naikin alarm **OLT_UNREACHABLE**
  dan ONU-nya dianggap hilang; kalau sukses, walk tabel ONU dan simpan pembacaan
  (redaman/status) + ONU liar ke kotak masuk.
- Tiap OLT jalan di transaksinya sendiri — satu OLT error tak menjatuhkan yang lain.

---

## Diagnostik SNMP: "OLT nyambung tapi ONU-nya nol"

Ini kegagalan paling nyebelin karena **gak ada error sama sekali**: OLT bisa
dihubungi (gak ada alarm OLT_UNREACHABLE), polling jalan, tapi ONU-nya nol terus.
Sebabnya biasanya **OID-nya meleset**. Peta MIB GPON kami (`MibProfiles`:
ZTE/HUAWEI/FIBERHOME) disusun dari dokumentasi vendor dan **belum diadu dengan
perangkat GPON nyata**; firmware yang beda kerap menggeser sub-tree. Kalau OID salah,
walk-nya balik kosong — dan "kosong" gak dibedain dari "OLT-nya emang gak punya ONU".

Buat itu ada **tab Diagnostik** di detail OLT (izin `monitoring.collector.manage`).
Server yang nembak perangkatnya — teknisi gak perlu `snmpwalk`, gak perlu SSH ke
server, dan community string gak pernah keluar dari server.

**1. Uji peta OID** (`GET /api/monitoring/olts/{id}/snmp-check`) — nanya perangkat
pakai OID yang **persis dipakai polling**, lalu kasih vonis per peran:

| Vonis | Artinya | Tindakan |
|---|---|---|
| **Terbaca** | menjawab & nilainya masuk akal | aman |
| **Kosong** | sub-tree gak dijawab: OID salah buat firmware ini, atau fiturnya mati | cari OID benernya pakai walk manual |
| **Tak terbaca** | **menjawab tapi gak satu pun nilainya kebaca** — skala/satuan atau pemetaan status beda | paling licin: polling "sukses" tapi metrik kosong |
| **Belum dipetakan** | profil vendor kami emang belum punya OID buat peran itu | metrik itu selalu kosong |

Peran ber-label **wajib** (serial/MAC, status, redaman RX) yang gak "Terbaca"
menandai identitas atau metrik utama yang bermasalah, bukan selalu berarti nol ONU. Nilai **mentah** ikut ditampilkan
di samping tafsirannya — dari situ kelihatan skalanya: `-2350` itu 0,01 dBm,
`-23500` itu 0,001 dBm.

**2. Walk OID manual** (`GET /api/monitoring/olts/{id}/snmp-walk?oid=…&limit=50`) —
telusuri sub-tree buat **nyari** OID yang bener. Hasilnya OID penuh + nilai, siap
disalin. Dua batasan yang disengaja:
- OID wajib di bawah `1.3.6.1` dan **minimal 7 angka** — walk dari akar di OLT
  produksi bisa jalan belasan menit sambil bikin CPU manajemennya megap-megap.
- Sasarannya **cuma OLT yang ada di inventory tenant ini**; host/community diambil
  server dari basis data, gak nerima dari pemanggil. Jadi endpoint ini gak bisa
  dipelintir jadi pemindai jaringan.

Kalau ketemu OID yang bener buat firmware-mu, kirim hasil walk-nya ke tim — profil
MIB-nya yang diperbaiki, bukan ditambal per-perangkat.

---

## Kotak masuk auto-provisioning (menu Provisioning)

Isinya ONU yang OLT lihat tapi belum jadi pelanggan. Kolom penting: serial/MAC,
OLT + label PON, status terakhir, redaman terakhir, berapa kali kelihatan.

**Status baris:**
- `DISCOVERED` — baru, **nunggu tindakan**.
- `PROVISIONED` — sudah ditautkan ke pelanggan.
- `IGNORED` — sengaja diabaikan (mis. ONU tetangga/uji coba).

**Saran auto-link** (biar operator tinggal konfirmasi) punya tingkat keyakinan:

| Keyakinan | Artinya |
|---|---|
| **HIGH** | pelanggan nunggu instalasi + ODP + port jelas → layak **1-klik** |
| **MEDIUM** | pelanggan & ODP ketebak tapi ada alternatif → pra-isi, **periksa dulu** |
| **LOW** | cuma ODP + port yang ketebak dari topologi; pelanggan pilih manual |
| **NONE** | tak ada yang bisa ditebak (PON belum dipetakan / OLT belum dikenal) |

**Tiga aksi** (izin `monitoring.provisioning.manage`):
- **Terima / Provisi** — tautkan ke pelanggan + pasang ke port ODP. Jadi ONU
  pelanggan; baris pindah ke `PROVISIONED`.
- **Abaikan** — tandai `IGNORED`. Masih ada di riwayat, tak muncul lagi di daftar
  "perlu tindakan". Kalau ONU-nya masih nyala, scan berikutnya **tak** menghidupkan
  ulang baris ini.
- **Hapus** — buang barisnya **permanen** dari basis data. Bedanya sama Abaikan:
  kalau ONU-nya **masih nyala**, siklus polling berikut bakal **mendeteksinya lagi**
  sebagai baris `DISCOVERED` baru. Pakai Hapus buat beberes sampah/salah-scan, bukan
  buat "menyembunyikan" ONU yang masih hidup — buat itu pakai Abaikan.

### Zero-touch (auto-provision policy)

Menu punya sakelar **auto-provisioning** (`/api/monitoring/auto-provision-policy`,
default **mati**). Kalau dinyalakan, ONU liar berkeyakinan **HIGH** langsung
ditautkan otomatis oleh penjadwal — operator gak perlu pencet "Terima". Yang
MEDIUM/LOW/NONE tetap nunggu operator. Nyalain hanya kalau backlog instalasi &
pemetaan PON-mu rapi, biar gak salah tautkan.

### Hapus OLT → kotak masuk-nya ikut dibersihkan

Kalau sebuah OLT **dihapus**, semua ONU terdeteksi yatim milik OLT itu **otomatis
dibersihkan** dari kotak masuk (event `OltDeletedEvent` → pembersihan latar). Jadi
gak ada sisa baris nunjuk OLT yang udah gak ada. (Baris yang sudah `PROVISIONED`
jadi ONU pelanggan tak terpengaruh — itu sudah pindah ke dunia customer.)

---

## Kelola ONU pelanggan (detail pelanggan → tab Perangkat/ONU)

Setelah ONU ditautkan, siklus hidupnya di sisi pelanggan:

| Aksi | Efek | Syarat |
|---|---|---|
| **Daftar** | ONU tercatat, status `PENDING` | serial + pelanggan |
| **Pasang ke ODP** | tautkan ke port ODP, status jadi `OFFLINE` (nunggu nyala) | ODP + port |
| **Lepas** | copot dari ODP | — |
| **Hapus** | buang ONU **permanen** | **harus dilepas dulu** — kalau masih terpasang, ditolak (`... masih terpasang di ODP, lepas dulu`) |
| **Set DISMANTLED** | matiin lunak: auto-lepas + simpan riwayat | alternatif Hapus kalau mau jejaknya tetap ada |

Status ONU pelanggan: `PENDING` → `ONLINE`/`OFFLINE`/`LOS` (dari pembacaan) →
`DISMANTLED` (dibongkar).

**Hapus vs DISMANTLED** — dua-duanya "mengakhiri" ONU, bedanya:
- **Hapus** = benar-benar hilang dari basis data. Dipakai buat salah-input atau ONU
  yang belum pernah kepasang. Wajib dilepas dari ODP dulu (invarian: port ODP tak
  boleh nunjuk ONU hantu).
- **DISMANTLED** = tetap tercatat sebagai riwayat (pernah ada, sekarang dibongkar),
  otomatis lepas dari ODP. Dipakai buat pelanggan berhenti tapi kamu mau audit trail.

---

## Jebakan

- **HSGQ: SNMP di port 1161, bukan 161.** Perangkat HSGQ EPON (mis. HSGQ-E04I)
  sering ekspos SNMP di **1161**. Kalau OLT didaftarkan dengan port default 161,
  probe timeout → OLT dianggap **unreachable** → alarm OLT_UNREACHABLE, gak ada ONU.
  Isi **SNMP port = 1161** di form OLT. Cek dulu port sebenarnya:
  ```bash
  snmpget -v2c -c <community> <ip>:1161 1.3.6.1.2.1.1.1.0   # sysDescr → nongol "HSGQ..."
  ```
- **`nc -vzu <ip> <port>` "succeeded" BUKAN bukti SNMP.** UDP itu connectionless —
  `nc` sering bilang sukses walau gak ada yang dengerin. Buktikan pakai `snmpwalk`/
  `snmpget` beneran.
- **Community/port salah = OLT_UNREACHABLE, bukan "0 ONU".** Kalau alarm unreachable
  nyala, cek community + port + reachability, jangan nyari-nyari ONU dulu.
- **OLT nyambung tapi ONU nol = curigai OID, bukan jaringan.** Buka tab **Diagnostik**
  di detail OLT dan jalankan uji peta OID (lihat bagian Diagnostik SNMP di atas).
- **Vendor NOKIA/OTHER dilewati diam-diam.** Gak ada ONU, gak ada alarm. Ganti ke
  vendor yang ada adapternya (ZTE/HUAWEI/FIBERHOME/HSGQ).
- **Discovery gak instan.** Setelah daftar OLT, tunggu satu siklus (≤5 menit).
- **Hapus baris kotak masuk ≠ menyembunyikan ONU.** ONU yang masih nyala bakal
  ke-detect lagi setelah dihapus. Mau sembunyiin permanen → **Abaikan**.
- **Hapus ONU pelanggan ditolak kalau masih terpasang.** Lepas dari ODP dulu, atau
  pakai DISMANTLED.

---

## Ringkas

- Server polling OLT via SNMP tiap ~5 menit; OLT wajib reachable + punya IP,
  community, dan **port** yang benar (HSGQ = **1161**).
- Adapter ada buat **ZTE/HUAWEI/FIBERHOME (GPON)** + **HSGQ (EPON MAC / G01ID GPON serial)**;
  NOKIA/OTHER dilewati diam-diam.
- **ONU nol padahal OLT nyambung** → tab **Diagnostik** di detail OLT: uji peta OID
  (Terbaca / Kosong / Tak terbaca / Belum dipetakan) + walk OID manual buat nyari
  OID yang bener.
- ONU liar masuk **kotak masuk Provisioning**: Terima (tautkan) / Abaikan (sembunyi) /
  Hapus (buang, bisa ke-detect lagi kalau masih nyala). Zero-touch = auto-terima yang
  HIGH. Hapus OLT membersihkan kotak masuk yatimnya.
- ONU pelanggan: Daftar → Pasang ODP → Lepas → Hapus (harus dilepas dulu) atau
  DISMANTLED (lunak, simpan riwayat).

Lihat juga: [`catalog.md`](catalog.md) (paket & rate-limit), [`bras-radius.md`](bras-radius.md)
(RADIUS/PPPoE), [`vpn.md`](vpn.md) (overlay biar OLT/router reachable dari server).

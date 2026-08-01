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
| NOKIA | — | — | **ada di dropdown tapi belum ada adapter** → dilewati diam-diam, **tanpa** alarm |
| OTHER | — | — | monitoring tak didukung, dilewati |

> ⚠️ **NOKIA & OTHER dilewati tanpa bunyi.** Kalau OLT-nya kepilih NOKIA/OTHER,
> polling-nya di-skip senyap — gak ada ONU nongol, gak ada alarm OLT_UNREACHABLE.
> Jangan bingung "kok gak ada apa-apa". Pilih vendor yang ada adapternya.

GPON identitasnya **serial** (4 huruf kode vendor + heksa, mis. `ZTEGC0FFEE01`).
HSGQ EPON tak punya serial GPON — identitasnya **MAC** (mis. `C0FD8465FD12`).

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
- Adapter ada buat **ZTE/HUAWEI/FIBERHOME (GPON)** + **HSGQ (EPON, identitas MAC)**;
  NOKIA/OTHER dilewati diam-diam.
- ONU liar masuk **kotak masuk Provisioning**: Terima (tautkan) / Abaikan (sembunyi) /
  Hapus (buang, bisa ke-detect lagi kalau masih nyala). Zero-touch = auto-terima yang
  HIGH. Hapus OLT membersihkan kotak masuk yatimnya.
- ONU pelanggan: Daftar → Pasang ODP → Lepas → Hapus (harus dilepas dulu) atau
  DISMANTLED (lunak, simpan riwayat).

Lihat juga: [`catalog.md`](catalog.md) (paket & rate-limit), [`bras-radius.md`](bras-radius.md)
(RADIUS/PPPoE), [`vpn.md`](vpn.md) (overlay biar OLT/router reachable dari server).

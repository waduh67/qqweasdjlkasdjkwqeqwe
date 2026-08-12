# RADIUS-as-a-service — satu FreeRADIUS pusat untuk semua tenant

Model FTTH SaaS ini menaruh **satu FreeRADIUS + Postgres (`radius-db`) di stack
platform**, dipakai bersama semua tenant. Tenant **tidak** memasang server RADIUS
sendiri dan tak pernah menyentuh SQL/JDBC-nya. Onboarding-nya tiga langkah:

1. **(opsional) join VPN** — kalau router-nya di belakang NAT tanpa IP publik
   (lihat [`vpn.md`](vpn.md)); kalau punya IP publik, langkah ini dilewati.
2. **daftarkan router** sebagai klien RADIUS di UI — cukup **alamat + shared secret**.
3. **generate akun PPPoE** per-pelanggan (lewat langganan + paket di modul `catalog`).

Auth + accounting jalan **tanpa** langkah teknis manual di sisi tenant: Mikrotik
menembak keluar ke FreeRADIUS pusat, dan aplikasi yang menulis otorisasi + membaca
sesi. Ini pola cermin [VPN-as-a-service](vpn.md) (hub = platform).

Bandingkan dengan model lama (Phase 7) yang membocorkan plumbing ke tenant:
form BRAS minta `URL JDBC / User DB / Password DB`, klien RADIUS didaftar manual di
`.env`/`clients.conf` (restart FreeRADIUS), dan `radcheck.username` flat (global-unik).
Ketiganya ditutup pivot ini.

---

## Mekanisme isolasi tenant (inti)

Tiap router (BRAS) milik **satu** tenant. FreeRADIUS mengenali request datang dari
klien/NAS mana (source-IP + shared secret, anti-spoof) → menurunkan tenant → memberi
**prefix** ke kunci SQL:

```
Pelanggan ketik:   budi                                  (bare, tak berubah di CPE)
FreeRADIUS:        sql_user_name = "%{client:shortname}:%{User-Name}"  → "tenantA:budi"
Tenant B "budi":   "tenantB:budi"                         → baris beda, nol tabrakan
```

- `nas.shortname` = **slug tenant**. FreeRADIUS membacanya via `%{client:shortname}`
  (nol query tambahan, anti-spoof karena diikat ke shared secret klien).
- `radcheck` / `radusergroup` / `radacct` di-key `"{slug}:{username}"`.
- Server menulis otorisasi dengan **kunci yang sama** — kontrak format ini wajib
  identik di dua sisi. Di server: `RadiusProvisioningDispatcher.scoped(slug, username)`
  → `"{slug}:{username}"`; slug diresolusi sekali dari `TenantApi.findById(...).slug`.
- **Username kembar antar-tenant BOLEH** — scope-nya per-tenant, bukan global.
- `plan:{planId}` (grup paket) memakai UUID → sudah unik lintas-tenant, tak perlu prefix.

Konfigurasi FreeRADIUS yang mewujudkan ini ada di
`deploy/radius/freeradius/mods-enabled/sql`: `read_clients = yes` + `client_table = "nas"`
+ `sql_user_name = "%{client:shortname}:%{User-Name}"`.

---

## Pembagian data-plane (server vs collector)

Kunci keputusan: `radius-db` **ko-lokasi** dengan server platform; collector on-prem
tenant **tak punya rute** ke DB internal itu. Maka seluruh data-plane RADIUS pindah
ke **server**:

| Operasi | Model lama (Phase 7) | Sekarang |
|---|---|---|
| Provision `radcheck`/`radusergroup` | collector JDBC | **server** JDBC langsung |
| Sync `radgroupreply` (paket) | collector JDBC | **server** JDBC langsung |
| Baca `radacct` (sesi/usage) | collector JDBC | **server** JDBC langsung |
| CoA/Disconnect DAE :3799 | collector | **server** (codec `RadiusDae` dipindah) |
| Auth + accounting | — | Mikrotik → FreeRADIUS **langsung** (keluar) |

Collector **tetap** dipakai untuk OLT SNMP dan vendor **MIKROTIK-native** (kontrol
sesi via REST RouterOS). Nilai vendor `FREERADIUS` **dihapus** dari registri BRAS
(migrasi V39): `nas` adalah RADIUS *client*, sedang FreeRADIUS adalah *server*-nya, jadi
memilihnya sebagai vendor BRAS tak bermakna. Baris lama (bila ada) dinormalkan ke `OTHER`.
Enum kini `{ MIKROTIK, CISCO, JUNIPER, OTHER }`.

---

## Self-service klien RADIUS (dynamic clients dari tabel `nas`)

Saat tenant mendaftarkan BRAS di UI, `NasService` menulis baris di tabel `nas`
radius-db lewat `RadiusClientRegistryPort` → `FreeRadiusClientRegistryAdapter` (JDBC):

- `nasname` = alamat manajemen BRAS (IP publik **atau** overlay VPN).
- `shortname` = **slug tenant** (kunci isolasi `sql_user_name`).
- `secret` = shared secret (didekripsi dari `Nas.coaSecret` saat sinkron).

Idempoten: DELETE-by-`nasname` lalu INSERT. Ganti-alamat = cabut baris lama + daftar
baru; nonaktif/hapus = cabut. Digerbangi `RadiusConnectionResolver.configured` — di
dev/test tanpa radius-db langkah ini dilewati mulus, dan kegagalannya me-rollback CRUD
`nas` agar state tak menyimpang.

> **Catatan reload.** `read_clients=yes` memuat tabel `nas` saat FreeRADIUS **start**
> (belum ada `dynamic_clients` tanpa-reload). Router yang baru didaftar perlu satu
> reload FreeRADIUS agar auth-nya diterima. Dampaknya kecil: sesi PPPoE hidup ada di
> Mikrotik, bukan di FreeRADIUS. Dynamic-clients-tanpa-reload = enhancement lanjutan.

Shared secret di UI **satu nilai, dua arah**: dipakai Mikrotik untuk auth ke FreeRADIUS
**dan** oleh server untuk CoA/Disconnect (RFC 5176) ke BRAS. Form menyediakan tombol
**Generate** (`crypto.getRandomValues`, base64url) — operator tak perlu mengarang
sendiri; nilai yang sama harus ditempel di konfigurasi RADIUS Mikrotik.

---

## Jalur-tulis: antrean + worker server-side

Provisioning bukan sinkron — ia mengalir lewat antrean `bng_action` (reuse rantai aksi
Phase 7) yang diklaim worker server:

- `RadiusProvisioningDispatcher` (`@Scheduled`, tiap `dispatchInterval` PT10S) mengklaim
  aksi `PROVISION`/`DEPROVISION`/`SYNC_GROUP` per-tenant (`REQUIRES_NEW`), meresolusi
  slug + password (didekripsi dari `SubscriberAccess.secret`), lalu memanggil
  `RadiusProvisioningPort`.
- `FreeRadiusJdbcAdapter` (impl port) menulis `radcheck`/`radusergroup`/`radgroupreply`
  via `RadiusConnectionResolver.connectionFor(tenantId)` — idempoten DELETE-lalu-INSERT.
- **Pembelahan klaim** collector vs server: aksi `SESSION_CONTROL` untuk NAS
  `reachability=COLLECTOR` tetap ke collector; sisanya (`PROVISION`/`DEPROVISION`/
  `SYNC_GROUP` + kontrol sesi NAS non-COLLECTOR) diklaim server.

`radius-db` yang sesaat mati **tidak** menggagalkan apa pun: pool `initializationFailTimeout=-1`,
aksi menumpuk `PENDING` lalu jalan begitu DB pulih (degradasi anggun sampai `maxRetry` PT1H,
lewat itu → `FAILED` agar tak mengulang selamanya).

---

## Jalur-baca: accounting server-side

`RadiusAccountingPoller` (`@Scheduled`, tiap `sessionPollInterval` PT30S) per-tenant
memanggil `RadiusAccountingReadPort` → `RadacctJdbcAdapter`: `SELECT ... FROM radacct
WHERE username LIKE '{slug}:%' AND acctstoptime IS NULL`, lalu **mengupas prefiks**
`{slug}:` → `SessionObservation` bare → reuse `BngSessionIngestService` (inti netral)
→ `radius_session`/`accounting_record`. `nasId=null` karena `radacct` global hanya
menyimpan `nasipaddress` (string), bukan id NAS tenant.

---

## Reachability CoA (per-NAS, 3-jalur)

**Kunci:** auth/acct = Mikrotik nembak KELUAR ke FreeRADIUS → jalan tanpa VPN, bahkan di
full-NAT. Hanya **CoA/Disconnect** (server → Mikrotik :3799) yang butuh jalur balik.
Kolom `nas.reachability` menandai jalurnya (migrasi V37, default `COLLECTOR`):

| Kondisi Mikrotik | `reachability` | Jalur CoA |
|---|---|---|
| IP publik statis | `DIRECT` | Server → `IP_publik:3799` langsung; tenant buka 3799/udp ke IP server |
| NAT + join VPN | `VPN` | Server → `overlay_ip:3799` lewat overlay (server ko-lokasi hub) |
| NAT tanpa VPN | `COLLECTOR` | Collector on-prem menembak CoA lokal (jalur lama Phase 7) |
| NAT tanpa VPN & collector | `NONE` | **Degradasi anggun** — perubahan berlaku saat login ulang |

- `ServerRadiusDaeAdapter` (impl `RadiusSessionControlPort`) merakit paket via codec
  `contract.radius.RadiusDae` (murni-Kotlin, dipindah dari collector ke modul `contract`
  agar bisa dipakai dua sisi). Disconnect-NAK 503 (sesi tak ada) = idempoten/sukses;
  CoA-NAK dilempar.
- `DIRECT` **dan** `VPN` menembak DAE lewat jalur yang **sama** — ke `nas.address:3799`
  (IP publik untuk DIRECT, IP overlay untuk VPN). Sesi `radacct` dibaca **malas** untuk
  keduanya (butuh Acct-Session-Id/NAS-IP). `NONE` & CoA-tanpa-sesi-hidup →
  `COMPLETED`-bercatatan, tak gagal keras.
- **Alamat di `nas`** = alamat yang sama dipakai target CoA (overlay IP kalau VPN, IP
  publik kalau tidak) — satu identitas, dua arah.
- **Syarat jalur `VPN`:** container `server` harus **ter-rute ke subnet overlay** (server
  ko-lokasi hub). Auto-resolve overlay IP dari peer VPN tertaut (`VpnApi` + linkage
  peer↔nas) = enhancement lanjutan; kini operator mengisi overlay IP sebagai `nas.address`.
- DNAT port-publik VPN (`RemotePortRange` 20000-40000 → port layanan di perangkat) hanya
  untuk remote manajemen (Winbox/API/SSH), **bukan** jalur CoA.

---

## Skalabilitas

Beban FreeRADIUS = **rate paket**, bukan jumlah user (sesi PPPoE long-lived; 50rb sesi
@interim 5m ≈ 167 acct/dtk = enteng). Satu FreeRADIUS logis, stateless, sanggup
puluhan-ribu user.

- Bottleneck pertama = **DB** (`radacct` tumbuh), bukan daemon → obat: partisi `radacct`
  per-bulan + job cleanup + pooling.
- Scaling daemon (lebih untuk HA): N FreeRADIUS **stateless** → 1 `radius-db` sama;
  Mikrotik daftar 2+ server untuk failover native.
- Shard per-tenant hanya di skala ekstrem; data sudah tenant-keyed. Jahitan sudah
  disiapkan: `RadiusConnectionResolver.connectionFor(tenantId)` (kini balikin 1 cluster)
  — sharding kelak = ubah pemilihan pool, pemanggil tak berubah.

---

## Konfigurasi (`ftth.radius`)

`RadiusProperties` (`@ConfigurationProperties(prefix = "ftth.radius")`):

| Properti | Env prod | Bawaan | Guna |
|---|---|---|---|
| `url` | `FTTH_RADIUS_DB_URL` | `""` | URL JDBC radius-db. **Kosong → provisioning server-side mati** (app tetap boot) |
| `username` | `FTTH_RADIUS_DB_USER` | `""` | User radius-db (rahasia platform) |
| `password` | `FTTH_RADIUS_DB_PASSWORD` | `""` | Password radius-db |
| `enabled` | `FTTH_RADIUS_ENABLED` | `true` | Sakelar eksplisit (matikan walau url terisi) |
| `maxPoolSize` | — | `5` | Ukuran pool (provisioning jarang) |
| `dispatchInterval` | — | PT10S | Selang worker klaim aksi provisioning |
| `sessionPollInterval` | — | PT30S | Selang poller baca `radacct` |
| `batchSize` | — | `100` | Aksi diklaim per putaran per-tenant |
| `maxRetry` | — | PT1H | Batas usia aksi gagal transien sebelum `FAILED` |

Di prod (`docker-compose.prod.yml`) env `FTTH_RADIUS_DB_*` diturunkan dari blok
`RADIUS_DB_*` yang sama dipakai service `radius-db` + `freeradius`. Server `depends_on:
radius-db: service_started` (ordering saja, bukan healthy — jaga resilience boot).

> **Bug prod yang ditutup S4:** sebelumnya service `server` sama sekali tak punya env
> radius-db → `RadiusConnectionResolver.configured=false` → provision/baca/registri
> **no-op diam-diam** di prod. Kini ter-wire.

---

## Keamanan

- **Shared secret terenkripsi** di adapter (`SecretCipher`); DB aplikasi hanya melihat
  ciphertext. Ke radius-db `nas.secret` ditulis plaintext (FreeRADIUS memang butuh
  plaintext untuk verifikasi klien) — radius-db internal, tak terekspos ke luar.
- **Tak pernah bocor lewat view.** `NasView` hanya membawa `hasCoaSecret` (boolean),
  tak pernah mengembalikan secret. UI menampilkannya sekali saat operator men-generate;
  setelah simpan tak bisa dibaca ulang.
- **Anti-spoof tenant** via ikatan `nas` (source-IP + shared secret) → `shortname`
  menentukan prefix SQL. Tenant tak bisa memakai/mengubah klien tenant lain.
- **Isolasi kunci SQL** `{slug}:{username}` menjaga radcheck/radacct antar-tenant tak
  bertabrakan walau username-nya identik.

---

## Alur (ringkas)

**Tenant (sekali per router):**

1. (opsional) Generate akun VPN, tempel di Mikrotik → dapat overlay IP tetap (kalau NAT).
2. UI **BRAS & RADIUS** → tambah BRAS: nama, alamat (IP publik / overlay), **Generate**
   shared secret. Simpan → server menulis baris `nas` (nasname/shortname/secret).
3. Set RADIUS client di Mikrotik ke IP FreeRADIUS pusat + shared secret yang sama;
   arahkan PPPoE AAA ke RADIUS; terima incoming DAE :3799 bila mau CoA jalur DIRECT/VPN.
4. Operator platform reload FreeRADIUS sekali (memuat klien `nas` baru).

**Tenant (berulang, per-pelanggan):**

5. Buat paket di **Paket Internet** (modul `catalog`) → grup `plan:{planId}` di RADIUS.
6. Provisi akun PPPoE di detail pelanggan (tab Akses): username + password + paket.
   Server antre `SYNC_GROUP` + `PROVISION` → `radgroupreply` + `radcheck`/`radusergroup`
   ter-key `{slug}:{username}`.
7. CPE dial `budi` (bare) → FreeRADIUS auth `{slug}:budi` → balikkan Mikrotik-Rate-Limit
   dari grup paket. Ubah paket = 1 baris `radgroupreply` + CoA ke sesi hidup.

---

## Peta kode

| Lapis | Berkas |
|---|---|
| Config koneksi | `bng/config/RadiusProperties.kt`, `bng/adapter/outbound/radius/RadiusConnectionResolver.kt` |
| Port | `bng/application/port/outbound/{RadiusProvisioningPort,RadiusAccountingReadPort,RadiusSessionControlPort,RadiusClientRegistryPort}.kt` |
| Adapter JDBC/DAE | `bng/adapter/outbound/radius/{FreeRadiusJdbcAdapter,RadacctJdbcAdapter,ServerRadiusDaeAdapter,FreeRadiusClientRegistryAdapter}.kt` |
| Worker | `bng/application/service/{RadiusProvisioningDispatcher,RadiusSessionControlDispatcher,RadiusAccountingPoller}.kt` |
| Grup paket | `bng/domain/model/RadiusGroups.kt` (`plan:{planId}` + `:fup`) |
| Codec DAE shared | `contract/.../radius/RadiusDae.kt` |
| Deploy | `deploy/docker-compose.prod.yml`, `deploy/radius/freeradius/{mods-enabled/sql,clients.conf}`, `deploy/DEPLOY.md` (Bagian K) |

Untuk uji end-to-end dengan perangkat/servis nyata, lihat [`lab-bras-radius.md`](lab-bras-radius.md).
</content>
</invoke>

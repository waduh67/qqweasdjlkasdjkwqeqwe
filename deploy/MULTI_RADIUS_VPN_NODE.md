# Panduan Setup Node RADIUS & VPN Terpisah di VPS (Multi-Server)

Dokumen ini adalah panduan langkah demi langkah untuk memasang **FreeRADIUS** dan **OpenVPN Server** di VPS terpisah yang memiliki **IP Publik Statis**.

Dengan arsitektur ini:
- **Aplikasi Utama (Web & API Core)** tetap berjalan di Mini PC rumah/kantor via Cloudflare Tunnel.
- **RADIUS & VPN Hub** berjalan di VPS murah dengan IP publik statis agar Router Mikrotik dapat langsung terhubung dari mana saja tanpa kendala NAT/Cloudflare.

---

## 1. Spesifikasi Server VPS yang Direkomendasikan

FreeRADIUS dan OpenVPN adalah program native C yang sangat ringan dan efisien. Kamu **tidak butuh VPS mahal**.

| Komponen | Rekomendasi Minimum | Catatan |
| :--- | :--- | :--- |
| **CPU** | 1 Core (vCPU) | Cukup untuk ribuan request autentikasi/detik |
| **RAM** | 1 GB | Konsumsi aktif FreeRADIUS + Postgres + OpenVPN < 400 MB |
| **Penyimpanan** | 20 GB SSD | Skema radius-db sangat hemat ruang |
| **IP Address** | **1x Public IPv4 Statis** | **Wajib** untuk ditembak router Mikrotik |
| **OS** | Ubuntu 24.04 LTS / 22.04 LTS | Standar & kompatibel |

> [!TIP]
> **Pilihan Provider Murah:**
> - VPS Lokal Indonesia (IDCloudHost, DomaiNesia, Biznet Gio, CloudKilat): Rp 45.000 – Rp 80.000 / bulan.
> - VPS Global Latensi Rendah (Hetzner SG, DigitalOcean): $3.5 – $5 / bulan.

---

## 2. Topologi Jaringan & Port yang Dibuka

```
                       ┌────────────────────────────────────────────────────────┐
                       │                     VPS Node Luar                      │
                       │               (IP Publik: 203.0.113.10)                │
                       │                                                        │
┌────────────────┐     │  ┌──────────────┐     ┌───────────┐     ┌───────────┐  │
│ Router         │─UDP─┼─▶│ FreeRADIUS   │────▶│ radius-db │     │  OpenVPN  │◀─┼──TCP 1194 (Tunnel)
│ Mikrotik       │1812 │  │ (Auth/Acct)  │SQL  │(Postgres) │     │    Hub    │  │
└────────────────┘1813 │  └──────────────┘     └─────▲─────┘     └───────────┘  │
                       └─────────────────────────────┼──────────────────────────┘
                                                     │
                                               JDBC (Port 5432)
                                                     │
                                       ┌─────────────┴────────────┐
                                       │     Mini PC (Aplikasi)   │
                                       │ ftth.karuhundeveloper.com│
                                       └──────────────────────────┘
```

### Port Firewall di VPS (UFW)
| Port / Protokol | Layanan | Sumber yang Diizinkan |
| :--- | :--- | :--- |
| **`22 / TCP`** | Akses SSH Admin | Dari IP kamu / mana saja |
| **`1812 / UDP`** | RADIUS Authentication | Dari IP Mikrotik / Publik |
| **`1813 / UDP`** | RADIUS Accounting | Dari IP Mikrotik / Publik |
| **`1194 / TCP`** | OpenVPN Server (Mikrotik VPN) | Dari IP Mikrotik / Publik |
| **`5432 / TCP`** | PostgreSQL (`radius-db`) | **HANYA dari IP Mini PC** (atau lewat VPN) |

---

## 3. Langkah Instalasi di VPS Baru

### Langkah A: Update Server & Pasang Docker
Login ke VPS baru kamu via SSH:
```bash
sudo apt update && sudo apt upgrade -y
curl -fsSL https://get.docker.com | sh
sudo systemctl enable --now docker
```

### Langkah B: Siapkan Direktori Node RADIUS
```bash
sudo mkdir -p /opt/radius-node/initdb /opt/radius-node/mods-enabled
cd /opt/radius-node
```

Salin skema tabel FreeRADIUS ke `initdb`:
Buat file `/opt/radius-node/initdb/01-schema.sql` (skema standar FreeRADIUS 3.x untuk PostgreSQL):
```sql
CREATE TABLE IF NOT EXISTS radcheck (
    id SERIAL PRIMARY KEY,
    username VARCHAR(64) NOT NULL DEFAULT '',
    attribute VARCHAR(64) NOT NULL DEFAULT '',
    op CHAR(2) NOT NULL DEFAULT '==',
    value VARCHAR(253) NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS radcheck_username ON radcheck (username, attribute);

CREATE TABLE IF NOT EXISTS radreply (
    id SERIAL PRIMARY KEY,
    username VARCHAR(64) NOT NULL DEFAULT '',
    attribute VARCHAR(64) NOT NULL DEFAULT '',
    op CHAR(2) NOT NULL DEFAULT '=',
    value VARCHAR(253) NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS radreply_username ON radreply (username, attribute);

CREATE TABLE IF NOT EXISTS radgroupcheck (
    id SERIAL PRIMARY KEY,
    groupname VARCHAR(64) NOT NULL DEFAULT '',
    attribute VARCHAR(64) NOT NULL DEFAULT '',
    op CHAR(2) NOT NULL DEFAULT '==',
    value VARCHAR(253) NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS radgroupcheck_groupname ON radgroupcheck (groupname, attribute);

CREATE TABLE IF NOT EXISTS radgroupreply (
    id SERIAL PRIMARY KEY,
    groupname VARCHAR(64) NOT NULL DEFAULT '',
    attribute VARCHAR(64) NOT NULL DEFAULT '',
    op CHAR(2) NOT NULL DEFAULT '=',
    value VARCHAR(253) NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS radgroupreply_groupname ON radgroupreply (groupname, attribute);

CREATE TABLE IF NOT EXISTS radusergroup (
    id SERIAL PRIMARY KEY,
    username VARCHAR(64) NOT NULL DEFAULT '',
    groupname VARCHAR(64) NOT NULL DEFAULT '',
    priority INT NOT NULL DEFAULT 1
);
CREATE INDEX IF NOT EXISTS radusergroup_username ON radusergroup (username);

CREATE TABLE IF NOT EXISTS radacct (
    radacctid BIGSERIAL PRIMARY KEY,
    acctsessionid VARCHAR(64) NOT NULL DEFAULT '',
    acctuniqueid VARCHAR(32) NOT NULL DEFAULT '',
    username VARCHAR(64) NOT NULL DEFAULT '',
    realm VARCHAR(64) DEFAULT '',
    nasipaddress INET NOT NULL,
    nasportid VARCHAR(32) DEFAULT NULL,
    nasporttype VARCHAR(32) DEFAULT NULL,
    acctstarttime TIMESTAMP WITH TIME ZONE NULL DEFAULT NULL,
    acctupdatetime TIMESTAMP WITH TIME ZONE NULL DEFAULT NULL,
    acctstoptime TIMESTAMP WITH TIME ZONE NULL DEFAULT NULL,
    acctinterval INT DEFAULT NULL,
    acctsessiontime BIGINT DEFAULT NULL,
    acctauthentic VARCHAR(32) DEFAULT NULL,
    connectinfo_start VARCHAR(50) DEFAULT NULL,
    connectinfo_stop VARCHAR(50) DEFAULT NULL,
    acctinputoctets BIGINT DEFAULT NULL,
    acctoutputoctets BIGINT DEFAULT NULL,
    calledstationid VARCHAR(50) NOT NULL DEFAULT '',
    callingstationid VARCHAR(50) NOT NULL DEFAULT '',
    acctterminatecause VARCHAR(32) NOT NULL DEFAULT '',
    servicetype VARCHAR(32) DEFAULT NULL,
    framedprotocol VARCHAR(32) DEFAULT NULL,
    framedipaddress INET NULL DEFAULT NULL,
    framedipv6address INET NULL DEFAULT NULL,
    framedipv6prefix INET NULL DEFAULT NULL,
    framedinterfaceid VARCHAR(44) DEFAULT NULL,
    delegatedipv6prefix INET NULL DEFAULT NULL
);
CREATE INDEX IF NOT EXISTS radacct_active_session_idx ON radacct (nasipaddress, acctsessionid) WHERE acctstoptime IS NULL;
CREATE INDEX IF NOT EXISTS radacct_username_active_idx ON radacct (username) WHERE acctstoptime IS NULL;

CREATE TABLE IF NOT EXISTS nas (
    id SERIAL PRIMARY KEY,
    nasname VARCHAR(128) NOT NULL,
    shortname VARCHAR(32),
    type VARCHAR(30) DEFAULT 'other',
    ports INT,
    secret VARCHAR(60) NOT NULL,
    server VARCHAR(64),
    community VARCHAR(50),
    description VARCHAR(200) DEFAULT 'RADIUS Client'
);
CREATE INDEX IF NOT EXISTS nas_nasname ON nas (nasname);
```

### Langkah C: Buat Docker Compose Node RADIUS
Buat file `/opt/radius-node/docker-compose.yml`:
```yaml
version: "3.8"
services:
  radius-db:
    image: postgres:16-alpine
    restart: unless-stopped
    environment:
      POSTGRES_DB: radius
      POSTGRES_USER: radius
      POSTGRES_PASSWORD: "GantiPasswordSuperAmanRadius123"
    volumes:
      - radiusdata:/var/lib/postgresql/data
      - ./initdb:/docker-entrypoint-initdb.d:ro
    ports:
      # Port ini digunakan oleh backend FTTH di Mini PC untuk menulis user & membaca session
      - "5432:5432"

  freeradius:
    image: freeradius/freeradius-server:3.2.5
    restart: unless-stopped
    depends_on:
      - radius-db
    environment:
      RADIUS_DB_HOST: radius-db
      RADIUS_DB_PORT: "5432"
      RADIUS_DB_NAME: radius
      RADIUS_DB_USER: radius
      RADIUS_DB_PASSWORD: "GantiPasswordSuperAmanRadius123"
    volumes:
      - ./mods-enabled/sql:/etc/freeradius/mods-enabled/sql:ro
      - ./clients.conf:/etc/freeradius/clients.conf:ro
    command: ["radiusd", "-f"]
    ports:
      - "1812:1812/udp"
      - "1813:1813/udp"

volumes:
  radiusdata:
```

Nyalakan stack RADIUS di VPS:
```bash
cd /opt/radius-node
sudo docker compose up -d
```

### Langkah D: Pasang OpenVPN Hub (Otomatis dari Dashboard)
Kamu tidak perlu mengonfigurasi OpenVPN secara manual:
1. Buka dashboard web: `https://ftth.karuhundeveloper.com`
2. Login sebagai Admin Platform (`admin@karuhundeveloper.com` / `password`).
3. Buka menu **Server VPN** ➔ Klik **Tambah Server VPN**:
   - **Nama**: `VPS Node 1 - Jakarta` (atau nama pilihanmu)
   - **Host**: Masukkan IP Publik VPS tersebut (misal `203.0.113.10`)
   - **Port**: `1194`
   - **Protokol**: `TCP`
   - **Subnet Tunnel**: `10.8.0.0/24`
4. Klik Simpan. Sistem akan memunculkan kartu dengan **Perintah Pasang Satu Baris**:
   ```bash
   curl -fsSL https://ftth.karuhundeveloper.com/api/vpn/provision/<TOKEN> | sudo bash
   ```
5. Jalankan perintah tersebut di terminal VPS kamu.
6. OpenVPN langsung terpasang, terhubung, dan aktif!

### Langkah E: Atur Firewall VPS (UFW)
Untuk keamanan maksimal (database Postgres port 5432 tidak boleh dibuka ke sembarang orang):
```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing

sudo ufw allow 22/tcp          # Port SSH
sudo ufw allow 1812/udp        # RADIUS Authentication
sudo ufw allow 1813/udp        # RADIUS Accounting
sudo ufw allow 1194/tcp        # OpenVPN Server

# Izinkan port database Postgres HANYA dari IP publik Mini PC kamu:
sudo ufw allow from <IP-PUBLIK-MINI-PC> to any port 5432 proto tcp

sudo ufw enable
```

---

## 4. Cara Menghubungkan Aplikasi Mini PC ke VPS RADIUS

Di file `/home/hidupjokowi/ftth/.env` di Mini PC kamu:
```bash
# Arahkan koneksi RADIUS platform ke IP publik VPS baru:
FTTH_RADIUS_DB_URL=jdbc:postgresql://<IP-PUBLIK-VPS>:5432/radius
FTTH_RADIUS_DB_USER=radius
FTTH_RADIUS_DB_PASSWORD=GantiPasswordSuperAmanRadius123

# Host publik yang ditampilkan di UI Mikrotik:
FTTH_RADIUS_PUBLIC_HOST=<IP-PUBLIK-VPS>
FTTH_RADIUS_ENABLED=true
```
Lalu restart container server di Mini PC:
```bash
docker compose -f /home/hidupjokowi/ftth/docker-compose.prod.yml restart server
```

---

## 5. Cara Menghubungkan Mikrotik RouterOS ke Node VPS

Di terminal Mikrotik (Winbox / CLI):
```routeros
/radius
add address=<IP-PUBLIK-VPS> \
    secret="<SECRET-YANG-DIISI-DI-MENU-BRAS>" \
    service=ppp \
    authentication-port=1812 \
    accounting-port=1813 \
    timeout=3000ms

/ppp aaa
set use-radius=yes accounting=yes interim-update=5m
```
Kini seluruh PPPoE secret dan sesi pelanggan langsung terkelola secara realtime melalui VPS dengan IP statis!

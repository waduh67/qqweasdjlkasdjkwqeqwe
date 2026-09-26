# FTTH di host dengan reverse proxy yang sudah berjalan

Gunakan Docker Compose minimal 2.24.4. Simpan konfigurasi dan secret FTTH di
direktori proyek tersendiri; pertahankan konfigurasi, volume, dan jaringan aplikasi
yang sudah ada. Image aplikasi harus berasal dari hasil gate rilis yang lulus.

Salin `docker-compose.prod.yml`, `docker-compose.shared-proxy.yml`,
`Caddyfile.shared-proxy`, serta folder inisialisasi/backup yang disebut panduan deploy.
Di `.env` FTTH, tambahkan nama jaringan Docker proxy yang sebenarnya dan subnetnya:

```dotenv
FTTH_EDGE_PROXY_NETWORK=existing-app_default
FTTH_EDGE_PROXY_CIDR=172.18.0.0/16
FTTH_ISOLIR_PORTAL_URL=https://ftth.example.net/portal
FTTH_CORS_ORIGINS=https://ftth.example.net
FTTH_VPN_PUBLIC_BASE_URL=https://ftth.example.net
```

SMTP dapat disiapkan kemudian melalui Setelan Email platform. Jika belum ada,
kosongkan `FTTH_MAIL_HOST` dan `FTTH_MAIL_FROM`. `FTTH_MAIL_HEALTH_ENABLED=false`
menonaktifkan probe SMTP bawaan Spring yang hanya membaca konfigurasi environment;
health database tetap aktif. Jika memakai SMTP fallback dari environment, set
`FTTH_MAIL_HEALTH_ENABLED=true` untuk menyertakannya dalam health. Pengiriman email
dari setelan database diperiksa lewat fitur uji email di platform.

Nilai jaringan di atas hanya contoh. Periksa jaringan container proxy sebelum
mengisinya. CIDR membatasi sumber header forwarded yang dipercaya gateway FTTH.
Jangan memasukkan jaringan publik atau `0.0.0.0/0`.

Tambahkan virtual host berikut pada proxy Caddy yang sudah ada, validasi kandidat
konfigurasinya, lalu lakukan reload. Simpan salinan konfigurasi sebelumnya dan
periksa bahwa situs lain tetap merespons:

```caddyfile
ftth.example.net {
    reverse_proxy ftth-gateway:80
}
```

Gateway FTTH bergabung ke jaringan proxy dengan alias `ftth-gateway`. Hanya gateway
yang bergabung; PostgreSQL, MinIO, backend dan ACS NBI tetap di jaringan FTTH.
TLS berakhir pada proxy luar, lalu gateway meneruskan informasi protokol klien yang
dipercaya. Gateway tidak menerbitkan port host 80 atau 443.

Gunakan kedua berkas Compose pada setiap operasi FTTH:

```bash
docker compose --project-directory /opt/ftth --env-file /opt/ftth/.env \
  -f /opt/ftth/docker-compose.prod.yml \
  -f /opt/ftth/docker-compose.shared-proxy.yml config --quiet

docker compose --project-directory /opt/ftth --env-file /opt/ftth/.env \
  -f /opt/ftth/docker-compose.prod.yml \
  -f /opt/ftth/docker-compose.shared-proxy.yml up -d
```

Untuk image yang diangkut sebagai arsip terverifikasi, muat arsip terlebih dahulu,
tetapkan referensi image lokal yang sudah diperiksa, lalu gunakan `--pull never`.
Docker dengan penyimpanan containerd dapat melaporkan ID manifest OCI, sedangkan
Docker lama melaporkan digest konfigurasi. Cocokkan digest konfigurasi dan seluruh
layer setelah pemuatan; perbedaan jenis ID tidak membolehkan memakai image lain.

Jangan memakai perintah deploy otomatis untuk host mandiri pada host proxy bersama:
perintah itu hanya memuat Compose dasar dan akan mencoba mengambil port 80/443.
Rollout host ini harus selalu menyertakan overlay dan menjaga referensi image rilis.

## Port inbound Azure

| Port | Protokol | Sumber dan pemakaian |
|---|---|---|
| 22 | TCP | IP admin untuk SSH |
| 80,443 | TCP | Web/API dan penerbitan sertifikat HTTPS |
| 7547 | TCP | Jaringan ONT untuk ACS/TR-069; gunakan kredensial CWMP |
| 7567 | TCP | Jaringan ONT bila memakai download firmware |
| 1194 | TCP | Hub OpenVPN; kompatibel dengan MikroTik v6 |
| 20000–40000 | TCP | Forwarding port perangkat VPN jika diaktifkan |
| 20000–40000 | UDP | Hanya bila menggunakan forwarding UDP, misalnya SNMP |
| 1812,1813 | UDP | IP NAS/router untuk RADIUS auth/accounting |
| 8880 | TCP | Jaringan pelanggan untuk halaman isolir, bila dipakai |

Port CoA `3799/UDP` diterima router dari server; bukan listener inbound VPS.
PostgreSQL, MongoDB, MinIO, backend 8080, dan ACS NBI 7557 tidak membutuhkan inbound
publik. Port monitoring tetap loopback. Aturan NSG dan firewall host harus sesuai.

Domain web boleh memakai proxy Cloudflare. ACS, OpenVPN, RADIUS, dan forwarding
perangkat memakai IP publik atau nama DNS-only karena bukan trafik proxy web biasa.
Setelah rollout, periksa HTTPS, login, versi migrasi, role runtime non-owner,
readiness backend, batas akses ACS, status VPN, dan restart tanpa pergantian image.

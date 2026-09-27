# Operasi FTTH di Azure

Host: `fajar@70.153.16.143`, web: <https://ftth.karuhundeveloper.com>.
Instalasi ini memakai image CI aplikasi `1306b65c34167b2d48f4817ce72990fed3b85aa3`
dan konfigurasi deployment `db09c3c2b37ad29b07464b05cd02895265f99a0d`.
Database sudah menjalankan 367 migrasi sampai `178.12`. Jangan mengubah migrasi
yang sudah diterapkan; migrasi berikutnya adalah `178.13`.

## Login dan pemakaian awal

Akun platform: `admin@karuhundeveloper.com`. Password awal acak disimpan pada
berkas privat di VPS. Baca melalui sesi SSH sendiri:

```sh
ssh -i ~/.ssh/id_ed25519 fajar@70.153.16.143
sudo cat /opt/ftth/setup-private/admin-credentials.txt
```

Buka web, masuk sebagai admin platform, lalu gunakan **Tenant** untuk onboarding
ISP. Tidak ada tenant pelanggan atau stok contoh yang dibuat pada produksi.
Panduan gudang ada di [warehouse-review.md](../docs/warehouse-review.md) dan
[warehouse.md](../docs/warehouse.md). Tenant yang punya riwayat gudang dilindungi
dari penghapusan; gunakan **Suspend**.

Hub **Azure FTTH** tersedia di **Infrastruktur → Server VPN**:
`70.153.16.143:1194`, protokol **TCP**, subnet tunnel `10.8.0.0/24`.
Buat akun/peer sesuai tenant melalui aplikasi sebelum memasang konfigurasi klien
pada router. Belum ada router pelanggan yang didaftarkan atau diuji fisik.

Setelan ACS dan koneksi lain ada di
`/opt/ftth/setup-private/connection-settings.txt` (root, mode `0600`). ACS CWMP
mengharuskan kredensial; jangan mengirimkannya lewat Git atau tangkapan layar.
SMTP belum diisi; pengiriman email memerlukan konfigurasi **Setelan Email**
platform. Layanan aplikasi/database tetap memiliki pemeriksaan kesehatan aktif.

## Azure NSG dan firewall

Tambahkan aturan **Inbound** berikut pada NSG yang terpasang pada NIC/subnet VPS.
Batasi sumber ke alamat admin, NAS, atau jaringan perangkat bila tersedia.

| Port tujuan | Protokol | Kegunaan / sumber |
|---|---|---|
| 22 | TCP | SSH, batasi ke IP admin |
| 80, 443 | TCP | Web dan TLS |
| 7547 | TCP | ACS/TR-069 dari CPE |
| 1194 | TCP | OpenVPN, termasuk kebutuhan RouterOS v6 |
| 1812, 1813 | UDP | RADIUS autentikasi dan accounting dari NAS/router |
| 7567 | TCP | Opsional: file/firmware GenieACS |
| 20000–40000 | TCP | Bila memakai port forwarding ke peer VPN |
| 20000–40000 | UDP | Hanya bila membuat forwarding UDP |
| 8880 | TCP | Opsional: portal isolir melalui alamat IP |

UDP `3799` untuk CoA dibuka pada **router/NAS penerima**, bukan sebagai layanan
inbound VPS ini. Database `5432`, MongoDB `27017`, MinIO `9000`, backend `8080`,
dan API internal GenieACS `7557` tidak perlu dibuka publik.

Web boleh memakai proxy Cloudflare. ACS/VPN/RADIUS memakai IP di atas atau
hostname **DNS only**; proxy web Cloudflare biasa tidak membawa protokol tersebut.
UFW dan aturan tunnel lokal sudah disiapkan. Pemeriksaan eksternal membuktikan
1194/TCP hingga TLS dan penolakan autentikasi; ini tidak membuktikan aturan UDP
NSG atau forwarding pelanggan yang belum dibuat. Tidak ada perubahan NSG Azure
melalui akun Azure pada pekerjaan ini.

## Pemeriksaan layanan

Selalu pakai wrapper agar overlay jaringan dan image yang dipin ikut terbaca:

```sh
sudo /opt/ftth/ftth-compose ps
sudo /opt/ftth/ftth-compose logs --tail 100 server
sudo systemctl status openvpn-server@server.service ftth-vpn-sync.timer ftth-vpn-firewall.service
curl -I https://ftth.karuhundeveloper.com
```

Tiga belas container FTTH memakai `restart: unless-stopped`. OpenVPN, timer
sinkronisasi, dan aturan firewall tunnel diaktifkan saat boot. Pengulangan
layanan firewall tidak menggandakan aturan. Restart backend dengan container
dan image yang sama sudah diuji: akun admin, hub, PKI, dan schema tetap ada.
Reboot seluruh VPS belum dilakukan karena host juga melayani aplikasi lain.

TLS publik ditangani Caddy milik `/opt/ruang-foto`. Konfigurasi FTTH berada di
blok `BEGIN/END FTTH MANAGED SITE` pada `/opt/ruang-foto/Caddyfile`, meneruskan
ke `ftth-gateway:80`. Pertahankan seluruh konfigurasi situs Drive di luar blok
itu. Jangan menjalankan stack Caddy kedua pada port 80/443 atau me-restart Docker
untuk perubahan FTTH biasa. Lihat [SHARED-PROXY.md](SHARED-PROXY.md).

## Backup dan pemulihan

Backup aplikasi dan RADIUS berada di `/opt/ftth/backups/{app,radius}`. Jadwal
host ini masing-masing 02:30 dan 03:00 **WIB (UTC+7)**, retensi 14 hari. Backup awal dan
backup setelah pemasangan VPN/restart berhasil dibuat. Salin backup ke penyimpanan
di luar VPS; backup lokal saja tidak melindungi dari hilangnya disk VPS.

```sh
sudo /opt/ftth/ftth-compose exec -T backup sh /opt/backup/backup.sh
sudo /opt/ftth/ftth-compose exec -T backup-radius sh /opt/backup/backup.sh
```

Paket pemulihan privat awal disimpan di
`/opt/ftth/setup-private/server-recovery-20260927.tar.gz` dan disalin ke mesin
kerja agent. Paket mencakup konfigurasi/rahasia FTTH, backup PostgreSQL, dan PKI
serta unit VPN. Jangan unggah paket ini ke Git. Image hasil CI juga dipertahankan
pada mesin kerja agent. Paket tersebut adalah salinan awal, bukan layanan backup
offsite terjadwal atau bukti pemulihan bencana menyeluruh.

Ikuti [backup.md](../docs/backup.md) untuk restore ke database tujuan yang terpisah,
mempertahankan role pemilik dan ACL. Drill backup/restore terisolasi sudah lulus;
database produksi tidak ditimpa untuk pengujian. Jangan menurunkan schema,
menjalankan `down -v`, menghapus volume, atau mengedit stok langsung melalui SQL.

## Catatan installer VPN

Installer generik pada image ini memiliki cacat lama: cleanup DNAT dengan
`grep` dan `pipefail` berhenti ketika tidak menemukan aturan lama. Installer yang
dipakai **host ini** sudah mendapat koreksi terbatas pada blok cleanup tersebut;
kegagalan membaca aturan firewall tetap menghentikan pemasangan. Berkas asli,
patch, hasil review, dan hash tersimpan privat di `setup-private`.

Template aplikasi generik belum diubah. Jangan menjalankan ulang installer dari
UI di host yang sudah terpasang, atau menganggap installer host baru otomatis
mendapat koreksi ini. Helper firewall khusus host memulihkan tiga aturan tunnel
pada boot tanpa menyimpan atau mengganti seluruh aturan Docker/UFW.

Pengujian GPON memakai dokumentasi/MIB dan fixture offline. Sertifikasi perangkat
GPON/MikroTik fisik dan distribusi aplikasi native tidak termasuk hasil instalasi ini.

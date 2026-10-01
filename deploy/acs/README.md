# ACS pada VPS terpisah

Aplikasi dapat berjalan di VPS A dan satu GenieACS bersama di VPS B. Admin
platform mengatur koneksi pada **Infrastruktur > Server ACS** (/platform/acs).
Paket ini memakai Dockerfile repo (GenieACS 1.2.16) dan Mongo 4.4. Jalankan dari
checkout repo utuh agar konteks build ../../docker/genieacs tersedia.

## Instalasi VPS B

1. Pasang Docker Engine dan Compose v2. Arahkan DNS api-acs.example.net dan
   acs.example.net ke VPS B. Isi hostname tanpa skema/port pada .env.
2. Dari deploy/acs, salin .env.example ke .env, lalu chmod 600 .env. Isi
   ACS_API_HOST, ACS_DEVICE_HOST dan ACS_API_USERNAME (misalnya ftth-app).
3. Buat password API dan jalankan perintah interaktif berikut. Simpan password
   asli untuk halaman admin. Salin hash bcrypt ke ACS_API_PASSWORD_HASH dalam
   .env; pertahankan tanda kutip tunggal agar dollar tidak diinterpolasi Compose.

~~~sh
docker run --rm -it caddy:2 caddy hash-password
~~~

4. Samakan FTTH_CPE_ONT_ACS_USERNAME/PASSWORD di VPS A dan B. Pada armada lama,
   pertahankan mode autentikasi CWMP yang sudah digunakan. Mode enforce hanya
   dipakai setelah ONT diberi kredensial yang sesuai.
5. Bangun dan nyalakan stack dari folder deploy/acs:

~~~sh
docker compose config --quiet
docker compose up -d --build
docker compose ps
~~~

Port 443 adalah API NBI melalui Caddy dengan HTTPS dan Basic Auth. Port 80 untuk
validasi sertifikat. NBI asli 7557 dan Mongo 27017 tidak dipublikasikan. Batasi
443 ke alamat egress VPS A di firewall/NSG bila memungkinkan, sambil menjaga
validasi sertifikat melalui port 80. Tidak ada UI GenieACS publik.

Buka TCP 7547 (CWMP) dan 7567 (unduhan firmware) untuk jaringan ONT yang
memerlukannya. Jalur CWMP/firmware paket ini memakai HTTP; gunakan jaringan
operator atau VPN dengan firewall yang sesuai bila ONT tidak mendukung TLS.
ACS_DEVICE_HOST harus dapat diakses ONT; hostname container tidak bisa digunakan.

## Pengaturan aplikasi di VPS A

Isi /platform/acs:

- NBI URL: https://api-acs.example.net
- Username API: ftth-app
- Password API: password asli yang hash-nya dipasang di Caddy
- CWMP URL lengkap: http://acs.example.net:7547/

Simpan, lalu **Uji koneksi tersimpan**. Ini menguji aplikasi ke NBI, bukan jalur
ONT. Setelah disimpan, seluruh record database menang atas environment, termasuk
username kosong. Password kosong mempertahankan secret lama; username kosong
menonaktifkan Basic Auth. Password API terenkripsi dengan FTTH_ENCRYPTION_SECRET
serta tidak dikirim kembali ke browser. Pertahankan kunci saat migrasi/restore.
Kegagalan ACS tidak memicu fallback ke ACS lokal. Operasi baru memakai konfigurasi
baru tanpa restart; diagnostik yang sudah berjalan tetap memakai server awal.

Sebelum penyimpanan pertama, bawaan berasal dari FTTH_CPE_GENIEACS_BASE_URL,
FTTH_CPE_GENIEACS_USERNAME/PASSWORD dan FTTH_CPE_PUBLIC_HOST/CWMP_PORT. Set
FTTH_CPE_GENIEACS_BASE_URL ke URL remote di .env VPS A. Gunakan kedua Compose
berikut agar empat service ACS lokal tidak ikut dinyalakan:

~~~sh
cd deploy
docker compose -f docker-compose.prod.yml -f docker-compose.remote-acs.yml config --quiet
docker compose -f docker-compose.prod.yml -f docker-compose.remote-acs.yml up -d
~~~

Jangan aktifkan profil local-acs dalam mode remote. Override tidak menghentikan
container lama yang sudah berjalan. Setelah migrasi terverifikasi, hentikan
service lokal dengan perintah berikut; volume tetap ada untuk rollback:

~~~sh
docker compose -f docker-compose.prod.yml stop genieacs-cwmp genieacs-nbi genieacs-fs genieacs-mongo
~~~

Compose produksi tanpa override mempertahankan instalasi ACS lokal. Kredensial
ONT dan connection request tetap dikendalikan environment deployment aplikasi.

## Cara ONT privat terhubung

ONT memulai HTTP/S Inform ke CWMP secara outbound. ACS membalas dan memberikan
task di dalam sesi itu. ONT tidak harus memiliki IP publik, tetapi membutuhkan
IP manajemen, DNS (bila menggunakan domain), gateway serta route ke VPS B.
MikroTik meneruskan trafik dan memakai src-NAT/masquerade bila subnet private
keluar lewat internet. OLT harus meneruskan management VLAN dan mengizinkan ACL.

~~~text
ONT -- Inform --> OLT / management VLAN --> MikroTik / NAT --> VPS B CWMP
VPS A aplikasi -------- HTTPS API NBI ----------------------> VPS B ACS
VPS B ACS -------- connection request (perlu route balik) --> ONT
~~~

**ONT router/PPPoE:** pastikan WAN yang menjalankan TR-069 memiliki IP, gateway,
DNS dan service TR-069 aktif. PPPoE pelanggan online belum membuktikan proses
TR-069 memakai WAN yang benar.

**ONT bridge:** PPPoE pada router pelanggan di belakang ONT tidak otomatis memberi
IP pada ONT. Buat management WAN/VLAN tersendiri pada ONT (DHCP atau static),
dengan IP, gateway, DNS dan binding TR-069. Bawa VLAN melalui OLT ke MikroTik.
Nomor VLAN/menu bergantung vendor dan konfigurasi operator; gunakan VLAN jaringan
sendiri, bukan nomor dari contoh acak.

Jika firewall/walled garden hanya mengizinkan tujuan tertentu, izinkan host/IP
CWMP port 7547, firmware port 7567, dan DNS ke resolver ONT. Terapkan pada
forward/ACL yang benar, termasuk profil terisolir bila ACS harus tetap aktif
selama isolir. Allowlist URL tanpa route/gateway/management WAN tidak cukup.
ONT tidak perlu mengakses API NBI aplikasi.

Agar perintah segera berjalan, **VPS B** harus mencapai ConnectionRequestURL
milik ONT. IP private di balik NAT biasanya tidak bisa dihubungi langsung.
Gunakan VPN/site-to-site dari VPS B ke subnet management ONT, route balik,
serta firewall untuk port connection request ONT. VPN yang hanya menghubungkan
VPS A ke MikroTik belum menyediakan jalur ini untuk VPS B.

Tanpa jalur balik, reboot/ubah konfigurasi dapat diantre sampai Inform berikutnya.
Atur Periodic Inform mengikuti kartu Setelan ONT (default 300 detik). Mengirim
task belum berarti ONT selesai mengeksekusinya. Periksa status task/Inform.

Uji dari jaringan ONT: DNS, route host CWMP, TCP 7547, kredensial dan last Inform
pada konsol. Periksa log CWMP serta counter firewall MikroTik. Mengganti CWMP URL
pada platform hanya memperbarui panduan; ONT lama perlu diubah melalui jalur
pengelolaan yang masih aktif atau secara lokal.

## Cadangan, migrasi dan rollback

Cadangkan seluruh database genieacs: config, devices, tasks, presets, provisions,
serta fs.files/fs.chunks (GridFS firmware). Simpan archive dengan izin terbatas
lalu salin ke lokasi cadangan terpisah:

~~~sh
umask 077
docker compose exec -T mongo mongodump --db genieacs --archive --gzip > genieacs.archive.gz
~~~

Saat migrasi, hentikan penulis CWMP/NBI/FS lama dalam maintenance window lalu ambil
dump terakhir. Deployment lama memakai service genieacs-mongo. Pindahkan archive
ke VPS B dan mulai hanya Mongo target:

~~~sh
docker compose up -d mongo
docker compose exec -T mongo mongorestore --archive --gzip < genieacs.archive.gz
docker compose up -d
~~~

Restore ke database kosong; jangan menggabungkan dua armada ACS. Alihkan DNS/URL
CWMP dan NBI saat target siap. Mempertahankan hostname/URL CWMP lama melalui DNS
mempermudah ONT lama; tunggu propagasi dan konfirmasi Inform. Verifikasi metadata,
task, firmware dan unduhan firmware dari jaringan ONT. Bootstrap CWMP menyelaraskan
cwmp.auth dari environment VPS B, sehingga kredensial harus cocok sebelum CWMP naik.

Untuk rollback, hentikan penulis baru, kembalikan NBI lewat admin serta DNS/CWMP ke
ACS lama, lalu nyalakan service lama dengan Compose produksi. Evaluasi task yang
sudah dijalankan selama perpindahan sebelum mencoba ulang. Jangan menjalankan dua
ACS sebagai penulis untuk armada yang sama. Jangan menghapus volume atau memakai
down -v selama migrasi/rollback. Cadangkan juga DB aplikasi dan kunci enkripsinya.

Referensi resmi: dokumentasi GenieACS environment variables/API reference,
Docker Compose profiles, dan Caddy basic_auth/hash-password.

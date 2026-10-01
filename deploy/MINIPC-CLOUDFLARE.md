# Deploy mini PC melalui Cloudflare Tunnel

Mini PC produksi menjalankan Docker Desktop sebagai user hidupjokowi.
/opt/ftth menunjuk ke /home/hidupjokowi/ftth; nginx host meneruskan web
ke 127.0.0.1:3080 dan API ke 127.0.0.1:8080. Cloudflare Tunnel
melayani ftth.karuhundeveloper.com dan SSH ssh67.karuhundeveloper.com.

## Akses dan secret GitHub

Pasang cloudflared pada klien SSH dan gunakan konfigurasi berikut:

~~~sshconfig
Host ssh67.karuhundeveloper.com
    User hidupjokowi
    ProxyCommand cloudflared access ssh --hostname %h
    StrictHostKeyChecking yes
~~~

Workflow memerlukan VPS_HOST, VPS_USER, VPS_SSH_KEY, dan VPS_KNOWN_HOSTS.
Isi known_hosts dengan host key yang diverifikasi melalui akses terpercaya.
Public key harus ada di authorized_keys mini PC. Workflow memakai
GITHUB_TOKEN dengan izin packages:read untuk login registry; token disimpan
sementara dalam folder rilis lalu dihapus.

## Rilis normal

Merge ke main menjalankan CI dan smoke test image, mempublikasikan image
yang sama persis, lalu mengaktifkan digest server/web melalui SSH.
Deteksi native tidak memerlukan sudo. DOCKER_HOST berasal dari context
Docker Desktop sebelum konfigurasi registry sementara digunakan.

Aktivasi menyimpan backup database, Compose, dan env dalam
/opt/ftth/releases/<commit>/. Hanya server dan web yang dibuat ulang.
Compose terpasang berupa JSON hasil render, dengan digest immutable dan
mode 600 karena memuat nilai env. Sejumlah env kosong memicu hang pada
Docker Desktop di mesin ini; launcher mengekspor nilai kosong melalui shell
sebelum menjalankan entrypoint asli. Nilai dolar di-escape untuk render kedua.

activation.json harus berstatus PASS, image yang berjalan harus sesuai
digest/revision rilis, backend health dan readiness web harus UP.
Jika gagal dan riwayat Flyway belum berubah, aktivasi mengembalikan Compose
dan image lama. Jika skema sudah berubah, status RECOVERY_REQUIRED
memerlukan pemulihan database yang sesuai atau perbaikan maju.
Jangan menjalankan seluruh stack dengan Compose rilis untuk rollback aplikasi.

## Upgrade database lama V173 RADIUS

Rilis lama memakai V173 untuk RADIUS; main memakai V173–V178 untuk
warehouse dan memindahkan RADIUS ke V179. Script
scripts/deploy/upgrade-legacy-v173.sql hanya menerima versi, checksum,
dan tabel legacy yang tepat, lalu memindahkan satu catatan migrasi dan
menyiapkan ownership/grant sebelum migrasi warehouse memperketat izin.

Sebelum memakai script, restore dump ke database latihan dan verifikasi
upgrade dengan image final. Hentikan app lama, ambil dump baru dan backup
env/Compose, lalu jalankan script melalui psql ON_ERROR_STOP.
Runtime memakai warehouse_app (non-owner/NOBYPASSRLS); Flyway memakai
warehouse_owner. Password kedua role disimpan dalam env privat.
Jalankan migrasi satu kali dengan SPRING_FLYWAY_OUT_OF_ORDER=true dan
scheduling/mutator dimatikan. Verifikasi riwayat tanpa kegagalan serta
data tenant/user sebelum aktivasi normal. Matikan kembali out-of-order.
Backup job harus memakai role owner agar seluruh tabel RLS ikut terbackup.

## ACS di VPS terpisah

Ikuti [panduan ACS](acs/README.md), lalu isi URL API NBI dan kredensial
gateway di /platform/acs. URL CWMP adalah alamat yang dihubungi ONT.
ONT private memulai koneksi keluar; jaringan manajemen ONT harus memiliki
rute dan izin DNS/HTTP(S) ke CWMP. Connection Request langsung ke ONT
memerlukan jalur balik yang dapat dijangkau; tanpa itu task menunggu Inform
berikutnya. Cloudflare Access login interaktif tidak cocok untuk CWMP ONT.

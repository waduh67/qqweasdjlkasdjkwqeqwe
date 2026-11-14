# Subnet IPv4 dan pemeriksaan ping

Menu Manajemen IP dapat mendaftarkan blok CIDR /20 sampai /32, maksimal 4.096
alamat per blok. Contoh 157.15.123.80/23 dinormalisasi menjadi 157.15.122.0/23:
512 alamat ditampilkan, dengan 510 host yang dapat dipakai. Network/broadcast
diberi label tersendiri dan tidak dialokasikan atau diping. /31 dan /32 menggunakan
semua alamat sebagai host.

Preview memperlihatkan subnet dan jumlah IP yang sudah terdaftar. Pendaftaran
mempertahankan identitas dan pemakai IP lama. Blok bertumpuk, ketidaksesuaian vendor,
atau alokasi pada batas network/broadcast ditolak. Alamat/vendor IP anggota blok
tidak diubah per alamat; gateway, catatan, dan pemakai tetap dapat dikelola.

Kolom Dipakai oleh menerima nama bebas seperti router kantor atau PT ABC. Alokasi
client B2B tetap memakai identitas client dan dilepas melalui halaman client B2B.
Status pemakaian terpisah dari hasil ping: IP tersedia bisa UP, dan IP terpakai
bisa DOWN.

Ping dapat dijalankan untuk satu IP atau seluruh host blok. Monitoring opsional
menjadwalkan semua host setiap lima menit. Worker server menggunakan job tersimpan,
lease 30 detik, dan maksimal 32 pemeriksaan serentak. Job yang belum selesai dapat
dilanjutkan setelah restart. FTTH_SCHEDULING_ENABLED=false menghentikan pengajuan
berkala; job manual dan job yang sudah antre tetap diproses.

UP berarti server menerima balasan ICMP. DOWN berarti balasan tidak diterima
sebelum batas waktu; perangkat masih bisa aktif jika firewall memblokir ICMP.
ERROR menandakan kegagalan pemeriksaan, misalnya executable ping tidak tersedia.
Tabel menyimpan RTT dan waktu pemeriksaan serta menandai hasil lebih dari sepuluh
menit. Mengubah alamat IP mandiri menghapus hasil alamat sebelumnya.

Izin network.ip.view membaca data, network.ip.manage mengelola subnet/pemakai,
dan network.ip.check menjalankan ping. Docker server memasang iputils-ping; runtime
server lain harus menyediakan perintah ping. Alamat divalidasi sebagai IPv4 dan
diteruskan sebagai argumen proses, tanpa shell.

API berada di /api/v1/network-inventory/blocks, /blocks/preview, /blocks/{id}/ping,
/ips/{id}/ping, dan /probe-jobs/{id}. Migrasi V224 menambah blok, label alamat,
alokasi manual, serta job/hasil pemeriksaan dengan RLS tenant.

Verifikasi backend memakai wrapper QA repository:

    bash scripts/warehouse/qa.sh server --tests '*IpBlocksIT' --tests '*NetworkInventoryIT' --tests '*Ipv4BlockTest' --no-parallel

Verifikasi frontend:

    cd web
    npm test -- --run src/pages/network-inventory/PublicIpPage.test.tsx src/api/networkInventory.test.ts --maxWorkers=2

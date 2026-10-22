# Manajemen IP dan koneksi pelanggan B2B

## Alur penggunaan

1. Buka **B2B → Vendor Jaringan** (`/network-vendors`). Tambahkan nama provider, kontak, catatan dan status. Vendor aktif tersedia di dropdown mainlink, backup dan IP. Vendor nonaktif yang sudah dipakai tetap dapat dilihat dan dipertahankan.
2. Buka **B2B → Manajemen IP** (`/network-ips`). Daftarkan setiap alamat IPv4 beserta subnet, gateway, vendor dan catatan. Tabel menampilkan **Tersedia** atau **Terpakai**, nama pelanggan dan provider. Cari berdasarkan IP/nama pelanggan atau filter status dan vendor; daftar memakai pagination.
3. Buka **B2B → Client B2B**, pilih Tambah atau Buka client. Isi Pelanggan dan Kunjungan, lalu **Koneksi**: nama PIC, nomor HP, bandwidth mainlink/backup dalam Mbps, infrastruktur sendiri/mitra, last mile, vendor dan ID pelanggan pada provider. Infrastruktur mitra wajib mempunyai vendor mainlink.
4. Pada **IP public**, pilih satu atau beberapa IP tersedia, maksimal 64. Pilihan tetap tersimpan ketika mencari, berpindah halaman atau langkah. Lengkapi subnet/gateway dan maksimal delapan DNS IPv4 yang dipisahkan koma.
5. Pada **Tunnel**, isi dial, username, password baru, remote/local address dan catatan. Password tersimpan disembunyikan; isian kosong mempertahankannya. Pilih **Hapus password tersimpan** untuk menghapus. Tinjau seluruh data sebelum Simpan.

Data jaringan dan alokasi IP berlaku langsung setelah Simpan. Penugasan/target bulanan serta nama/alamat/kontak client tetap mengikuti aturan snapshot bulan berikutnya. Tidak ada data contoh atau kredensial contoh yang dibuat otomatis.

IP hanya dialokasikan kepada satu pelanggan dalam tenant yang sama. Jika dua operator memilih IP yang sama, penyimpanan kedua ditolak dan seluruh perubahan client transaksi tersebut dibatalkan. Lepas IP dari form client untuk mengembalikannya ke daftar tersedia. Hapus client tanpa riwayat visit juga melepas IP. Client bersejarah harus dinonaktifkan sesuai aturan B2B; alokasinya tetap sampai dilepas secara eksplisit.

Alamat/vendor IP yang sedang terpakai tidak dapat diubah dan IP tersebut tidak dapat dihapus. Subnet/gateway/catatan inventaris boleh diperbarui. Subnet/gateway inventaris dan data teknis client merupakan catatan masing-masing; perubahan salah satunya tidak menimpa yang lain. Vendor yang masih dipakai tidak dapat dihapus; nonaktifkan atau pindahkan referensinya dahulu.

Klik nama pelanggan pada tabel Manajemen IP untuk membaca koneksi, alokasi dan data teknisnya. Teknisi NE dapat membuka detail ini tanpa izin kelola client atau akses rekap B2B.

## Izin per role

| Role bawaan | `network.ip.view` | `network.ip.manage` |
| --- | --- | --- |
| Admin | Ya | Ya |
| Manager | Ya | Tidak |
| Teknisi NE | Ya | Tidak |
| Teknisi FO | Tidak | Tidak |

Owner tetap mempunyai akses penuh dalam tenant. Menu dan tombol mengikuti izin saat ini, tanpa pencocokan email/nama akun. Mengubah jaringan melalui client membutuhkan `b2b.client.manage` dan `network.ip.manage`; melihat jaringan membutuhkan `network.ip.view`.

Startup menambah izin jaringan ke role bawaan tenant lama dan baru, termasuk tenant suspended, memakai penanda **NETWORK_IP_V1** sekali per role. Kustomisasi sesudah backfill dipertahankan pada restart berikutnya; assignment pengguna dan role buatan tenant tetap.

## API

Prefix `/api/v1/network-inventory`; tenant dan actor berasal dari sesi. Read membutuhkan `network.ip.view`, command membutuhkan `network.ip.manage`.

| Endpoint | Fungsi |
| --- | --- |
| `GET /vendors?q=&active=&page=&size=` | Cari vendor dan status aktif |
| `GET /vendors/{id}` | Vendor terpilih di luar halaman dropdown |
| `POST /vendors` / `PUT /vendors/{id}` | CRUD vendor dengan review/revisi |
| `DELETE /vendors/{id}?expectedRevision=` | Hapus vendor tanpa referensi |
| `GET /ips?q=&status=&vendorId=&page=&size=` | Inventaris; status `AVAILABLE`/`ALLOCATED` |
| `POST /ips` / `PUT /ips/{id}` | CRUD IPv4 dengan revisi |
| `DELETE /ips/{id}?expectedRevision=` | Hapus IP tersedia |
| `GET /clients/{id}` | Detail jaringan privat (`private, no-store`) |

POST/PUT mewajibkan UUID `Idempotency-Key` dan `expectedRevision` (nol untuk data baru). Retry memakai payload dan key yang sama; perubahan payload atau actor ditolak. Konflik revisi, alamat duplikat, alokasi ganda dan hapus data terpakai menghasilkan 409. IPv6/CIDR, subnet mask tidak berurutan, DNS tidak valid dan vendor baru nonaktif ditolak.

`network` pada POST/PUT `/api/v1/b2b/clients`:

```json
{
  "details": {
    "picName": "", "phone": "",
    "mainBandwidthMbps": 100, "mainInfrastructure": "PARTNER",
    "mainLastMile": "Metro", "mainVendorId": "<UUID vendor>", "mainCustomerId": "",
    "backupBandwidthMbps": 100, "backupVendorId": "<UUID vendor>", "backupCustomerId": "",
    "subnet": "255.255.255.248", "gateway": "157.15.122.99",
    "dns": ["157.15.122.122", "8.8.8.8"],
    "tunnelDial": "", "tunnelUsername": "",
    "tunnelRemoteAddress": "", "tunnelLocalAddress": "", "notes": ""
  },
  "publicIpIds": ["<UUID IP>"],
  "tunnelPassword": null,
  "clearTunnelPassword": false
}
```

Response berisi details, publicIps dan `tunnelPasswordSet`; password tidak pernah dibaca kembali. Password terenkripsi melalui SecretCipher. Canonical command client disimpan sebagai SHA-256 agar plaintext password tidak masuk riwayat retry; retry command lama tanpa network tetap kompatibel. Migrasi V222 memakai FORCE RLS, composite tenant foreign keys dan satu alokasi per `(tenant, IP)`.

## Verifikasi

```sh
scripts/warehouse/qa.sh server --tests '*NetworkInventoryIT' --tests '*TenantDefaultsIT' --tests '*B2BMonthlyIT'
scripts/warehouse/qa.sh web-test networkInventory.test.ts
scripts/warehouse/qa.sh browser network-inventory.spec.ts
scripts/warehouse/qa.sh browser b2b.spec.ts
```

QA menggunakan database/storage terisolasi dan akun sintetis. Browser mencakup dropdown vendor, inventaris, beberapa IP lintas halaman, review yang mempertahankan draft, akses NE, pelepasan IP dan penghapusan password pada desktop/ponsel dengan tema terang/gelap.

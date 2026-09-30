# Penggantian, Pelepasan, dan Pemulihan Aset

Penggantian perangkat tidak membuat perangkat lama menjadi stok tersedia.
Satu transaksi lokal mengonsumsi otorisasi pengganti, memasang aset baru,
menutup assignment/episode lama, memindahkan identitas lama ke custody transit
dengan status dan kondisi `QUARANTINE`, serta menyimpan relasi dan outbox.
Inspeksi/reissue task26 tidak dijalankan oleh alur ini.

## Perintah

- `POST /api/work-orders/{id}/assets/authorize`: `purpose=REPLACE` memerlukan
  WO `MIGRATION`, assignment lama aktif melalui `previousAssignmentId`, serta
  aset serial berbeda yang sudah di-issue dan diakui teknisi. `ownershipMode`
  perangkat baru adalah intent tersendiri, bukan warisan title perangkat lama.
- `POST /api/customers/{id}/assets/replace`: `authorizationId`,
  `expectedRevision`, `expectedAssignmentRevision`, `expectedTitleRevision`,
  `evidenceId`, dan `topology` opsional. Bukti harus signature terautentikasi
  pada WO penggantian dengan objek/digest yang cocok.
- `POST /api/customers/{id}/assets/remove`: `assignmentId`, `workOrderId`,
  `expectedRevision`, `expectedTitleRevision`, dan `evidenceId`. WO harus
  `DISMANTLE`; assignment dan posisi fisik harus masih terpasang.
- `POST /api/customers/{id}/assets/{assignmentId}/relocate`: `workOrderId`,
  `expectedWorkOrderRevision`, `expectedRevision` topologi, dan `topology`.
  Perintah ini hanya mengubah topologi ONU dan menyimpan riwayat/replay milik
  customer. Tidak ada document, receipt, movement, assignment, atau otorisasi
  inventory baru.

Ketiga perintah perubahan memerlukan `Idempotency-Key`. Replay diperiksa
sesudah kewenangan terkini; perubahan payload atau pemakaian ulang resource
dengan key baru ditolak. Request tidak menerima tenant, actor, legal owner,
old/new asset authority, maupun waktu removal dari pemanggil. Scope warehouse
yang dicabut menghasilkan penolakan `NOT_FOUND`, tanpa membocorkan hasil lama.

## Riwayat dan Title

`inventory_asset_removal` dan origin-nya immutable. Aset lama tetap memiliki
ID, serial kanonis, asal receipt, title dan riwayat assignment yang sama.
LOAN tetap `ISP`; SALE yang diterima tetap `CUSTOMER`. View
`inventory_asset_recovery_state` menunjukkan `IN_RECOVERY` setelah pelepasan
fisik dan mempertahankan referensi kewajiban handover, bila ada.

`customer_asset_retirement` menambahkan penanda retirement; snapshot instalasi
asli tidak ditulis ulang. ONU lama tetap ada, berstatus `DISMANTLED` dan memiliki
`retired_at`; ONU pengganti adalah episode baru. Riwayat customer menggabungkan
snapshot instalasi dengan penanda retirement. Referential/append-only guards
menolak hard delete customer/ONU yang masih dirujuk oleh riwayat.

Detach ODP biasa tidak dianggap pelepasan fisik dan tidak memindahkan stok.
Permit pengganti yang kalah terhadap removal disimpan sebagai authorization
retirement immutable; permit tersebut tidak dapat dikonsumsi.

## Revisi Episode

`customer_onu_episode_event` mengikat revisi ke event immutable per tenant/ONU,
assignment, aset, dan customer. Opening dimulai pada revisi0. Perubahan topologi
yang menghasilkan `onu_topology_history` menaikkan revisi episode tepat sekali;
retirement menaikkannya sekali lagi. Detach topologi yang merupakan bagian dari
retirement tidak menghitung kenaikan episode kedua. UPDATE angka revisi tanpa
event ditolak, termasuk skip, penurunan, atau event tambahan tanpa perubahan ONU.

Response retirement memakai revisi dan waktu dari SQL `RETURNING`, bukan snapshot
instalasi lama. Relokasi tetap mengembalikan `revision` topologi dan sekarang juga
`episodeRevision`; keduanya berasal dari row hasil UPDATE. Field tambahan tidak
disisipkan ke replay lama yang memang belum menyimpannya. Snapshot perintah lama
tetap immutable; pembacaan riwayat terkini memakai revisi ONU yang tervalidasi.

Migrasi tidak menulis ulang ONU, telemetry, atau riwayat topologi yang sudah ada.
Data VERIFIED lama memperoleh baseline dengan semantik lama: opening0 atau
retirement1. Riwayat topologi sebelum baseline tetap utuh; event berikutnya
melanjutkan revisi episode dari baseline tersebut. Angka liar yang sudah telanjur
tersimpan tidak dijadikan baseline sah: raw data tetap tersedia, tetapi validated
read/replay ditolak untuk rekonsiliasi. Baris staged/legacy tetap terbaca.
Ini bukan atribusi telemetry berbasis waktu task23.

## Provisioning Setelah Commit

Outbox `fulfillment_asset_outbox` dan delivery terpisah dari transaksi fisik.
Worker mengklaim delivery dengan lease, memanggil adapter tanpa transaksi/lock
inventory, kemudian mencatat `SUCCEEDED` atau `RECONCILIATION_REQUIRED`.
Klaim yang terputus dapat diambil ulang sesudah lease berakhir.

Bridge HTTP provisioning dikonfigurasi server melalui
`ftth.provisioning.asset-adapter-url` dan token opsional
`ftth.provisioning.asset-adapter-token`. Bridge menerima tenant/operation/customer/
WO/old-ONU/new-ONU dari outbox server, dengan `Idempotency-Key=operationId`.
Bridge wajib deduplikasi operation ID dan baru menjawab
`{"operationId":"...","state":"SUCCEEDED"}` setelah perubahan terverifikasi.
Response kosong, operation ID salah, HTTP error, dan transport error tidak
dianggap berhasil. Endpoint yang belum dikonfigurasi menghasilkan rekonsiliasi,
bukan keberhasilan semu. Gunakan HTTPS dan token untuk bridge di luar localhost.

`POST /api/customers/{id}/asset-operations/{operationId}/retry` mengulang delivery
setelah memeriksa actor/assignee/scope terkini. Tidak ada kompensasi stok atau
penghapusan episode akibat kegagalan provisioning. Task23 atribusi telemetry,
RMA, inspection/reissue, UI dan mobile tidak termasuk alur ini.

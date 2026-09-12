# Verifikasi material saat persetujuan WO

Persetujuan `POST /api/work-orders/{id}/approve` membekukan konteks WO dan
memverifikasi pemakaian yang sudah terjadi. Persetujuan **tidak** mengonsumsi,
memindahkan, memotong, mengembalikan, atau melepas stok.

## Snapshot dan pemilik

`WorkOrderApprovalService` menjaga lifecycle, izin terkini, area dan pemisahan
penyetuju dari pembuat submission. Listener fulfillment menyimpan snapshot
immutable sebelum commit: revisi WO, roster aktif, customer/subscription/order,
action, proof hash, plan/mode/reason, usage/revisi/hash/body, revisi dokumen sumber,
tautan visit dan applicability. Hash mencakup seluruh snapshot.

`InventorySettlementApi` dimiliki inventory. Pemilik ini memanggil validasi
task16 yang sama (`warehouse_assert_material_usage` termasuk proyeksi consumed
V175.22), bukan membangun ulang histori atau menyalin pemeriksaan yang lebih lemah.
Pemakaian harus lengkap terhadap plan submitted dan penerimaan teknisi yang sah.
Plan atau usage yang hilang tidak mengizinkan persetujuan.

Efek fulfillment bernama `INVENTORY` sekarang berarti verifikasi settlement
(`SETTLEMENT_VERIFY`), bukan consume. Receipt immutable
`inventory_material_settlement` menunjuk snapshot persetujuan dan usage/revisi
yang dibekukan. Outcome `VERIFIED` mempertahankan residual; `NO_MATERIAL`
memerlukan plan dan usage NONE submitted dengan alasan eksplisit.

## Efek yang berlaku

Standalone WO hanya memiliki verifikasi material dan hasil WO. Tidak ada efek
subscription, provisioning, order atau visit tanpa tautan yang dibekukan.
Subscription memerlukan customer/subscription yang eksplisit dan action layanan;
provisioning juga memerlukan akun BNG yang benar-benar tertaut. Order dan visit
memakai target/revisi snapshot, bukan revisi terbaru yang ditemukan saat apply.

Pemilik customer mengunci fingerprint revisi datanya. Order/visit memakai API
pemilik; FK tenant-composite visit baru menunggu lock WO sehingga tautan baru tidak
dapat menyusup di antara freeze dan commit. Tidak ada repository lintas module.

## Durabilitas dan konflik

Urutan utama: cutover/current authority, WO, snapshot/checkpoint, dokumen inventory,
lalu dimensi stok. Claim outbox memakai transaksi pendek terpisah. Coordinator
yang sudah ada menerapkan efek lokal dan receipt dalam satu transaksi baru;
kegagalan me-rollback semuanya sebelum merekam reconciliation dalam transaksi
pemulihan. Restart atau pengiriman ulang membaca progress/receipt durable.

Replay persetujuan memeriksa izin/aktor dan konteks terkini. WO yang berubah
setelah snapshot tidak boleh membuat settlement kedua. Rejection/resubmit sebelum
persetujuan mempertahankan fakta penggunaan dan membekukan revisi baru ketika
akhirnya disetujui. Perubahan setelah enqueue menghentikan seluruh efek sebelum
apply. Payload legacy tanpa applicability/snapshot, envelope tenant/hash salah,
dan event tidak didukung masuk `REQUIRES_RECONCILIATION`, bukan sukses kosong.

Order redelivery menerima authority fence pemilik yang sudah diperiksa, bukan
membutuhkan principal HTTP sintetis. Efek BNG yang eksplisit memakai entrypoint
MANDATORY: perubahan akun dan enqueue aksi berada dalam transaksi settlement.
Listener subscription setelah commit melihat status yang sudah diterapkan dan
tidak mengulang enqueue; tidak ada koneksi perangkat dalam transaksi ini.

Kabel100000MM yang sudah dipakai82500MM tetap consumed82500MM dan akuntabilitas
teknisi17500MM setelah approve/replay/restart. Penyelesaian residual task18,
deployment serial/task20, ONU, return dan UI/mobile tidak diimplementasikan di sini.

Migrasi V175.23-V175.29 mengikuti manifest forward-only; V175.22 dan predecessor
tetap. Snapshot/receipt FORCE RLS, append-only, memakai FK tenant-composite dan
validator deferred dengan pemeriksaan tenant internal. Endpoint settlement manual
yang lama tetap fail-closed; verifikasi ini hanya berasal dari approval WO.

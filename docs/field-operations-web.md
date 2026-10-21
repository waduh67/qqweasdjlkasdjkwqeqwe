# Antarmuka Operasional Lapangan

## Jalur peran

- Dispatcher membuat dan menugaskan work order melalui **Work Order & Kunjungan → Work Order**.
- Teknisi NE/FO membuka **Work Order & Kunjungan → Work Order Saya** (`/my-work-orders`). Pada workflow REFERENCE, tugas yang ditujukan ke teknisi tetap terlihat tanpa grant area atau gudang pribadi; daftar operator tetap dibatasi akses area.
- **Work Order & Kunjungan → Kunjungan Saya** (`/my-visits`) memuat kunjungan biasa pada penugasan WO, terpisah dari Visit B2B. Teknisi melakukan check-in, tiba di lokasi, check-out, lalu mengirim kunjungan. Tab Riwayat tetap menampilkan kunjungan miliknya sesudah penugasan berubah.
- Pada workflow legacy, penyelia meninjau submission `DONE/PENDING` dari detail work order; server menolak persetujuan oleh pengirim submission yang sama. Pada workflow REFERENCE, teknisi menyelesaikan WO setelah bukti dan material tervalidasi tanpa persetujuan penyelia.
- Teknisi NE/FO mengirim dan merevisi pekerjaan pagi/sore melalui **Daily Report → Laporan Pagi / Sore** (`/daily-reports`); owner/Admin/Manager memantau kelengkapan tim. Aturan dan API ada di [daily-reports.md](daily-reports.md).
- **Dashboard → KPI Teknisi** (`/kpi`) serta tab KPI dashboard menampilkan aktual, target, dan persentase capaian bulanan. Filter tim hanya tersedia bagi pemilik izin `kpi.team.view`. Owner mengatur kalender dan target melalui Setelan Gudang; lihat [operational-kpi-calendar.md](operational-kpi-calendar.md).
- Impor pelanggan memakai **Layanan Pelanggan → Pelanggan** dan hanya menampilkan metadata batch serta laporan aman dari server.

## Batas portal pelanggan

Portal memakai hanya `GET /api/portal/orders` dan `GET /api/portal/orders/{id}`. Status yang ditampilkan adalah `RECEIVED`, `REVIEWING`, `SCHEDULED`, `IN_PROGRESS`, `WAITING_CUSTOMER`, `COMPLETED`, `CANCELLED`, dan `REQUIRES_ATTENTION`.

Portal tidak menampilkan atau meminta koordinat GPS, bukti internal, kontrol gudang, catatan persetujuan, identitas/rute teknisi, atau status work-order internal. Kepemilikan pelanggan ditentukan server dari sesi portal; aplikasi web tidak mengirim ID pelanggan.

## Kontrak operator dan teknisi

Fieldservice memakai endpoint terautentikasi `/api/v1/fieldservice/visits`.
`GET /api/v1/fieldservice/visits` returns `PageResponse` with `scope=SELF`
(default, authenticated technician assignment), or explicit `scope=AREA|ALL`
for dispatcher/approver permissions. Optional `status`, `page`, and `size`
follow the common pagination contract. Filter `history=ACTIVE|HISTORY|ALL` memisahkan
penugasan aktif dan riwayat. Baris memuat purpose `FULFILLMENT|WORK_ORDER`,
kode/judul WO, nama pelanggan/area bila ada, jadwal, state, revision,
dan session timestamps. Nama dan akses berasal dari
server; teknisi hanya melihat penugasan/riwayat miliknya.
Pembuatan visit menerima `orderId`, `workOrderId`, `technicianId`, `plannedAt`, dan
`namespace`, `operationKey`, `payloadHash`, `revision`. Perintah `check-in`,
`on-site`, `check-out`, dan `submit` memakai `decision`/`reason` (hanya check-in)
atau empat field operasi yang sama. Tenant dan actor selalu berasal dari sesi JWT;
`supervisor` tidak pernah diterima dari JSON. Response hanya memuat id, state,
revision, dan keputusan attendance/server receipt.
`GET /api/v1/fieldservice/visits/{id}/work-session` mengembalikan hanya session id,
visit id, dan server-derived start/end/submission timestamps.

Inventory operator reads tersedia di `/api/inventory/warehouses`, `/items`,
`/stock`, `/reservations`, dan `/custody`. Semua response tenant-scoped dan
permission-gated; endpoint tidak mengembalikan entity persistence atau catatan
approval internal. Queue review inventory tersedia di
`GET /api/inventory/approvals/pending` dan hanya mengembalikan pending request
yang tier aktifnya memang menunjuk actor saat ini, tidak termasuk requester atau
custodian.

Completion Proof-of-Work memakai satu request shape untuk
`POST /api/work-orders/{id}/complete`:
`{ resolutionNote, proofRevision, artifacts[] }`. Setiap artifact wajib memuat
`kind`, `revisionId`, `revisionState` (COMMITTED untuk bukti aktif), dan optional
`correctionReason`; server tetap memvalidasi set artifact wajib dan revision.

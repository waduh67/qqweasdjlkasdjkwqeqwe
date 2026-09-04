# Antarmuka Operasional Lapangan

## Jalur peran

- Dispatcher membuat dan menugaskan work order melalui **Lapangan → Work Order**.
- Teknisi bekerja melalui **Lapangan → Tugas Saya**, termasuk mulai kerja, bukti, pembacaan optik, dan submission penyelesaian.
- Penyelia meninjau submission `DONE/PENDING` dari detail work order. Server menolak persetujuan oleh pengirim submission yang sama.
- Impor pelanggan memakai **Layanan Pelanggan → Pelanggan** dan hanya menampilkan metadata batch serta laporan aman dari server.

## Batas portal pelanggan

Portal memakai hanya `GET /api/portal/orders` dan `GET /api/portal/orders/{id}`. Status yang ditampilkan adalah `RECEIVED`, `REVIEWING`, `SCHEDULED`, `IN_PROGRESS`, `WAITING_CUSTOMER`, `COMPLETED`, `CANCELLED`, dan `REQUIRES_ATTENTION`.

Portal tidak menampilkan atau meminta koordinat GPS, bukti internal, kontrol gudang, catatan persetujuan, identitas/rute teknisi, atau status work-order internal. Kepemilikan pelanggan ditentukan server dari sesi portal; aplikasi web tidak mengirim ID pelanggan.

## Kontrak operator dan teknisi

Fieldservice memakai endpoint terautentikasi `/api/v1/fieldservice/visits`.
`GET /api/v1/fieldservice/visits` returns `PageResponse` with `scope=SELF`
(default, authenticated technician assignment), or explicit `scope=AREA|ALL`
for dispatcher/approver permissions. Optional `status`, `page`, and `size`
follow the common pagination contract. Each row contains only visit/work-order/
order IDs, state, revision, work-order schedule, and session timestamps.
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

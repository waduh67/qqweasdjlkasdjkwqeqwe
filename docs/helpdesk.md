# Modul `helpdesk` — tiket keluhan pelanggan ber-SLA

Satu **utas percakapan yang dibaca kedua pihak**: pelanggan melapor dari portal,
operator membalas dari konsol, dan keduanya melihat status yang sama persis. Bila
keluhannya butuh kunjungan, tiketnya **dieskalasi jadi work order** tanpa mengetik
ulang apa pun.

Sebelum modul ini keluhan tak punya tempat: pelanggan menelepon/WA, operator
mencatatnya di kepala sendiri, dan pelanggan tak pernah tahu laporannya sampai di
mana. Yang hilang bukan cuma catatan — juga **bukti waktu**: tak ada yang bisa
menjawab "berapa lama rata-rata kami membalas".

Beda dengan `incident` (modul monitoring): **insiden lahir dari alarm** jaringan dan
berakar pada perangkat; **tiket lahir dari manusia** dan berakar pada pelanggan. Satu
gangguan fiber bisa memunculkan satu insiden + belasan tiket. Keduanya sengaja tak
disatukan.

Boundary Spring Modulith ditegakkan `ModularityTests`. Arah dependency:
`portal → helpdesk` (pintu pelanggan), `helpdesk → workorder` (eskalasi),
`helpdesk → iam` (validasi penanggung jawab), `helpdesk → customer` (nama pelapor),
`inbox → helpdesk` (mendengar event SLA). `helpdesk` **tak pernah** memanggil balik
`portal` maupun `inbox`.

---

## Model domain — `Ticket`

```
Ticket (agregat)
├── identitas   code (TKT-XXXXXXXX, diturunkan dari id) · customerId + customerName (snapshot)
├── isi         category · subject · description (laporan awal)  +  messages[] (utas balasan)
├── penanganan  status · priority · assigneeId + assigneeName (snapshot)
├── janji waktu responseDueAt? · resolutionDueAt · firstResponseAt? · slaAlertedAt?
├── eskalasi    workOrderId? · workOrderCode? (snapshot — kode WO tak pernah berubah)
└── waktu       openedAt · lastActivityAt · resolvedAt? · closedAt?
```

Nama pelanggan & nama operator **disalin** saat dipakai, supaya antrean terbaca tanpa
join lintas-modul per baris. Pesan baru ditahan sebagai `pendingMessages` lalu ditulis
bersama agregatnya — pola yang sama dengan timeline insiden & work order.

`TicketCategory`: `KONEKSI_PUTUS` · `KONEKSI_LAMBAT` · `PERANGKAT` · `TAGIHAN` ·
`LAINNYA` · **`GANTI_PAKET`**. Yang terakhir bukan keluhan melainkan **permintaan**
(pelanggan ingin naik/turun paket, dikirim dari menu Profil portal). Ia ikut jadi
tiket karena butuh perkakas yang sama persis — penanggung jawab, tenggat, utas
balasan, eskalasi bila perlu ganti perangkat — tapi berkategori sendiri agar bisa
disaring & dilaporkan terpisah dari gangguan.

Prioritas awal **selalu `NORMAL`**; pelanggan tak memilihnya sendiri. Kalau pelapor
yang menentukan, dalam sebulan semua tiket jadi `URGENT` dan prioritas berhenti
membedakan apa pun.

---

## Alur status

```
        ┌──────────────── pelanggan membalas ───────────────┐
        ▼                                                   │
      OPEN ──operator membalas──▶ IN_PROGRESS ──selesai──▶ RESOLVED ──┐
        │                              │                              │
        └──────────────────────────────┴──────────────────────────────┴──▶ CLOSED
```

| Dari | Boleh ke |
|---|---|
| `OPEN` | `IN_PROGRESS` · `RESOLVED` · `CLOSED` |
| `IN_PROGRESS` | `OPEN` · `RESOLVED` · `CLOSED` |
| `RESOLVED` | `OPEN` · `CLOSED` |
| `CLOSED` | — (terminal) |

`RESOLVED` artinya **operator menyatakan selesai, pelanggan belum mengonfirmasi**.
Membalas dari portal membuka tiketnya kembali ("masih rusak") — dan jam penyelesaian
dimulai ulang dari titik itu, bukan diwarisi dari ronde yang sudah lewat. `CLOSED`
terminal: utasnya tak bisa dibalas lagi. Transisi ilegal ditolak di **domain**
(`ConflictException`), bukan di controller.

Penugasan (`assignTo`) sengaja **tidak** mengubah status: memegang tiket belum berarti
mengerjakannya, dan "sedang ditangani" yang muncul otomatis saat penugasan membuat
status itu berhenti berarti apa-apa. Ia juga tak menulis pesan ke utas — pembagian
kerja internal bukan urusan pelanggan; jejaknya masuk ke **audit**.

---

## Dua jam, bukan satu

Yang dirasakan pelanggan ada dua hal berbeda, jadi dihitung terpisah:

- **`responseDueAt`** — tenggat **dibalas**. Hidup hanya selama bola di tangan
  operator: dikosongkan saat operator membalas, dinyalakan lagi saat pelanggan
  membalas. Sengaja bukan "tenggat balasan pertama" — pelanggan yang membalas lagi
  setelah dijawab kembali menunggu, dan menghitung SLA sekali di awal saja membuat
  tiket yang digantung di balasan kedua terlihat sehat.
- **`resolutionDueAt`** — tenggat **dinyatakan selesai**, dihitung dari tiket dibuka.

Kebijakannya (`TicketSla`, konstanta sistem):

| Prioritas | Balas dalam | Selesai dalam |
|---|---|---|
| `URGENT` | 30 menit | 4 jam |
| `HIGH` | 1 jam | 8 jam |
| `NORMAL` | 4 jam | 24 jam |
| `LOW` | 8 jam | 72 jam |

**Jam dinding 24/7, bukan jam kerja.** Gangguan internet tak mengenal jam kantor — SLA
yang berhenti berdetak jam 5 sore akan memberi lampu hijau palsu pada keluhan yang
masuk Jumat malam dan baru disentuh Senin.

Angkanya **belum bisa disetel per tenant**, dan itu keputusan sadar: SLA yang bisa
diatur sendiri cenderung dilonggarkan sampai tak pernah terlewat, dan angka yang tak
pernah terlewat tak memberi tahu apa-apa. Kalau nanti benar-benar perlu per-tenant,
tempat berubahnya cuma `TicketSla`.

Menaikkan prioritas **menggeser tenggat balasan secara relatif** (titik mulainya
dipertahankan), bukan menghitung ulang dari sekarang — kalau tidak, menaikkan ke
`URGENT` justru *memperpanjang* waktu tersisa. Tenggat penyelesaian dihitung ulang
dari `openedAt`, sehingga tiket lama yang dinaikkan langsung terlihat telat kalau
memang sudah menganggur.

`responseOverdue` / `resolutionOverdue` **dihitung di server** dan ikut di view: jam
browser operator bisa meleset, dan "lewat SLA" adalah angka yang dilaporkan ke
manajemen — ia harus punya satu sumber kebenaran. Satu penanda waktu dipakai untuk
seluruh halaman, supaya dua tiket bertenggat sama tak tampil beda status di layar yang
sama.

---

## Penjaga SLA

```
HelpdeskSlaScheduler  @Scheduled(fixedDelayString = "${ftth.helpdesk.sla-scan-interval:PT5M}")
  └─ tiap tenant aktif → TenantContext.runAs → HelpdeskSlaSweeper.run()   (REQUIRES_NEW)
       ├─ findOverdue(now, onlyUnalerted = true)
       ├─ markSlaAlerted(now) + save
       └─ publish TicketSlaBreached  →  inbox (kotak masuk operator)
```

Kenapa perlu penyapu berkala, padahal "lewat SLA" bisa dihitung saat halaman dibuka?
Karena **tiket yang paling mungkin terlewat justru yang tak pernah dibuka siapa pun**.
Antrean yang hanya jujur ketika dilihat adalah antrean yang membiarkan keluhan Sabtu
malam mengendap sampai Senin.

Satu pelanggaran = **satu teriakan**, bukan satu tiap lima menit sampai seseorang
menyerah dan mematikan notifikasinya. Penanda `slaAlertedAt` yang jadi remnya, dan ia
**dibersihkan lagi begitu tiketnya bergerak** (dibalas, dibuka ulang, prioritasnya
diubah) sehingga ronde berikutnya boleh berteriak lagi.

Satu tiket menghasilkan satu event walau kedua tenggatnya lewat sekaligus; **tenggat
balasan menang** — pelanggan yang belum dijawab sama sekali lebih mendesak daripada
yang sudah dijawab tapi belum tuntas (`overdueKind` = `RESPONSE` / `RESOLUTION`).

`TicketSlaBreached` sengaja **tidak** dikirim ke pelanggan: pelanggan tak perlu diberi
tahu bahwa kita melanggar janji sendiri; yang perlu tahu adalah orang yang bisa
mengerjakannya.

---

## Dua pintu, satu agregat

Pelanggan dan operator memakai **agregat yang sama**; yang berbeda hanya pintu
masuknya — jadi status yang dibaca pelanggan mustahil menyimpang dari yang dikerjakan
operator.

| | Operator (konsol) | Pelanggan (portal) |
|---|---|---|
| Pintu | `TicketService` (`TicketQuery` + `ManageTicketUseCase`) | `HelpdeskApiService` (kontrak publik `HelpdeskApi`) |
| Endpoint | `/api/helpdesk/tickets/**` (JWT operator) | `/api/portal/me/tickets/**` (JWT portal) |
| Cakupan | seluruh tenant | **hanya miliknya** — tiket orang lain dijawab `404`, bukan `403` |
| Nama staf | apa adanya | disamarkan jadi **"Tim dukungan"** |
| Enum | tipe helpdesk | diratakan jadi `String` |
| Rem | — | maksimal **5 laporan terbuka** per pelanggan |

Batas 5 laporan terbuka menahan portal berubah jadi corong spam yang menenggelamkan
antrean operator; pesannya mengarahkan pelanggan membalas laporan yang sudah ada
supaya penanganannya tak terpecah.

Menjawab "tidak berhak" untuk tiket orang lain sama saja dengan **membenarkan bahwa
tiket itu ada** — karena itu pesannya identik dengan tiket yang memang tak ada.

---

## Eskalasi ke work order

```
POST /api/helpdesk/tickets/{id}/escalate
  └─ WorkorderApi.raiseRepair(RaiseRepairCommand)
       title       = "[TKT-XXXXXXXX] {judul tiket}"     ← teknisi bisa merunut balik
       description = laporan pelanggan + catatan operator
       priority    = pilihan operator, atau warisan prioritas tiket
  └─ ticket.attachWorkOrder(id, code) → status naik ke IN_PROGRESS + jejak SYSTEM di utas
```

**Sekali saja**: tiket yang sudah punya WO harus ditangani lewat WO itu, bukan dengan
menerbitkan WO kedua untuk keluhan yang sama (`ConflictException`). Kode WO ikut tampil
di portal, jadi pelanggan tahu keluhannya sudah dijadwalkan ke teknisi.

---

## Laporan kinerja

`HelpdeskReportApi.supportReport(from, to)` (dipakai halaman *Laporan*) menghitung dari
tiket tersimpan — murni baca, tak menyentuh agregat lain. Dua himpunan diambil
**terpisah** (yang **masuk** di rentang dan yang **tuntas** di rentang) karena satu
tiket boleh melewati batas periode.

| Angka | Arti |
|---|---|
| `openedCount` / `resolvedCount` | masuk vs tuntas di rentang |
| `openedByCategory` | komposisi keluhan |
| `avgFirstResponseHours` | rata-rata sampai balasan pertama; **null** bila belum ada yang dijawab (bukan 0) |
| `avgResolutionHours` | rata-rata sampai dinyatakan selesai |
| `responseBreachedCount` | dijawab melewati tenggat, **atau** belum dijawab padahal tenggatnya lewat |
| `resolutionBreachedCount` | tuntas melewati tenggat penyelesaian |
| `slaCompliancePercent` | `(tuntas − langgar) / tuntas × 100`; null bila tak ada yang tuntas |

---

## Skema & indeks

| Tabel | Isi |
|---|---|
| `helpdesk_ticket` | agregat tiket (RLS per tenant) |
| `helpdesk_ticket_message` | utas percakapan (`CUSTOMER` / `OPERATOR` / `SYSTEM`) |

Migrasi: `V81` (tabel), `V83` (penanggung jawab + SLA), `V85` (kategori `GANTI_PAKET`).

```sql
-- antrean "punya saya"
ix_ticket_assignee  (tenant_id, assignee_id, last_activity_at DESC)
-- sapuan penjaga SLA; parsial supaya indeksnya hanya memuat tiket yang masih hidup
ix_ticket_sla_due   (tenant_id, resolution_due_at) WHERE status <> 'CLOSED'
```

Backfill `V83` mematikan RLS **sementara** saat `UPDATE`: Flyway berjalan sebagai role
`NOBYPASSRLS` tanpa GUC `app.tenant_id`, jadi tanpa itu `UPDATE`-nya menyentuh nol baris
sementara `SET NOT NULL` di bawahnya melihat semua baris (DDL tak tunduk RLS) — dan
migrasinya gagal justru di database yang sudah berisi tiket. Pola sama `V29/V39/V44/V52/V75`.

---

## Konfigurasi

| Properti | Bawaan | Guna |
|---|---|---|
| `ftth.helpdesk.sla-scan-interval` | `PT5M` | selang sapuan penjaga SLA |

---

## API & izin

| Endpoint | Izin |
|---|---|
| `GET /api/helpdesk/tickets` · `/summary` · `/{id}` | `helpdesk.ticket.view` |
| `POST /api/helpdesk/tickets/{id}/replies` | `helpdesk.ticket.reply` |
| `POST /api/helpdesk/tickets/{id}/status` · `/assignee` · `/priority` · `/escalate` | `helpdesk.ticket.manage` |
| `GET/POST /api/portal/me/tickets/**` | — (JWT portal; cakupan dari principal) |

Penyaring antrean: `query` (kode/judul/nama pelanggan), `status`, `category`,
`customerId`, `assigneeId`, `unassigned`, `overdue`. `unassigned` menang atas
`assigneeId` bila keduanya terisi; `overdue` hanya menyaring saat `true` — "belum lewat
SLA" tak pernah jadi tampilan tersendiri. Urutan bawaan `lastActivityAt DESC`: antrean
bantuan dibaca dari percakapan terakhir.

Penugasan divalidasi ke `iam` — id sembarang akan menempelkan nama kosong di antrean,
dan pengguna nonaktif melahirkan tiket "punya pemilik" yang tak pernah dibuka siapa pun.
`userId` kosong = tiket dikembalikan ke antrean bersama.

Izin `helpdesk.ticket.*` non-platform, jadi otomatis masuk role **Tenant Admin**
(disinkron idempoten oleh `AdminProvisioner`).

---

## Kaitan lintas-modul

```
portal ──HelpdeskApi (scoped ke pelanggan yang login)──▶ helpdesk ──RaiseRepairCommand──▶ workorder
                                                            │  │
                              iam ◀──findUser (validasi PJ)──┘  └──TicketSlaBreached──▶ inbox
                                                            │
                          customer ◀──findCustomer (nama pelapor)
```

`helpdesk` memiliki tabel tiketnya sepenuhnya; `portal` hanya merangkai kontrak
publiknya. Batas ditegakkan `ModularityTests`.

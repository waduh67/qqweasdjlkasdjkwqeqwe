# Modul `workorder` — pekerjaan lapangan & buktinya

Satu **perintah kerja untuk satu kunjungan**: pasang baru, perbaikan, pindah alamat,
bongkar, atau kunjungan preventif. Ia yang menjawab pertanyaan yang tak bisa dijawab
peta jaringan maupun tagihan: *siapa berangkat ke mana, kapan, dan apa buktinya sudah
dikerjakan*.

Modul ini bukan sekadar daftar tugas. Ia **tulang punggung akuntabilitas**: penyelesaian
WO PSB-lah yang menghidupkan langganan (dan memulai tagihan), penyelesaian WO DISMANTLE
yang mengakhirinya. Selama pekerjaan lapangan cuma dicatat di grup WhatsApp, tanggal
aktivasi selalu jadi debat — dan yang kalah selalu pelanggan atau kas.

Boundary Spring Modulith ditegakkan `ModularityTests`. Arah dependency:
`workorder → customer` (nama & lokasi pelanggan, aktivasi/terminasi langganan),
`workorder → iam` (validasi & nama teknisi); ke arah sebaliknya `helpdesk`, `onboarding`,
`monitoring`, `reporting`, dan `subscriber360` memanggil kontrak publik `WorkorderApi`.
`workorder` **tak pernah** memanggil balik pemanggilnya.

---

## Model domain — `WorkOrder`

```
WorkOrder (agregat)
├── identitas   code (WO-XXXXXXXX, diturunkan dari id) · type · priority
├── isi         title · description · scheduledAt
├── tautan      customerId? · subscriptionId? · incidentId? · areaId?   (id polos, tanpa FK)
├── penugasan   assignees[] (tim datar) · assignedAt
├── jalannya    status · startedAt · completedAt · resolutionNote? · cancelReason?
├── pengukuran  rxBeforeDbm? · rxAfterDbm?            (redaman optik, dBm)
├── kurasi      approvalStatus? · approvedBy? · approvedAt? · approvalNote?
└── jejak       WorkOrderEvent[] (timeline, ditulis bersama agregatnya)

WorkOrderEvidence (agregat kecil, per foto)   ─┐  byte di object storage,
WorkOrderSignature (agregat kecil, satu per WO)─┘  metadata di DB
```

Bukti foto & tanda tangan sengaja **bukan** bagian dari agregat `WorkOrder`: memuat satu
work order tak boleh ikut menyeret belasan lampiran yang jarang dibaca. Keduanya menunjuk
`workOrderId` polos, persis seperti timeline.

`WorkOrderType`: `PSB` (pasang baru) · `REPAIR` (perbaikan) · `MIGRATION` (pindah/ganti
perangkat) · `DISMANTLE` (bongkar) · `PREVENTIVE` (kunjungan sebelum rusak). Tipe bukan
label kosmetik — ia yang menentukan apakah penyelesaian WO menggerakkan langganan.

`createdBy` boleh **null** = dibuat sistem. Satu-satunya pembuat non-manusia hari ini
adalah pemeliharaan prediktif (lihat di bawah); memaksakan pembuat manusia di situ hanya
akan melahirkan "pengguna sistem" palsu yang mengotori audit.

---

## Alur status

```
   DRAFT ──assign──▶ ASSIGNED ──start──▶ IN_PROGRESS ──complete──▶ DONE
     │                   │                    ▲                      │
     └───────────────────┴────────cancel──────┴──────────────────────┤ (sebelum DONE)
                                              │                      │
                                        reject│                      │approve
                                              └──────────────────────┘
```

| Dari | Boleh ke |
|---|---|
| `DRAFT` | `ASSIGNED` (via `assign`) · `CANCELLED` |
| `ASSIGNED` | `IN_PROGRESS` (via `start`) · `CANCELLED` |
| `IN_PROGRESS` | `DONE` (via `complete`) · `CANCELLED` |
| `DONE` | `IN_PROGRESS` (via `reject`) — selain itu terminal |
| `CANCELLED` | — (terminal) |

Aturan yang ditegakkan di **domain** (`ConflictException`), bukan di controller:

- **`start` menuntut penugasan lebih dulu.** WO yang "sedang dikerjakan" tanpa ada yang
  ditugaskan adalah pekerjaan tanpa penanggung jawab; kalau ada masalah di rumah
  pelanggan, tak ada nama untuk ditanya.
- **`cancel` tak berlaku untuk WO yang sudah selesai.** Pekerjaan yang sudah terjadi tak
  bisa dibatalkan — yang bisa dilakukan penyelia adalah **menolaknya**.
- **`start` idempoten** (mulai dua kali = no-op), begitu pula `cancel`. Teknisi di
  lapangan menekan tombol dua kali karena sinyal lambat, bukan karena ingin dua kejadian.
- **Menghapus hanya boleh saat `DRAFT`.** Begitu WO ditugaskan ia sudah punya jejak
  (roster, timeline, mungkin bukti); membuangnya diam-diam menghapus riwayat orang lain.
  Sisanya dibatalkan — batal tetap terbaca, hilang tidak.

Roster teknisi adalah **tim datar**: tak ada konsep ketua, siapa pun anggota boleh
memulai & menyelesaikan, dan WO-nya muncul di "WO saya" tiap anggota. `assign` mengganti
roster **utuh** (bukan menambah) — dispatcher memikirkan "siapa yang berangkat", bukan
"siapa yang ditambahkan".

---

## Kurasi hasil kerja (approval)

```
complete()  →  approvalStatus = PENDING       (antrean penyelia)
                 ├─ approve()  → APPROVED     (selesai, tutup buku)
                 └─ reject(alasan)  → REJECTED + status kembali IN_PROGRESS
                                       └─ complete() lagi → PENDING lagi
```

"Selesai" menurut teknisi dan "selesai" menurut penyelia bukan hal yang sama, jadi
keduanya dicatat terpisah: `status` menyimpan kenyataan lapangan, `approvalStatus`
menyimpan penilaian atasnya.

Penolakan **wajib beralasan** dan **menghapus `completedAt`**: WO yang dibuka kembali
tak boleh terus terhitung sebagai penyelesaian di laporan hanya karena pernah diklaim
selesai. Menyelesaikannya lagi mereset keputusan lama (`approvedBy`/`approvedAt`/
`approvalNote` dikosongkan) supaya antrean tak menampilkan penolakan basi di samping
pekerjaan yang sudah diperbaiki.

`approvalStatus` **null** berarti "belum pernah selesai" — bukan "belum disetujui".
Migrasi `V16` menandai penyelesaian lama sebagai `APPROVED` (grandfather) agar antrean
persetujuan hanya berisi pekerjaan baru; kalau tidak, hari pertama fitur ini menyala
penyelia akan disambut ratusan WO lama yang mustahil dinilai ulang.

---

## Siapa boleh menyentuh WO orang lain

Ada dua peran yang sangat berbeda memakai endpoint yang sama:

| | Dispatcher / penyelia | Teknisi lapangan |
|---|---|---|
| Izin | `workorder.order.update` / `.close` / `.assign` / `.evidence.manage` | hanya `workorder.order.field` |
| Cakupan | **semua** WO tenant | **hanya WO yang ditugaskan ke dirinya** |

Penegakannya di service (`requireFieldAccess`), bukan cuma di `@PreAuthorize`: anotasi
hanya tahu "boleh menyentuh work order", ia tak tahu *work order yang mana*. Teknisi yang
membuka WO tetangganya lewat id akan ditolak `AccessDeniedException` — dan ini penting
justru karena aplikasi teknisi nanti berjalan di ponsel milik orang, bukan di jaringan
kantor.

Platform admin lolos otomatis lewat `hasPermission` (wildcard), jadi tak perlu
dikecualikan satu per satu.

---

## Bukti pengerjaan — foto & tanda tangan

```
POST /api/work-orders/{id}/evidence      (multipart)
   1. validasi   image/* · ≤ 15 MB · WO tidak DRAFT & tidak CANCELLED
   2. byte  ──▶ object storage  (MinIO/S3)   key: <tenant>/wo/<woId>/evidence/<id>
   3. metadata ──▶ DB (wo_evidence)          dalam transaksi
```

**Byte dulu, metadata belakangan.** Kalau simpan metadata gagal, yang tersisa adalah
objek yatim di storage — tak berbahaya dan bisa direkonsiliasi. Urutan sebaliknya
menghasilkan baris DB yang menjanjikan foto yang tak pernah ada: galeri yang bolong
justru saat dijadikan bukti sengketa.

Konten **diproksi server** lewat endpoint terautentikasi, bukan presigned URL. Gating
tenant & izin jadi terpusat di satu tempat; presigned URL yang bocor di grup chat berlaku
untuk siapa pun yang memegangnya. Kunci objek berprefiks `tenantId` sebagai lapis
pertahanan kedua — bahkan kalau suatu saat bucket dibaca langsung, tata letaknya sudah
terpisah per tenant.

Bukti hanya boleh dilampirkan pada WO yang **benar-benar dikerjakan**: `DRAFT` ditolak
(belum ada yang berangkat), `CANCELLED` ditolak (tak ada pekerjaan untuk dibuktikan).
Tanda tangan lebih ketat lagi — hanya `IN_PROGRESS` atau `DONE`, karena ia bukti
serah-terima, dan **satu per WO**: yang baru mengganti yang lama seutuhnya (baris DB +
objek storage) supaya tak ada dua "tanda tangan asli".

`EvidenceKind`: `BEFORE` · `AFTER` · `LOCATION` · `SERIAL` · `OTHER`. Geotag (`latitude`/
`longitude`) & `capturedAt` datang dari kamera teknisi, opsional — dipakai memverifikasi
foto benar diambil di rumah pelanggan, bukan di warung kopi.

Batas 15 MB per berkas sepadan dengan foto ponsel apa adanya; menaikkannya berarti
membiarkan satu WO menyandera bandwidth teknisi lain yang sedang mengunggah dari lapangan.

---

## Redaman optik sebagai bukti mutu

`rxBeforeDbm` / `rxAfterDbm` (dBm) direkam teknisi sebelum & sesudah bekerja. Selisihnya
adalah **bukti kuantitatif** bahwa perbaikan benar-benar memperbaiki sesuatu — angka yang
tak bisa didebat, tak seperti "sudah saya cek, bagus kok".

Rentang wajar `−40.0 … 0.0` dBm ditegakkan di domain: redaman ONU GPON selalu negatif,
jadi nilai di luar itu pasti salah ketik atau salah alat, dan angka salah lebih berbahaya
daripada tak ada angka. Kedua nilai opsional (boleh diisi bertahap) dan **boleh direkam
setelah WO selesai** — teknisi kerap baru sempat mengetik saat sudah di jalan pulang.

---

## Pemeliharaan preventif yang lahir sendiri

```
monitoring  ──OpticalDegradationDetected──▶  PreventiveMaintenanceListener   (AFTER_COMMIT)
                                                └─ TenantContext.runAs(event.tenantId)
                                                     └─ PreventiveMaintenanceService  (REQUIRES_NEW)
                                                          ├─ ONU → pelanggan lewat CustomerApi
                                                          ├─ sudah ada WO preventif terbuka? → berhenti
                                                          └─ WorkOrder.open(PREVENTIVE, HIGH, createdBy = null)
```

Pemindai prediktif melihat redaman sebuah ONU memburuk beberapa hari berturut-turut dan
menerbitkan WO **sebelum** pelanggan menelepon. Prioritasnya `HIGH` meski belum ada
gangguan: seluruh gunanya adalah dikerjakan sebelum berkembang jadi insiden.

Tiga keputusan yang menjaga ini tak jadi mesin spam:

- **AFTER_COMMIT** — WO hanya lahir dari sinyal yang benar-benar ter-commit, bukan dari
  pemindaian yang justru di-rollback.
- **`REQUIRES_NEW`** — pada fase AFTER_COMMIT transaksi penerbit sudah selesai tapi
  sinkronisasinya masih aktif; `REQUIRED` akan ikut transaksi mati itu dan `INSERT`-nya
  **tak pernah ter-commit**. Pola sama dengan `IncidentReconciler`.
- **Idempoten per pelanggan** — satu pelanggan cukup satu kunjungan preventif terbuka.
  Pemindaian berjalan berulang; tanpa rem ini, satu ONU yang memburuk pelan-pelan akan
  menerbitkan WO baru tiap ronde sampai antreannya tak terbaca lagi.

ONU yang tak terpetakan ke pelanggan (perangkat liar/uji) tak menghasilkan apa-apa — tak
ada rumah untuk dikunjungi. Tenant context dipasang **dari event**, bukan dari thread,
karena penerbitnya berjalan tanpa pengguna.

---

## Kontrak publik `WorkorderApi`

Yang dipakai module lain, sengaja tipis dan **rata** (tak membocorkan enum internal
maupun lifecycle):

| Operasi | Pemakai | Guna |
|---|---|---|
| `raisePsb(...)` | `onboarding` (wizard PSB ekspres) | pelanggan + langganan baru langsung punya jadwal pemasangan |
| `raiseRepair(...)` | `helpdesk` (eskalasi tiket) | keluhan yang butuh kunjungan jadi WO, tanpa ketik ulang |
| `openPsbByCustomer()` | `monitoring` (auto-provisioning ONU) | ONU yang baru muncul dicocokkan ke pemasangan yang menunggu |
| `fieldOpsReport(from, to)` | `reporting` | rekap kerja lapangan satu periode |
| pencarian ber-`customerId` | `subscriber360` | riwayat pekerjaan di satu layar pelanggan |

`openPsbByCustomer()` memilih WO **terjadwal paling awal** bila satu pelanggan punya
beberapa order terbuka — yang paling dekat waktunya paling mungkin jadi milik ONU yang
baru menyala.

`raiseRepair` sengaja **tanpa area**: keluhan datang dari meja bantuan yang tak tahu peta,
penempatannya urusan dispatcher.

---

## Laporan kerja lapangan

`FieldOpsReport` dihitung dari WO yang **selesai di rentang** (`completed_at`), murni baca.

| Angka | Arti |
|---|---|
| `completedCount` · `completedByType` | volume & komposisi pekerjaan |
| `avgResolutionHours` | rata-rata dibuat → selesai |
| `avgRepairResolutionHours` | **MTTR** — hanya tipe `REPAIR`; dicampur PSB terjadwal, angka ini kehilangan artinya |
| `avgResponseHours` | rata-rata dibuat → mulai dikerjakan |
| `technicians[]` | per teknisi: jumlah tuntas + rata-rata jamnya |

Satu WO tim dibukukan ke **semua** anggota roster, bukan dibagi pecahan. Bagi penyelia,
"siapa saja yang ikut menuntaskan berapa" lebih berguna daripada 0,5 pekerjaan yang tak
pernah benar-benar setengah. Konsekuensinya jumlah kolom teknisi bisa melebihi
`completedCount` — itu memang disengaja, bukan salah hitung.

Rata-rata mengembalikan **null** (bukan 0) bila tak ada yang bisa dihitung, dan baris yang
titik akhirnya belum ada (mis. selesai tanpa pernah ditandai "mulai") dilewati — 0 jam
akan dibaca sebagai "sangat cepat", padahal artinya "tak tahu".

---

## Skema & indeks

| Tabel | Isi |
|---|---|
| `work_order` | agregat WO (RLS per tenant) |
| `work_order_assignee` | roster teknisi (satu baris = satu teknisi pada satu WO) |
| `wo_event` | timeline (`CREATED`/`UPDATED`/`ASSIGNED`/`STARTED`/`COMPLETED`/`CANCELLED`/`APPROVED`/`REJECTED`) |
| `wo_evidence` | metadata foto bukti + `storage_key` |
| `wo_signature` | tanda tangan serah-terima, unik per WO |

Migrasi: `V6` (WO + timeline), `V7` (bukti & tanda tangan), `V8` (`created_by` nullable —
WO buatan sistem), `V15` (redaman optik), `V16` (approval + grandfather), `V41` (tautan
langganan), `V46` (roster banyak teknisi).

```sql
uq_work_order_code            (tenant_id, code)                     -- kode manusiawi unik per tenant
ix_work_order_tenant_status   (tenant_id, status, created_at DESC)  -- antrean dispatcher
ix_wo_assignee_technician     (tenant_id, technician_id)            -- papan "WO saya"
ix_work_order_customer        (tenant_id, customer_id) WHERE customer_id IS NOT NULL
ix_work_order_incident        (tenant_id, incident_id) WHERE incident_id IS NOT NULL
ix_work_order_approval_pending(tenant_id, completed_at) WHERE approval_status = 'PENDING'
uq_wo_signature_work_order    (tenant_id, work_order_id)            -- satu tanda tangan per WO
```

`V46` memindahkan penugasan dari kolom tunggal `assigned_to` ke tabel penghubung; backfill-nya
mematikan RLS `work_order` **sementara** karena Flyway berjalan sebagai role `NOBYPASSRLS`
tanpa GUC `app.tenant_id` — tanpa itu `SELECT`-nya melihat nol baris dan roster lama hilang
diam-diam di database yang justru sudah berisi data. Tabel barunya di-`INSERT` **sebelum**
policy-nya dipasang. Pola sama `V29/V39/V44/V52/V75`.

---

## Konfigurasi

Object storage dipakai bersama dengan gambar QRIS billing:

| Properti | Bawaan | Guna |
|---|---|---|
| `ftth.storage.endpoint` | `http://localhost:9000` | alamat MinIO/S3 |
| `ftth.storage.bucket` | `ftth-evidence` | bucket berkas biner |
| `ftth.storage.region` | `us-east-1` | region klien S3 |
| `ftth.storage.access-key` · `.secret-key` | `ftth` · `ftthminio` | kredensial (ganti di produksi) |
| `ftth.storage.path-style-access` | `true` | MinIO tak mendukung virtual-host style |

---

## API & izin

| Endpoint | Izin |
|---|---|
| `GET /api/work-orders` · `/{id}` | `workorder.order.view` |
| `GET /api/work-orders/mine` | `workorder.order.view` **atau** `workorder.order.field` |
| `GET /api/work-orders/dashboard` | `workorder.dashboard.view` |
| `POST /api/work-orders` | `workorder.order.create` |
| `PUT /api/work-orders/{id}` · `DELETE` | `workorder.order.update` |
| `POST /api/work-orders/{id}/assign` | `workorder.order.assign` |
| `POST /api/work-orders/{id}/start` · `/optical` | `workorder.order.update` **atau** `workorder.order.field` |
| `POST /api/work-orders/{id}/complete` | `workorder.order.close` **atau** `workorder.order.field` |
| `POST /api/work-orders/{id}/cancel` | `workorder.order.close` |
| `POST /api/work-orders/{id}/approve` · `/reject` | `workorder.order.approve` |
| `GET /api/work-orders/{id}/evidence` · `/evidence/{eid}/content` · `/signature` · `/signature/content` | `workorder.evidence.view` |
| `POST/DELETE .../evidence` · `.../signature` | `workorder.evidence.manage` **atau** `workorder.order.field` |

Pasangan izin "dispatcher **atau** field" itulah yang membuat satu endpoint melayani dua
peran; cakupannya dipersempit di service seperti dijelaskan di atas.

Penyaring daftar: `query`, `type`, `status`, `assignedTo`, `approvalStatus`, `customerId`.
Teknisi & penyetuju sama-sama pengguna `iam`, jadi namanya diresolusi **sekali-batch** per
halaman (bukan per baris) — daftar 50 WO tak boleh berubah jadi 50 panggilan lintas-modul.

Izin `workorder.*` non-platform, jadi otomatis masuk role **Tenant Admin** (disinkron
idempoten oleh `AdminProvisioner`).

---

## Kaitan lintas-modul

```
onboarding ──raisePsb──┐                       ┌──activateForInstallation (PSB selesai)──▶ customer
helpdesk ──raiseRepair─┼──▶  workorder  ───────┤
monitoring ──openPsbByCustomer─┘   │           └──terminateForDismantle (DISMANTLE selesai)─▶ customer
                                   │
        iam ◀──findUser / usersByIds (validasi & nama teknisi)
                                   │
    monitoring ──OpticalDegradationDetected──▶ (WO preventif)
                                   │
                                   └──WorkOrderAssigned──▶ (titik kait notifikasi aplikasi teknisi)
```

Aktivasi/terminasi langganan dipanggil **setelah** WO tersimpan dan bersifat **idempoten**
di sisi `customer` (no-op bila status langganan tak sesuai) — menyelesaikan ulang WO yang
sempat ditolak penyelia tak menggeser tanggal aktivasi yang sudah tercatat, dan tanggal
itulah yang jadi dasar tagihan prorata.

`WorkOrderAssigned` terbit AFTER_COMMIT dan hari ini hanya dicatat ke log: ia titik kait
untuk push aplikasi teknisi (Compose Multiplatform, menyusul paling akhir). Dispatcher
notifikasi sungguhan tinggal menambah listener sejenis **tanpa menyentuh module ini**.

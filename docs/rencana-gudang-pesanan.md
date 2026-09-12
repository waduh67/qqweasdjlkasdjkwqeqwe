# Rencana Kerja — Manajemen Gudang (poin 3) & Manajemen Pesanan (poin 4)

Status dokumen: **rencana, belum dieksekusi**. Disusun 2026-09-12 dari hasil audit
kode penuh pada modul `inventory`, `workorder`, `fieldservice`, `order`,
`fulfillment`, dan `onboarding`.

Sumber kebutuhan: notulen diskusi 1/9/2026 poin 3 & 4, plus arahan tambahan:

> "jd disini kan ada work order tuh, jd sumber barang-nya itu sumbernya dari
> gudang, misal ada wo buat pemasangan, kan dia bawa kabel, dll dari gudang tuh,
> dan harus detail juga."

---

## 1. Temuan pokok — kenapa ini bukan "tinggal nyambungin"

Hasil audit, dipisah jujur antara yang benar-benar jalan dan yang cuma kerangka.

### 1.1 Modul `inventory` (gudang)

| Lapisan | Kondisi |
|---|---|
| Skema DB | ✅ **Matang.** 12 tabel (V144–V147, V162), semua `ENABLE` + `FORCE ROW LEVEL SECURITY`, constraint & unique idempotency lengkap |
| Domain model | ✅ Matang. State machine aset serial, 19 `MovementKind`, ledger dua-sisi, approval multi-tier |
| Unit test | ✅ Ada (4 kelas, 14 metode, fake in-memory) |
| **Spring wiring** | ❌ **3 dari 7 service tidak punya `@Service`** — `InventoryMovementLedgerService`, `InventoryReconciliationService`, `MaterialConsumptionService`. Keempat `*ApiAdapter` juga bukan bean |
| **Persistensi runtime** | ❌ Ledger, approval, cycle count, dan material fact **disimpan di `mutableListOf`/`linkedMapOf` dalam memori proses** — hilang tiap restart, tidak kena RLS |
| **Master data** | ❌ **Tidak ada tabel SKU/item sama sekali.** `Sku` & `Lot` ada di domain tapi tanpa tabel, tanpa repository, tanpa referensi. Semua `skuId` di sistem adalah UUID menggantung |
| Endpoint tulis | ❌ Dari 11 endpoint, hanya 2 yang menulis (link ONU + keputusan approval). Tidak ada: buat gudang, daftar aset, terima barang, transfer, keluarkan, retur, stock opname |
| Integration test | ❌ Nol. Tidak ada `@SpringBootTest`, tidak ada regresi RLS |
| UI | ⚠️ `WarehouseOperationsPage` ada tapi read-only, 118 baris, menampilkan UUID mentah (`SKU {row.skuId}`) |

Dua sumber kebenaran yang **tidak pernah direkonsiliasi**: `inventory_serialized_asset`
(per-unit) dan `inventory_balance_projection` (kuantitas). `MovementLeg` **tidak punya
field `assetId` maupun `serialNumber`** — jadi mutasi kuantitas dan mutasi aset serial
adalah dua pulau terpisah.

Lubang kontrol: matriks approval **dikirim klien di request body**, jadi pemohon bisa
memilih approver-nya sendiri dan meng-grant `emergency` override untuk dirinya sendiri.
Untuk fitur yang tujuannya "mencegah kebocoran aset", ini membatalkan tujuannya.

### 1.2 Jembatan Work Order → Gudang

**Tidak ada sama sekali.** Grep seluruh repo: modul `workorder` dan `fieldservice`
**nol** import ke `com.duluin.ftth.inventory`. `WorkOrder` tidak punya field material,
BOM, reservasi, maupun konsumsi.

Satu-satunya jalur adalah saga `fulfillment` — dan ia **terpasang tapi mati** karena
tiga putus di rantai yang sama:

1. `InventoryApiService.fulfillmentAllocations()` → `return emptyList()` (hardcoded stub).
   Sedangkan `FulfillmentContracts.kt:143` memperlakukan list kosong sebagai error
   `INVENTORY_ALLOCATIONS_NOT_FOUND`. Jadi preflight **dijamin gagal**.
2. `WorkOrderService.kt:175` menerbitkan `FulfillmentApproved` dengan
   `applicableEffects = setOf("SUBSCRIPTION", "PROVISIONING", "WORK_ORDER")` —
   `INVENTORY`, `ORDER`, dan `VISIT` **tidak pernah diminta**.
3. `FulfillmentCoordinator.effectsForWorkOrder()` yang justru memilih effect set lengkap
   untuk PSB/DISMANTLE adalah `private` dan **tidak pernah dipanggil** (dead code).

Kabar baiknya: mesin saga-nya sendiri sudah matang — checkpoint, outbox, lease 60 detik,
`FOR UPDATE SKIP LOCKED`, resume setelah restart, idempotency per-effect. Yang mati cuma
sakelarnya.

Detail lain: `applyInventory` menghitung `installed = workOrderKind != "DISMANTLE"` tapi
tetap memanggil `consumeFulfillment`, tidak pernah `returnFulfillment` — jadi pembongkaran
akan tercatat sebagai barang keluar, bukan barang masuk.

### 1.3 Pencatatan SN/MAC

Hari ini nomor seri masuk sistem sebagai **foto**, bukan data:
`EvidenceKind.SERIAL` ("Label/serial number ONU atau perangkat") adalah JPEG, dan di
taksonomi Proof of Work ia justru dipetakan ke `null` — artinya foto serial **tidak
dihitung** sebagai artefak bukti kerja.

`InventoryApi.findBySerial` dan `linkInstalledOnu` sudah ada dan sudah ter-expose di
`POST /api/inventory/serialized/{id}/installed-onu`, tapi **tidak ada satu pun jalur
penyelesaian work order yang memanggilnya**. Ditambah bug: `toEntity()` selalu menulis
`lastOperationKey = null`, jadi idempotency `linkInstalledOnu` tidak berfungsi.

### 1.4 Modul `order` (pesanan)

| Lapisan | Kondisi |
|---|---|
| Aggregate `Order` | ✅ Matang: 8 state, transisi ketat, optimistic lock `revision`, idempotency `(namespace, key, payloadHash)` |
| Persistensi | ✅ JPA beneran dipakai di produksi (`OrderPersistenceAdapter` `@Component`) |
| Skema DB | ✅ V141: `order_record/line/operation/audit/outbox` + trigger tenant-immutable + RLS |
| **Proyeksi portal** | ❌ `InMemoryOrderCustomerProjection` — `ConcurrentHashMap`. **Riwayat pesanan pelanggan kosong setelah restart** |
| **Order publik** | ❌ Tidak ada. Semua endpoint butuh JWT; permit-list tidak memuat `/api/orders` |
| **Konsep calon pelanggan** | ❌ `order_record.customer_id` `NOT NULL REFERENCES customer(id)` — pemesan wajib sudah jadi pelanggan. Tidak ada entitas lead/prospect |
| Nomor pesanan | ❌ Hanya UUID. Bandingkan `work_order.code` = `WO-XXXXXXXX` |
| Endpoint list/search | ❌ Tidak ada. Hanya `GET /api/orders/{id}` — antrian back-office tidak bisa dibangun |
| Timeline | ❌ `order_audit` & `order_outbox` ada tabelnya, **tidak pernah ditulis**. `publishedEvents()` tidak punya pemanggil |
| Jembatan ke WO | ⚠️ Kolom `work_order.order_id` ada (V163, tanpa FK) tapi **hanya terisi kalau operator mengetik manual**. Express-PSB tidak pernah membuat `Order` |
| **UI** | ❌ **Nol.** Tidak ada `web/src/api/order.ts`, tidak ada halaman yang memanggil `/api/orders` |

### 1.5 Batch Import CSV

✅ **Ini bagian paling sehat di seluruh cakupan.** Pipeline staging pelanggan sudah
produksi-grade: staging → validasi → commit/cancel/retry, credential vault
(`SEALED → CONSUMED | PURGED`) dengan `SecretCipher`, identitas file = SHA-256 byte mentah,
batas 25 MiB / 100.000 baris, worker promosi dengan 3 percobaan lalu `PERMANENT_FAILED`,
cron retensi 30 hari + tabel audit, output CSV aman dari formula injection, dan UI
lengkap (`ImportCustomersPage`, polling 1500 ms).

❌ Yang kurang: `importType` **hardcoded `"CUSTOMERS_CSV"`** — belum ada jalur untuk
mengimpor **pesanan/calon pelanggan**. Dan endpoint legacy JSON `POST /import/customers`
masih terbuka; ia melewati staging, vault, dan retensi, serta menerima password plaintext.

### 1.6 Mobile

Boilerplate murni. `mobile/feature/workorders` total 152 baris. Tidak ada entitas work
order (state cuma 7 nilai sealed tanpa id/kode/pelanggan), tidak ada HTTP client, tidak
ada kamera/scanner/GPS. `ObserveWorkOrders` bahkan membuang list-nya:
`onSuccess = { if (it.isEmpty()) WorkOrderState.Ready else WorkOrderState.Ready }`.

**Konsekuensi untuk rencana ini:** scan SN/MAC di lapangan **fase pertama lewat web
(browser HP teknisi)**, bukan aplikasi native. Bikin mobile-nya dari nol adalah poin 2
di notulen, bukan poin 3.

---

## 2. Keputusan arsitektur

### K1 — Dua jalur, bukan satu: sinkron untuk reservasi, saga untuk komit

Pertanyaan terbuka dari audit: `workorder` panggil `inventory` langsung, atau semua lewat
`fulfillment`?

**Jawaban: dua-duanya, dibagi menurut kebutuhan.**

- **Keluar barang ke teknisi (sebelum kerja) = panggilan sinkron** `workorder → inventory`
  lewat port baru `InventoryAllocationApi`. Alasannya: gudang harus menolak seketika kalau
  stok kurang atau serial sudah dipegang orang lain. Saga asinkron akan bilang "nanti gagal"
  setelah teknisi berangkat — itu tidak berguna.
- **Komit konsumsi final (setelah supervisor approve) = lewat saga `fulfillment`.**
  Alasannya: di titik itu kita butuh durabilitas, idempotency, dan retry — dan semua itu
  **sudah dibangun dan sudah diuji**. Tinggal dinyalakan.

Yang menyatukan keduanya: baris alokasi yang ditulis saat pengeluaran barang **adalah**
data yang dibaca `fulfillmentAllocations(workOrderId)` saat approval. Jadi stub
`emptyList()` itu terisi secara alami, bukan ditambal.

### K2 — `work_order_material` milik modul `workorder`, ledger milik `inventory`

WO menyimpan **rencana vs realisasi** (planned / issued / used / returned / variance).
Gudang menyimpan **kebenaran stok** (ledger + aset serial). WO tidak pernah menulis ledger
langsung; ia selalu lewat `InventoryAllocationApi`. Dengan begitu `ModularityTests` tetap
hijau: `workorder` hanya menyentuh tipe base-package `inventory`.

### K3 — Serial adalah data, foto adalah pelengkap

`EvidenceKind.SERIAL` tetap ada sebagai bukti visual, tapi serial yang **mengikat** adalah
field terstruktur di `work_order_material_serial`, hasil scan/ketik, divalidasi ke
`inventory_serialized_asset`. Foto tidak lagi jadi satu-satunya jejak.

### K4 — Matriks approval pindah ke server

Tabel `inventory_approval_policy` per tenant: (movement kind, ambang nilai/kuantitas) →
daftar tier + peran approver. Request klien **hanya boleh** menyebut apa yang diminta;
tier dan approver ditentukan server dari kebijakan. `emergency` override butuh permission
terpisah dan selalu menulis audit. Tanpa ini, "mencegah kebocoran aset" tidak tercapai.

### K5 — Calon pelanggan = entitas `order_lead`, bukan `customer` palsu

Menaruh prospect ke tabel `customer` akan mengotori tagihan, langganan, dan laporan.
`order_record.customer_id` dibuat nullable, ditambah `lead_id`; saat order diterima dan
pemasangan dijadwalkan, lead dipromosikan jadi `customer` dalam satu transaksi.

---

## 3. Alur target (end-to-end)

```
                            ┌──────────────── POIN 4 ────────────────┐
  Pengunjung web            │                                        │
      │                     │  POST /api/public/orders (per-tenant)   │
      ├── isi form ────────►│  → order_lead + order_record(DRAFT→SUBMITTED)
      │                     │  → nomor ORD-2609-0042                  │
      │                     │                                        │
      ├── cek status ──────►│  GET /api/public/orders/track           │
      │                     │  (nomor + HP, throttled)                │
      │                     └────────────────────────────────────────┘
      │                                    │
  Operator: antrian pesanan ──ACCEPT──► promosi lead→customer
                                             │
                                             ▼
                            ┌──────────────── POIN 3 ────────────────┐
  WO PSB dibuat otomatis (order_id terisi, FK nyata)
      │
      ├─ BOM template per tipe WO ──► work_order_material (planned)
      │     PSB: dropcore 100m, ONT 1, patchcord 2, adapter 2, roset 1
      │
      ├─ Gudang keluarkan barang ──► InventoryAllocationApi.issueToTechnician()
      │     • kuantitas: ledger OUT(bin) / IN(owner=TECHNICIAN)
      │     • serial: scan SN/MAC → asset.status=ISSUED, custody=teknisi
      │     • tulis inventory_allocation(work_order_id, ...)   ← ini yang dibaca saga
      │
      ├─ Teknisi kerja, catat realisasi di WO detail (browser HP)
      │     • qty terpakai per baris
      │     • scan serial ONT terpasang → linkInstalledOnu(asset → onu → customer)
      │     • sisa: RETURN atau LOST + alasan
      │
      ├─ complete() → validasi: baris serialized wajib punya serial,
      │                actual ≤ issued, selisih wajib beralasan
      │
      └─ Supervisor approve (4-eyes, sudah ada)
             │
             └─► FulfillmentApproved{SUBSCRIPTION, PROVISIONING, WORK_ORDER,
                                     INVENTORY, ORDER, VISIT}
                    │
                    ├─ INVENTORY ─► consumeFulfillment / returnFulfillment (DISMANTLE)
                    │                → ledger CONSUME, asset.status=CONSUMED
                    │                → inventory_customer_material_fact (persisten)
                    │                → Subscriber360 "material terpasang" hidup
                    │
                    └─ ORDER ─────► order FULFILL → portal pelanggan jadi COMPLETED
```

Restock (poin 3, jalur terpisah):

```
Petugas gudang ajukan restock ──► inventory_approval (tier dari POLICY server, bukan klien)
      │
      ├─ Tier 1 approve ──► Tier 2 approve ──► ... ──► APPROVED
      │                                                   │
      │                                                   └─► movement RESTOCK/RECEIVE
      │                                                        + daftar aset serial (bulk scan)
      ├─ REJECTED / REWORK_REQUIRED ──► kembali ke pemohon
      └─ EXPIRED (sweeper terjadwal) ──► otomatis batal
```

---

## 4. Paket kerja

Penomoran migrasi dimulai dari **V173** (tertinggi sekarang V172).

### P0 — Bikin modul `inventory` benar-benar hidup (blocker semua fase lain)

| # | Pekerjaan |
|---|---|
| P0.1 | `V173__inventory_item_master.sql` — `inventory_item` (kode, nama, kategori ONT/DROPCORE/PATCHCORD/ADAPTER/ACCESSORY/OTHER, satuan PCS/METER, `serialized boolean`, `track_mac boolean`, `reorder_point`, aktif) + RLS FORCE. Entity/repo/adapter JPA |
| P0.2 | `V174__inventory_movement_serial_link.sql` — tambah `asset_id uuid` + `serial_number` ke `inventory_movement_leg`, index, CHECK `serialized = (asset_id IS NOT NULL)`. Ini yang menyatukan dua sumber kebenaran |
| P0.3 | Persistensi `InventoryMovementLedgerService` ke `inventory_movement` + `inventory_movement_leg` + `inventory_balance_projection` (tabelnya sudah ada, tinggal adapter) |
| P0.4 | Persistensi `InventoryApprovalService` ke `inventory_approval*` (4 tabel sudah ada, termasuk trigger immutability keputusan) |
| P0.5 | Persistensi `MaterialConsumptionService` ke `inventory_customer_material_fact` (tabel yatim sejak V147) |
| P0.6 | Persistensi `InventoryReconciliationService` + `inventory_cycle_count` |
| P0.7 | `@Service`/`@Component` untuk 3 service tak ter-wire + 4 `*ApiAdapter`. Setelah ini `Subscriber360Service.materialApi` tidak lagi null |
| P0.8 | Perbaiki bug idempotency `linkInstalledOnu` (`toEntity()` membuang `lastOperationKey`) |
| P0.9 | `InventoryIT.kt` pertama — regresi RLS lintas tenant, assert 11 permission terdaftar, smoke ledger→balance |

**Definisi selesai:** restart aplikasi tidak menghilangkan data inventory apa pun.

### P1 — Permukaan tulis gudang + approval restock yang benar (inti poin 3)

| # | Pekerjaan |
|---|---|
| P1.1 | `V175__inventory_approval_policy.sql` — matriks tier per tenant (kind, ambang, urutan tier, peran approver, TTL kadaluwarsa) |
| P1.2 | Pindahkan penentuan tier dari request body ke policy server. `emergency` jadi permission terpisah + audit wajib |
| P1.3 | Sweeper kadaluwarsa approval (`@Scheduled`, pola sama seperti `EvidenceRetentionWorker`) |
| P1.4 | Tutup loop approval→movement: `APPROVED` langsung mengeksekusi efek yang dijanjikan (`inventory_approval_effect` sudah ada) |
| P1.5 | Endpoint tulis: buat/ubah gudang & bin, CRUD item, daftar aset serial massal (tempel/scan daftar SN+MAC), terima barang (GRN), transfer antar lokasi, keluarkan, retur, penyesuaian, stock opname |
| P1.6 | Endpoint baca yang hilang: daftar ledger (paginated), query saldo per item/lokasi, van stock per teknisi, laporan selisih |
| P1.7 | Permission baru: `inventory.restock.request`, `inventory.restock.receive`, `inventory.movement.issue`, `inventory.movement.return`, `inventory.movement.adjust`, `inventory.count.perform`, `inventory.count.approve` |
| P1.8 | Test: `InventoryApprovalIT`, `InventoryMovementIT` (termasuk kasus stok kurang & serial dobel) |

### P2 — Jembatan Work Order ↔ Gudang (inti permintaan)

| # | Pekerjaan |
|---|---|
| P2.1 | `V176__workorder_material.sql` — `work_order_material` (wo_id, item_id, planned_qty, issued_qty, used_qty, returned_qty, lost_qty, variance_reason) + `work_order_material_serial` (baris ↔ asset_id, SN, MAC, terpasang/retur) + `workorder_material_template` (BOM per tipe WO, bisa diubah tenant) |
| P2.2 | Domain: `MaterialLine` + `MaterialPlan` di `workorder/domain/model/`, invariant dalam bahasa Indonesia sesuai konvensi |
| P2.3 | Port lintas modul baru `InventoryAllocationApi` di base package `inventory`: `planFor(type)`, `reserveForWorkOrder`, `issueToTechnician`, `returnFromTechnician`, `allocationsFor(workOrderId)` |
| P2.4 | Implementasi nyata `fulfillmentAllocations(workOrderId)` — baca alokasi yang ditulis P2.3 |
| P2.5 | `WorkOrderService.approve` memakai `effectsForWorkOrder(type)` (buka private-nya) supaya `INVENTORY`, `ORDER`, `VISIT` ikut jalan |
| P2.6 | `applyInventory` bercabang: DISMANTLE → `returnFulfillment`, selain itu → `consumeFulfillment` |
| P2.7 | Perluas `WorkOrder.complete()`: baris serialized wajib punya serial terverifikasi, `used ≤ issued`, selisih wajib beralasan, sisa wajib dideklarasikan RETURN atau LOST |
| P2.8 | Scan serial saat penyelesaian → `linkInstalledOnu(asset → onu)` + ikat ke customer |
| P2.9 | Permission: `workorder.material.view`, `workorder.material.record`, `workorder.material.issue` |
| P2.10 | Test: `WorkOrderMaterialIT` (PSB penuh: rencana→keluar→pakai→approve→ledger terpotong), `DismantleReturnIT`, regresi "approve tanpa serial ditolak" |

**Definisi selesai:** satu WO PSB dari dibuat sampai di-approve memotong stok gudang
secara persisten, dengan setiap ONT tercatat SN+MAC-nya dan tertaut ke pelanggan.

### P3 — UI gudang + material di work order

| # | Pekerjaan |
|---|---|
| P3.1 | `web/src/api/inventory.ts` diperluas (sekarang 6 GET + 1 POST) — semua endpoint P1 |
| P3.2 | `WarehouseOperationsPage` ditulis ulang: tab Item, Stok, Aset Serial, Mutasi, Approval, Stock Opname. Resolusi nama item (hilangkan UUID mentah) |
| P3.3 | Form pengajuan restock + layar approval bertingkat (tampilkan tier, siapa sudah setuju, sisa waktu) |
| P3.4 | Pendaftaran aset serial massal: tempel/scan banyak SN+MAC sekaligus, validasi duplikat di klien dan server |
| P3.5 | Tab **Material** di `WorkOrderDetailBody`: rencana vs keluar vs pakai vs retur, tombol keluarkan barang (untuk dispatcher/gudang), input scan serial (untuk teknisi) |
| P3.6 | Halaman **Stok Van** per teknisi + laporan selisih |
| P3.7 | Entri nav `Layout.tsx` + guard `RequirePermission` di `App.tsx` |
| P3.8 | Test presentasi halaman (pola `HelpdeskPage.test.tsx`) + test modul API |

Catatan: panggil skill `netops-ui` sebelum mendesain layar baru.

### P4 — Portal pesanan publik + track record (inti poin 4)

| # | Pekerjaan |
|---|---|
| P4.1 | `V177__order_lead_and_number.sql` — `order_lead` (nama, HP, email, alamat, koordinat, paket diminati, sumber, status), `order_record.customer_id` jadi nullable + `lead_id`, `order_number` unik per tenant + sequence, `order_customer_projection` durabel |
| P4.2 | Ganti `InMemoryOrderCustomerProjection` dengan proyeksi JPA yang di-update dari event order |
| P4.3 | Hidupkan `order_audit` + `order_outbox` (sekarang tabelnya ada tapi tak pernah ditulis) → jadi sumber timeline "track record" |
| P4.4 | `POST /api/public/orders` — resolusi tenant dari slug (pola yang sudah dipakai `/bayar/<slug>/<uuid>`), throttle per-IP, honeypot/rate limit, tanpa JWT. Masuk permit-list `SecurityConfig` |
| P4.5 | `GET /api/public/orders/track` — lacak dengan nomor pesanan + nomor HP (atau token bertanda tangan), throttled, hanya mengembalikan status ringkas |
| P4.6 | `GET /api/orders` — daftar/cari/filter untuk operator (paginated `PageResponse`) |
| P4.7 | Isi 2 status portal yang mati (`WAITING_CUSTOMER`, `REQUIRES_ATTENTION`) |
| P4.8 | Test: `PublicOrderIT` (anonim bisa order + lacak, tidak bisa lihat order tenant lain), regresi RLS untuk `order_lead` |

### P5 — UI pesanan + otomasi Order ↔ Work Order

| # | Pekerjaan |
|---|---|
| P5.1 | `web/src/api/order.ts` — **belum ada sama sekali** |
| P5.2 | Halaman publik: form pemesanan (rute tanpa auth) + halaman lacak pesanan |
| P5.3 | Halaman operator: antrian pesanan, detail, tombol transisi (ACCEPT/SCHEDULE/REJECT/CANCEL), timeline |
| P5.4 | ACCEPT → promosi `order_lead` → `customer` + **buat WO PSB otomatis** dengan `order_id` terisi; tambah FK nyata di `work_order.order_id` |
| P5.5 | Navigasi dua arah Order ↔ WO di kedua halaman detail |
| P5.6 | Efek `ORDER` di saga: WO di-approve → order otomatis `FULFILLED` → portal pelanggan `COMPLETED` |
| P5.7 | Riwayat pesanan di portal pelanggan (sekarang selalu kosong setelah restart) |

### P6 — Batch Import CSV pesanan / calon pelanggan

| # | Pekerjaan |
|---|---|
| P6.1 | Longgarkan `importType` (`CUSTOMERS_CSV` → + `ORDERS_CSV`), lebarkan CHECK constraint terkait |
| P6.2 | Kontrak CSV pesanan: `name, phone, email, address, latitude, longitude, package_name, connection_type, preferred_install_date, source, notes` — tanpa kolom kredensial, jadi vault tidak diperlukan |
| P6.3 | Validator + promotor batch → `order_lead` + `order_record(SUBMITTED)`, memakai kembali seluruh pipeline staging/commit/retry/retensi yang sudah ada |
| P6.4 | Laporan CSV hasil impor (pola aman formula-injection yang sudah ada) |
| P6.5 | UI: perluas `ImportCustomersPage` dengan pemilih jenis impor, atau halaman `ImportOrdersPage` terpisah |
| P6.6 | Pensiunkan endpoint legacy `POST /import/customers` (JSON, melewati staging/vault/retensi, menerima password plaintext) — tandai deprecated lalu hapus |

### P7 — Uji end-to-end, dokumentasi, kebersihan

| # | Pekerjaan |
|---|---|
| P7.1 | Playwright e2e: (a) restock → approval bertingkat → barang masuk; (b) order publik → operator terima → WO → keluarkan material → scan serial → selesai → approve → stok terpotong; (c) impor CSV pesanan |
| P7.2 | Seed lab: tambah item gudang + stok awal + BOM default ke `docker/lab/seed-*.py` supaya `make lab` langsung bisa dicoba |
| P7.3 | `docs/gudang.md`, `docs/pesanan.md`; entri `CHANGELOG.md`; perbarui `README.md` (sekarang berhenti di Fase 7 dan tidak menyebut 6 modul, termasuk gudang) |
| P7.4 | Hapus dead code: `Sku`/`Lot` tanpa tabel, `InventoryInvariantException` yang tak pernah dilempar, `inventory_serial_tombstone` yang tak pernah dibaca/ditulis — atau pakai sekalian |

---

## 5. Estimasi

Asumsi: dikerjakan oleh saya dengan fan-out subagent untuk bagian yang independen,
Postgres `ftth_test` lokal hidup, review dari kamu di akhir tiap fase.

| Fase | Isi | 1 dev manusia | Saya (fan-out) |
|---|---|---|---|
| P0 | Inventory jadi persisten + ter-wire | 4–5 hari | **1–1,5 hari** |
| P1 | Endpoint tulis gudang + approval server-side | 5–6 hari | **1,5–2 hari** |
| P2 | Jembatan WO ↔ gudang + serial | 6–7 hari | **2–2,5 hari** |
| P3 | UI gudang + material di WO | 6–7 hari | **2–2,5 hari** |
| P4 | Order publik + track record | 5–6 hari | **1,5–2 hari** |
| P5 | UI pesanan + otomasi Order↔WO | 5–6 hari | **1,5–2 hari** |
| P6 | Import CSV pesanan | 3–4 hari | **1 hari** |
| P7 | E2E + dokumentasi + bersih-bersih | 4–5 hari | **1,5 hari** |
| | **Total** | **38–46 hari kerja** | **12–15 hari kalender** |

Jalur kritis tidak bisa dipendekkan: **P0 → P1 → P2 → P3** harus berurutan (tiap fase
butuh fase sebelumnya jalan). Tapi **P4–P6 (poin 4) tidak bergantung pada P0–P3 sama
sekali** — bisa jalan paralel. Kalau dua jalur dikerjakan bersamaan:

> **Realistis: 8–10 hari kalender** untuk poin 3 + poin 4 lengkap sampai ada test e2e.

Kalau butuh sesuatu yang bisa didemokan lebih cepat:

| Target | Isi | Waktu |
|---|---|---|
| **Demo minimal** | P0 + P2 (tanpa UI cantik) — buktikan WO memotong stok dengan serial tercatat | **3–4 hari** |
| **Poin 4 saja** | P4 + P5 + P6 — portal order + tracking + import CSV | **4–5 hari** |
| **Lengkap** | P0–P7 | **8–10 hari** |

### Yang membuat estimasi ini bisa meleset

1. **Butuh migrasi data?** Kalau sudah ada tenant produksi dengan data inventory, P0.1
   (item master) perlu backfill dan pemetaan `skuId` menggantung. Estimasi di atas
   mengasumsikan belum ada data produksi gudang. Kalau ada, +2–3 hari.
2. **BOM default & matriks approval adalah keputusan bisnis.** Saya bisa bikin
   mekanismenya, tapi isi angkanya (PSB butuh berapa meter dropcore, restock di atas
   berapa juta butuh berapa tier) harus dari kamu. Kalau menunggu, jalur kritis molor.
3. **Poin 1 (PPPoE/BRAS) belum diuji end-to-end.** Kalau di tengah jalan ketemu bug di
   sana yang menghalangi alur PSB, itu di luar hitungan ini.
4. **Scan barcode/QR di HP** butuh HTTPS untuk akses kamera. Lab jalan di
   `http://localhost:8000`. Fase 1 saya pakai input teks/tempel dulu; kamera menyusul.

---

## 6. Keputusan yang saya butuh darimu

Saya kasih rekomendasi masing-masing; kalau didiamkan, saya jalan dengan rekomendasi itu.

| # | Pertanyaan | Rekomendasi saya |
|---|---|---|
| D1 | Mulai dari mana? | **Dua jalur paralel**: satu agent jalur gudang (P0→P1→P2→P3), satu agent jalur pesanan (P4→P5→P6). Selesai paling cepat, dan dua jalur tidak menyentuh file yang sama |
| D2 | Scan serial wajib atau opsional saat menyelesaikan WO? | **Wajib untuk item `serialized = true`** (ONT/ONU). Kalau opsional, pelacakan aset bocor lagi dan tujuan poin 3 tidak tercapai |
| D3 | Berapa tier approval restock default? | **2 tier**: Kepala Gudang → Manajer Operasional, dengan ambang nilai yang bisa diatur tenant. Bisa diubah dari UI setelah jadi |
| D4 | Boleh saya set `work_order.order_id` jadi FK nyata? | **Ya.** Sekarang tanpa FK, jadi bisa menunjuk order yang sudah dihapus. Butuh migrasi pembersihan dulu kalau ada data |
| D5 | Endpoint legacy `POST /import/customers` (JSON) dihapus? | **Ya, di P6.6.** Ia menerima password plaintext dan melewati vault + retensi |
| D6 | Order publik: butuh verifikasi OTP WhatsApp/SMS? | **Belum di fase ini** — cukup throttle per-IP + nomor HP sebagai kunci pelacakan. OTP menambah dependensi gateway, bisa menyusul |
| D7 | Ada data gudang produksi yang harus dimigrasikan? | Saya asumsikan **tidak ada**. Kalau ada, kabari sebelum P0 mulai |

---

## 7. Yang sengaja TIDAK masuk rencana ini

Supaya cakupan jelas dan tidak melebar diam-diam:

- **Aplikasi mobile teknisi** (notulen poin 2) — masih boilerplate 152 baris, butuh
  dibangun dari nol. Fase ini pakai browser HP.
- **Uji end-to-end PPPoE/BRAS** (notulen poin 1) — kamu bilang sudah dikerjakan tapi
  belum diuji; itu pekerjaan terpisah.
- **Supplier / Purchase Order / Goods Receipt Note formal** — P1.5 punya "terima barang",
  tapi tanpa entitas supplier, PO, dan pencocokan tiga arah. Kalau perlu, +3–4 hari.
- **Valuasi persediaan (FIFO/average cost), harga pokok, integrasi akuntansi.**
- **Batch/expiry tracking dan hierarki bin berjenjang** — `LocationKind.BIN` ada, tapi
  rencana ini memperlakukannya datar.
- **Sinkronisasi offline untuk pencatatan material** — `OfflineSyncProtocol` ada tapi
  store-nya in-memory, tanpa controller, dan klien web memalsukan `payloadHash`
  (`api/fieldservice.ts:34` membuat hash 64-karakter dari `randomUUID`). Ini pekerjaan
  tersendiri yang berpasangan dengan aplikasi mobile.

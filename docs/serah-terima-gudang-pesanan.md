# Serah terima — Gudang (poin 3) & Pesanan (poin 4)

Status per commit `f637ccb7`, branch `feat/gudang-dan-pesanan` (dicabang dari `main`).
Dokumen ini ditulis supaya sesi atau agen lain bisa melanjutkan tanpa mengulang penggalian.
Rencana aslinya ada di `docs/rencana-gudang-pesanan.md`; dokumen INI yang menggambarkan
keadaan sebenarnya.

---

## 1. Cara cepat memverifikasi keadaan

```bash
./gradlew :server:test --rerun          # ~18 menit
cd web && ./node_modules/.bin/vitest run # ~1 menit — 65 berkas, 407 tes, SEMUA lulus
cd web && ./node_modules/.bin/tsc --noEmit -p tsconfig.app.json
```

Panggil binernya langsung dari `node_modules/.bin`. `npx` di lingkungan ini di-shim dan
MENELAN perintahnya: ia mencetak `npm notice run 'tsc'` lalu keluar dengan status 0 tanpa
menjalankan apa pun — persis bentuk kegagalan yang paling berbahaya, yaitu terlihat lulus.

**Garis dasar yang SEHAT: 1741 tes, 2–3 gagal.**

| Tes yang gagal | Sifat |
| --- | --- |
| `ProvisioningMigrationCompatibilityIT > v125 ...` | **Pra-ada**, gagal identik di `main`. Tesnya memigrasi sampai V124 lalu memakai kolom yang baru lahir di V125. |
| `ProvisioningMigrationCompatibilityIT > v127 ...` | **Pra-ada**, sama, untuk V126/V127. |
| `VpnIT > provisioning end-to-end ...` | **Flaky.** Lulus di sebagian run, gagal di sebagian lain, tanpa perubahan kode. Belum ditelusuri — jadi 2 kegagalan pada run yang beruntung, 3 pada yang tidak. |

Kalau ada kegagalan DI LUAR tiga ini, itu regresi — jangan lanjut sebelum beres.

### Jangan pernah menjalankan dua Gradle sekaligus

Dua task `:server:test` berbagi `server/build/test-results` DAN database `ftth_test`.
Kalau dua jalan bersamaan, hasilnya **kegagalan hantu** yang menunjuk ke tempat yang salah —
`NoSuchBeanDefinitionException` di kelas yang tak bersalah, atau
`java.nio.file.NoSuchFileException: .../in-progress-results-generic*.bin`. Ini sudah terjadi
dan membuang berjam-jam ke pengejaran bug yang tidak ada.

Kalau menjalankan agen paralel, bungkus SETIAP pemanggilan Gradle:

```bash
flock /tmp/ftth-gradle.lock ./gradlew :server:test --tests 'X' --rerun
```

Dua jebakan kecil yang sudah memakan korban:

- `./gradlew ... --tests 'X'` tanpa `--rerun` bisa menjawab `BUILD SUCCESSFUL in 1s` **tanpa
  menjalankan apa pun** (task dianggap up-to-date).
- `./gradlew ... 2>&1 | tail -150` membuang detail kegagalan DAN memulangkan exit code milik
  `tail` (selalu 0), jadi build yang GAGAL terlihat sukses. Redirect ke berkas, lalu `echo $?`.

---

## 2. Apa yang sudah jadi

### Poin 3 — Gudang & logistik

| Hal | Keadaan |
| --- | --- |
| Matriks approval bertingkat | **Selesai.** Kebijakan milik server, bukan klien. Terikat ke mutasinya (V180) sehingga approver tak bisa menyetujui permintaan A lalu memberlakukan mutasi B. |
| Material work order ↔ saldo gudang | **Selesai.** V186: template BOM per jenis WO, rencana vs realisasi per item, satu baris per unit berserial. |
| Scan serial wajib | **Selesai.** WO tidak bisa ditutup selama ada unit berserial yang sudah keluar gudang tapi belum dideklarasikan nasibnya. |
| Hapus buku unit berserial | **Selesai.** ONT hilang/rusak/write-off punya jalur, lewat `/adjustments`. |
| `AWAITING_RECEIPT` | **Selesai** (V188). Aset terdaftar tapi belum jadi stok — satu-satunya status yang tidak pernah punya baris di proyeksi saldo. |
| Pemisahan izin transfer | **Selesai.** `inventory.movement.transfer` terpisah dari `.issue`. |
| Penarikan aset saat DISMANTLE (P2.6) | **Selesai.** V197 `work_order_recovered_asset`. Teknisi men-scan ONT yang dicabut; saldo baru bergerak saat WO disetujui, dan mendaratnya di van teknisi berstatus `RETURNED` — BUKAN langsung jadi stok layak jual. Dari van, unitnya naik ke rak lewat `POST /api/inventory/returns` biasa. |
| Approver dari peran | **Selesai.** Tier boleh berbunyi "Kepala Gudang" saja; pemegangnya diresolusi ke `iam` setiap kali dibaca dan tidak pernah ikut tersimpan. Pemegang nonaktif otomatis gugur. |
| Layar/UI gudang | **Selesai.** `/warehouse`, enam tab: stok, mutasi, riwayat, persetujuan, stock opname, master data. Penjaga rutenya DITURUNKAN dari tabel tab (`WAREHOUSE_VIEW_PERMISSIONS`), jadi menambah tab otomatis melebarkan izinnya. Editor kebijakan merender `approverIds` dan `roleHolderIds` sebagai dua kelompok terpisah dan tidak pernah mengirim yang kedua. |

### Poin 4 — Pesanan & pelanggan

| Hal | Keadaan |
| --- | --- |
| Portal pesan & lacak publik | **Selesai.** `/api/public/orders/{tenantSlug}`. Slug/nomor/HP salah dijawab kalimat identik supaya tak ada enumerasi. |
| WO PSB otomatis saat ACCEPT | **Selesai**, satu transaksi dengan gerbang idempotensi. |
| Penanda portal otomatis | **Selesai.** Tiga pemicu; kalimat untuk pelanggan ditulis sistem, bukan operator/teknisi. |
| Penanda portal di antrean operator | **Selesai.** `portalFlag`/`portalFlagReason`/`portalFlagSource` ikut di `OrderSummaryView` DAN `OrderView`, dengan penyaring server `?flagged=` dan `?portalFlag=`. Akal-akalan lama (memindai `GET /{id}/timeline` satu per satu untuk tiap baris) sudah dibuang; `portalFlagSetAt` yang tersisa hanya membaca STEMPEL WAKTU, bukan memutuskan keadaannya. |
| Impor CSV massal | **Selesai.** Pratinjau sebelum commit, alasan per baris, idempotensi dua lapis, batas 2 MiB / 1000 baris, berkas ekspor Excel diterima apa adanya. |
| Layar/UI pesanan | **Selesai.** `/orders` (antrean), `/orders/:id` (detail + lini masa, hanya transisi yang sah), `/orders/import` (pratinjau → commit), `/orders/leads`. Tidak ada form "buat pesanan" operator — jalur masuk nyatanya lead + impor; `POST /api/orders` butuh `customerId` + id baris katalog sehingga form mentah hanya akan menghasilkan 400. |

---

## 3. Keputusan yang menunggu pemilik proyek

1. **`WorkOrderAssignmentRef.orderId` sebenarnya berisi `customerId`.**
   `WorkOrderApiService.kt:39` mengoper `workOrder.customerId` ke parameter posisi ketiga yang
   bernama `orderId`. Keduanya `UUID`, jadi kompilasi selalu mulus. Akibatnya `Visit.orderId`
   di seluruh sistem menyimpan id PELANGGAN, dan `FieldServiceService.create` mencocokkannya
   terhadap nilai yang sama salahnya — jadi konsisten secara internal, tapi konsumen BARU yang
   membaca `Visit.orderId` sebagai id pesanan akan mendapat data salah tanpa error apa pun.
   Sudah dikelilingi lewat `WorkorderApi.orderIdOf(workOrderId)`, **belum diperbaiki**.
   Perbaikan jujurnya: ganti nama kolom + field jadi `customerId`. Menyentuh `fieldservice` +
   `workorder` + migrasi + `WorkOrderIT`.

2. **Ambang approval dihitung dari KUANTITAS, bukan nilai rupiah.** Satu ambang berlaku untuk
   semua satuan, jadi ambang yang masuk akal untuk meteran dropcore jadi sangat longgar untuk
   ONT. Terasa makin nyata sekarang setelah unit berserial punya jalur approval.

3. **Peran custom kehilangan hak transfer.** Peran rakitan tangan yang dulu hanya diberi
   `inventory.movement.issue` harus ditambahi `inventory.movement.transfer` manual. Tidak ada
   migrasi backfill — SENGAJA, karena backfill otomatis menghidupkan lagi penggabungan yang
   justru mau dipisah.

4. **`remoteAddr` di belakang Caddy.** Rem laju per-IP portal publik saat ini melihat satu
   alamat yang sama untuk semua pengunjung. Butuh `ForwardedHeaderFilter` + konfigurasi proxy
   tepercaya sebelum produksi.

5. **`order_audit.to_status` dipakai ulang** untuk menyimpan nama pemasang penanda pada baris
   `ORDER_FLAGGED`. UI yang mem-parse kolom itu sebagai `OrderStatus` akan pecah.

---

## 4. Celah yang diketahui (bukan keputusan, memang belum dikerjakan)

**Gudang**
- `REVERSAL` tidak membatalkan perpindahan status aset (`settlesSerialTo` null). Belum bisa
  terjadi — `ledger.reverse()` tidak punya satu pun pemanggil di kode produksi — tapi begitu
  jalurnya dibuka lewat endpoint, celah ini ikut terbuka.
- Tidak ada kunci di tingkat aset untuk permintaan hapus buku yang menggantung. Dua permintaan
  atas serial yang sama boleh sama-sama antre; yang kedua gagal sebagai konflik terbaca di
  tangan approver (ada tesnya), tapi approver-lah yang menanggung kejutannya.
- `AWAITING_RECEIPT` ikut muncul di `GET /api/inventory/items` tanpa penanda khusus. UI yang
  menjumlahkannya begitu saja akan menampilkan barang yang belum pernah masuk rak sebagai stok.
- Tidak ada endpoint untuk melihat / membatalkan restock berserial yang masih menggantung.
- Efek `VISIT` **sengaja belum disambung** ke approval WO. Preflight-nya menuntut tepat satu
  kunjungan `fieldservice`, dan `workorder` tidak bisa menanyakannya tanpa melahirkan siklus
  modul. Kalau dipaksa, **SETIAP** approval WO ditolak. Pemicunya harus datang dari sisi
  `fieldservice`.
- Pembatalan baris penarikan (`POST .../recovered-assets/{id}/cancel`) TIDAK ditolak setelah
  WO-nya disetujui. Saldo sudah terlanjur bergerak, jadi barisnya berbunyi "dibatalkan" sementara
  unitnya nyata-nyata sudah bertambah di van teknisi — persis kebalikan dari pertanyaan yang mau
  dijawab pembatalan ("kenapa saldo TIDAK bertambah"). Bukan kebocoran: penarikan ulang atas unit
  yang sama tetap tertutup karena `recoverAsset` menuntut status `CONSUMED` dan unit yang sudah
  diproses berada di `RETURNED`. Bentuknya sama dengan perubahan material setelah approval, yang
  juga belum dijaga. Penjaganya harus lahir di sisi `workorder` (status WO tidak terlihat dari
  `inventory`).
- Belum ada layar untuk penarikan aset — endpoint-nya lengkap
  (`GET|POST /api/work-orders/{id}/materials/recovered-assets`), klien-nya belum ada.
- Jalur consume saat approve tidak memvalidasi saldo negatif. Yang menahannya CHECK
  `work_order_material_realized_ck` + mutasi ISSUE yang sudah tervalidasi saat barang keluar.

**Pesanan**
- Belum ada bukti end-to-end bahwa approval WO nyata mendarat di `REQUIRES_RECONCILIATION`
  lalu menandai pesanannya. Terbukti di dua batas terpisah (coordinator menerbitkan event;
  event menandai halaman publik), belum sebagai satu jalan utuh.
- Kegagalan menulis penanda SETELAH commit meninggalkan pesanan tanpa penanda. Tidak ada retry
  dan tidak ada dead-letter.
- Belum ada layar/endpoint yang MENDAFTAR pesanan bertanda sistem — operator masih harus
  menemukannya sendiri di antrean.
- Ambang diam `/unreachable` tidak berlaku untuk pesanan tanpa baris audit ACCEPT (data lama
  pra-V178).

**Read model yang menyulitkan UI** (ditemukan saat membangun layarnya, belum diperbaiki)
- **Tidak ada nama item di read model gudang.** `StockBalanceView`, `MovementLegView`,
  `VanStockLineView`, `OpenCountView`, `BalanceAnomalyView` hanya membawa `itemCode`/`locationCode`.
  Setiap layar harus menggabungkan sendiri terhadap `/item-master`; pengguna tanpa
  `inventory.item.view` kembali membaca kode tanpa nama.
- **Tidak ada resolusi nama orang.** Custodian, teknisi, pemohon, approver semuanya UUID mentah
  dan satu-satunya jalan resolusi adalah `/api/users` (iam) — yang justru lazim TIDAK dipegang
  petugas gudang. Layarnya menelan 403 dan mencetak UUID.
- **Approver stock opname tak bisa melihat yang harus ia setujui.** `GET /api/inventory/counts/open`
  menuntut `inventory.count.perform`, sementara menyetujui menuntut `inventory.count.approve`.
  Pemegang izin approve saja kena 403 di daftarnya dan tak punya jalan ke `/counts/{id}/approval`.
- **Nama field sidik payload tidak konsisten.** Semua body mutasi memakai `payloadHash`, tapi
  `ApprovalDecisionBody` dan `CountApprovalBody` memakai `operationHash`.
- `/api/inventory/balances` tanpa paging/pencarian/urutan — seluruh tabel saldo dikirim tiap muat.
- `/unreachable` tidak bisa ditebak klien: syarat ketiganya (diam ≥ `ftth.order.unreachable-silence`,
  bawaan P3D) hanya diketahui server, jadi tombolnya bisa aktif lalu tetap gagal.
- Registrasi serial massal menolak seluruh batch karena satu serial buruk, tanpa daftar terstruktur
  serial mana yang gagal.
- Impor tidak punya endpoint untuk membuang batch berstatus PREVIEWED, dan tak ada retry per baris
  untuk baris FAILED setelah commit.

**Lain-lain**
- **`CONSUMED` bukan lagi status terminal.** Tabel transisi di `InventoryModels.kt` kini
  mengizinkan `CONSUMED -> RETURNED`, dan HANYA itu. Harus begitu: ONT yang sudah terpasang di
  rumah pelanggan berstatus `CONSUMED`, jadi tanpa pintu ini tidak ada cara apa pun
  mengembalikannya ke pembukuan setelah dibongkar. Yang menahan penyalahgunaannya bukan tabel
  transisinya melainkan tiga penjaga di atasnya: hanya unit `CONSUMED` yang bisa di-scan, satu
  unit hanya boleh ditarik sekali (indeks parsial `work_order_recovered_asset_active_uq`), dan
  saldo tidak bergerak sebelum orang LAIN menyetujui WO-nya. `CONSUMED -> LOST/DISPOSED` tetap
  tertutup karena cabang `WRITE_OFF_SOURCES` dievaluasi lebih dulu.
- **`returnToWarehouse` sekarang menerima DUA status asal, bukan satu.** Dulu `resolveAssets(...)`
  menuntut `ISSUED` persis. Unit hasil penarikan DISMANTLE tiba di van sudah berstatus `RETURNED`,
  jadi bentuk lama menolaknya dan unitnya MENUMPUK di van tanpa satu pun jalan kembali ke rak —
  jalur penarikannya lengkap, ujungnya buntu. Sekarang `RETURNABLE_FROM = {ISSUED, RETURNED}`, dan
  leg OUT dikelompokkan menurut status asal TIAP unit. Pengelompokan itu bukan hiasan: proyeksi
  saldo menyimpan kuantitas per status, jadi menyamaratakan semuanya sebagai `ISSUED` membuat ember
  `ISSUED` minus sementara ember `RETURNED` di van tak pernah berkurang. Satu retur boleh memuat
  campuran keduanya. Ada tesnya di `WorkOrderAssetRecoveryIT` ("…bukan mandek di van").
- Poin 1 (PPPoE/BRAS) sudah dibangun tapi **belum diuji end-to-end**.
- Poin 2 (aplikasi teknisi mobile) masih boilerplate.
- Approval PSB tidak pernah bisa menuntaskan saga pada percobaan pertama: `PROVISIONING`
  menuntut akun BNG sudah ACTIVE, sementara aktivasinya sendiri berjalan
  `@TransactionalEventListener(AFTER_COMMIT)` dari transaksi yang sama. Jadi setiap WO PSB
  mendarat di `REQUIRES_RECONCILIATION / BNG_ACTIVATION_NOT_CONFIRMED` sampai
  `FulfillmentOutboxWorker.drain()` terjadwal mengulanginya. **Pra-ada**, bukan akibat pekerjaan
  ini, tapi setiap tes yang menyentuh alur ini harus mensimulasikan retry itu.

---

## 5. Peta migrasi

Tertinggi: **V197**. Flyway forward-only, `outOfOrder=false` — versi lebih rendah yang belum
pernah diterapkan akan menggagalkan boot.

| Versi | Isi |
| --- | --- |
| V179–V182 | Audit override darurat, tautan approval↔mutasi, induk lokasi, kebijakan approval |
| V183–V185 | Rem laju publik, penanda portal, FK WO→pesanan |
| V186 | Material work order (template BOM, rencana/realisasi, serial) |
| V187 | Tambal `created_at`/`updated_at` di `inventory_fulfillment_effect` |
| V188 | `AWAITING_RECEIPT` |
| V191 | Sebab pembatalan kunjungan |
| V192–V193 | Batch & baris impor CSV |
| V194 | Asal penanda portal (SISTEM vs OPERATOR) |
| V197 | `work_order_recovered_asset` — unit berserial yang ditarik dari pelanggan saat WO DISMANTLE |

V189, V190, V195, V196 sengaja kosong — dicadangkan untuk agen paralel dan tidak terpakai.
Lompatan nomor TIDAK masalah bagi Flyway.

---

## 6. Langkah berikutnya yang disarankan, berurutan

1. Bawa nama item/lokasi/orang ke read model gudang (§4), supaya penggabungan id→nama di klien
   bisa dibuang dan petugas tanpa `inventory.item.view` berhenti membaca kode telanjang.
2. Benahi izin daftar stock opname (§4) — approver yang tak bisa melihat antreannya itu kontrol
   yang mati diam-diam, bukan sekadar layar kosong.
3. Belum ada layar material per WO dengan scan serial; API-nya sudah lengkap
   (`InventoryAllocationApi`), tinggal layarnya. Layar yang sama harus memuat tab **penarikan
   aset** untuk WO DISMANTLE — tanpa itu jalur P2.6 hanya bisa dipakai lewat curl, dan teknisi
   di lapangan tidak punya cara mencatat ONT yang dia cabut.
4. Putuskan `WorkOrderAssignmentRef.orderId` (§3.1) sebelum ada konsumen baru yang ikut salah.
5. Uji poin 1 (PPPoE/BRAS) end-to-end.

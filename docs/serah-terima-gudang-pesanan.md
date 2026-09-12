# Serah terima — Gudang (poin 3) & Pesanan (poin 4)

Status per commit `42cd29c4`, branch `feat/gudang-dan-pesanan` (dicabang dari `main`).
Dokumen ini ditulis supaya sesi atau agen lain bisa melanjutkan tanpa mengulang penggalian.
Rencana aslinya ada di `docs/rencana-gudang-pesanan.md`; dokumen INI yang menggambarkan
keadaan sebenarnya.

---

## 1. Cara cepat memverifikasi keadaan

```bash
./gradlew :server:test --rerun          # ~24 menit
cd web && ./node_modules/.bin/vitest run # ~1 menit — 68 berkas, 452 tes, SEMUA lulus
cd web && ./node_modules/.bin/oxlint src # 0 error, 4 peringatan `react(refs)` yang memang pra-ada
cd web && ./node_modules/.bin/tsc --noEmit -p tsconfig.app.json
```

Panggil binernya langsung dari `node_modules/.bin`. `npx` di lingkungan ini di-shim dan
MENELAN perintahnya: ia mencetak `npm notice run 'tsc'` lalu keluar dengan status 0 tanpa
menjalankan apa pun — persis bentuk kegagalan yang paling berbahaya, yaitu terlihat lulus.

**Garis dasar yang SEHAT: 1774 tes, 2–3 gagal.**

Perhatikan: pada garis dasar sehat pun Gradle mencetak **`BUILD FAILED`**, karena dua kegagalan
pra-ada di bawah ini memang gagal. Jangan panik melihatnya, dan jangan pula memakainya sebagai
alasan mengabaikan kegagalan lain — nilai run dari daftar nama tes yang gagal, bukan dari kata
`FAILED`.

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
- `./gradlew ... > log 2>&1; echo "EXIT=$?"` juga MENIPU kalau yang dibaca adalah status
  perintah gabungannya: `;` membuat `echo` jadi perintah terakhir, dan `echo` selalu sukses.
  Sudah terjadi — `BUILD FAILED in 23m` terlaporkan sebagai exit 0. Yang bisa dipercaya hanya
  `grep -E "BUILD (SUCCESSFUL|FAILED)" log` atau hitungan dari
  `server/build/test-results/test/TEST-*.xml`. Selalu adu jumlah tesnya dengan garis dasar di
  atas: BUILD FAILED yang isinya persis dua kegagalan pra-ada itu BUKAN regresi.

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
| Read model gudang membawa namanya sendiri | **Selesai.** `MovementLegView`, `MovementEntryView`, `StockBalanceView`, `VanStockLineView`/`VanStockView`, `OpenCountView`, `BalanceAnomalyView`, plus seluruh permukaan `/api/inventory/approvals` kini memulangkan `itemName`, `locationKind`, dan nama orang (`actorName`, `custodyOwnerName`, `technicianName`, `custodianName`, `requesterName`, `approverName`, `delegatedFromName`). Klien TIDAK BOLEH menggabungkan id→nama lagi: `/item-master` dan `/api/users` menuntut `inventory.item.view`/`iam.user.view` yang lazim tidak dipegang petugas gudang, dan itulah yang dulu membuat layarnya mencetak UUID. Resolusi orang lewat `IamApi` in-process (tanpa `@PreAuthorize`), SATU panggilan per request, id yang ternyata id lokasi tidak ditanyakan. |
| Read model material WO membawa namanya sendiri | **Selesai (server).** `WorkOrderMaterialView.technicianName`, `WorkOrderMaterialSerialView.scannedByName`, dan `WorkOrderRecoveredAssetView.technicianName`/`recoveredByName`/`cancelledByName`. Diisi di jalur BACA **dan** SELURUH jalur tulis (`planMaterial`, `issueMaterial`, `recordMaterialUsage`, `scanMaterialSerial`, `recoverAsset`, `cancelRecoveredAsset`) — kalau satu jalur tulis lupa, layarnya berkedip dari nama ke UUID tepat setelah tombol ditekan. Resolusi lewat `WorkOrderPeopleNames` (`IamApi` in-process, tanpa `@PreAuthorize`), **satu** `usersByIds` per request: baris diambil dulu, seluruh id orang dikumpulkan ke satu `Set`, himpunan kosong tidak memanggil apa pun. Fallback id tak teresolusi = UUID-nya, bukan string kosong. `technicianName`/`cancelledByName` `null` HANYA bila id sumbernya memang null. Tidak ada kolom nama baru di tabel — nama yang tersimpan akan beku saat orangnya berganti nama. Dijaga `WorkOrderMaterialNamesIT`. |
| `locationKind` boleh `null` | **Sengaja.** `inventory_location` tidak punya kolom nama, hanya `code` + `kind` — yang dibawa `kind` karena persis itu yang digabungkan klien. Lokasi yang sudah terhapus dipulangkan `null`, bukan ditebak jadi `WAREHOUSE`; klien menulis em dash. Menebaknya membuat saldo yatim terlihat seperti stok yang jelas tempatnya. |
| `custodyOwnerName` untuk `OwnerKind.CUSTOMER` | **Sengaja UUID.** Resolusinya mencoba kamus lokasi lalu kamus pengguna, tidak bercabang pada `kind`. Menanyakan id pelanggan ke modul `customer` melahirkan siklus modul (alur fulfillment sudah berjalan ke arah sebaliknya). |
| Izin baca stock opname | **Selesai.** `inventory.count.view` baru di `PermissionCatalog`. `GET /counts/open` dijaga `@authz.canAny('inventory.count.view','inventory.count.perform','inventory.count.approve')` dan memulangkan `OpenCountView`, bukan agregat `CycleCount`. Dua izin lama itu **JEMBATAN, bukan desain**: izin di-seed dari kode dan hanya peran sistem "Tenant Admin" yang di-backfill, jadi mengandalkan izin baru saja justru MENUTUP daftar bagi petugas yang hari ini memakainya. Boleh dicabut setelah peran custom tiap tenant diberi `inventory.count.view`. Dijaga `InventoryCountAccessIT` dari dua sisi, termasuk tes negatif agar `canAny` tidak jadi stempel karet. |
| Layar material & penarikan aset per WO | **Selesai.** Dua kartu di `WorkOrderDetailBody`: `WorkOrderMaterials` (rencana/BOM, keluarkan dari gudang, pemakaian curah, scan nomor seri) dan `WorkOrderRecoveredAssets` (scan unit yang dicabut + pembatalannya). **Kartu terpisah, bukan satu**, karena arah barangnya berlawanan: material mengalir gudang→pelanggan, penarikan mengalir pelanggan→van; menyatukannya menaruh kolom "keluar" dan "masuk" bersebelahan tanpa penanda arah. Keduanya menyembunyikan diri sendiri (mengikuti `WorkOrderFiberWork`) dan BUKAN tab — halaman detail WO sengaja satu kolom yang di-scroll. `unscannedQuantity` dipajang sebagai badge peringatan DI ATAS tabel karena itulah satu-satunya hal yang menahan tombol "Selesai"; tanpa itu teknisi hanya melihat penolakan berulang tanpa tahu sebabnya. Pemilih teknisi di kartu penarikan diisi dari roster WO, bukan `/api/users`. Daftar item di formulir dirakit dari baris material + template, bukan `/item-master`. Kartu penarikan tidak menembak server sama sekali bila `workorder.material.view` tidak dipegang, dan saat itu ia MENGAKU tak bisa membaca daftarnya alih-alih bilang "belum ada" — klaim yang tak pernah diverifikasi akan meyakinkan teknisi bahwa scan-nya gagal. |
| Catatan material beku setelah WO disetujui | **Selesai.** `WorkOrderService.requireMaterialWritable` menolak 409 pada SEMUA jalur tulis (`planMaterial`, `issueMaterial`, `recordMaterialUsage`, `scanMaterialSerial`, `recoverAsset`, `cancelRecoveredAsset`) ketika `approvalStatus == APPROVED`. Persetujuan penyelia adalah titik saga memotong saldo; tulisan sesudahnya mengubah angka yang sudah dipakai memotong dan tak ada yang membukukan selisihnya. Lahir di `workorder`, BUKAN `inventory` — status & persetujuan WO tidak terlihat dari sana — dan di service, BUKAN controller, supaya jalur non-HTTP ikut terjaga. Hanya `APPROVED` yang mengunci: `null`, `PENDING`, dan terutama `REJECTED` tetap bisa ditulis, karena WO yang ditolak memang dikembalikan ke lapangan untuk DIPERBAIKI dan saldonya belum bergerak. Menggantikan penutupan sisi-klien yang lama (tombol batal dimatikan saat `DONE`), yang jalur curl-nya masih terbuka. Dijaga `WorkOrderMaterialAfterApprovalIT` (4 tes), termasuk bukti bahwa tiga jalur BACA tetap 200. |
| Pemilih van tanpa izin gudang | **Selesai.** `GET /api/work-orders/{id}/materials/van-locations`, dijaga `@authz.canAny('workorder.material.view','workorder.material.record')`, memulangkan `WorkOrderVanLocationView(id, code)` terurut kode. Dibuat karena teknisi lapangan TIDAK memegang `inventory.location.view` — dan memberikannya akan sekaligus membuka gudang, bin, serta master data gudang, jauh lebih lebar dari sekadar "van mana yang boleh kupilih". Menyaring `VEHICLE` **dan** `TECHNICIAN`: repo ini konsisten menyebut van stock sebagai dua-duanya (lihat `InventoryOperationsService`), jadi menyaring VEHICLE saja membuat tenant yang memodelkan van sebagai TECHNICIAN dapat daftar kosong. Penyaringan jenis ada di sisi `inventory` karena `LocationKind` milik modul itu. `vanLocations` SENGAJA tidak memakai `requireReadAccess` (yang menuntut `workorder.order.view`) — itu akan mengembalikan kebuntuan yang sama; yang tetap ditegakkan cuma tenant (RLS → WO tenant lain 404) dan cakupan area. Dijaga `WorkOrderVanLocationIT` (6 tes). |
| Kode van di baris penarikan | **Selesai.** `WorkOrderRecoveredAssetView.technicianLocationCode` diisi di jalur baca **dan** kedua jalur tulis. SATU pembacaan lokasi untuk seluruh daftar (id dikumpulkan ke `Set` dulu); `recoverAsset` malah **nol** query tambahan karena kodenya datang dari `holder` yang sudah dibaca untuk penjaga D6c. Kolom "Van" di layar memakainya langsung. |
| Koreksi scan nomor seri | **Selesai (layar saja — server sudah bisa sejak awal).** Tombol "Koreksi" per baris serial mengisi formulir scan dengan serial + nasib baris itu lalu memindahkan fokus ke pemilih nasib, dengan badge "Mode koreksi" yang menyebut nasib LAMA apa adanya. Gerbangnya SAMA dengan bagian scan (`canRecord && !terminal`), bukan gerbang kedua yang bisa menyimpang. Tidak ada endpoint baru dan JANGAN dibuat — lihat catatan koreksi di §4. |
| Mengosongkan rencana material | **Selesai.** `PUT .../materials` menerima `clear: true`; `lines` diabaikan saat itu. Bidang TERSENDIRI, bukan disimpulkan dari `lines` kosong, karena bentuk itu sudah berarti "pakai BOM apa adanya" sejak awal — kalau disamakan, dispatcher yang menghapus baris terakhir lalu menyimpan justru mendapat rencana PENUH kembali, tanpa satu pun pesan kesalahan. Tidak ada jalur hapus terpisah di server: `requested` kosong membuat seluruh baris jadi yatim dan jatuh ke penjaga yang memang sudah menolak baris ber-`issuedQuantity > 0`. Di layar: tombol merah + konfirmasi wajib, penolakan server ditampilkan apa adanya. Pengosongan juga TIDAK menuntut pelanggan (`customerId` di `PlanWorkOrderMaterialCommand` kini nullable, dan penjaganya pindah ke titik PEMBUATAN baris di `planMaterial`): `WorkOrder.update` boleh menulis `customerId = null` pada WO non-terminal, jadi menuntutnya di pintu masuk membuat WO yang pelanggannya dilepas setelah rencananya tersusun menolak pengosongan selamanya dengan alasan yang tak berhubungan. Efek sampingnya disengaja — `replan` baris yang sudah ada kini juga boleh tanpa pelanggan, karena ia tidak melahirkan efek saga baru. Dijaga `WorkOrderMaterialPlanClearIT` (6 tes), termasuk bukti barisnya benar-benar terhapus dari tabel (query native di dalam `TenantContext.runAs`) dan bahwa penolakan 409 meninggalkan rencana UTUH, bukan setengah terhapus. |
| N+1 master barang di read model material | **Selesai.** `InventoryItemRepository.findAllByIds(ids)` baru; `materials()` dan `recoveredAssets()` menyusun katalognya SEKALI per request. Bukan `findAll(tenantId)` — itu menyeret seluruh katalog tenant demi belasan baris dan pada tenant ber-ribuan SKU ongkosnya lebih besar dari N+1 yang mau dihindari. Himpunan kosong tidak menyentuh basis data. Item yang hilang TETAP melempar `NotFoundException`, sama seperti `itemOf` yang digantikannya: baris material menunjuk item lewat FK, jadi id tak teresolusi berarti master barangnya rusak. |
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
- Formulir PENGELUARAN barang (`IssueSection`) masih butuh `iam.user.view` untuk memilih pemegang
  custody: daftar calonnya datang dari `/api/users`. Berbeda dari pemilih van yang sudah dibereskan
  — aktornya di sini petugas GUDANG, yang memang lazim memegang izin itu, jadi ini jauh lebih
  jinak. Kalau kelak ada tenant yang membatasinya, polanya sudah ada: bikin endpoint sempit di
  bawah namespace work order, jangan melebarkan izin.
- **KOREKSI atas revisi dokumen sebelumnya — baca ini sebelum membangun apa pun soal serial.**
  Versi lalu menulis "tidak ada jalur mengoreksi scan nomor seri yang salah" dan menaruhnya
  sebagai langkah nomor satu di §6. Itu SALAH, dan siapa pun yang mempercayainya akan membangun
  endpoint yang tidak perlu. `WorkOrderMaterialLine.attachSerial` sudah melakukan UPSERT sejak
  awal — `serials.filterNot { it.assetId == serial.assetId } + serial` — dan
  `usedQuantity`/`returnedQuantity`/`lostQuantity` SELALU dihitung ulang dari daftar serial, bukan
  ditambah inkremental, persis supaya koreksi tidak melenceng. Id baris serialnya pun sengaja
  dipertahankan (`priorId`) karena itulah `targetId` saga; id baru akan memotong saldo dua kali.
  Jadi: men-scan ulang nomor seri yang sama di WO yang sama dengan nasib berbeda MENGGANTI catatan
  lama. Yang kurang cuma di layar, dan **itu sudah ditutup** (tombol "Koreksi" per baris serial).
  JANGAN tambahkan endpoint "hapus serial": ia justru merusak idempotensi yang sekarang jalan.
- `WorkOrderRecoveredAssetView` masih membawa `customerId` sebagai UUID telanjang, tanpa
  `customerName`. **Ini bukan kelalaian, ini tembok arsitektur**: `inventory` tidak boleh
  bergantung pada modul `customer` (`ModularityTests` menolaknya, dan arahnya akan melahirkan
  siklus karena `customer` sudah menyentuh permukaan gudang). Klien juga dilarang menggabungkan
  id→nama sendiri. Jalan keluarnya kelak salah satu dari: `CustomerApi` base-package bertipe
  `namesByIds` yang dipanggil in-process seperti `IamApi`, ATAU membawa nama pelanggan ikut serta
  sejak baris penarikan dibuat. Sampai itu diputuskan, kolom "dicabut dari pelanggan siapa"
  memang TIDAK ditampilkan — lebih baik hilang daripada memajang UUID.
- `issueMaterial` memulangkan status berbeda untuk kekeliruan yang SAMA: mengeluarkan item yang
  tidak ada di rencana menjawab **400** kalau itemnya ada di master, tapi **404** kalau UUID-nya
  tak dikenal — karena pesan 400-nya dirakit lewat `itemOf(...)`, yang melempar `NotFoundException`
  lebih dulu. Dari sisi pemanggil keduanya sama-sama "item ini tidak ada di rencana". **Pra-ada.**
- `PlanMaterialLineBody.quantity` memakai `@PositiveOrZero`, jadi baris rencana berkuantitas 0
  diterima dan tersimpan sebagai baris hidup. Akibatnya "0" dan "tidak ada" jadi dua keadaan
  berbeda di rencana, dan layar akan memajang baris yang tak menjanjikan barang apa pun.
  **Pra-ada**; belum jelas apakah disengaja (merencanakan nol = "sengaja tidak dibawa") atau
  kelalaian. Putuskan sebelum ada laporan yang menjumlahkannya.
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

**Read model yang menyulitkan UI** (ditemukan saat membangun layarnya)
- **Izin non-`.view` yang menjaga permukaan BACA juga memicu 402, bukan cuma 403.**
  `AccessChecker.isWrite(code) = !code.endsWith(".view")`, dan izin tulis melewati
  `assertNotLocked` — jadi endpoint baca yang dijaga izin tanpa akhiran `.view` menolak tenant
  yang langganannya tertunggak saat sekadar MEMBACA. Sudah diperbaiki untuk `/counts/open`;
  permukaan lain belum disisir satu per satu. **ATURAN repo: hanya izin `*.view` yang boleh
  menjaga pembacaan.**
- **`custodianName` sudah dipulangkan permintaan approval tapi belum dipakai layar mana pun.**
  Bukan bug, tapi kolom "pemegang custody yang diminta" itu justru konteks yang dibutuhkan
  approver sebelum memutuskan.
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

Tiga butir teratas revisi lalu (koreksi scan, pembatalan setelah approval, pemilih van) SUDAH
tertutup — lihat §2. Yang tersisa:

1. Putuskan `customerName` di baris penarikan aset (§4). Ini keputusan ARSITEKTUR, bukan
   pekerjaan menyalin kolom: `inventory` tidak boleh bergantung pada `customer`, jadi pilihannya
   `CustomerApi.namesByIds` in-process seperti `IamApi`, atau menitipkan nama pelanggan sejak
   baris penarikan dibuat. Sampai diputuskan, kolomnya sengaja tidak ditampilkan.
2. Putuskan `WorkOrderAssignmentRef.orderId` (§3.1) sebelum ada konsumen baru yang ikut salah.
3. Uji poin 1 (PPPoE/BRAS) end-to-end.
4. Telusuri `VpnIT > provisioning end-to-end` yang flaky (§1). Selama ia masih berkedip, setiap
   run penuh menuntut pembacanya menebak apakah 3 kegagalan itu normal atau regresi — dan tebakan
   semacam itu cepat atau lambat akan menelan regresi sungguhan.

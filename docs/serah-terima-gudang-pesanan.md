# Serah terima — Gudang (poin 3) & Pesanan (poin 4)

Status per commit `3a1ca732`, branch `feat/gudang-dan-pesanan` (dicabang dari `main`).
Dokumen ini ditulis supaya sesi atau agen lain bisa melanjutkan tanpa mengulang penggalian.
Rencana aslinya ada di `docs/rencana-gudang-pesanan.md`; dokumen INI yang menggambarkan
keadaan sebenarnya.

---

## 1. Cara cepat memverifikasi keadaan

```bash
./gradlew :server:test --rerun          # ~18 menit
```

**Garis dasar yang SEHAT: 1733 tes, 3 gagal.**

| Tes yang gagal | Sifat |
| --- | --- |
| `ProvisioningMigrationCompatibilityIT > v125 ...` | **Pra-ada**, gagal identik di `main`. Tesnya memigrasi sampai V124 lalu memakai kolom yang baru lahir di V125. |
| `ProvisioningMigrationCompatibilityIT > v127 ...` | **Pra-ada**, sama, untuk V126/V127. |
| `VpnIT > provisioning end-to-end ...` | **Flaky.** Lulus di sebagian run, gagal di sebagian lain, tanpa perubahan kode. Belum ditelusuri. |

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
| Layar/UI gudang | **BELUM ADA.** Seluruhnya baru API. |

### Poin 4 — Pesanan & pelanggan

| Hal | Keadaan |
| --- | --- |
| Portal pesan & lacak publik | **Selesai.** `/api/public/orders/{tenantSlug}`. Slug/nomor/HP salah dijawab kalimat identik supaya tak ada enumerasi. |
| WO PSB otomatis saat ACCEPT | **Selesai**, satu transaksi dengan gerbang idempotensi. |
| Penanda portal otomatis | **Selesai.** Tiga pemicu; kalimat untuk pelanggan ditulis sistem, bukan operator/teknisi. |
| Impor CSV massal | **Selesai.** Pratinjau sebelum commit, alasan per baris, idempotensi dua lapis, batas 2 MiB / 1000 baris, berkas ekspor Excel diterima apa adanya. |
| Layar/UI pesanan | **BELUM ADA.** Seluruhnya baru API. |

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
- `IamApi.usersWithRole` sudah ada di `iam` tapi **belum disambungkan** ke matriks
  approval. Selama belum, tenant tetap harus menyebut UUID approver satu per satu.
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
- P2.6 (DISMANTLE → `returnFulfillment`) belum dikerjakan.
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

**Lain-lain**
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

Tertinggi: **V194**. Flyway forward-only, `outOfOrder=false` — versi lebih rendah yang belum
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

V189, V190, V195, V196 sengaja kosong — dicadangkan untuk agen paralel dan tidak terpakai.
Lompatan nomor TIDAK masalah bagi Flyway.

---

## 6. Langkah berikutnya yang disarankan, berurutan

1. Sambungkan `IamApi.usersWithRole` ke matriks approval gudang (pondasinya sudah ada).
2. UI gudang: stok per lokasi, permintaan restock + antrean approval, material per WO dengan
   scan serial.
3. UI pesanan: antrean, layar impor CSV (pratinjau → commit), daftar pesanan bertanda sistem.
4. Putuskan `WorkOrderAssignmentRef.orderId` (§3.1) sebelum ada konsumen baru yang ikut salah.
5. Uji poin 1 (PPPoE/BRAS) end-to-end.

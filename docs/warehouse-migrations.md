# Migrasi dan lingkungan QA warehouse

Baseline `ebf98fdf270b30ac30b7a01b1f609b39e8414618` memiliki 169 migrasi,
versi maksimum `V172__evidence_retention_claim_state.sql`. Celah V56-V58
adalah riwayat, bukan slot bebas. Manifest ini dibekukan untuk branch
`feat/warehouse-workorder`. Task04 menambahkan V173 serta M02 V174, V174.1,
V174.2 dan V174.3; versi historis tidak diubah.

| Slot | Versi | Pemilik tugas | Cakupan |
| --- | --- | --- | --- |
| M01 | V173 | 04 | Precision, masters, identity claims, cutover/auth fences |
| M02 | V174, V174.1, V174.2, V174.3 | 04 | Documents, posting, reservations, inspection, scopes, material plans; canonical identity, provenance chain and aggregate lot capacity guards |
| M03 | V175 | 11 | Approval, counts, remaining operations |
| M04 | V176 | 19 | Assignments, customer installation episodes |
| M05 | V177 | 43 | Preservation, staging, reconciliation |
| M06 | V178 | 43 | Admission-scoped constraints and compatibility gates |

## M01/M02: persistence task04

Reservasi tambahan V174.1 dicatat sebelum file dibuat setelah probe PostgreSQL
menemukan upper/btrim default berbeda dari codec Kotlin untuk Unicode/whitespace.
Versi ini berada setelah V174 dan sebelum M03 V175, tidak menduduki slot owner
lain, tidak memakai ulang gap, dan tidak mengubah checksum V173/V174 yang sudah
diuji pada database task. PostgreSQL ICU root collation `und-x-icu` digunakan
untuk uppercase penuh (misalnya sharp-s -> SS), dengan himpunan whitespace
Character.isWhitespace/isSpaceChar yang sama dengan Kotlin trim. Candidate
menyimpan canonical/claim sebelumnya; claim alias lama tidak dihapus atau
diberikan kepada aset lain.

V173 membuat SKU/conversion/supplier, identity claim/candidate, cutover dan epoch
IAM, lot/segment; V174 membuat document/line, operation/outbox/inbox, reservation,
material plan/line, usage snapshot, inspection dan warehouse scope. Semua tabel
tenant baru memakai FORCE RLS, USING/WITH CHECK dan foreign key tenant gabungan.
`inventory_segment.id` adalah stockIdentityId; untuk SERIAL sama dengan assetId.
Lot dan segment bukan pengganti asset fisik lama.

Kolom quantity/raw serial/MAC lama tidak dihapus atau ditafsirkan ulang. Quantity
lama boleh null untuk baris baru yang hanya mempunyai quantity_base; pembaca lama
harus memakai proyeksi kompatibilitas, bukan mengubah MM menjadi count.
Serialized movement leg lama mempunyai unit yang diketahui (EA); bulk legacy dan
saldo dengan unit tidak terbukti tetap quantity_base/base_unit null. Seluruh
baris lama tetap LEGACY_UNRESOLVED dan tidak masuk dimensi saldo VERIFIED.

Satu claim unik tanpa predicate mencadangkan setiap grup canonical; candidate
menyimpan semua sumber asset, tombstone dan ONU beserta raw aslinya. Grup bertabrakan
CONFLICT tanpa admitted_asset_id; tidak ada pemilihan pemenang. Candidate hanya
ditulis migration owner. Deferred FK diverifikasi sebelum ALTER TABLE berikutnya
agar upgrade dengan kandidat tidak gagal karena pending trigger events.

Dokumen dimulai DRAFT; header dan lines membeku setelah transisi pertama. Revisi
update harus tepat sebelumnya+1. Operation/original response, event, inbox,
inspection, usage snapshot, lot dan posting APPLIED append-only. Outbox adalah
event immutable; task06 tetap bertanggung jawab atas protokol delivery/retry.
Receipt origin dan claim SERIAL divalidasi di akhir transaksi untuk mendukung
insert atomik document/line/asset/segment tanpa menciptakan origin palsu.

`InventoryTenantInitializationApi` adalah API internal owner, bukan controller.
Caller wajib memakai transaksi aktif dan TenantContext tenant yang dibuat;
initializeNewEmptyTenant memeriksa waktu pembuatan tenant setelah activation
migrasi serta ketiadaan stock/history. Existing tenant diinisialisasi LEGACY,
termasuk tenant kosong. Missing policy menghasilkan CUTOVER_REQUIRED. Tidak ada
inisialisasi otomatis pada read atau listener asinkron yang mengaku atomik.

InventoryTenantPolicyService menyediakan fence transaksi, C10 allowlist dan
LEGACY->VALIDATING dengan watermark/batch/pending legacy movement IDs. Fence
tidak menggantikan permission check atau memberi hak approval. Operasi migrasi
yang perlu persetujuan tetap INDEPENDENT_APPROVER_REQUIRED; finalisasi mengembalikan
typed INDEPENDENT_APPROVAL_NOT_INSTALLED. Guard DB juga menutup VALIDATING->ENFORCED
sampai pemilik approval menyediakan validasi nyata melalui migrasi forward-only
yang terkoordinasi. Task05/06 wajib menghubungkan seluruh writer lama/baru ke
posting dan fence sebelum mengaktifkan operasi warehouse. Task04 tidak memasang
receipt API, posting service, permission adapter, assignment atau workflow WO.

Uji task04:

```sh
scripts/warehouse/qa.sh server --tests '*WarehouseSchemaIT*' --rerun-tasks --no-parallel
```

Suite menjalankan clean/upgrade semua migration dalam schema temporer bernama
unik, SQL adversarial sebagai warehouse_app, JPA round-trip dan dua Spring context
baru untuk restart. Schema fixture dihapus, database/volume task dipertahankan.
Owner dipakai hanya untuk DDL/fixture upgrade, bukan bukti akses aplikasi.

Jangan memakai ulang atau menomori ulang migrasi yang sudah diterapkan.
Jika merge menduduki slot ini, hentikan implementasi dan sepakati migrasi
kompatibilitas forward-only. Tambahan file per slot memerlukan pembaruan
manifest terkoordinasi sebelum SQL dibuat. Database produksi tidak diperiksa
atau diubah oleh tugas baseline ini.

## QA lokal terisolasi

Prasyarat: Linux, Bash, Docker Compose, akses socket lokal Docker (atau
`sudo -n docker`), JDK21/Gradle wrapper, Node/npm, curl, openssl, flock.
Port 25432 dan 29000 harus bebas. Tidak ada fallback ke `ftth_test`.

```sh
scripts/warehouse/test-environment.sh up
scripts/warehouse/test-environment.sh check
scripts/warehouse/qa.sh server --tests '*WarehouseEnvironmentIT*' --rerun-tasks --no-parallel
scripts/warehouse/qa.sh stop
scripts/warehouse/test-environment.sh down
```

Runner membuat `.omo/runtime/warehouse-test.env` milik user dengan mode0600.
Jangan source, cetak, atau commit file ini. Namespace Compose memuat hash
worktree dan token acak; container, network, volume, dan kedua database
memiliki marker. Endpoint dipublikasikan hanya pada 127.0.0.1.
`warehouse_owner` adalah pemilik migrasi NOSUPERUSER dengan BYPASSRLS untuk
backfill lintas tenant; `warehouse_app` bukan owner, bukan anggota owner,
NOSUPERUSER/NOCREATEROLE/NOCREATEDB/NOBYPASSRLS. Extension dipasang admin
hanya pada database task-owned. Aplikasi mendapat DML, bukan DDL.

Semua override konfigurasi dari shell ditolak sebelum akses DB. Runner
mengekspor datasource Spring dan Flyway secara terpisah setelah validasi.
Override inline lama pada `SchedulingSpringContextIT` juga mengikuti
`SPRING_DATASOURCE_URL`; ini penting karena properti annotation Spring test
berprioritas lebih tinggi daripada environment. Fallback lama di test tersebut
tetap hanya untuk eksekusi di luar runner, bukan jalur warehouse QA.
Gradle dan lifecycle lingkungan menggunakan satu lock per worktree.
`down` hanya menghapus container/network milik task dan mempertahankan volume.
Startup yang diinterupsi membersihkan container task, sehingga `up` dapat
diulang dengan env/volume yang sama. Marker tidak cocok harus diselidiki,
bukan diatasi dengan menghapus volume lama atau melonggarkan validator.
`stop` tetap dapat berjalan sesudah `down`: cleanup lokal memeriksa file env
dan identitas PID tanpa memerlukan koneksi DB yang sudah dihentikan.

`web-test FILTER`, `web-check`, dan `kmp` mengikuti command plan. KMP gagal
secara eksplisit hingga modul materials tugas42 tersedia. Browser juga
gagal eksplisit hingga config/spec tugas31 dan isolasi adapter mutasi eksternal
tersedia; tidak ada hasil browser palsu atau backend produksi yang dipakai.
Profil `application-warehouse-e2e.yml` tugas31 harus mematikan adapter mutasi
perangkat/notifikasi eksternal tanpa mengganti layanan warehouse/customer/WO
atau storage dengan mock. Runner menolak browser sebelum profil ini ada.
Sesudah tersedia, runner memakai metadata `server/build/warehouse/boot-jar-path.txt`
dari task bootJar (bukan wildcard JAR), port17880/14188 dengan proxy eksplisit,
JSON health backend/proxy, laporan Playwright nonzero pada desktop/mobile,
serta cleanup process group dengan PID/start-time/marker yang tercatat.
Readiness wajib menyertakan health contributor `components.warehouse.details`
dengan `database=warehouse_e2e`, `user=warehouse_app`, dan `marker` dari
`WAREHOUSE_ENVIRONMENT_MARKER`; respons generik UP saja tidak diterima.
Contributor task31 harus membaca identitas dari koneksi DB aktif, bukan
sekadar menyalin environment. Detail ini hanya boleh terbuka di profil lokal.
Config Playwright task31 harus mengaktifkan reporter JSON (boleh bersama line),
mengikuti `PLAYWRIGHT_JSON_OUTPUT_NAME`. Laporan hilang, nol test pada salah
satu project, test dilewati, flaky, atau gagal membuat runner gagal.

## 2026-09-07: koreksi verifikasi independen task04

Manifest M02 kini **V174, V174.1, V174.2, V174.3**. V174.2 diperiksa bebas
dan dicadangkan sebelum SQL dibuat. V173/V174/V174.1 tetap byte-identical;
V175-V178 tetap milik task berikutnya. Koreksi forward ini menutup AV-01
(rantai VERIFIED), AV-02 (opening tanpa approval), dan AV-03 (lot serta saldo
parent split), bukan migrasi approval/assignment baru.

Pemeriksaan admission/provenance dan spendability memakai constraint trigger
deferred sehingga receipt dan split atomik diperiksa terhadap keadaan akhir
transaksi. Parent terminal tidak boleh menyisakan saldo VERIFIED positif atau
encumbrance terbuka. Alias claim lama tetap dicadangkan; RETIRED bukan izin stok.
Opening memakai satu extension point DB document/revision-bound yang selalu
menolak sampai task12 memasang pembuktian approval independen yang nyata.

Tenant owner menerbitkan event root saat tenant benar-benar dibuat. Listener
inventory dan IAM sinkron, MANDATORY, fail-fast pada transaksi yang sama;
tidak memakai AFTER_COMMIT/best-effort. JDBC scoped di koneksi Hibernate yang
sama memulihkan GUC asal tanpa mengganti tenant EntityManager di tengah transaksi.
Pengiriman event ganda tidak mempromosikan policy existing atau menaikkan epoch.

## V174.3: kapasitas agregat root lot

V174.3 dicadangkan setelah pemeriksaan slot bebas, sebelum SQL dibuat. Tidak ada
perubahan byte V173/V174/V174.1/V174.2 atau pemakaian slot V175-V178.
Jumlah seluruh root VERIFIED (`parent_segment_id IS NULL`) per tenant/lot tidak
boleh melebihi received_quantity_base. ACTIVE, SPLIT dan RETIRED tetap dihitung;
child hanya mewakili pemecahan root, bukan penerimaan fisik tambahan.

BEFORE INSERT/UPDATE mengunci row lot lama/baru menurut tenant/id, lalu constraint
deferred memeriksa SUM numeric terhadap keadaan akhir transaksi. Mutasi segment
lot memakai READ COMMITTED (READ UNCOMMITTED PostgreSQL ekuivalen); snapshot
transaction REPEATABLE READ/SERIALIZABLE ditolak40001 agar snapshot lama tidak
melewati SUM setelah menunggu writer READ COMMITTED. Caller harus mengulang
seluruh transaksi dengan profil row-lock READ COMMITTED; tidak ada pelemahan
append-only lot hanya untuk memperbarui counter alokasi.

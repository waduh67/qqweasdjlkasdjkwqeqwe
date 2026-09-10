# Migrasi dan lingkungan QA warehouse

Baseline `ebf98fdf270b30ac30b7a01b1f609b39e8414618` memiliki 169 migrasi,
versi maksimum `V172__evidence_retention_claim_state.sql`. Celah V56-V58
adalah riwayat, bukan slot bebas. Manifest ini dibekukan untuk branch
`feat/warehouse-workorder`. Task04 menambahkan V173 serta M02 V174, V174.1,
V174.2, V174.3, V174.4 dan V174.5; versi historis tidak diubah.

| Slot | Versi | Pemilik tugas | Cakupan |
| --- | --- | --- | --- |
| M01 | V173 | 04 | Precision, masters, identity claims, cutover/auth fences |
| M02 | V174, V174.1, V174.2, V174.3, V174.4, V174.5, V174.6, V174.7, V174.8, V174.9, V174.10, V174.11, V174.12, V174.13, V174.14 | 04 / 06 / 07 / 08 / 10 | Documents, posting, reservations, inspection, scopes, material plans; canonical identity, provenance chain, aggregate lot capacity, internally scoped deferred validators, durable delivery metadata, fulfillment observations and WO reference revisions; master metadata, control-plane operation binding, reference admission, tenant/site/area consistency and effective ancestry guards; receipt intake snapshots, secured evidence and exact inspection disposition; immutable intake-content evidence binding; reservation allocation links and demand supply snapshots |
| M03 | V175, V175.1, V175.2, V175.3, V175.4, V175.5, V175.6, V175.7 | 11 / 12 | Versioned policy/rules/tiers/approvers, warehouse applicability, durable settings replay, delegation lifecycle; approval/count source snapshots and repair/replenishment foundations; table-specific policy child validation and exact-value compatibility; sealed approval queue, candidate requirements and command receipts; atomic terminal/effect constraints and deferred tenant assertion; attempted-decision replay context and durable decision bindings |
| M04 | V176 | 19 | Assignments, customer installation episodes |
| M05 | V177 | 43 | Preservation, staging, reconciliation |
| M06 | V178 | 43 | Admission-scoped constraints and compatibility gates |

## M03: reservation task11

Task12 reserves `V175_3__warehouse_durable_approvals.sql` before SQL creation.
It adds sealed evaluation/source snapshots, immutable tier/candidate requirements,
actor-bound replay and document-bound effect receipts. Legacy approvals remain
staged, never inferred from movement IDs. V175 through V175.2 and all V174 bytes
remain unchanged. Opening approval stays closed without a migration source owner.

V175.4 was reserved before SQL creation to bind the terminal approved outcome to
its owner posting, outbox and inbox receipt at transaction commit. Its header
expiry check alone did not cover later balance-lock waits; AV12 adds the typed
pre-admission/post-lock application guard. Applied migration bytes are preserved.

V175.5 is reserved before SQL creation after the task04 regression gate found
the new deferred approval validator missing its own entry scope assertion.
The correction preserves V175.4 and adds `warehouse_assert_deferred_scope`
inside the validator, not a bypassable companion trigger.

AV12 reserves V175.6 for immutable attempted-decision command context and V175.7
for insert/deferred decision bindings before SQL creation. Slots verified free;
V175.3-.5 and all predecessors remain unchanged. Null-policy legacy decisions
must never attach to executable source-bound requests. No V176+ changes.

Task11 reserves `V175__warehouse_policy_foundations.sql` before creating SQL.
V174.14 and every earlier migration remain byte-identical. V176 and later slots
remain reserved for their existing owners. This expansion does not create
approval requests, decisions, movements, repair jobs or replenishment requests.
Policy settings are control-plane operations; evaluation is not approval.

V175.1 is reserved before SQL creation after the real policy-child insertion
test exposed PostgreSQL record-field resolution across heterogeneous triggers.
V175 was already applied successfully and remains unchanged; V175.1 makes the
approver-only tier check a separate PL/pgSQL branch.

V175.2 is reserved before SQL creation to let new source-bound approval/count
rows omit legacy integer projections rather than fabricate zero or truncate
exact base quantities. Legacy rows remain unchanged; complete new source
snapshots are required whenever those compatibility projections are absent.

## M01/M02: persistence task04

Task10 mencadangkan V174.14 sebelum SQL dibuat. Tambahan ini mengikat reservasi
ke line demand/plan dan revisi sumber nyata, menyimpan snapshot shortage, serta
mengizinkan siklus reservasi baru setelah release tanpa membuka reservasi terminal.
Release/expiry mengembalikan state demand sesuai jumlah tersisa. Semua byte
V173-V174.13 dipertahankan; V175+ tidak dipakai. Gate task04 wajib diulang.

AV8 mencadangkan V174.13 sebelum SQL dibuat, setelah memastikan slot bebas.
Revision/hash konten intake terpisah dari revision operation dokumen. Evidence
baru terikat snapshot konten yang tepat; evidence historis tanpa binding tetap
disimpan tetapi tidak boleh mengotorisasi inspeksi baru. Binding tidak direkayasa
dari snapshot receipt terkini. V174.12 dan seluruh byte sebelumnya dipertahankan;
V175+ tidak dipakai. Gate schema mencakup seluruh tabel task08 dan upgrade174.13.

Task08 mencadangkan V174.12 sebelum SQL dibuat. Tambahan M02 menyimpan snapshot
intake draft, bukti objek privat dan disposition inspeksi yang terikat identitas,
line dan operation. Stok tetap melalui posting task05/command task06. Opening
balance tetap fail-closed tanpa approval independen task12. Tidak mengubah byte
V173-V174.11, tidak memakai V175+, dan tidak mengaktifkan policy approval baru.

Task07 mencadangkan V174.8 sebelum SQL dibuat: metadata category/model/minimum
SKU, site location, serta binding operation master tanpa dokumen stok palsu.
Operasi stok tetap wajib memiliki document_id; hanya namespace master bernama
yang boleh memakai binding master. Guard arsip/referensi dan hierarki diperketat
secara forward-only. V173 hingga V174.7 tidak diubah; V175+ tetap milik task lain.
Gate perubahan ini mencakup seluruh WarehouseSchemaIT, restart/upgrade, dan
WarehouseMasterIT melalui warehouse_app non-owner dengan FORCE RLS.

V174.9 dicadangkan setelah uji V174.8: pertahankan SQLSTATE 23503 untuk referensi
master asing/hilang (bukan 23514), tutup binding operation NULL, dan cegah hard
delete serta referensi saldo/posting baru ke master arsip. V174.8 yang sudah
diterapkan pada warehouse_test tidak diedit ulang.

AV7-02 mencadangkan V174.10 sebelum SQL dibuat, setelah pemeriksaan seluruh
V174.x memastikan slot bebas. Tambahkan key tenant/site bila belum tersedia,
FK gabungan inventory_location ke site, serta guard area site dan parent/site.
FK NOT VALID mempertahankan siteId historis yang sudah dangling tanpa menghapus
data; write baru wajib valid, read/replay menyembunyikan reference lama tidak
konsisten. Jangan memvalidasi atau memperbaiki reference historis secara diam-diam.
Tidak ada perubahan byte V174.9 ke bawah atau pengambilalihan slot V175+.

V174.11 dicadangkan sebelum SQL dibuat setelah pemeriksaan slot V174.x. Koreksi
inheritance menambah fence revisi topology per tenant (FORCE RLS), validasi
deferred atas seluruh ancestry dan subtree yang berubah, scope assertion pada
validator sebenarnya, serta penolakan cycle/depth overflow. Site efektif adalah
satu-satunya site non-null pada rantai, bukan site immediate parent. Histori
tidak konsisten dipertahankan tanpa backfill destruktif dan disembunyikan reader.
V174.10 dan seluruh migrasi sebelumnya tetap byte-identical; V175+ tidak dipakai.

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

## V174.4: scope tenant saat constraint deferred dieksekusi

V174.4 diperiksa bebas dan dicadangkan sebelum SQL dibuat. Byte/checksum V173
sampai V174.3 tidak berubah; slot V175-V178 tidak digunakan. Constraint kapasitas
menolak scope tenant kosong/berbeda dan lot referensi yang tidak terlihat atau
hilang, bukan melewati validasi ketika RLS menyaring row tersebut.

Guard deferred invoker-rights juga menjaga tenant row untuk segment, lot, asset,
balance, reservation, claim dan usage snapshot. Tidak ada SECURITY DEFINER atau
penonaktifan RLS. Tenant GUC harus cocok saat validasi commit; context yang
dipulihkan sebelum commit diperiksa normal. Staging oleh migration owner setelah
V174.4 juga harus memasang context tenant secara eksplisit, bukan memanfaatkan
BYPASSRLS sebagai pengganti scope transaksi.

## V174.5: scope internal pada setiap validator deferred

V174.5 diperiksa bebas dan dicadangkan sebelum SQL dibuat. V173-V174.4 tetap
byte-identical dan V175-V178 tetap milik task berikutnya. Audit katalog menemukan
20 trigger constraint warehouse DEFERRABLE (selain FK internal PostgreSQL),
menggunakan delapan fungsi. Kapasitas lot dan companion sudah memiliki assertion
internal; enam fungsi lain diperbarui tanpa mengubah logika domain setelahnya:
asset claim, stock provenance, source provenance, origin, segment conservation,
dan usage postings. Semuanya SECURITY INVOKER.

Setiap fungsi memanggil `warehouse_assert_deferred_scope(NEW.tenant_id)` sebagai
statement pertama, sebelum IF/SELECT/loop. Scope bukan bukti yang dapat dipakai
ulang dari companion: `SET CONSTRAINTS ... IMMEDIATE` pada constraint lain tidak
boleh menghilangkan validasi yang masih pending. Pengujian mengisolasi setiap
fungsi dengan mengeksekusi constraint lainnya terlebih dahulu, termasuk data
valid pada scope correct/restored dan penolakan data invalid melalui constraint
aktualnya sendiri. Mutasi source baru sesudah validasi awal diperiksa ulang.

## Reservasi V174.6: metadata command dan delivery task06

Slot V174.6 diperiksa bebas sebelum file SQL dibuat. Patch M02 ini hanya
menambahkan metadata canonical command immutable dan state delivery mutable yang
mereferensikan outbox immutable. Payload/event lama tidak diubah. Semua byte
V173-V174.5 tetap dipertahankan; V175-V178 tetap milik owner berikutnya.
Gate WarehouseSchemaIT wajib dijalankan kembali setelah patch.

## Reservasi V174.7: delivery produksi dan revisi referensi

Slot V174.7 diperiksa bebas sebelum SQL dibuat. Tambahan M02 ini membuat jurnal
observasi provenance milik fulfillment, revision fence WO/roster dan larangan
menghapus state delivery. Tidak mengaktifkan settlement, approval atau assignment
baru. Seluruh byte V173-V174.6 dipertahankan dan gate task04 dijalankan kembali.

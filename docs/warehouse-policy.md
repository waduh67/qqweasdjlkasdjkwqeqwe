# Kebijakan dan ruang akses gudang

Task11 menyediakan konfigurasi durable dan evaluasi server. Evaluasi **bukan
persetujuan**: tidak membuat request, keputusan, movement, atau efek stok.
Queue, keputusan maker-checker, dan posting atomik tetap milik task12.

## HTTP

Semua endpoint berikut memakai prefix `/api/v1/warehouse/settings`.
Identitas tenant/aktor berasal dari autentikasi dan diperiksa ulang melalui
IAM current-authority fence, bukan hanya claim JWT lama.

| Method/path | Izin dan hasil |
| --- | --- |
| GET `/policy` | `inventory.approval.view`; `{configured,current}`, termasuk `configured:false` untuk tenant belum dikonfigurasi |
| GET `/policy/history?page=0&size=25` | `inventory.approval.view`; versi immutable terbaru dahulu, maksimum100 |
| PUT `/policy` | `inventory.approval.manage`; ganti seluruh kebijakan dengan `expectedRevision` dan `Idempotency-Key` |
| GET `/scopes/{userId}` | `inventory.location.view`; grant/revocation lokasi yang dapat diakses aktor |
| PUT `/scopes/{userId}/{locationId}` | `inventory.location.manage`; `{expectedRevision,active}` dan `Idempotency-Key` |
| GET `/delegations` | `inventory.approval.view`; grant durable beserta expiry/revocation |
| POST `/delegations` | `inventory.approval.manage`; grant scoped dan time-bounded, `Idempotency-Key` |
| POST `/delegations/{id}/revoke` | `inventory.approval.manage`; `{expectedRevision}` dan `Idempotency-Key` |
| POST `/evaluate` | approval view atau izin operasi yang sesuai; hanya `{sourceDocumentId,sourceRevision}` |

Revisi kebijakan/grant baru adalah1; `expectedRevision:0` berarti belum ada.
Scope warisan task07 dapat memiliki revisi0; gunakan revisi hasil GET untuk
resource yang telah ada. Mutasi berikutnya harus tepat pada revisi sekarang.
Kunci replay terikat namespace, aktor, hash payload server, resource, dan scope.
Replay mengembalikan body asli setelah pemeriksaan otoritas saat ini. Aktor lain,
aktor dinonaktifkan, atau scope aktor dicabut tidak dapat mengambil respons lama.
Policy/scope/delegation changes mengambil cutover fence sebelum exclusive IAM
fence dan menaikkan epoch dalam transaksi yang sama. History tidak diperbarui.

Contoh policy (UUID diisi dari API IAM/master):

```json
{
  "expectedRevision": 0,
  "currency": "IDR",
  "expiryHours": 24,
  "warehouseIds": ["00000000-0000-0000-0000-000000000001"],
  "rules": [{
    "operation": "ADJUSTMENT",
    "tiers": [{
      "minimumMinor": "100000",
      "userIds": ["00000000-0000-0000-0000-000000000002"],
      "roleIds": []
    }]
  }]
}
```

Currency wajib eksplisit, threshold berupa string integer positif, tiers harus
naik tanpa duplikasi, expiry policy1-720 jam. User/role approver harus aktif/ada
dan memiliki `inventory.approval.decide`; harus ada jalur independen dengan area
dan warehouse scope yang sesuai. Role IAM tidak memiliki status aktif tersendiri;
role yang dihapus atau kehilangan permission tidak lagi memenuhi persyaratan.
Penggantian memerlukan akses ke seluruh warehouse policy lama dan baru.

## Setup tenant kosong

Gunakan IAM HTTP yang ada: buat area dan berikan area ke administrator, buat
role approver, buat user kedua, tetapkan role/area, lalu buat warehouse melalui
master API task07. Berikan scope eksplisit kepada approver, baru simpan policy.
Policy/delegation setup tidak otomatis memberikan izin stok atau semua warehouse.
Setup master task07 yang sudah ada tetap tersedia dalam LEGACY/VALIDATING.

Untuk administrator IAM tanpa riwayat warehouse scope, first-scope exception
hanya berlaku pada permintaan grant **untuk dirinya sendiri**, `active:true`,
`expectedRevision:0`, belum ada policy, belum pernah ada baris scope aktor,
dan current permissions mencakup `iam.user.assign`, `iam.role.create`,
`inventory.approval.manage`, serta `inventory.location.manage`. Lokasi harus aktif
dan area/site tetap diizinkan. Operation menyimpan `bootstrap:true` secara
append-only. Setelah grant pertama/policy tersimpan, pengecualian ditutup;
revocation tidak membuka kembali pengecualian. Sebelum grant eksplisit itu,
scope kosong tetap berarti nol akses stok.

## Evaluasi dan handoff task12

`WarehousePolicyEvaluationApi.evaluate(source)` mengambil cutover/current IAM
fence dan mengunci source document serta memeriksa revisinya. Policy replacement
memakai exclusive authority fence sehingga versi current tidak dapat berubah
selama evaluasi. `evaluateForAction(source,action)` adalah port server-only untuk
named workflow; action harus cocok dengan jenis dokumen, misalnya
ISSUE/ISSUE_EXCEPTION. Action tidak diterima dari body HTTP.

Nilai diambil dari line dokumen dan original lot cost lineage. Numerator biaya
minor-unit dikalikan quantity base aktual dan dibagi denominator base aslinya.
Penjumlahan memakai rasional integer eksak; tidak ada pembulatan/FX/zero fallback.
`COST_BASIS_REQUIRED` meminta cost basis yang belum tersedia;
`CURRENCY_MISMATCH` meminta currency sumber yang sama dengan policy.

Receipt/issue biasa tanpa rule threshold yang memerlukan approval menghasilkan
`IN_POLICY`. Exception selalu memerlukan tier pertama, termasuk nilai di bawah
threshold pertama; tier lanjutan berlaku ketika nilai mencapai thresholdnya.
`APPROVAL_REQUIRED` memuat immutable policy version, tier/candidate requirements,
excluded identities, exact value/currency, authority epoch, dan hash server.
Task12 harus menyimpan snapshot ini dan mengevaluasi ulang authority/source saat
keputusan/eksekusi; hasil evaluasi tidak mengotorisasi posting dengan sendirinya.

Requester, custodian teknisi, counter, dan delegate dari identity terlarang tidak
dapat memenuhi requirement. Platform privilege tidak menghapus independensi.
Tidak ada kandidat independen menghasilkan `INDEPENDENT_APPROVER_REQUIRED`.
User/role/area/scope dicek dari keadaan durable saat evaluasi.

Delegation memakai `approverId`, `delegateId`, optional `sourceRoleId`,
`locationId`, `operation`, `validUntil`, dan `expectedRevision:0`. Awal grant
berasal dari clock PostgreSQL; akhir wajib di masa depan, maksimum30 hari.
Tidak ada self grant, chain, atau cycle. Role source harus dimiliki delegator
dan cocok dengan rule policy. Expired/revoked grant tidak memberikan authority.
Grant tidak dapat diedit atau dihapus; revocation adalah perubahan terminal
ber-revisi dengan actor/time serta immutable operation receipt.

## Kompatibilitas approval lama

`POST /api/inventory/approvals` sekarang hanya menerima
`{sourceDocumentId,sourceRevision}`. Body berisi `approverIds`, `tiers`, `amount`,
`value`, `currency`, `policySnapshotHash`, `operationHash`, `movementId`, target
efek, atau field authority lain ditolak400 `MALFORMED_REQUEST`.
Source-only request mengembalikan409 beserta hasil evaluasi; bukan request queue
yang diterima. Legacy decision dengan hash/movement override juga ditolak400;
decision tanpa override tetap fail-closed409 sampai workflow durable task12.
Map request/decision legacy belum diganti pada task11; tidak dipakai sebagai
configuration authority dan tidak menerima request/decision baru dari HTTP ini.

## Persistence dan QA

M03 memakai V175, V175.1, V175.2; manifest diperbarui sebelum setiap SQL dibuat.
Seluruh V174.x dan slot V176+ tidak berubah. Delapan tabel tenant baru memakai
FORCE RLS, tenant composite FK, business uniqueness, index, dan append-only atau
revision guards. Approval/count tables diperluas tanpa backfill outcome palsu;
proyeksi integer legacy boleh null hanya untuk source-bound exact snapshots.
Repair/replenishment tables hanya fondasi, bukan operasi bisnis baru.

Gate task11: `scripts/warehouse/qa.sh server --tests '*WarehousePolicyIT*' --rerun-tasks --no-parallel`.
Suite mencakup HTTP setup, malformed bodies, role/scope revocation, costs,
currency, independence, delegation expiry/cycles, concurrent updates dan
SIGKILL/restart exact replay. Packaged smoke memakai real S3 adapter/local MinIO
dan `warehouse_app`, bukan owner atau BYPASSRLS. Gunakan profil non-`test` untuk
packaged JAR: ObjectStorage profil test berada di test classpath, bukan JAR.
Mail health check yang tidak dikonfigurasi harus diisolasi pada smoke harness;
tidak mematikan validator warehouse/RLS.

### Hasil verifikasi task11

Implementasi `ef47855d` diverifikasi pada 9-10 September2026, tanpa CodeGraph/LSP.
Compiler Kotlin, Spring HTTP, packaged JAR, dan PostgreSQL dipakai langsung.

| Gate | Tests | Gagal | Skipped |
| --- | ---: | ---: | ---: |
| WarehousePolicyIT, exact command run1 | 17 | 0 | 0 |
| WarehousePolicyIT, exact command run2 | 17 | 0 | 0 |
| Core/contracts/quantity/schema/current-authority/IAM/Modularity | 332 | 0 | 0 |
| Masters/posting | 173 | 0 | 0 |
| Receipt/query/reservation/PDF, final rerun | 248 | 0 | 0 |

Gabungan unik gate di atas:770 test. Satu invokasi seluruh suite mencapai batas
1800 detik runner dan **tidak dihitung lulus**; tiga batch bounded menggantikannya.
Batch reservation pertama menemukan fixture yang memakai aktor revoked untuk
memulihkan scope sendiri. Fixture sekarang membuktikan self-restore ditolak dan
memakai manager lain yang masih berizin; focused6 dan full248 rerun lulus.

Clean no-cache `:server:clean :server:bootJar --no-build-cache --rerun-tasks
--no-parallel` lulus. SHA256 JAR:
`3fe14759fab3796e7c81b87d2980169cc552980f161610d4022f0d10b43afd32`.
Packaged HTTP pada localhost17911 melakukan signup tenant baru, area/user/role,
dua warehouse, scope, policy, ordinary/exception evaluation, unknown cost,
currency mismatch, revoked approver, stale revision, rejected authority fields,
SIGKILL dan exact policy replay. Probe PostgreSQL menunjukkan satu policy,
epoch14, nol decision/effect; `warehouse_app` bukan superuser, BYPASSRLS,
CREATEDB/CREATEROLE, atau pemilik hak CREATE schema. Delapan tabel baru FORCE RLS.
Smoke memakai health group DB tersendiri: aggregate health pada database QA
yang kotor dapat memuat kegagalan job fixture lain dan tidak diklaim sehat.

| Migration | SHA256 | Flyway checksum |
| --- | --- | ---: |
| V175 | `2d97d88e5a98721586b2e2f5a742cf4ae84612ce8d0abe84360da0f93e0929ce` | -1289745380 |
| V175.1 | `910d5a6790d5309f5a69bf53b06d534b399bacfe0ba9909a357970e35495a3df` | 1153309233 |
| V175.2 | `671496759ff9f2ec8b47f0135004fead7fb0a390317914f84f77f2ad9733bc1a` | -382730080 |

Tidak ada file migrasi lama diubah. Test JVM dan packaged server dihentikan;
cleanup Compose hanya menghapus container/network task dan mempertahankan volume.
Evidence/notepad lokal tidak dimasukkan ke commit.

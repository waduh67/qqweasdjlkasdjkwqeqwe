# Persetujuan dokumen durable

Task12 mengganti otoritas request/decision/effect berbasis map dengan PostgreSQL.
Model lama dipindahkan ke test source set untuk karakterisasi historis dan tidak
masuk bootJar. Policy evaluation task11 tetap bukan izin posting.

## Sumber dan batas efek

Pemilik sumber yang tersedia adalah receipt supplier task08: final approval
menjalankan penerimaan ke inspeksi melalui WarehousePosting, bukan melalui
movement ID dari klien. Satu receipt menghasilkan satu movement dan pasangan
OUT/IN; replay tidak menerima barang lagi. Inspeksi/putaway tetap terpisah.

Owner registry bersifat server-only. Sumber tanpa handler terdaftar ditolak
SOURCE_NOT_VERIFIED; tidak ada callback/class/action yang dapat dipilih klien.
Opening balance tetap fail-closed, termasuk warehouse_assert_opening_approval:
belum ada lifecycle migration batch/source/evidence yang aman. Settlement WO
task17, count task27, dan loss/scrap/adjustment task28 tidak diimplementasikan atau
diganti dengan posting generik. Handler berikutnya wajib menambahkan validasi
source dan efeknya sendiri; approval tidak boleh memotong material yang sudah
dikonsumsi.

## HTTP

Prefix utama `/api/v1/warehouse/approvals`. Tenant dan actor berasal dari
autentikasi, kemudian diperiksa ulang di bawah current-authority fence.

| Method/path | Input dan hasil |
| --- | --- |
| GET `/` | `page=0`, `size=25` (maksimum100), optional `status`; queue scoped dengan urutan requestedAt menurun lalu ID |
| GET `/{id}` | Detail status/revision/expiry dan effect operation ID bila approved |
| GET `/{id}/history` | Keputusan immutable dalam urutan revision |
| POST `/request` | Hanya `{sourceDocumentId,sourceRevision}`, Idempotency-Key;201 pending |
| POST `/decide` | `{requestId,expectedRevision,decision,reason?,evidenceReference?}`, Idempotency-Key |
| POST `/rework` | `{requestId,expectedRevision}`, Idempotency-Key; expectedRevision adalah revisi sumber setelah reject |

Request/rework membutuhkan approval.request dan approval.view serta akses sumber.
Decision membutuhkan approval.view dan approval.decide, current source-role
eligibility, area dan warehouse scope. Request hanya dapat diajukan pemilik sumber.
Reject wajib beralasan; evidence reference harus milik dokumen yang sama.
Field tambahan/authority, termasuk tier/approver/hash/value/currency/movement/
effect target, ditolak400. Anonymous401, forbidden403, sumber inaccessible404,
stale/business conflict409.

Respons command disimpan sebagai status HTTP dan body asli. GET detail/history
tidak mengirim snapshot internal biaya, policy global, atau epoch. Namespace/key
terikat actor, request dan hash payload canonical. Replay memeriksa permission,
scope dan eligibility saat ini sebelum membaca respons; fresh session actor sama
boleh, actor lain atau actor revoked tidak boleh. Respons request asli tetap
PENDING pada replay walaupun detail terkini sudah APPROVED.

## Snapshot dan concurrency

Request mengunci cutover, current authority, topology, source document/lines,
lalu request/command identity. Snapshot menyimpan full internal task11 evaluation,
policy version dan hash versi policy, exact numerator/denominator/currency,
excluded requester/custodian/counter identities, candidate/delegation requirements,
authority/cutover epoch, source header/lines/intake termasuk label dan hash konten.
Unknown cost atau currency mismatch tetap diblokir task11, bukan dianggap nol.
Snapshot tidak dihitung ulang untuk mengikuti biaya atau policy baru.

Tiers dan candidate requirements sealed pada transaksi pembuatan. Kandidat lama
bukan authority: keputusan memakai enabled users, permission role sumber,
membership, area/scope dan delegation aktif saat itu. Actual delegation snapshot
menyimpan delegator, source role, scope, validFrom/validUntil serta revision.
Requester/custodian/counter/delegated-self tidak dapat memenuhi tier, termasuk
dengan privilege platform. Identity actor maupun delegator tidak dapat digunakan
lagi untuk meniru dua tier independen. Request yang seluruh tier-nya tidak dapat
dipenuhi identity independen ditolak dengan setup error.

Document lock mendahului request lock. Tiers diproses menurut urutan snapshot;
expectedRevision harus sesuai. Final decision, request state, source owner posting,
outbox, inbox dan effect receipt berada dalam satu local transaction. Database
memastikan APPROVED memiliki jumlah keputusan lengkap dan receipt owner yang
sesuai. Guard expiry sekarang memperoleh lock admission sebelum write pertama,
dan memeriksa ulang setelah lock saldo sebelum movement header (koreksi AV12).
Owner-effect uniqueness terikat source document revision, bukan hanya key.

Revision/content/state/cutover berubah menghasilkan terminal STALE tanpa efek.
Expired menghasilkan terminal EXPIRED tanpa efek. Worker bounded memproses
expiry dengan cutover/current-authority fence dan document/request locks; lebih
dari satu worker tidak menaikkan revisi terminal dua kali. GET detail juga
menyelesaikan expiry yang ditemukan. Jadwal mengikuti ftth.scheduling.enabled.

Reject menyimpan decision dan menandai inventory_document.approval_disposition
sebagai REWORK_REQUIRED, menaikkan source revision tanpa movement/effect.
Receipt tetap draft fisik; ordinary receive diblokir. Rework oleh requester
menghapus disposition dan menaikkan revisi lagi. Resubmit membuat request baru;
request/decision lama tidak dibuka kembali.

## Kompatibilitas dan legacy staging

`/api/inventory/approvals` memakai input aman yang sama. Read `/{id}` dan
`/pending` mempertahankan approvalId/type/requesterId/custodianId/requestedAt/
expiresAt/status/revision/decisions. RECEIPT diproyeksikan sebagai RESTOCK.
Amount numeric hanya diberikan bila exact integer yang muat Long dan actor
memiliki cost.view; selain itu null, tidak membulatkan atau mengarang nol.
Pending legacy projection hanya memuat kandidat yang saat ini independen.
Write lama yang mengirim operationHash/movementId tetap400, bukan diabaikan.

Approval/decision/effect historis tanpa source binding tetap tersimpan sebagai
legacy-staged dan tidak masuk queue executable. Tidak ada inference target dari
movementId, backfill keputusan palsu, atau replay physical legacy movement.
Baris tanpa scope sumber yang dapat dibuktikan tidak diekspos sebagai detail
approval operasional; rekonsiliasi evidence-backed tetap tugas cutover berikutnya.

## Verifikasi awal task12 (sebelum koreksi AV12)

| Gate | Tests | Failures | Skipped |
| --- | ---: | ---: | ---: |
| Exact WarehouseApprovalIT run1 | 28 | 0 | 0 |
| Exact WarehouseApprovalIT run2 | 28 | 0 | 0 |
| Core/schema/current-authority/IAM/command/Modularity | 332 | 0 | 0 |
| Posting/master | 173 | 0 | 0 |
| Receipt/query/reservation/PDF | 248 | 0 | 0 |
| Policy | 27 | 0 | 0 |

Gabungan unik808 test dalam batch bounded. Exact command:
`scripts/warehouse/qa.sh server --tests '*WarehouseApprovalIT*' --rerun-tasks --no-parallel`.
Run final10m48s dan11m4s. Failing-first awal2/2; kompatibilitas read juga1/1 merah
sebelum diperbaiki. Gate schema menemukan missing deferred tenant assertion;
V175.5 memperbaikinya, termasuk test konteks tenant kosong saat constraint dipaksa.

Failure injection mencakup request, requirements, decision, owner posting/outbox,
inbox, effect receipt dan response. Semua diuji tanpa partial decision, lot,
movement/legs atau receipts. Concurrency memakai real PostgreSQL dan latch,
bukan sleep. Expiry boundary tests menggeser test-only clock spy; clock produksi
tetap PostgreSQL. Test restart memakai process baru, discarded HTTP response dan
SIGKILL. Tidak ada test yang dikecualikan/skipped.

Clean no-cache bootJar sukses38s. SHA256:
`269b9db3ea3a0ff1338332de70d2e559fc2ed7ca195f57f30c7d9a8920f53830`.
Packaged HTTP memakai dua proses localhost17912/17913, dua actor/role, source
receipt nyata, real S3 adapter dan local MinIO, isolated warehouse_test schema.
Queue survives restart; same-key concurrent final decision dan restart replay
identik. Probe menghasilkan request1/decision1/effect1/movement1/legs2/inbox1.
Authority fields400, actor lain403, disabled checker403. Role probe:
warehouse_app, bukan superuser/BYPASSRLS/schema CREATE. Test schema dan proses
manual dihapus/dihentikan; volumes tetap dipertahankan.

| Migration | SHA256 | Flyway checksum |
| --- | --- | ---: |
| V175.3 | `592043ef4402955d8bb5d6405b0c496f2014976528e6210a7c8c143b5f8aeeed` | -1346446974 |
| V175.4 | `2953350b3d2e3c3f35bafcdfc6532d0cba72aafd6df06c4cf426a5d6de0bd661` | -299268832 |
| V175.5 | `e2f61e802bf6999abbded37ba073150a88f088da63c8c487354f0a1e112ace14` | -1393074964 |

Manifest diperbarui sebelum setiap migrasi dibuat. Seluruh byte V175/V175.1/
V175.2, V174 dan slot V176+ tidak berubah. Evidence lokal berada pada
`.omo/evidence/warehouse-workorder-asset-provenance/task-12/`, tidak masuk commit.
Checkpoint implementasi/test akhir03a91019; commit dokumentasi penutup tidak
mengubah runtime. Persetujuan independen Wave2 tetap gate orchestrator.

## Koreksi verifier AV12

Verifier ses_f74f2b817ffeZ651Bnta7Fyh5Q menemukan tiga blocker pada49275c86
meskipun suite awal lulus. Ketiganya direproduksi failing-first, kemudian
diperbaiki tanpa mengubah byte migrasi terdahulu atau membuka owner baru.

### AV12-1: expiry setelah menunggu lock

ReceiptApprovalPostingGuard memperoleh lock DML tabel admission/posting/receipt,
FK master, dan key identitas SERIAL/MAC/LOT secara deterministik sebelum
decision atau admission melakukan write. Lock ROW EXCLUSIVE kompatibel dengan
DML biasa; ini bukan serialisasi seluruh transaksi gudang. Receipt membuat
stock identities baru, sehingga key unik dan hak insert tabel diamankan sebelum
identitas/balance itu ada. Jalur receipt biasa memakai urutan identity key sama.

ReceiptPostingApproval mengikat request ID, pending revision, source/revision,
policy ID/hash, source hash, cutover epoch, expiresAt, dan transaction ID.
Guard memeriksa DB clock dan source setelah lock admission, sebelum admission,
serta setelah lock balance aktual sebelum header. Bukan callback/client field;
guard hanya untuk receipt ingress. Future owner harus menyediakan validasi dan
lock protocol sendiri, bukan memakai guard receipt untuk count/settlement.

Jika guard mendapati EXPIRED/STALE, seluruh transaction percobaan dibatalkan.
Boundary decide kemudian membuka satu transaction recovery yang mengambil ulang
cutover/authority/source/request locks, mengecek current eligibility, dan menyimpan
terminal response tanpa decision/effect. Bila expiry worker sudah menang, body
terminalnya dipakai tanpa menaikkan revision lagi. Tidak ada retry physical effect.
Decide adalah boundary pemilik transaksi dan menolak ambient transaction agar
rollback selesai sebelum recovery; semua write sukses tetap satu local commit.

### AV12-2: replay tanpa decision row

V175.6 menyimpan attempted_decision immutable pada setiap command decide yang
diterima, termasuk STALE, EXPIRED dan revision/terminal conflict. Context berisi
source/request/policy binding, attempted tier/action/revision dan delegation.
Replay selalu memeriksa current eligibility terhadap attempted tier, tidak
bergantung pada ada/tidaknya decision row. Delegation yang dipakai attempt juga
harus tetap eligible. Actor berbeda, revoked role/membership/permission/area/
warehouse atau delegation tidak mendapat body asli.

Receipt sebelum V175.6 tidak ditulis ulang: tier berasal dari revision pada
original outcome dan urutan requirement immutable, lalu authority tetap diperiksa.
Metadata lama yang tidak dapat dipetakan fail-closed, bukan melewati eligibility.

### AV12-3: binding keputusan database

V175.7 menambahkan insert guard dan deferred guard dengan internal
warehouse_assert_deferred_scope. Executable request wajib memakai policy ID
matching nonnull, required tier, sealed direct candidate atau delegation valid
dari sealed source candidate, kolom/snapshot decision yang cocok, dan identity
independen. Requester/custodian/counter/excluded/delegated-self, tier/revision/
effective identity berulang ditolak. Parent request lock menserialisasi insert.

Null-policy decision hanya boleh merujuk request historis yang benar-benar
unbound (source/evaluation/policy-version ID null). Upgrade test mempertahankan
baris legacy, termasuk bentuk historis yang tidak valid untuk workflow baru;
baris itu tidak menjadi executable. Tidak ada penghapusan/penulisan ulang histori.

### Bukti koreksi

Baseline JAR269b9db3 direproduksi lagi dalam turn koreksi, bukan hanya dibaca dari
laporan verifier. Dengan deadline DB-clock+10s dan balance table lock14s yang
teramati, baseline menghasilkan APPROVED:1/decision1/effect1/movement1/legs2.
JAR terkoreksi menghasilkan EXPIRED:1/decision0/effect0/movement0/legs0/lot0.

Packaged dua-proses membuktikan revoked STALE replay berubah409-body-asli menjadi
403; EXPIRED replay juga403 setelah revocation. Fresh-session eligible replay
tetap byte-identical setelah restart. Tiga direct warehouse_app forgeries yang
diterima baseline sekarang ditolak, sementara proper application approval sukses.
Positive packaged concurrency tetap request1/decision1/effect1/movement1/legs2/
inbox1. Non-owner role bukan superuser/BYPASSRLS/schema CREATE.

| Gate koreksi | Tests | Failures | Skipped |
| --- | ---: | ---: | ---: |
| Exact WarehouseApprovalIT run1 | 38 | 0 | 0 |
| Exact WarehouseApprovalIT run2 | 38 | 0 | 0 |
| Core/schema/command/IAM/Modularity | 332 | 0 | 0 |
| Posting/master | 173 | 0 | 0 |
| Receipt/query/reservation/PDF | 248 | 0 | 0 |
| Policy | 27 | 0 | 0 |

Total unik818. Run exact12m42s/11m25s; clock deadline fixture memakai trigger
INSERT-time hanya pada disposable schema, bukan update expiresAt produksi.
Lock races memakai latch/observed pg_blocking_pids; pg_sleep14 hanya deadline
fixture. Test recovery membuktikan decision percobaan sudah rollback sebelum
expiry worker menang. Semua failure-injection stages sebelumnya tetap lulus.

Clean no-cache bootJar38s, SHA256
`b88201c1269a7e62cc383848840d5029ced0f8ac0fd33344cc9051956698bb0e`.

| Migration | SHA256 | Flyway checksum |
| --- | --- | ---: |
| V175.6 | `d7d95d539a9ef0e5df23b360bd40b98282209821eb918460fd24dba085ff273c` | -234338125 |
| V175.7 | `a7926fcb4a96d743a3490ffc9219ad16516a5e7029e1dac701e1599907d5a828` | -1922251006 |

Semua V175.3-.5 dan predecessor tetap byte-identical; V176+ tidak disentuh.
Evidence/notepad lokal: task-12/corrections. Checkpoint runtime/test aa12a06d;
commit penutup hanya dokumentasi. Re-verifikasi independen tetap diperlukan.

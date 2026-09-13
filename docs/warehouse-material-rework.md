# Rework material setelah pemakaian

`PUT /api/work-orders/{id}/materials/plan` tetap menolak plan yang memiliki
physical history. Tidak ada flag untuk melewati `assertReplaceable()`.
Rework menggunakan command tersendiri pada WO `IN_PROGRESS` dengan QA `REJECTED`.
WO cancelled/done, material CLOSED, actor yang tidak berwenang dan revision stale
ditolak tanpa perubahan plan atau stok.

## Konteks dan command

Ambil `GET /api/work-orders/{id}/materials/rework-context` terlebih dahulu.
Owner memberikan `expectedRevision`, `workOrderRevision`, `previousPlanId`,
`previousUsageId`, `expectedUsageRevision`, `previousEvidenceRevision` dan
`evidenceRevision`. Caller tidak perlu membaca database atau menghitung hash.

Tambahkan `reason` nonblank dan `deltas` ke objek itu, lalu kirim
`POST /api/work-orders/{id}/materials/rework` dengan `Idempotency-Key`.
Setiap delta berisi SKU, positive `quantityBase`, `baseUnit` dan `continuousCut`.
Substitution, negative quantity dan empty delta tidak diterima oleh jalur ini.
Tenant, actor dan authority tidak berasal dari body.

`previousEvidenceRevision` adalah canonical hash submission sebelumnya yang
disimpan owner WO. `evidenceRevision` adalah revision kumpulan artifact aktif
sekarang. Kedua token diverifikasi terhadap owner di bawah lock WO. ID, kind dan
source setiap artifact dibekukan dengan FK tenant ke registry evidence.
Perubahan evidence setelah pembacaan konteks membuat command atau alokasi baru
stale; immutable receipt historis tidak ditulis ulang.

## Delta, bukan penggantian history

Plan baru menyimpan **hanya demand tambahan**. Seluruh line lama diwarisi melalui
ID dan snapshot immutable, beserta predecessor plan/revision. Contoh:

- Plan1 requested100000MM, issued100000MM dan used82500MM tetap utuh.
- Rework plan2 menambah10000MM. Total requested menjadi110000MM.
- Reserve/pick/dispatch pada plan2 hanya mengalokasikan10000MM baru, bukan
  mengeluarkan ulang100000MM lama.
- Summary menghitung setiap plan dalam lineage satu kali. Physical obligations
  tetap dihitung dari semua issue/usage tanpa double counting.

Plan delta langsung SUBMITTED dalam transaksi rework yang sama. Old/new plan,
prior usage, old/new evidence, inherited lines, reason, actor dan replay outcome
commit/rollback bersama. Plan, usage, evidence content dan facts lama tidak diubah.

## Pemakaian dan resubmission

Setelah rework aktif, `/materials/correct-use` memerlukan `reworkId` dan
`evidenceRevision` selain expected WO/use revisions dan source receipt/identity.
Usage baru memakai plan/revision baru. Source line dan requested quantity tetap
menunjuk plan asal bila memakai acknowledged remnant yang diwarisi.
Fresh receipt dari demand tambahan juga dapat digunakan; predecessor global
usage dan source-specific prior usage disimpan terpisah.

Contoh alur teruji:82500 awal +5000 dari receipt baru +7500 dari remnant lama
menghasilkan95000 consumed dan15000 accountable dari110000 issued. Ketiga usage
revision tetap immutable; original82500 tidak didebit lagi. QA resubmission
memverifikasi coverage usage seluruh inherited/new lines, bukan hanya delta
terakhir, dan tidak menambah posting konsumsi.

## Integritas dan replay

Same-key exact replay mengembalikan bytes asli setelah pemeriksaan authority dan
revision/evidence yang masih berlaku. Changed payload/new key untuk predecessor
yang sudah dipakai ditolak. Direct reservation owner dan physical operation
inserts memakai rework evidence fence yang sama; tidak ada bypass melalui route
inventory. Safe release/unpick tetap tersedia.

Tabel rework memakai FORCE RLS, composite tenant FKs, append-only children dan
final-state guards. Raw post-use plan tanpa rework lineage ditolak. Pending
positive rework demand juga mencegah material close sampai issued atau WO
dibatalkan; physical residual/pending inspection tetap mengikuti aturan task18.

Migrations V175.44-.47 bersifat forward-only. V175.37-.43 dan seluruh predecessor
tidak diubah. Task19 assignment, generic transfer task25, inspection/reissue task26,
UI dan mobile bukan bagian command ini. Checkbox task18 tetap untuk review independen.

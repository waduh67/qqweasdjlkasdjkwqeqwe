# Lifecycle dan sisa material WO

Fulfillment mengoordinasikan API publik workorder dan inventory. Workorder tetap
memiliki lifecycle, roster dan QA; inventory memiliki identitas, posisi, posting,
otorisasi handover dan snapshot pertanggungjawaban. Tidak ada repository lintas
module dalam koordinasi ini.

## Pembatalan dan penugasan

Pembatalan memegang fence cutover/current authority dan lock WO yang sama dengan
reservasi, pick, receipt dan pemakaian. Preflight menolak material picked dengan
`MATERIAL_UNPICK_REQUIRED` (409). Operator harus menjalankan unpick issue secara
eksplisit sebelum membatalkan. Hanya reservasi unpicked yang dilepas atomik;
pelepasan tidak membuat movement fisik baru.

Transit, material acknowledged dan consumed tidak dikembalikan oleh pembatalan.
Pemakaian82500 dari100000MM tetap82500 consumed dengan17500 yang harus
dipertanggungjawabkan. Mengganti roster tidak mengubah satu pun posisi fisik.
Teknisi lama kehilangan hak pemakaian baru, tetapi masih dapat mengembalikan
identitas yang benar-benar berada dalam custody-nya. Pembacaan privat memeriksa
izin dan scope lokasi saat ini, termasuk setelah penugasan dicabut.

## API sisa material

Semua mutasi memakai header `Idempotency-Key`; body tidak menerima tenant, actor,
custodian, movement atau approval authority. Quantity adalah string integer base
unit; `17500` MM berarti17.500m, bukan angka floating point.

| Endpoint di `/api/work-orders/{id}/materials` | Fungsi |
| --- | --- |
| GET `/settlement` | Status teknis, QA, provisioning owner dan material terpisah |
| POST `/settlement` | Tutup material hanya bila seluruh kewajiban nol |
| POST `/return` | Sender memindahkan sisa acknowledged ke return transit nonavailable |
| POST `/handover/authorize` | Dispatcher mengikat sumber, quantity dan receiver dari lokasi teknisi tujuan |
| POST `/handover` atau `/reallocate` | Sender menjalankan handover custody WO yang telah diotorisasi |
| POST `/residuals/acknowledge` | Receiver mengakui dokumen residual secara independen |
| POST `/correct-use` | Tambah measured-use delta positif dengan referensi usage sebelumnya |

Return menyebut receipt, issue line, usage/remnant bila sudah digunakan, identitas
stok dan lokasi quarantine. Dispatch memposting debit custody dan kredit transit
sekali. Receiver berizin dan berscope menerima menjadi QUARANTINE, tidak AVAILABLE.
Sender tidak boleh menggantikan receiver acknowledgement.

Handover di sini khusus custody dalam WO yang sama. Dispatcher, sender dan named
receiver adalah pihak berbeda. Receiver diturunkan dari lokasi TECHNICIAN aktif
dan roster WO, bukan klaim authority dalam body. Partial handover membentuk child
identity eksplisit; sisanya tetap pada sender. Receiver tidak boleh memakai
material sebelum acknowledgement. Ini bukan transfer gudang generik task25 atau
alokasi demand lintas WO.

## Snapshot dan closure

Dokumen issue menjadi asal kewajiban immutable dengan due timestamp24 jam sejak
dispatch. Lifecycle CANCEL/REASSIGN/REWORK/RESUBMIT/CLOSE menambah revision beserta
source-line snapshots. Persamaan setiap snapshot dalam base unit:

`issued = used + returned + transferred_out + disposed + still_accountable`

Handover antar-teknisi pada WO yang sama tidak mengurangi total kewajiban WO;
quantity handover dan kedua custodian ada pada dokumen residual immutable.
Returned quarantine tetap masuk outstanding closure sampai disposition inspection
yang sah tersedia. Task18 tidak menyediakan bypass untuk tahap task26 tersebut.

State material adalah OPEN, SETTLING, CLOSED atau OVERDUE. OVERDUE diturunkan saat
baca dari timestamp durable, tanpa scheduler yang memindahkan stok. Close dengan
sisa, transit, reservasi atau pending inspection mengembalikan
`MATERIAL_OBLIGATION_OUTSTANDING` (409) tanpa perubahan. Penutupan material tidak
mengubah status teknis atau QA. `OWNER_APPLIED` pada provisioning berarti efek
pemilik fulfillment telah diterima, bukan bukti perangkat jaringan sudah selesai.

Delta pemakaian bersifat positif dan merujuk usage revision sebelumnya serta
current acknowledged custody. Ia menambah snapshot, posting dan fact baru;
fact task16 dan receipt settlement task17 tidak ditulis ulang. Koreksi yang
berarti pengembalian fisik harus memakai return, bukan negative usage.

Checkpoint ini belum menuntaskan acceptance rework task18: penggantian material
plan setelah issue/usage masih ditolak oleh `MaterialPlanningStore.assertReplaceable`,
dan lifecycle belum menyimpan tautan eksplisit ke revision plan/evidence baru.
Positive usage delta bukan pengganti kemampuan replanning tersebut. Checkbox
task18 harus tetap terbuka sampai owner workflow dan pengujiannya ditambahkan.

## Integritas dan batas

Tabel baru memakai FORCE RLS, FK tenant gabungan dan append-only guards. Snapshot
quantity harus cocok dengan source graph. Posisi residual divalidasi terhadap
paired immutable postings pada final transaction state, termasuk DELETE,
temporary-zero/restore dan selective deferred timing. Closure juga diperiksa
ulang pada final state bila ada perubahan dokumen atau reservasi.

Task19 customer assignment, task25 generic transfers, task26 inspection/reissue,
repair/RMA, UI dan mobile tidak diimplementasikan di sini.

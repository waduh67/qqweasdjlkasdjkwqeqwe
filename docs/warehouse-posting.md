# Posting gudang atomik (task 5)

`WarehousePostingService` adalah otoritas posting internal, bukan endpoint atau
pengganti otorisasi task 6. `WarehousePost` membawa referensi dokumen/revisi,
operation dan hasil internal yang dibentuk server, leg berdimensi penuh,
reservasi, split, fakta, usage snapshot, serta event opsional. Jangan deserialize
tipe internal ini dari body HTTP. Caller memegang fence cutover dalam transaksi
asal, serta fence authority/WO yang diperlukan entry point sebelum masuk posting.
Service memerlukan transaksi READ COMMITTED dan tenant Hibernate/GUC yang sama.

## Transaksi dan konservasi

Satu transaksi lokal menyimpan operation, revisi dokumen, header APPLIED, paired
legs, saldo, reservasi, posisi/custody serial, fakta material/fulfillment, usage
snapshot dan outbox. Error pada salah satu tahap menggagalkan seluruh transaksi.
Tidak ada delivery/retry lease, inbox consumer, authorization replay, atau
pemeriksaan IAM baru pada task ini. Posting reference duplikat ditolak; tidak
mengembalikan payload lama sebelum task 6 memasang pemeriksaan otorisasi sekarang.

Konservasi dihitung checked `StockQuantity` per SKU/lot/unit dan identitas atau
parent split yang sama. MM/serial dipindahkan utuh; pemakaian sebagian MM harus
membuat child CUT/REMNANT dan mengosongkan parent SPLIT dalam posting yang sama.
Parent tidak dapat dipakai lagi. Bulk EA dapat terbagi ke beberapa dimensi dengan
jumlah posisi sama dengan kapasitas identitas. Serial selalu 1 EA dan satu posisi
positif. Riwayat receipt, leg, unit, origin dan claim tidak ditulis ulang.

Master lokasi bernama `RECEIPT_SOURCE` (TRANSIT) adalah boundary penerimaan,
bukan stok yang dapat diissue. Hanya receipt terverifikasi pertama untuk identitas
itu boleh memiliki OUT dari boundary ini; leg tersimpan dengan status
`RECEIPT_SOURCE`, tanpa saldo negatif pemasok. Pasangan IN adalah stok fisik.
Rebuild mengecualikan status boundary immutable, bukan lookup nama lokasi terbaru.
Lokasi `CONSUMED` (CUSTOMER_SITE) adalah sink eksplisit: balance tetap ada untuk
konservasi/provenance, status CONSUMED, tidak boleh menjadi source posting biasa.
Consume hanya dari posisi ISSUED teknisi, dengan fakta kuantitas sink yang sama.
Nama master ini perlu disediakan oleh setup/lifecycle berikutnya; tidak ada
auto-create phantom stock atau fallback lokasi.

Reserve/release/pick menyimpan encumbrance, bukan leg fisik. Pick mengganti
unpicked dengan picked; dispatch menutup satu reservation sambil memindahkan
fisik. Split dapat menutup reservation parent dan membuat reservation child dengan
jumlah unpicked/picked yang sama. Saldo akhir tidak boleh di bawah encumbrance.
Customer-owned/quarantine/transit/consumed tidak menjadi reservation tersedia.

## Lock dan rebuild

Setelah fence lebih tinggi, dokumen/origin dan line dikunci berurutan, lalu lot,
identitas `(SKU,stockIdentityId)`, kemudian dimensi penuh terurut. Pembuatan saldo
nol memakai UNIQUE dimensi dan `ON CONFLICT DO NOTHING`, dilanjutkan row lock/read.
Seluruh OUT diperiksa sebelum IN; serial source dikosongkan sebelum target diisi.
FK task 4 memakai key-share dan beberapa trigger memakai FOR UPDATE: membuat draft
line yang menunjuk stok bersama lalu melakukan posting bersaing dalam transaksi
yang sama dapat memerlukan retry seluruh transaksi setelah 409. Untuk transisi
normal, simpan draft lebih dahulu dan jalankan posting pada dokumen yang sudah
committed. Tidak ada retry sebagian tahap atau izin saldo negatif.

`rebuild(expectedCutoverEpoch)` mengambil fence tenant eksklusif tanpa mengubah
policy. Fence menunggu semua posting aktif dan menahan posting baru sampai commit.
Ledger immutable diurutkan menurut waktu server/ID dan diagregasi dalam transaksi
READ COMMITTED yang kini konsisten. Saldo VERIFIED direkonsiliasi in-place tanpa
TRUNCATE, DELETE, atau mengubah staged legacy. Intermediate zero rows tidak tampak
di luar transaksi; kegagalan rebuild rollback. Reservation tetap tabel encumbrance
durable dengan snapshot perubahan pada event. Maintenance ini memblokir posting
tenant sementara, bukan rebuild online tanpa blocking.

## Kompatibilitas

DurableInventoryFulfillmentService, MaterialConsumptionService dan adapter
InventoryMovementApi menerjemahkan legacy serialized commands lewat posting yang
sama, dengan acknowledged issue, tenant/custodian/source revision yang cocok.
Legacy integer bulk yang tidak memiliki unit terverifikasi ditolak, tidak dianggap
MM/EA. Retur menuju inspeksi, bukan menciptakan IN dari ketiadaan barang. Read fakta
count legacy tetap dipertahankan, MM tidak dikonversi menjadi integer count.
Epoch authority pada snapshot compatibility berasal dari issue tersimpan, bukan
klaim bahwa otorisasi sekarang telah dicek; task 6 tetap harus memasang fence IAM.

Helper ledger/reconciliation in-memory lama tidak pernah Spring bean dan kini
berada di test sources untuk karakterisasi historis saja. Tidak dikemas dalam
bootJar. Lifecycle approval/count dan assignment tetap milik task berikutnya.
Tidak ada migration V173-V174.5 yang diubah dan tidak ada slot migration baru.

Verifikasi:

```sh
scripts/warehouse/qa.sh server --tests '*WarehousePostingIT*' --rerun-tasks --no-parallel
```

Suite mencakup 1000 m + 10 ONU, issue 100 m + 1 ONU, use 82.5 m + 1 ONU,
retur/inspeksi 17.5 m, rollback tiap tahap, reservasi/split, entry point lama,
duplicate/stale/quantity/context, race identitas terakhir, missing-row, urutan
multi-dimensi, rebuild bersaing, dan fresh-context restart.

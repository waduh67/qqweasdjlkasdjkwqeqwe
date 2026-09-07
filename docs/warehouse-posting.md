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

## Koreksi verifier AV5

Bagian ini memperketat implementasi task 5 di atas; bukan implementasi task 6.

- Status OUT harus cocok dengan posisi yang dikunci. Semua IN untuk satu dimensi
  harus menyatakan status yang sama. Status hasil berasal dari IN tersebut, atau
  status posisi sebelumnya bila posting hanya mengurangi kuantitas dimensi itu.
  Urutan leg tidak pernah menentukan status saldo atau custody serial.
- Setiap leg fisik baru menyimpan revisi state dimensi berikutnya pada kolom
  `inventory_movement_leg.revision` yang sudah tersedia. Nomor berasal dari row
  saldo terkunci dan disimpan immutable bersama leg; rebuild memulihkan high-water
  mark ini bila row proyeksi hilang. Rebuild memilih state IN terbaru menurut
  revisi tersebut, bukan UUID leg/posting. Waktu server hanya menjadi urutan
  fallback untuk riwayat lama dengan revisi nol. Riwayat lama dengan state IN
  bertentangan pada urutan yang sama ditolak sebagai ambigu, bukan ditebak atau
  diubah. Perubahan status dan partial bulk tetap konsisten setelah rebuild.
- Tidak ada publikasi `PostingPhaseReached`, callback tahap, atau
  `ApplicationEventPublisher` dalam jalur posting produksi. Hanya row outbox
  durable yang menjadi batas delivery. Uji rollback delapan tahap memakai
  decorator JDBC yang dipasang eksplisit dari test sources; class/injector itu
  tidak berada dalam bootJar dan tidak otomatis ditemukan Spring.
- Pembuatan atau kenaikan encumbrance memerlukan posisi tepat yang dikunci,
  kuantitas positif, status AVAILABLE, SERVICEABLE milik ISP, serta lokasi ACTIVE
  dengan `issue_eligible=true`. Scope custody harus cocok dengan jenis lokasi:
  WAREHOUSE pada WAREHOUSE/BIN, TECHNICIAN pada TECHNICIAN, VEHICLE pada VEHICLE.
  Penurunan/release accountability yang sudah ada tetap boleh setelah status atau
  eligibility lokasi berubah. Ini bukan pengganti pemeriksaan izin IAM task 6.
- Customer/work order fakta dan work order usage harus cocok dengan dokumen
  posting yang dikunci. Sink konsumsi berkustodian CUSTOMER yang sama dengan
  customer fakta. Referensi issue eksplisit juga mengikat context, line,
  kuantitas acknowledged, unit dan identitas asli/descendant yang sama. Posting
  standalone tanpa fakta tidak memerlukan customer buatan.
- `quantity_base` Long dan unit selalu disimpan independen dari proyeksi legacy.
  Kolom legacy `quantity` diisi hanya untuk EA yang muat dalam Int; MM atau EA
  lebih besar tetap null. Pembaca count legacy tidak mengekspos fakta tersebut;
  bukan overflow, truncation, atau konversi MM menjadi count. V2 nanti dapat
  membaca field exact yang sudah durable.

Tidak ada byte migrasi yang sudah diterapkan, RLS, admission, provenance,
lot-capacity, atau deferred tenant-scope guard yang diubah untuk koreksi ini.

## Pengikatan aktual dan transisi pick

- Eligibility diperiksa bila total encumbrance **atau picked** meningkat. Pick
  unpicked40 menjadi picked40 bukan kenaikan total, tetapi tetap membutuhkan
  posisi AVAILABLE dan lokasi eligible. Penurunan/release tetap boleh; unpick
  memindahkan picked ke unpicked tanpa menambah total atau saldo fisik, dan tidak
  membuat posisi IN_TRANSIT/noneligible menjadi available.
- Setiap leg OUT maupun IN wajib sama dengan identitas line terkunci atau
  descendant-nya. Descendant yang baru dibuat harus berasal dari split dalam
  posting yang sama; identitas committed mengikuti lineage database. Kesamaan SKU
  atau ancestor bersama tidak mengizinkan sibling menggantikan alokasi line.
  SKU, lot dan unit aktual juga harus cocok dengan line.
- Kuantitas OUT/IN harus berpasangan per line. Kuantitas yang dialokasikan tidak
  boleh melebihi quantity line. Split dapat mempertahankan REMNANT pada posisi,
  custody, condition, owner dan status source yang persis sama: bagian retained
  itu tidak dihitung sebagai issue/consume kedua. Jika seluruh hasil split tetap
  berupa remnant lokal, line mengikat kuantitas parent penuh. Retained remnant
  tetap wajib memenuhi lineage dan konservasi fisik; label REMNANT tidak dapat
  menyembunyikan pemindahan ke custody/lokasi/condition/owner/status lain.
- Source issue mengikat identitas **aktual**, termasuk child, bukan hanya ID yang
  tertulis pada draft line. Line issue acknowledged dikunci `FOR NO KEY UPDATE`
  untuk menserialisasi kuantitas: total alokasi dari seluruh line dalam satu
  posting ditambah fakta used/returned yang telah diposting lewat issue line itu
  tidak boleh melampaui accepted quantity. Riwayat tetap immutable; bukan counter
  in-memory atau implementasi replay/IAM task 6.
- Movement tanpa fakta tetap mengikat lineage, SKU, unit dan quantity issue yang
  dirujuk. Quantity acknowledged serta pengurangan oleh fakta terdahulu berlaku
  untuk posting material accountable; pemeriksaan struktural tidak dilewati
  hanya karena daftar fakta kosong.

## Fakta hanya untuk alokasi yang benar-benar berpindah

Hasil validasi `PostingLineBindings` sekarang membawa quantity alokasi, leg IN,
leg retained, leg yang layak menjadi fakta, dan kapasitas fakta per line. Satu
perhitungan posisi/identitas yang sama dipakai untuk retensi fisik dan kelayakan
fakta; persistence fakta tidak menebak ulang status remnant.

- Fakta harus menunjuk tepat satu leg IN pada tepat satu line. Quantity fakta
  harus sama dengan leg itu; total fakta per line tidak boleh melampaui kapasitas
  faktual maupun quantity alokasi line tersebut.
- REMNANT yang tetap pada posisi/custody/condition/owner/status source tidak
  menghasilkan fakta used/returned/installed. Pure local cutting atau pertukaran
  mapping line juga tidak dapat menyatakan stok yang sebenarnya tetap di tempat
  sebagai returned. Pemeriksaan posisi memakai pasangan identitas yang sama atau
  parent split yang tepat di seluruh posting, bukan sekadar urutan line.
- Split retained tanpa fakta tetap sah dan konservasi fisiknya tidak diubah.
  RETURN100m menjadi moved60m+retained40m hanya boleh menghasilkan fakta moved60m,
  bukan100m. Sisa40m baru dapat menghasilkan fakta bila benar-benar dipindahkan
  melalui posting berikutnya.
- Budget source tetap memakai alokasi yang telah dikunci dan fakta immutable
  terdahulu. Karena fakta sekarang dibatasi oleh alokasi faktualnya, retained
  material tidak dapat menyelundupkan fakta di luar accepted quantity. Tidak ada
  migration, rewrite histori, atau perubahan delivery/otorisasi task6.

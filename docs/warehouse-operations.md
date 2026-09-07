# Command warehouse dan delivery durable

Task 6 memasang otorisasi terkini, replay, fence referensi dan delivery produksi.
Tidak menambahkan endpoint operasional task7+, workflow settlement, deployment,
atau kebijakan approval task11/12.

## Otoritas saat ini

`CurrentAuthorityPersistence` mengambil shared catalog fence lalu shared row lock
epoch tenant, dan membaca ulang enabled user, role, permission aktif dan area
melalui JDBC. JWT hanya identitas dan ID sesi opsional, bukan sumber permission.
Fence berlaku hanya pada transaksi/thread asal. Area restricted kosong bukan
unrestricted. Scope gudang dibaca pemilik inventory di bawah fence IAM yang sama.

Mutasi user, role, area, bootstrap grant dan 2FA mengambil fence eksklusif sebelum
membaca target. Mutasi administratif juga mengecek permission database sekarang:
JWT lama tidak dapat dipakai untuk memberikan kembali akses yang telah dicabut.
Login/refresh diserialisasikan sebelum membaca user agar save TOTP tidak menimpa
field authority yang sudah berubah. Scope gudang mengambil cutover lebih dahulu,
lalu fence IAM eksklusif. Satu transaksi menaikkan epoch sekali; rollback
membatalkan epoch dan mutasi. Generation handle lokal menginvalidasi snapshot yang
dibuat di tengah transaksi bila hook perubahan dipanggil lagi, tanpa double epoch.

Catalog sync memiliki global exclusive fence dan mengunci epoch **seluruh tenant**,
termasuk suspended, melalui scoped JDBC dengan RLS tetap aktif. Catalog dan epoch
commit bersama. Sync tanpa perubahan tidak menaikkan epoch. Tidak ada dependensi
IAM ke inventory. Delegation berbasis memori tidak tersedia: register ditolak
`INDEPENDENT_APPROVER_REQUIRED` sampai pemilik approval menyediakan setup durable.
Tidak ada grant delegation yang berhasil atau cache delegation yang menjadi izin.

## Command, referensi dan replay

`WarehousePreparedCommand` adalah input internal, bukan body HTTP. Canonical JSON
mengurutkan property secara rekursif dan menolak duplicate key, trailing JSON,
floating-point quantity dan key malformed. Namespace/action/hash/actor/session
dibentuk server; field operation dari primitive task5 diganti, bukan dipercaya.
Referensi eksplisit hanya `document:<uuid>` dan `workorder:<uuid>`; target lain
ditolak. Revisi dokumen command sendiri memakai `expectedRevision` terpisah.

Urutan command: cutover, current authority/scope, WO references, document references,
operation identity, lalu pipeline posting task5. Owner workorder memegang row lock
serta memeriksa revision/area/customer/roster yang relevan. Semua transisi user WO
mengambil cutover/current authority sebelum row lock. Revision WO juga berubah
pada perubahan roster. Inventory mengunci dan membandingkan seluruh document
references, termasuk source document, sebelum menjalankan posting atau replay.
Stock/issue/line revisions tetap divalidasi primitive posting di bawah lock-nya.
Lock timeout, deadlock dan serialization conflict pada reference readers menjadi409.

Same-key replay mengembalikan receipt asli setelah otorisasi sekarang. Actor lain,
disabled user, permission/area/scope yang hilang atau reference revision stale
tidak menerima body sebelumnya. Sesi baru actor yang tetap berizin boleh replay.
Hash memakai referensi stabil, bukan lookup mutable saat replay. Key sama dengan
payload berbeda dan different key untuk action/document revision yang sama
ditolak. Operation, response, canonical metadata, posting dan outbox commit bersama.

DurableInventoryFulfillmentService, MaterialConsumptionService dan
InventoryMovementApi masuk WarehouseCommandService juga. Legacy consume/return
memerlukan user sekarang, customer/WO yang cocok, acknowledged issue dan custody;
consume memerlukan roster aktif. Caller tenant/actor harus cocok context, namespace
dan hash lama tidak menjadi authority. Replay merekonstruksi request dari stable
identity yang tersimpan, bukan mencari issue terbaru. Hasil movement dan waktunya
dibekukan sebelum posting. Anonymous worker tidak boleh menyamar sebagai teknisi.
Legacy fulfillment `INVENTORY` effect masuk rekonsiliasi sebelum owner effects,
bukan consume otomatis saat QA; settlement task17 tidak diaktifkan di sini.

## Dispatcher dan consumer produksi

V174.6 menyimpan canonical identity immutable dan delivery state terpisah dari
outbox immutable. V174.7 menambah jurnal provenance fulfillment, WO/roster revision
fence dan larangan menghapus delivery state. V173-V174.6 tidak diubah.

`WarehouseOutboxDispatcher` memakai node UUID, claim transaksi pendek, lalu membaca
event untuk lease yang masih cocok. Pemanggilan `WarehouseOutboxDeliveryPort`
berjalan **di luar transaksi lock**; delivered/failure ditulis pada transaksi baru
dengan owner/token/attempt/expiry fencing. Scheduler mengikuti saklar scheduling
existing dan memproses maksimal25 event per tenant setiap putaran. Payload hanya
diparse sebagai bentuk posting yang dikenal dan dicek terhadap posting ID durable;
tidak ada polymorphic class loading. Unknown route menjadi NO_HANDLER terminal,
malformed/stale event menjadi rekonsiliasi, bukan sukses kosong.

Lease memakai clock database, expiry30 detik, maksimal8 attempt, backoff maksimal
300 detik dan kode error redacted. Crash setelah delivery tetapi sebelum ACK dapat
menyebabkan redelivery: jaminannya **at-least-once delivery, exactly-once efek DB**,
bukan exactly-once pemanggilan jaringan. Terminal/retry/delivered dapat diperiksa
pada `inventory_outbox_delivery`; payload/history tidak ditulis ulang.

Adapter produksi `WarehouseProvenanceConsumer` menerima event bertipe dan memakai
`WarehouseInboxApi.consume`. Receipt inbox dan insert append-only
`fulfillment_warehouse_observation` commit/rollback bersama. Consumer authority
adalah event tersimpan yang menunjuk posting APPLIED dengan cutover epoch asal,
bukan actor dari pesan bebas. Jurnal hanya mengamati fakta committed; tidak
memposting stok lagi, menyetujui settlement atau memanggil BNG/perangkat.
Fulfillment checkpoint/legacy consumer juga mengambil cutover fence sebelum lock.
Provisioning/perangkat tetap pada jalur after-commit/outbox pemiliknya.

## Bukti

Command exact dijalankan dua kali, masing-masing39 test tanpa gagal/skipped:

```sh
scripts/warehouse/qa.sh server --tests '*WarehouseConcurrencyIT*' --rerun-tasks --no-parallel
```

Gabungan task1-6, schema, IAM/2FA, fulfillment, workorder dan modularity:453 test,
nol gagal/skipped. Arsip XML/HTML disimpan sebelum clean build di evidence task6.
Probe Spring/JVM terpisah membuktikan response-loss replay, original JWT revoked,
rollback inbox+effect, delivery-port call di luar transaksi, lost ACK dan restart:
3 outbox =3 delivered =3 inbox =3 observation, tanpa posting tambahan. Runtime
source probe diarsipkan lalu dihapus. Tidak ada test hook atau probe dalam bootJar.

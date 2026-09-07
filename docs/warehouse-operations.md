# Fondasi command warehouse task 6

Status: **task 6 belum selesai**. Primitive di bawah sudah diuji dengan transaksi
PostgreSQL; belum menjadi jalur wajib semua writer/consumer. Jangan mengaktifkan
workflow WO/customer atau menyatakan exactly-once delivery sistem berdasarkan
primitive ini saja.

## Otoritas dan receipt

`CurrentAuthorityPersistence` mengimplementasikan `CurrentAuthorityApi` milik IAM.
Shared row lock epoch bertahan sampai transaksi berakhir. Enabled user, role,
permission aktif dan area dibaca ulang melalui JDBC, bukan klaim permission JWT
atau entity JPA yang mungkin sudah tercache. JWT hanya menyediakan identitas dan
ID sesi opsional. Restricted area kosong berarti nol area, bukan unrestricted.

UserService, RoleService, AreaService dan grant AdminProvisioner mengambil fence
eksklusif sebelum membaca/mengubah target. Increment berulang dalam satu transaksi
digabung menjadi satu perubahan epoch; rollback membatalkan epoch dan mutasi.
Penulisan 2FA juga dipagar. Login/refresh mengambil fence sebelum memuat user agar
save TOTP tidak mengembalikan field authority yang sudah dicabut.

`WarehousePreparedCommand` adalah input internal, bukan body HTTP. Factory
membekukan daftar posting serta JSON kanonis, mengurutkan property secara rekursif
dan menolak duplicate key, trailing JSON, floating-point quantity serta key
malformed. Map referenced revisions masuk hash; validasi seluruh revisi lintas
owner masih harus disambungkan oleh integrasi berikutnya dalam task 6.

`WarehouseCommandService.execute` mengambil cutover lalu current-authority fence,
memeriksa permission dan scope gudang saat ini, kemudian menserialisasikan
namespace/key dengan PostgreSQL advisory transaction lock. Actor/session, hash,
namespace, business action dan original response dibentuk server; field operation
yang dibawa primitive task 5 tidak dipercaya. Same-key replay mengembalikan
receipt immutable setelah pemeriksaan actor/scope. Changed payload atau different
key untuk action/revision yang sudah diposting ditolak. Posting, identity metadata
dan receipt commit bersama.

Adapter saat ini hanya menerima RECEIVE, RESERVE, RELEASE, TRANSFER dan RETURN.
CONSUME/assignment/approval belum diaktifkan melalui adapter command ini. Tidak ada
controller HTTP baru atau jalur bypass tambahan untuk operasi yang belum didukung.

## Delivery dan inbox

V174.6 menambahkan `inventory_command_identity` immutable dan
`inventory_outbox_delivery` mutable. Payload/history outbox lama tidak diubah.
State delivery menyimpan owner UUID, token per lease, expiry dari clock database,
attempt count maksimal delapan, next attempt dengan backoff maksimal 300 detik,
DELIVERED/TERMINAL dan kode error redacted. Completion hanya berlaku bagi owner,
token, attempt dan lease aktif yang cocok. Lease expired dapat diambil node lain.

`WarehouseInboxApi.consume` adalah API internal callback **efek database lokal**,
bukan callback HTTP atau izin memanggil BNG/perangkat. Receipt dan efek callback
commit/rollback bersama. Server-consumer rule hanya menerima event outbox tersimpan
yang menunjuk posting APPLIED dan masih cocok dengan cutover epoch. Ini melanjutkan
fakta yang telah committed, bukan otorisasi user baru untuk posting stok. External
delivery harus berjalan di luar transaksi lock dan belum disambungkan di sini.

## Gate yang masih terbuka

- Dispatcher durable dan wiring consumer fulfillment produksi belum tersedia.
- Jalur legacy DurableInventoryFulfillmentService/MaterialConsumptionService belum
  memakai command replay/current-authority layer baru.
- PermissionCatalogSeeder global dan delegation InventoryApprovalService belum
  mengikuti protokol authority epoch lengkap.
- Belum ada bukti seluruh paused mint/consume race area/scope, validasi referenced
  revisions lintas owner, atau temporary manual Spring/JVM runner yang diwajibkan.

Verifikasi checkpoint: dua run exact WarehouseConcurrencyIT masing-masing 16 test,
gabungan task1-5/task6 384 test, seluruhnya nol gagal/skipped. Schema gates mencakup
V174.6, FORCE RLS, composite FK dan metadata immutable; V173-V174.5 byte-identical.
Clean no-cache bootJar berhasil. Hasil ini tidak menutup gate integrasi di atas.

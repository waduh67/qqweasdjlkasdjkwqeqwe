-- `inventory_fulfillment_effect` kehilangan `created_at`/`updated_at` sejak V162.
--
-- Entity-nya (`InventoryFulfillmentEffectJpaEntity`) turun dari `TenantAwareJpaEntity` ->
-- `BaseJpaEntity`, dan superclass itu MEMETAKAN kedua kolom tersebut ke SETIAP subclass.
-- Tabelnya tidak pernah punya kolomnya, jadi setiap SELECT lewat JPA ke tabel ini gagal
-- dengan `ERROR: column ifeje1_0.created_at does not exist` (SQLSTATE 42703).
--
-- Cacat ini tidak pernah kelihatan karena jalur bacanya mati: `InventoryApi.fulfillmentAllocations`
-- masih stub `emptyList()`, sehingga `DurableInventoryFulfillmentService.apply` — satu-satunya
-- pemanggil `findByTenantIdAndNamespaceAndOperationKey` — TIDAK PERNAH dieksekusi. Begitu stub
-- itu diisi data nyata (P2), baris pertama `apply()` langsung meledak, dan karena error-nya
-- terjadi di tengah transaksi saga, seluruh transaksi Postgres jadi aborted: efek berikutnya
-- hanya melihat `current transaction is aborted` dan saga mendarat di REQUIRES_RECONCILIATION
-- tanpa satu pun pesan yang menyebut kolom yang hilang.
--
-- Jalur tulisnya SENGAJA tetap memakai native `insertIfAbsent` dengan daftar kolom eksplisit,
-- jadi kedua kolom baru WAJIB punya DEFAULT — tanpa itu INSERT idempotent tersebut melanggar
-- NOT NULL pada baris pertama yang ditulis.
ALTER TABLE inventory_fulfillment_effect
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();

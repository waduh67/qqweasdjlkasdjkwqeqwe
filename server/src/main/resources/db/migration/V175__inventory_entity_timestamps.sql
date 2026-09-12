-- Menyelaraskan tabel inventory dengan kontrak `BaseJpaEntity`.
--
-- Setiap JPA entity di repo ini mewarisi `created_at` dan `updated_at` dari
-- `BaseJpaEntity`, jadi Hibernate SELALU menyertakan kedua kolom itu di INSERT. Lima tabel
-- inventory lahir tanpa salah satu (atau keduanya) karena sampai sekarang belum ada entity
-- yang menulis ke sana — service-nya menyimpan state di memori proses. Begitu service-nya
-- dipersistenkan, INSERT pertama akan gagal dengan "column updated_at does not exist" di
-- runtime, bukan saat build (`ddl-auto: none`, jadi tidak ada validasi skema saat boot).
--
-- Kolom ditambahkan dengan DEFAULT now() dan bukan lewat UPDATE terpisah: pada
-- `inventory_approval_decision` ada trigger `inventory_approval_decision_immutable` yang
-- MENOLAK setiap UPDATE baris, jadi backfill gaya "ADD COLUMN nullable lalu UPDATE" akan
-- meledak persis di tabel itu. Table rewrite akibat DEFAULT volatile TIDAK memicu trigger
-- baris, sehingga jalur ini yang aman untuk semuanya.
ALTER TABLE inventory_approval_decision   ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE inventory_approval_effect     ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE inventory_approval_delegation ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE inventory_cycle_count         ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();

ALTER TABLE inventory_customer_material_fact
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();

-- `inventory_cycle_count.created_at` sengaja tanpa DEFAULT sejak V147 sehingga penulis WAJIB
-- mengirim waktunya sendiri; diberi DEFAULT di sini supaya seragam dengan tabel lain dan
-- supaya INSERT manual (seed/lab) tidak perlu tahu detail itu. Nilai dari aplikasi tetap menang.
ALTER TABLE inventory_cycle_count ALTER COLUMN created_at SET DEFAULT now();

-- Efek approval dibaca per tenant untuk dashboard "apa yang sudah diputuskan"; tanpa index
-- ini query itu jadi seq scan penuh begitu volume approval tumbuh.
CREATE INDEX inventory_approval_effect_tenant_idx ON inventory_approval_effect (tenant_id, emitted_at, id);
CREATE INDEX inventory_customer_material_customer_idx ON inventory_customer_material_fact (tenant_id, customer_id, recorded_at);
CREATE INDEX inventory_approval_delegation_tenant_idx ON inventory_approval_delegation (tenant_id, delegate_id, valid_until);

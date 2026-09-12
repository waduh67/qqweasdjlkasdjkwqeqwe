-- Menyatukan dua sumber kebenaran gudang yang selama ini TIDAK PERNAH direkonsiliasi.
--
-- `inventory_serialized_asset` tahu di mana unit ONT bernomor seri X berada, sedangkan
-- `inventory_movement_leg` tahu berapa banyak barang berpindah — tapi leg tidak punya
-- kolom apa pun yang menunjuk ke aset. Jadi "ONT keluar 1 unit" dan "ONT SN-123 dipegang
-- teknisi" adalah dua pulau terpisah: kalau saldo bilang 3 unit dan daftar aset bilang
-- 2 unit, tidak ada satu pun baris yang bisa memberi tahu unit mana yang hilang.
--
-- Dua kolom di bawah ini yang menutup lubang itu. `serial_number` disalin (denormalisasi)
-- dari aset SENGAJA: ledger adalah catatan historis, dan nomor seri yang tercatat saat
-- mutasi terjadi tidak boleh ikut berubah kalau baris asetnya kelak diperbaiki atau dihapus.
ALTER TABLE inventory_movement_leg
    ADD COLUMN asset_id      uuid,
    ADD COLUMN serial_number varchar(128);

-- "Riwayat perpindahan unit ini" adalah pertanyaan pertama saat menelusuri aset hilang.
CREATE INDEX inventory_leg_tenant_asset_idx ON inventory_movement_leg (tenant_id, asset_id)
    WHERE asset_id IS NOT NULL;
CREATE INDEX inventory_leg_tenant_serial_idx ON inventory_movement_leg (tenant_id, serial_number)
    WHERE serial_number IS NOT NULL;

-- Backfill leg warisan sebelum CHECK dipasang.
--
-- Flyway jalan sebagai role NOBYPASSRLS dan `inventory_movement_leg` ter-FORCE RLS,
-- sedangkan GUC app.tenant_id tak di-set saat migrasi — tanpa mematikan RLS sementara,
-- UPDATE ini menyentuh NOL baris sementara ADD CONSTRAINT di bawahnya memvalidasi SEMUA
-- baris (DDL tak tunduk RLS) dan migrasi gagal justru di database yang sudah berisi ledger.
-- Pola sama V29/V39/V44/V52/V75/V83.
--
-- Kenapa isinya menurunkan flag, bukan mengisi asset_id: leg berserial warisan sama sekali
-- tidak menyimpan identitas unit (kolomnya baru lahir di migrasi ini), jadi tidak ada data
-- yang bisa dipulihkan. Yang KONSERVATIF adalah membiarkan kebenaran kuantitas utuh —
-- arah, jumlah, lokasi, custody semuanya tidak disentuh — dan hanya mencabut flag
-- `serialized` yang memang tak pernah dibaca siapa pun selain CHECK ini. Menghapus atau
-- membiarkan baris melanggar constraint jauh lebih merusak untuk sebuah ledger.
ALTER TABLE inventory_movement_leg DISABLE ROW LEVEL SECURITY;

UPDATE inventory_movement_leg
SET serialized = false
WHERE serialized AND asset_id IS NULL;

ALTER TABLE inventory_movement_leg ENABLE ROW LEVEL SECURITY;

-- ATURAN: mutasi barang berserial WAJIB menyebut unit mana yang berpindah. Ini yang
-- membuat rekonsiliasi saldo vs daftar aset mungkin dilakukan sama sekali.
ALTER TABLE inventory_movement_leg
    ADD CONSTRAINT inventory_leg_serial_identity_ck CHECK (NOT serialized OR asset_id IS NOT NULL);

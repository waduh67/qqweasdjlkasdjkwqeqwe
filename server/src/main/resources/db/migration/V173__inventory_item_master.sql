-- Master data item gudang — potongan yang selama ini HILANG dari modul inventory.
--
-- Sebelum tabel ini ada, setiap `sku_id` di `inventory_movement_leg`,
-- `inventory_balance_projection`, `inventory_cycle_count`, dan
-- `inventory_serialized_asset` adalah UUID menggantung: tidak ada satu pun baris
-- yang menjelaskan barang apa itu, satuannya apa, dan apakah ia berserial. Akibatnya
-- UI gudang memajang "SKU <uuid>" dan tidak ada cara memvalidasi bahwa leg dengan
-- `serialized = true` memang barang berserial. Tabel ini yang memberi arti pada UUID itu.
--
-- SENGAJA bernama `inventory_item` (bukan `sku`) supaya sejalan dengan kolom `item_id`
-- yang sudah dipakai ledger sejak V145.
CREATE TABLE inventory_item (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    code varchar(64) NOT NULL,
    name varchar(200) NOT NULL,
    category varchar(24) NOT NULL,
    unit varchar(16) NOT NULL,
    serialized boolean NOT NULL,
    track_mac boolean NOT NULL DEFAULT false,
    reorder_point numeric(14, 3),
    active boolean NOT NULL DEFAULT true,
    -- Kode item adalah identitas yang diketik/di-scan petugas gudang; unik per tenant,
    -- bukan global, karena dua ISP boleh punya kode internal yang sama.
    CONSTRAINT inventory_item_tenant_code_uq UNIQUE (tenant_id, code),
    CONSTRAINT inventory_item_category_ck CHECK (
        category IN ('ONT', 'DROPCORE', 'FEEDER', 'PATCHCORD', 'ADAPTER', 'CONNECTOR', 'ACCESSORY', 'TOOL', 'OTHER')
    ),
    CONSTRAINT inventory_item_unit_ck CHECK (unit IN ('PCS', 'METER', 'ROLL', 'SET')),
    -- MAC hanya masuk akal untuk barang yang dilacak per unit. Tanpa CHECK ini, operator
    -- bisa menandai dropcore (satuan METER, tanpa unit fisik tunggal) sebagai "lacak MAC",
    -- lalu pendaftaran massal aset menuntut MAC untuk barang yang tidak punya MAC sama sekali.
    CONSTRAINT inventory_item_track_mac_ck CHECK (NOT track_mac OR serialized),
    CONSTRAINT inventory_item_reorder_point_ck CHECK (reorder_point IS NULL OR reorder_point >= 0)
);

-- Jalur baca utama adalah "daftar item aktif tenant ini, dikelompokkan kategori" (form
-- pengeluaran barang dan BOM work order). Index mengikuti bentuk query itu, bukan (id).
CREATE INDEX inventory_item_tenant_active_idx ON inventory_item (tenant_id, active, category);

ALTER TABLE inventory_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_item FORCE ROW LEVEL SECURITY;

-- NULLIF dipakai supaya koneksi tanpa GUC `app.tenant_id` (mis. Flyway) melihat NOL baris
-- alih-alih meledak saat cast '' ke uuid.
CREATE POLICY inventory_item_tenant_policy ON inventory_item
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

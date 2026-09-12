-- Jembatan Work Order <-> Gudang (P2).
--
-- Sampai sekarang "material yang dibawa teknisi" tidak punya satu baris pun di basis data.
-- Akibatnya `InventoryApi.fulfillmentAllocations()` TIDAK PUNYA APA PUN untuk dikembalikan,
-- selalu mengembalikan daftar kosong, dan saga fulfillment yang sudah matang itu berhenti di
-- preflight dengan `INVENTORY_ALLOCATIONS_NOT_FOUND`. Artinya: WO PSB bisa disetujui,
-- pelanggan bisa aktif, tapi kabel dan ONT yang dipakai TIDAK PERNAH keluar dari saldo
-- gudang. Selisihnya baru muncul saat stok fisik diadu dengan sistem berbulan-bulan kemudian,
-- dan saat itu sudah tidak mungkin lagi tahu unit mana yang hilang di WO mana.
--
-- Tiga tabel di bawah ini yang menutup lubang itu:
--   * `work_order_material_template`  -> BOM per jenis WO, dipakai MEMPRA-ISI rencana.
--   * `work_order_material`           -> rencana vs realisasi per WO per item.
--   * `work_order_material_serial`    -> unit berserial yang benar-benar terpasang/terpakai.
--
-- Ketiganya milik modul `inventory`, BUKAN `workorder`. Ledger gudang tidak boleh punya dua
-- jalan masuk (lihat komentar di `InventoryOperationsService`), dan `ModularityTests`
-- menolak modul `inventory` mengimpor `workorder`. Jadi `work_order_id` disimpan polos
-- TANPA foreign key ke `work_order` — sama seperti id lintas-modul lain di skema ini.
-- Penjaganya adalah `tenant_id` + RLS, bukan referential integrity lintas-modul.

-- ---------------------------------------------------------------------------
-- BOM: template material per jenis work order
-- ---------------------------------------------------------------------------
--
-- SENGAJA hanya pra-isi, BUKAN pembatas. Teknisi lapangan menemui rumah yang butuh 80 meter
-- dropcore padahal template bilang 50, dan menolak WO-nya karena "di luar BOM" hanya
-- melahirkan kebiasaan mencatat 50 lalu mengambil 30 sisanya tanpa jejak. Yang dijaga di
-- sini adalah SELISIHNYA TERLIHAT (`template_quantity` di-snapshot ke barisan rencana),
-- bukan selisihnya dilarang.
CREATE TABLE work_order_material_template (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    -- Nilai `WorkOrderType`. Disimpan sebagai teks, bukan FK ke tabel jenis, karena jenis WO
    -- adalah enum di kode dan tidak punya tabel; CHECK di bawah yang menjaganya tetap jujur.
    work_order_type varchar(24) NOT NULL,
    item_id uuid NOT NULL REFERENCES inventory_item(id),
    planned_quantity integer NOT NULL,
    note varchar(200),
    CONSTRAINT work_order_material_template_uq UNIQUE (tenant_id, work_order_type, item_id),
    CONSTRAINT work_order_material_template_type_ck CHECK (
        work_order_type IN ('PSB', 'REPAIR', 'MIGRATION', 'DISMANTLE', 'PREVENTIVE')
    ),
    CONSTRAINT work_order_material_template_qty_ck CHECK (planned_quantity > 0)
);

-- ---------------------------------------------------------------------------
-- Rencana + realisasi material satu work order
-- ---------------------------------------------------------------------------
CREATE TABLE work_order_material (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    work_order_id uuid NOT NULL,
    work_order_type varchar(24) NOT NULL,
    item_id uuid NOT NULL REFERENCES inventory_item(id),
    -- NOT NULL dengan sengaja. `inventory_fulfillment_effect.customer_id` dan
    -- `inventory_customer_material_fact.customer_id` keduanya NOT NULL, jadi material yang
    -- direncanakan untuk WO tanpa pelanggan TIDAK AKAN PERNAH bisa di-commit oleh saga —
    -- ia hanya akan menggantung sebagai efek yang selalu gagal. Lebih baik ditolak di
    -- pintu depan dengan pesan yang bisa dibaca dispatcher.
    customer_id uuid NOT NULL,
    -- Snapshot dari `inventory_item` saat rencana dibuat. Di-snapshot, bukan di-join
    -- belakangan: kategori item boleh diubah petugas master data, dan laporan material
    -- historis tidak boleh ikut berubah retroaktif. `serialized` di-snapshot karena ia
    -- menentukan BENTUK alokasi yang dikirim ke saga (satu baris per unit vs per baris).
    item_category varchar(24) NOT NULL,
    serialized boolean NOT NULL,
    -- Kuantitas dari BOM saat rencana dibuat. NULL berarti item ini ditambahkan teknisi di
    -- luar template. Inilah yang membuat variansi terhadap BOM bisa dilihat tanpa harus
    -- menebak-nebak template versi kapan yang berlaku waktu itu.
    template_quantity integer,
    planned_quantity integer NOT NULL DEFAULT 0,
    -- Terisi setelah gudang benar-benar mengeluarkan barangnya lewat `InventoryOperationsService.issue`.
    -- Selama masih 0, tidak ada satu unit pun yang boleh diklaim terpakai.
    issued_quantity integer NOT NULL DEFAULT 0,
    used_quantity integer NOT NULL DEFAULT 0,
    returned_quantity integer NOT NULL DEFAULT 0,
    lost_quantity integer NOT NULL DEFAULT 0,
    -- Van stock teknisi: lokasi + pemegang tempat barang berada SETELAH dikeluarkan gudang.
    -- Dua kolom ini yang membentuk dimensi saldo `(item, lokasi, pemilik, TECHNICIAN, ISSUED)`
    -- yang nanti dipotong saat WO disetujui. Tanpa keduanya, saga tidak tahu saldo MANA yang
    -- harus berkurang dan pemotongannya akan mendarat di dimensi yang tidak pernah diisi —
    -- saldo gudang utuh, saldo teknisi minus.
    technician_id uuid,
    technician_location_id uuid REFERENCES inventory_location(id),
    variance_reason varchar(500),
    CONSTRAINT work_order_material_uq UNIQUE (tenant_id, work_order_id, item_id),
    CONSTRAINT work_order_material_type_ck CHECK (
        work_order_type IN ('PSB', 'REPAIR', 'MIGRATION', 'DISMANTLE', 'PREVENTIVE')
    ),
    CONSTRAINT work_order_material_qty_ck CHECK (
        planned_quantity >= 0 AND issued_quantity >= 0 AND used_quantity >= 0
        AND returned_quantity >= 0 AND lost_quantity >= 0
    ),
    -- Realisasi tidak boleh melebihi yang benar-benar dikeluarkan gudang. Tanpa CHECK ini,
    -- teknisi bisa melaporkan memasang 5 ONT dari 3 yang diambil, dan saga akan dengan patuh
    -- memotong 5 unit dari saldo yang hanya berisi 3 — saldo minus, dan tidak ada cara
    -- membedakan salah input dari barang yang benar-benar hilang.
    CONSTRAINT work_order_material_realized_ck CHECK (
        used_quantity + returned_quantity + lost_quantity <= issued_quantity
    ),
    -- Barang yang sudah keluar gudang WAJIB punya pemegang. Baris yang `issued_quantity > 0`
    -- tapi tanpa teknisi/lokasi adalah stok yang menguap dari pembukuan.
    CONSTRAINT work_order_material_custody_ck CHECK (
        issued_quantity = 0 OR (technician_id IS NOT NULL AND technician_location_id IS NOT NULL)
    )
);

-- ---------------------------------------------------------------------------
-- Unit berserial yang benar-benar dipakai di lapangan
-- ---------------------------------------------------------------------------
--
-- Foto bukti pemasangan TIDAK menggantikan tabel ini: foto tidak bisa di-join, tidak bisa
-- dijumlahkan, dan tidak bisa menjawab "ONT dengan SN ini sekarang ada di rumah siapa".
-- Nomor seri adalah data terstruktur, fotonya pelengkap.
CREATE TABLE work_order_material_serial (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    material_id uuid NOT NULL REFERENCES work_order_material(id) ON DELETE CASCADE,
    -- Diduplikasi dari baris induknya SENGAJA: jalur baca terpanas adalah "semua serial WO
    -- ini" (dipakai saat penyelesaian WO dan saat saga menyusun alokasi), dan menempuhnya
    -- lewat join ke induk pada setiap panggilan saga hanya menambah satu langkah tanpa guna.
    work_order_id uuid NOT NULL,
    asset_id uuid NOT NULL REFERENCES inventory_serialized_asset(id),
    -- Disalin dari asetnya saat di-scan, bukan di-join belakangan: ini catatan historis, dan
    -- nomor seri yang tercatat di sebuah WO tidak boleh ikut berubah kalau baris asetnya
    -- kelak diperbaiki.
    serial_number varchar(128) NOT NULL,
    mac_address varchar(32),
    -- INSTALLED = terpasang di pelanggan (dikonsumsi dari saldo saat WO disetujui).
    -- RETURNED  = dibawa pulang, harus kembali ke gudang lewat retur biasa.
    -- LOST      = hilang/rusak di lapangan, harus lewat penyesuaian yang butuh persetujuan.
    -- Ketiganya wajib dideklarasikan; unit yang sudah keluar gudang tidak boleh "menghilang"
    -- dari pembukuan hanya karena teknisi lupa melaporkannya.
    outcome varchar(16) NOT NULL,
    scanned_at timestamptz NOT NULL DEFAULT now(),
    scanned_by uuid NOT NULL,
    CONSTRAINT work_order_material_serial_uq UNIQUE (tenant_id, work_order_id, asset_id),
    CONSTRAINT work_order_material_serial_outcome_ck CHECK (outcome IN ('INSTALLED', 'RETURNED', 'LOST')),
    CONSTRAINT work_order_material_serial_number_ck CHECK (length(btrim(serial_number)) > 0)
);

-- Satu unit fisik hanya bisa terpasang di SATU tempat. Tanpa indeks ini, ONT yang sama bisa
-- dilaporkan terpasang di dua pelanggan berbeda dan kedua baris sama-sama terlihat sah —
-- saldo dipotong dua kali untuk barang yang cuma ada satu. RETURNED/LOST sengaja di luar
-- indeks: satu unit memang boleh pernah dibawa-pulang di banyak WO sepanjang hidupnya.
CREATE UNIQUE INDEX work_order_material_serial_installed_uq
    ON work_order_material_serial (tenant_id, asset_id)
    WHERE outcome = 'INSTALLED';

-- Jalur baca panas: "material WO ini" (layar teknisi, validasi penyelesaian, dan alokasi saga).
CREATE INDEX work_order_material_wo_idx ON work_order_material (tenant_id, work_order_id);
CREATE INDEX work_order_material_serial_wo_idx ON work_order_material_serial (tenant_id, work_order_id);
CREATE INDEX work_order_material_serial_material_idx ON work_order_material_serial (tenant_id, material_id);
CREATE INDEX work_order_material_template_type_idx ON work_order_material_template (tenant_id, work_order_type);

ALTER TABLE work_order_material_template ENABLE ROW LEVEL SECURITY;
ALTER TABLE work_order_material_template FORCE ROW LEVEL SECURITY;
ALTER TABLE work_order_material ENABLE ROW LEVEL SECURITY;
ALTER TABLE work_order_material FORCE ROW LEVEL SECURITY;
ALTER TABLE work_order_material_serial ENABLE ROW LEVEL SECURITY;
ALTER TABLE work_order_material_serial FORCE ROW LEVEL SECURITY;

-- NULLIF supaya koneksi tanpa GUC `app.tenant_id` (Flyway, tooling) melihat NOL baris
-- alih-alih meledak saat cast '' ke uuid.
CREATE POLICY work_order_material_template_tenant_policy ON work_order_material_template
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY work_order_material_tenant_policy ON work_order_material
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY work_order_material_serial_tenant_policy ON work_order_material_serial
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

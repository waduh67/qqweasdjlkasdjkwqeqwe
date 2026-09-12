-- Jalur PENARIKAN aset saat work order DISMANTLE (celah "P2.6").
--
-- Sampai sekarang WO DISMANTLE TIDAK PUNYA CARA APA PUN mengembalikan ONT pelanggan ke
-- pembukuan gudang. `InventoryApi.returnFulfillment` sudah ada lengkap dengan idempotensi dan
-- proyeksi saldonya, tapi NOL pemanggil produksi. Akibatnya setiap pembongkaran berakhir sama:
-- ONT-nya dicabut, dibawa pulang teknisi, dan secara pembukuan TETAP berstatus CONSUMED di
-- rumah pelanggan yang sudah berhenti berlangganan — selamanya. Unitnya nyata, ada di tangan
-- seseorang, tapi tidak muncul di saldo mana pun, tidak bisa dikeluarkan lagi ke WO berikutnya,
-- dan tidak pernah terhitung saat opname. Itu bukan selisih yang "nanti ketahuan"; itu aset
-- yang lenyap dari sistem pada detik WO-nya disetujui.
--
-- ---------------------------------------------------------------------------
-- Kenapa TABEL SENDIRI, bukan menumpang `work_order_material` (D1)
-- ---------------------------------------------------------------------------
--
-- `work_order_material` memodelkan satu kalimat: "sekian unit item X DIAMBIL DARI GUDANG untuk
-- WO ini". ONT yang ditarik dari rumah pelanggan TIDAK PERNAH diambil dari gudang untuk WO ini —
-- ia keluar dari gudang bertahun-tahun lalu lewat WO PSB yang sama sekali berbeda. Memaksanya
-- ke tabel itu merusak tiga hal sekaligus:
--   * `planned_quantity` jadi tak bermakna (tidak ada yang "merencanakan" menarik ONT tertentu;
--     teknisi baru tahu SN-nya saat berdiri di depan perangkatnya);
--   * `issued_quantity` jadi bohong (gudang tidak pernah mengeluarkan apa pun untuk ini), dan
--     CHECK `work_order_material_realized_ck` (realisasi <= yang dikeluarkan) akan menolak setiap
--     penarikan karena pembaginya nol;
--   * `assertMaterialReadyForCompletion` akan menuntut "alasan selisih" untuk sesuatu yang memang
--     tidak punya rencana untuk disimpangi — WO DISMANTLE yang benar jadi tidak bisa ditutup.
--
-- ---------------------------------------------------------------------------
-- Kenapa HANYA BERSERIAL (D2)
-- ---------------------------------------------------------------------------
--
-- Tidak ada kolom `quantity` di sini, dan itu disengaja. Dropcore bekas yang dicabut dari rumah
-- pelanggan adalah SCRAP, bukan stok: panjangnya tak bisa diukur ulang dengan jujur di lapangan,
-- kondisinya tak bisa diverifikasi, dan tidak ada satu pun cara membedakan "80 meter bekas"
-- dari "80 meter yang diketik". Mengembalikan barang curah bekas ke stok layak jual adalah
-- bentuk kebocoran yang paling gampang disalahgunakan — angka yang tidak bisa dibantah siapa
-- pun. Unit berserial punya identitas fisik: satu SN, satu barang, bisa diadu dengan rak.
--
-- ---------------------------------------------------------------------------
-- Kenapa TIDAK ADA tier approval gudang tersendiri (D7)
-- ---------------------------------------------------------------------------
--
-- Penarikan ini TIDAK menambah stok layak jual: unitnya mendarat di status RETURNED di van
-- teknisi (D3), dan baru jadi AVAILABLE setelah gudang benar-benar menerimanya lewat jalur retur
-- biasa yang punya pemeriksaannya sendiri. Mata keduanya adalah PERSETUJUAN WO ITU SENDIRI —
-- saldo tidak bergerak sedikit pun sebelum WO-nya disetujui, dan penyetujunya bukan teknisi yang
-- men-scan. Menambah tier approval gudang terpisah di atas itu berarti dua antrean persetujuan
-- untuk satu peristiwa fisik yang sama, dan antrean kedua yang tidak menambah informasi apa pun
-- akan selalu di-approve buta. Keputusan ini ditulis di sini supaya terlihat dan bisa dibantah.
--
-- Sama seperti V186: `work_order_id` dan `customer_id` disimpan POLOS tanpa foreign key
-- lintas-modul. `ModularityTests` menolak modul `inventory` mengimpor `workorder`/`customer`,
-- dan penjaganya adalah `tenant_id` + RLS.

CREATE TABLE work_order_recovered_asset (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    work_order_id uuid NOT NULL,
    -- FK ke aset nyata, BUKAN sekadar teks nomor seri. Penarikan atas SN karangan harus mustahil:
    -- kalau kolomnya cuma varchar, "menarik" ONT fiktif jadi cara termudah menambah stok hantu.
    asset_id uuid NOT NULL REFERENCES inventory_serialized_asset(id),
    -- Di-snapshot dari asetnya saat ditarik, bukan di-join belakangan. Ini catatan historis
    -- "unit apa yang dibongkar hari itu", dan tidak boleh ikut berubah kalau baris asetnya kelak
    -- diperbaiki petugas master data.
    serial_number varchar(128) NOT NULL,
    mac_address varchar(32),
    item_id uuid NOT NULL REFERENCES inventory_item(id),
    -- Ikut di-snapshot karena `inventory_fulfillment_effect.item_category` NOT NULL dan
    -- kategori item boleh diubah petugas master data; laporan penarikan historis tidak boleh
    -- ikut berubah retroaktif.
    item_category varchar(24) NOT NULL,
    -- Pelanggan yang perangkatnya dibongkar. NOT NULL dengan alasan yang sama seperti V186:
    -- `inventory_fulfillment_effect.customer_id` NOT NULL, jadi penarikan tanpa pelanggan TIDAK
    -- AKAN PERNAH bisa di-commit saga — ia hanya menggantung sebagai efek yang selalu gagal.
    customer_id uuid NOT NULL,
    -- Pemegang barang SETELAH dicabut. Keduanya NOT NULL (beda dengan `work_order_material`,
    -- di mana keduanya baru terisi setelah gudang mengeluarkan barang): unit yang ditarik SUDAH
    -- ada di tangan seseorang pada detik barisnya dibuat. Dua kolom inilah yang membentuk
    -- dimensi saldo `(item, lokasi, pemilik, TECHNICIAN, RETURNED)` yang bertambah saat WO
    -- disetujui. Tanpa keduanya, leg IN mendarat di dimensi yang tak pernah diisi dan barangnya
    -- kembali "ada di sistem tapi tidak di tangan siapa pun".
    technician_id uuid NOT NULL,
    technician_location_id uuid NOT NULL REFERENCES inventory_location(id),
    -- Kondisi APA ADANYA menurut teknisi, bukan vonis akhir. Vonis sebenarnya jatuh saat gudang
    -- memeriksa unitnya (RETURNED -> AVAILABLE atau -> QUARANTINE). Yang dijaga kolom ini adalah
    -- ADANYA pernyataan awal: tanpa itu, ONT yang jelas hangus dan ONT yang mulus tiba di gudang
    -- sebagai dua baris yang identik, dan pemeriksanya tidak punya apa pun untuk diadu.
    condition varchar(16) NOT NULL,
    note varchar(500),
    recovered_at timestamptz NOT NULL DEFAULT now(),
    recovered_by uuid NOT NULL,
    -- Pembatalan SENGAJA berupa penanda waktu, bukan DELETE. Baris yang dihapus tidak bisa
    -- menjawab "kenapa saldo tidak bertambah padahal teknisi bilang sudah men-scan"; baris yang
    -- dibatalkan bisa. Baris yang dibatalkan TIDAK ikut dipotong saga (lihat `allocationsFor`).
    cancelled_at timestamptz,
    cancelled_by uuid,
    cancel_reason varchar(500),
    CONSTRAINT work_order_recovered_asset_condition_ck CHECK (condition IN ('GOOD', 'DAMAGED')),
    CONSTRAINT work_order_recovered_asset_serial_ck CHECK (length(btrim(serial_number)) > 0),
    -- Pembatalan harus lengkap atau tidak ada sama sekali: baris yang punya `cancelled_at` tanpa
    -- `cancelled_by` adalah penarikan yang batal tanpa ada yang bertanggung jawab.
    CONSTRAINT work_order_recovered_asset_cancel_ck CHECK (
        (cancelled_at IS NULL AND cancelled_by IS NULL) OR (cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL)
    )
);

-- PENJAGA TERPENTING SELURUH FITUR INI (D6b).
--
-- Satu unit fisik hanya boleh ditarik SEKALI. Tanpa indeks ini, satu ONT bisa dicatat ditarik di
-- dua WO DISMANTLE berbeda — atau dua kali di WO yang sama lewat dua request bersamaan — dan
-- saga akan dengan patuh MENAMBAH saldo dua kali untuk satu barang yang cuma ada satu. Itulah
-- stok hantu: unit yang ada di pembukuan tapi tidak ada di rak, dan karena ia lahir dari mutasi
-- yang semuanya terlihat sah, tidak ada satu pun laporan yang bisa menunjuk mana yang palsu.
--
-- Baris yang DIBATALKAN sengaja di luar indeks: penarikan yang dibatalkan tidak pernah menggerakkan
-- saldo, jadi unit yang sama memang harus boleh ditarik ulang setelah salah scan diperbaiki.
CREATE UNIQUE INDEX work_order_recovered_asset_active_uq
    ON work_order_recovered_asset (tenant_id, asset_id)
    WHERE cancelled_at IS NULL;

-- Jalur baca panas: "aset yang ditarik di WO ini" — dipakai layar teknisi DAN penyusun alokasi saga.
CREATE INDEX work_order_recovered_asset_wo_idx ON work_order_recovered_asset (tenant_id, work_order_id);

ALTER TABLE work_order_recovered_asset ENABLE ROW LEVEL SECURITY;
ALTER TABLE work_order_recovered_asset FORCE ROW LEVEL SECURITY;

-- NULLIF supaya koneksi tanpa GUC `app.tenant_id` (Flyway, tooling) melihat NOL baris
-- alih-alih meledak saat cast '' ke uuid.
CREATE POLICY work_order_recovered_asset_tenant_policy ON work_order_recovered_asset
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

COMMENT ON TABLE work_order_recovered_asset IS
    'Unit berserial yang DITARIK dari pelanggan saat work order DISMANTLE. Bukan material yang diambil dari gudang (itu work_order_material) — ini arah sebaliknya.';
COMMENT ON COLUMN work_order_recovered_asset.item_category IS
    'Snapshot kategori item saat ditarik; dipakai inventory_fulfillment_effect.item_category yang NOT NULL.';
COMMENT ON COLUMN work_order_recovered_asset.technician_location_id IS
    'Van stock teknisi yang membawa pulang unitnya. Leg IN mendarat DI SINI, bukan di gudang: barangnya belum sampai rak.';
COMMENT ON COLUMN work_order_recovered_asset.condition IS
    'GOOD/DAMAGED menurut teknisi di lapangan. Vonis akhir tetap di tangan gudang saat RETURNED diperiksa jadi AVAILABLE atau QUARANTINE.';
COMMENT ON COLUMN work_order_recovered_asset.cancelled_at IS
    'Penanda batal, bukan DELETE. Baris yang dibatalkan tidak ikut dipotong saga dan tidak ikut unique index parsial.';

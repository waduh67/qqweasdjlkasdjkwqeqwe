-- Induk lokasi: bin harus tahu ia ada di gudang mana.
--
-- `inventory_location` sejak V144 hanya punya `code` + `kind`, sehingga BIN adalah pulau
-- yang tidak menempel pada gudang mana pun. Akibatnya laporan stok per gudang mustahil:
-- saldo tercatat per lokasi, dan tidak ada satu kolom pun yang bisa menjumlahkan bin-bin
-- di dalam satu gudang. Operator gudang akan melihat sepuluh baris "BIN-A1..BIN-C9" tanpa
-- cara mengetahui mana yang miliknya.
ALTER TABLE inventory_location
    ADD COLUMN parent_id uuid REFERENCES inventory_location(id);

-- Lokasi tidak boleh jadi induk dirinya sendiri. CHECK ini hanya menutup siklus sepanjang
-- satu; siklus yang lebih panjang dicegah di aplikasi (induk WAJIB berjenis WAREHOUSE dan
-- WAREHOUSE tidak boleh punya induk, sehingga kedalamannya maksimal dua).
ALTER TABLE inventory_location
    ADD CONSTRAINT inventory_location_parent_self_ck CHECK (parent_id IS NULL OR parent_id <> id);

CREATE INDEX inventory_location_parent_idx ON inventory_location (tenant_id, parent_id);

-- ============================================================
-- order — impor massal CSV: satu BARIS berkas = satu baris tabel
--
-- KENAPA per baris, bukan satu kolom JSON berisi ringkasan:
--
-- 1. KEGAGALAN PER BARIS, BUKAN PER BERKAS. Satu baris rusak (nomor HP kosong,
--    paket tidak dikenal) TIDAK BOLEH menggagalkan 499 baris lain. Tapi operator
--    WAJIB melihat baris KE BERAPA yang gagal dan KENAPA, dalam kalimat yang
--    bisa dibaca orang non-teknis. Itu berarti setiap baris butuh identitas dan
--    tempat menaruh pesannya sendiri.
--
-- 2. PRAVIEW YANG BISA DIBUKA ULANG. Praview yang hanya ada di respons HTTP akan
--    hilang begitu operator me-refresh halaman, dan satu-satunya jalan keluarnya
--    mengunggah ulang berkas 500 baris.
--
-- 3. TAUT BALIK. `order_id`/`lead_id` menjawab "pesanan ini datang dari baris
--    mana, di berkas mana, yang diunggah siapa" — pertanyaan yang selalu muncul
--    saat ada satu pesanan aneh di antara ratusan.
--
-- `fingerprint` adalah kunci idempotensi TINGKAT BARIS, terpisah dari
-- `content_hash` tingkat berkas. Dua berkas berbeda yang memuat orang, paket,
-- dan alamat yang sama tidak boleh melahirkan dua pesanan — dan itu justru kasus
-- yang PALING sering terjadi: operator mengirim ulang berkas yang sama dengan
-- beberapa baris tambahan di bawahnya. Nilainya dipakai apa adanya sebagai
-- `operation_key` di namespace `order-import`, sehingga penjagaannya dilakukan
-- oleh `order_operation` yang sudah ada — BUKAN oleh unique index baru di sini.
-- Dua penjaga untuk satu janji pasti akan menyimpang diam-diam.
--
-- KONSEKUENSI YANG DITERIMA SADAR: pelanggan yang sungguh-sungguh memesan paket
-- kedua yang identik di alamat yang sama, lewat impor, akan ditolak sebagai
-- duplikat. Itu jauh lebih murah daripada 500 pesanan kembar yang harus
-- dibatalkan satu per satu, dan operator selalu bisa membuatnya lewat layar
-- pesanan biasa.
--
-- `raw_line` SENGAJA TIDAK disimpan. Ia salinan kedua data pribadi calon
-- pelanggan yang tak ikut terhapus saat barisnya dihapus, dan seluruh isinya
-- sudah terurai ke kolom-kolom di bawah. Yang disimpan untuk baris yang GAGAL
-- diurai hanya `message` — cukup untuk memperbaikinya di berkas aslinya.
-- ============================================================

CREATE TABLE IF NOT EXISTS order_import_row (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenant(id),
    batch_id     uuid NOT NULL,
    -- Nomor baris di BERKAS ASLI (header = 1), bukan indeks setelah penyaringan.
    -- Operator memperbaiki berkasnya di Excel, dan Excel menomori dari 1.
    line_number  integer NOT NULL CHECK (line_number > 0),
    fingerprint  char(64),
    status       varchar(16) NOT NULL
                 CHECK (status IN ('ACCEPTED', 'REJECTED', 'DUPLICATE', 'CREATED', 'FAILED')),
    -- Kalimat untuk MANUSIA, bahasa Indonesia. Bukan kode kesalahan, bukan
    -- stack trace: yang membacanya operator penjualan, bukan pengembang.
    message      varchar(500),
    name         varchar(150),
    phone        varchar(32),
    email        varchar(200),
    plan_id      uuid,
    address      text,
    city         varchar(120),
    postal_code  varchar(24),
    notes        varchar(1000),
    order_id     uuid,
    lead_id      uuid,
    order_number varchar(20),
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    -- Baris yang berhasil dibuat WAJIB menunjuk pesanannya. Tanpa ini, batch
    -- yang setengah jadi tak bisa dibedakan dari batch yang gagal seluruhnya.
    CHECK ((status = 'CREATED') = (order_id IS NOT NULL)),
    -- Baris yang ditolak WAJIB punya alasan. Penolakan tanpa kalimat adalah
    -- persis kegagalan yang membuat fitur ini tak terpakai: operator melihat
    -- "37 ditolak" dan tak punya satu pun petunjuk untuk memperbaikinya.
    CHECK (status NOT IN ('REJECTED', 'DUPLICATE', 'FAILED') OR message IS NOT NULL),
    FOREIGN KEY (tenant_id, batch_id) REFERENCES order_import_batch (tenant_id, id) ON DELETE CASCADE,
    UNIQUE (tenant_id, batch_id, line_number)
);

-- Layar praview membaca seluruh baris satu batch, berurutan seperti di berkasnya.
CREATE INDEX IF NOT EXISTS ix_order_import_row_batch
    ON order_import_row (tenant_id, batch_id, line_number);

-- "Pesanan ini datang dari impor yang mana?"
CREATE INDEX IF NOT EXISTS ix_order_import_row_order
    ON order_import_row (tenant_id, order_id)
    WHERE order_id IS NOT NULL;

DROP TRIGGER IF EXISTS order_import_row_tenant_immutable ON order_import_row;
CREATE TRIGGER order_import_row_tenant_immutable BEFORE UPDATE ON order_import_row
FOR EACH ROW EXECUTE FUNCTION order_tenant_is_immutable();

DO $$ DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['order_import_row'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON %I', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid)', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
    END LOOP;
END $$;

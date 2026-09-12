-- ============================================================
-- order — impor massal CSV: BERKAS-nya, sebagai jejak audit dan sebagai kunci
-- idempotensi
--
-- KENAPA berkasnya disimpan sebagai baris, bukan sekadar diproses lalu dilupakan:
--
-- 1. PRAVIEW SEBELUM COMMIT. Operator harus melihat baris mana yang akan
--    diterima dan mana yang ditolak BESERTA alasannya sebelum apa pun tersimpan.
--    Itu berarti ada keadaan "sudah diurai, belum dieksekusi" yang harus hidup
--    lebih lama daripada satu permintaan HTTP — kalau ia hanya ada di memori,
--    operator yang menekan "Commit" dari tab yang dibuka semenit kemudian akan
--    menerima "sesi hilang", dan satu-satunya jalan keluarnya mengunggah ulang.
--
-- 2. IDEMPOTENSI PER BERKAS. `UNIQUE (tenant_id, content_hash)` adalah janji
--    yang paling penting di tabel ini: mengunggah ulang berkas yang SAMA
--    memulangkan batch yang sudah ada, bukan melahirkan 500 pesanan kembar.
--    Operator yang ragu apakah unggahannya tadi berhasil PASTI akan mencoba
--    lagi — itu bukan kasus tepi, itu perilaku normal manusia di depan layar
--    yang menggantung.
--
-- 3. SIAPA MENGIMPOR APA. `imported_by` + `file_name` + cacah baris adalah
--    satu-satunya cara menjawab "500 pesanan ini datang dari mana?" tiga bulan
--    kemudian. Tanpa itu, impor massal adalah lubang akuntabilitas terbesar di
--    sistem ini: satu permintaan HTTP yang meninggalkan ratusan baris tanpa
--    pelaku.
--
-- `imported_by` SENGAJA uuid polos tanpa FK ke `app_user`: module `order` tak
-- boleh mengikat skemanya ke tabel module `iam` (pola sama V83, V177).
--
-- Isi berkasnya sendiri TIDAK disimpan. Yang disimpan hasil urainya per baris
-- (lihat V193) — menyimpan CSV mentah berarti menyimpan salinan kedua data
-- pribadi calon pelanggan yang tak pernah ikut terhapus saat barisnya dihapus.
-- ============================================================

CREATE TABLE IF NOT EXISTS order_import_batch (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenant(id),
    file_name     varchar(255) NOT NULL,
    -- SHA-256 heksadesimal atas BYTE MENTAH berkas, dihitung sebelum decoding.
    -- Mentah, bukan hasil normalisasi: dua berkas yang isinya sama tapi
    -- encoding-nya beda memang berkas berbeda, dan menyamakannya akan menolak
    -- unggahan ulang yang sah setelah operator memperbaiki encoding-nya.
    content_hash  char(64) NOT NULL,
    byte_size     bigint NOT NULL CHECK (byte_size > 0),
    -- Pemisah yang benar-benar dipakai saat mengurai. Excel di laptop berlokal
    -- Indonesia mengekspor dengan ';', bukan ','. Disimpan supaya laporan
    -- "kenapa semua baris saya ditolak" bisa dijawab tanpa menebak.
    delimiter     char(1) NOT NULL,
    status        varchar(16) NOT NULL
                  CHECK (status IN ('PREVIEWED', 'COMMITTED')),
    total_rows    integer NOT NULL CHECK (total_rows >= 0),
    accepted_rows integer NOT NULL CHECK (accepted_rows >= 0),
    rejected_rows integer NOT NULL CHECK (rejected_rows >= 0),
    -- Diisi saat commit. BUKAN sama dengan accepted_rows: baris yang lolos
    -- praview masih bisa gagal saat dieksekusi (paket dinonaktifkan di antara
    -- praview dan commit, nomor pesanan bentrok, dsb).
    created_rows  integer NOT NULL DEFAULT 0 CHECK (created_rows >= 0),
    failed_rows   integer NOT NULL DEFAULT 0 CHECK (failed_rows >= 0),
    imported_by   uuid,
    committed_at  timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    -- Status dan waktu commit harus sepakat. Batch COMMITTED tanpa waktu berarti
    -- laporan "impor terakhir kapan" bohong; waktu tanpa status berarti tombol
    -- Commit bisa ditekan dua kali.
    CHECK ((status = 'COMMITTED') = (committed_at IS NOT NULL)),
    -- Dipakai FK komposit dari order_import_row agar baris tak pernah menunjuk
    -- batch milik tenant lain (RLS menjaga kebocoran BACA, bukan integritas).
    UNIQUE (tenant_id, id)
);

-- Janji idempotensi itu sendiri. Tanpa index ini seluruh fitur ini berbahaya.
CREATE UNIQUE INDEX IF NOT EXISTS uq_order_import_batch_content
    ON order_import_batch (tenant_id, content_hash);

-- Layar "riwayat impor" dibaca dari yang terbaru.
CREATE INDEX IF NOT EXISTS ix_order_import_batch_tenant_created
    ON order_import_batch (tenant_id, created_at DESC);

DROP TRIGGER IF EXISTS order_import_batch_tenant_immutable ON order_import_batch;
CREATE TRIGGER order_import_batch_tenant_immutable BEFORE UPDATE ON order_import_batch
FOR EACH ROW EXECUTE FUNCTION order_tenant_is_immutable();

DO $$ DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['order_import_batch'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON %I', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid)', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
    END LOOP;
END $$;

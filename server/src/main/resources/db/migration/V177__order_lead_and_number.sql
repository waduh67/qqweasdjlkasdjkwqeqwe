-- ============================================================
-- order — calon pelanggan (lead) + nomor pesanan yang bisa dibaca manusia
--
-- Dua lubang yang ditambal sekaligus karena keduanya menyentuh `order_record`:
--
-- 1. PEMESAN WAJIB SUDAH JADI PELANGGAN. `order_record.customer_id` adalah
--    NOT NULL REFERENCES customer(id), jadi orang yang baru bertanya "bisa
--    pasang di alamat saya?" tidak punya tempat di sistem. Menaruhnya di tabel
--    `customer` akan mengotori tagihan, langganan, dan laporan churn — prospek
--    yang tak pernah jadi pelanggan akan terhitung sebagai pelanggan hilang.
--    Karena itu prospek dapat tabelnya sendiri (`order_lead`), dan `order_record`
--    menunjuk TEPAT SATU dari keduanya.
--
-- 2. PESANAN HANYA PUNYA UUID. Tidak ada yang bisa dibacakan lewat telepon.
--    Bandingkan `work_order.code` = `WO-XXXXXXXX`. Nomor pesanan memakai format
--    `ORD-YYMM-NNNN` supaya periode terbaca dari nomornya sendiri.
--
-- `interested_plan_id` SENGAJA uuid polos tanpa FK: paket milik module `catalog`,
-- dan module `order` tak boleh mengikat skemanya ke tabel module lain (pola sama
-- seperti `helpdesk_ticket.assignee_id` di V83).
-- ============================================================

CREATE TABLE IF NOT EXISTS order_lead (
    id                    uuid PRIMARY KEY,
    tenant_id             uuid NOT NULL REFERENCES tenant(id),
    name                  varchar(150) NOT NULL,
    -- Nomor HP WAJIB: ia satu-satunya kunci yang dipegang calon pelanggan untuk
    -- melacak pesanannya nanti, dan satu-satunya cara operator menindaklanjuti.
    phone                 varchar(32) NOT NULL,
    email                 varchar(200),
    address               text,
    latitude              double precision,
    longitude             double precision,
    interested_plan_id    uuid,
    source                varchar(16) NOT NULL
                          CHECK (source IN ('PUBLIC_WEB', 'CSV_IMPORT', 'OPERATOR', 'REFERRAL', 'OTHER')),
    status                varchar(16) NOT NULL
                          CHECK (status IN ('NEW', 'CONTACTED', 'QUALIFIED', 'CONVERTED', 'DROPPED')),
    converted_customer_id uuid,
    notes                 varchar(1000),
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    CHECK ((latitude IS NULL) = (longitude IS NULL)),
    -- CONVERTED berarti "sudah ada pelanggannya", dan sebaliknya. Tanpa ini,
    -- lead bisa berstatus CONVERTED tanpa pernah menunjuk pelanggan mana pun —
    -- promosi yang gagal di tengah akan terlihat seolah berhasil.
    CHECK ((status = 'CONVERTED') = (converted_customer_id IS NOT NULL)),
    -- Dipakai FK komposit dari order_record agar pesanan tak pernah menunjuk
    -- lead milik tenant lain (RLS saja tak menjaga integritas referensial).
    UNIQUE (tenant_id, id)
);

-- Operator menindaklanjuti lead dengan mengetik nomor HP yang menelepon balik.
CREATE INDEX IF NOT EXISTS ix_order_lead_tenant_phone ON order_lead (tenant_id, phone);
-- Antrean "prospek yang belum disentuh" dibaca dari yang terbaru.
CREATE INDEX IF NOT EXISTS ix_order_lead_tenant_status ON order_lead (tenant_id, status, created_at DESC);

DROP TRIGGER IF EXISTS order_lead_tenant_immutable ON order_lead;
CREATE TRIGGER order_lead_tenant_immutable BEFORE UPDATE ON order_lead
FOR EACH ROW EXECUTE FUNCTION order_tenant_is_immutable();

-- ------------------------------------------------------------
-- order_record: pemesan boleh berupa lead, dan setiap pesanan dapat nomor
-- ------------------------------------------------------------

ALTER TABLE order_record ALTER COLUMN customer_id DROP NOT NULL;
ALTER TABLE order_record ADD COLUMN IF NOT EXISTS lead_id uuid;
ALTER TABLE order_record ADD COLUMN IF NOT EXISTS order_number varchar(20);

ALTER TABLE order_record
    ADD CONSTRAINT fk_order_record_lead FOREIGN KEY (tenant_id, lead_id) REFERENCES order_lead (tenant_id, id);

-- TEPAT SATU pemesan. Kalau keduanya terisi, promosi lead→customer meninggalkan
-- dua identitas untuk satu orang dan laporan akan menghitungnya dua kali;
-- kalau keduanya kosong, pesanan jadi yatim dan tak bisa ditindaklanjuti.
ALTER TABLE order_record
    ADD CONSTRAINT ck_order_record_requester CHECK ((customer_id IS NULL) <> (lead_id IS NULL));

CREATE INDEX IF NOT EXISTS ix_order_record_tenant_lead ON order_record (tenant_id, lead_id)
    WHERE lead_id IS NOT NULL;

-- ------------------------------------------------------------
-- Pencacah nomor pesanan per (tenant, periode YYMM)
--
-- SENGAJA tabel pencacah, bukan `SELECT max(order_number) + 1`: dua permintaan
-- bersamaan akan membaca max yang sama dan menghasilkan nomor kembar, lalu salah
-- satunya gagal di UNIQUE dan pesanan pelanggan hilang tanpa jejak.
-- `INSERT ... ON CONFLICT DO UPDATE ... RETURNING` mengambil kunci baris pada
-- baris pencacah, jadi transaksi kedua MENUNGGU transaksi pertama commit lalu
-- membaca versi baris yang sudah naik — tidak ada dua pemanggil yang bisa
-- melihat nilai yang sama. UNIQUE (tenant_id, order_number) di bawah tetap
-- dipasang sebagai jaring pengaman terakhir yang gagal-tertutup.
--
-- Tanpa `id uuid` karena tabel ini bukan agregat dan tidak dipetakan sebagai
-- JPA entity; ia hanya disentuh lewat native query di dalam TenantContext.
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS order_number_counter (
    tenant_id  uuid NOT NULL REFERENCES tenant(id),
    period     char(4) NOT NULL,
    last_value integer NOT NULL DEFAULT 0 CHECK (last_value >= 0),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, period)
);

-- Backfill baris lama supaya `order_number` bisa dipasang NOT NULL + UNIQUE.
--
-- Flyway jalan sebagai role NOBYPASSRLS dan `order_record` ter-FORCE RLS,
-- sedangkan GUC app.tenant_id tak di-set saat migrasi — tanpa mematikan RLS
-- sementara, UPDATE di bawah menyentuh NOL baris sementara `SET NOT NULL`
-- melihat semua baris (DDL tak tunduk RLS) dan migrasi gagal justru di database
-- yang sudah berisi pesanan. Pola sama V29/V39/V44/V52/V75/V83.
ALTER TABLE order_record DISABLE ROW LEVEL SECURITY;

WITH numbered AS (
    SELECT id,
           'ORD-' || to_char(created_at, 'YYMM') || '-' || lpad(
               row_number() OVER (
                   PARTITION BY tenant_id, to_char(created_at, 'YYMM')
                   ORDER BY created_at, id
               )::text, 4, '0') AS generated
    FROM order_record
    WHERE order_number IS NULL
)
UPDATE order_record o SET order_number = n.generated FROM numbered n WHERE o.id = n.id;

-- Pencacah harus dimulai dari nomor terakhir hasil backfill, kalau tidak pesanan
-- berikutnya lahir dengan nomor yang sudah terpakai dan langsung ditolak UNIQUE.
INSERT INTO order_number_counter (tenant_id, period, last_value)
SELECT tenant_id, to_char(created_at, 'YYMM'), count(*)
FROM order_record
GROUP BY tenant_id, to_char(created_at, 'YYMM')
ON CONFLICT (tenant_id, period) DO NOTHING;

ALTER TABLE order_record ENABLE ROW LEVEL SECURITY;

ALTER TABLE order_record ALTER COLUMN order_number SET NOT NULL;
ALTER TABLE order_record ADD CONSTRAINT uq_order_record_number UNIQUE (tenant_id, order_number);

DO $$ DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['order_lead', 'order_number_counter'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON %I', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid)', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
    END LOOP;
END $$;

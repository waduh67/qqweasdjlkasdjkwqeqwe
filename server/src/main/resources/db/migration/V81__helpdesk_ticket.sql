-- ============================================================
-- helpdesk — tiket bantuan yang DILAPORKAN PELANGGAN dari portal
--
-- Sebelum ini keluhan pelanggan tak punya tempat: pelanggan menelepon/WA, operator
-- mencatatnya di kepala sendiri, dan pelanggan tak pernah tahu laporannya sampai di
-- mana. Tiket menutup lingkaran itu — satu utas percakapan yang DIBACA KEDUA PIHAK,
-- dengan status yang sama, plus jalur eskalasi ke work order bila butuh kunjungan.
--
-- Beda dengan `incident` (module monitoring): insiden lahir dari alarm jaringan dan
-- berakar pada perangkat; tiket lahir dari manusia dan berakar pada pelanggan. Satu
-- gangguan fiber bisa memunculkan satu insiden + belasan tiket.
-- ============================================================

CREATE TABLE helpdesk_ticket (
    id               uuid PRIMARY KEY,
    tenant_id        uuid          NOT NULL REFERENCES tenant (id),
    code             varchar(20)   NOT NULL,
    -- Id lintas-module (customer) disimpan polos tanpa FK, sesuai konvensi module lain.
    customer_id      uuid          NOT NULL,
    -- Nama disalin saat tiket dibuka: antrean operator terbaca tanpa join lintas-module.
    customer_name    varchar(150)  NOT NULL,
    category         varchar(20)   NOT NULL,
    subject          varchar(150)  NOT NULL,
    -- Laporan awal pelanggan; balasan berikutnya masuk ke tabel pesan.
    description      varchar(2000) NOT NULL,
    status           varchar(20)   NOT NULL,
    -- Work order hasil eskalasi (kodenya di-snapshot: kode WO tak pernah berubah).
    work_order_id    uuid,
    work_order_code  varchar(20),
    opened_at        timestamptz   NOT NULL DEFAULT now(),
    last_activity_at timestamptz   NOT NULL DEFAULT now(),
    resolved_at      timestamptz,
    closed_at        timestamptz,
    created_at       timestamptz   NOT NULL DEFAULT now(),
    updated_at       timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_ticket_status CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED')),
    CONSTRAINT ck_ticket_category CHECK (
        category IN ('KONEKSI_PUTUS', 'KONEKSI_LAMBAT', 'PERANGKAT', 'TAGIHAN', 'LAINNYA')
    )
);
CREATE UNIQUE INDEX ux_ticket_tenant_code ON helpdesk_ticket (tenant_id, code);
-- Antrean operator: disaring status, diurutkan percakapan terakhir.
CREATE INDEX ix_ticket_tenant_activity ON helpdesk_ticket (tenant_id, status, last_activity_at DESC);
-- Daftar laporan seorang pelanggan di portal + rem "berapa yang masih terbuka".
CREATE INDEX ix_ticket_customer ON helpdesk_ticket (customer_id, opened_at DESC);

-- Utas percakapan: append-only, dibaca pelanggan maupun operator. `author = SYSTEM`
-- untuk jejak otomatis (ganti status, eskalasi) supaya riwayatnya utuh dalam satu aliran.
CREATE TABLE helpdesk_ticket_message (
    id          uuid PRIMARY KEY,
    tenant_id   uuid          NOT NULL REFERENCES tenant (id),
    ticket_id   uuid          NOT NULL REFERENCES helpdesk_ticket (id) ON DELETE CASCADE,
    author      varchar(20)   NOT NULL,
    -- Pengguna/pelanggan penulisnya; null untuk pesan sistem murni.
    author_id   uuid,
    author_name varchar(150)  NOT NULL,
    body        varchar(2000) NOT NULL,
    at          timestamptz   NOT NULL DEFAULT now(),
    created_at  timestamptz   NOT NULL DEFAULT now(),
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_ticket_message_author CHECK (author IN ('CUSTOMER', 'OPERATOR', 'SYSTEM'))
);
CREATE INDEX ix_ticket_message_ticket ON helpdesk_ticket_message (ticket_id, at);

-- ------------------------------------------------------------
-- Row-Level Security (dua-lapis, sama dengan tabel bisnis lain)
-- ------------------------------------------------------------
DO
$$
    DECLARE
        t text;
    BEGIN
        FOREACH t IN ARRAY ARRAY ['helpdesk_ticket', 'helpdesk_ticket_message']
            LOOP
                EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
                EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
                EXECUTE format($f$
                    CREATE POLICY tenant_isolation ON %I
                        USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
                        WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
                    $f$, t);
            END LOOP;
    END
$$;

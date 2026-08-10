-- ============================================================
-- inbox — kotak masuk OPERATOR di dalam aplikasi
--
-- Sampai sekarang setiap peristiwa penting (tiket lewat SLA, gangguan baru terbuka,
-- work order ditugaskan) hanya berakhir jadi baris log server atau pesan WA ke
-- PELANGGAN. Operatornya sendiri tak pernah diberi tahu apa pun: ia baru tahu kalau
-- kebetulan membuka halaman yang tepat. Tabel ini memberi peristiwa itu satu tempat
-- yang bisa dilihat manusia — lonceng di pojok konsol.
--
-- Beda dengan `notification_*` (module notification): yang di sana KELUAR ke pelanggan
-- lewat WA/email dan tunduk pada saklar pemicu tenant. Yang di sini tinggal DI DALAM
-- aplikasi dan ditujukan ke petugas.
--
-- Penerima ditentukan saat DIBACA, bukan saat ditulis. Satu baris = satu peristiwa,
-- dengan salah satu dari dua cara menyebut audiensnya:
--   * target_user_id     -> pemberitahuan pribadi (mis. WO yang ditugaskan ke si teknisi)
--   * required_permission-> siapa pun yang berwenang menangani (mis. antrean tak bertuan)
-- Alternatifnya — fan-out saat menulis (satu baris per calon penerima) — akan salah
-- begitu role seseorang berubah: baris lama menempel ke orang yang tak lagi berhak,
-- dan orang yang baru berhak tak pernah melihat apa yang sudah telanjur ditulis.
-- ============================================================

CREATE TABLE inbox_notification (
    id                  uuid          PRIMARY KEY,
    tenant_id           uuid          NOT NULL REFERENCES tenant (id),
    -- Jenis peristiwa; dipakai konsol untuk memilih ikon & warna.
    kind                varchar(40)   NOT NULL,
    severity            varchar(10)   NOT NULL,
    title               varchar(150)  NOT NULL,
    body                varchar(500)  NOT NULL,
    -- Rute konsol yang dituju saat pemberitahuan diklik (mis. `/work-orders/<id>`).
    -- Null = tak ada tujuan yang lebih spesifik daripada halaman yang sedang dibuka.
    link                varchar(200),
    -- Ditujukan ke SATU pengguna. Id lintas-module tanpa FK, sesuai konvensi module lain.
    target_user_id      uuid,
    -- Ditujukan ke SIAPA PUN pemegang izin ini. Diisi hanya bila target_user_id kosong.
    required_permission varchar(60),
    -- Kunci idempoten: penjaga SLA & korelasi insiden berjalan berulang, dan satu
    -- peristiwa yang sama tak boleh menumpuk jadi sepuluh baris di lonceng orang.
    dedupe_key          varchar(150)  NOT NULL,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_inbox_severity CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    -- Dua cara menyebut audiens itu saling meniadakan: kalau keduanya terisi, "pribadi"
    -- dan "semua yang berwenang" bertabrakan dan tak ada jawaban yang benar.
    CONSTRAINT ck_inbox_audience CHECK (target_user_id IS NULL OR required_permission IS NULL)
);
CREATE UNIQUE INDEX ux_inbox_notification_dedupe ON inbox_notification (tenant_id, dedupe_key);
-- Lonceng selalu membaca "yang terbaru dulu" dalam satu tenant.
CREATE INDEX ix_inbox_notification_feed ON inbox_notification (tenant_id, created_at DESC);
-- Penyaring audiens pribadi (kolom paling sering dibandingkan setelah tenant).
CREATE INDEX ix_inbox_notification_target ON inbox_notification (tenant_id, target_user_id)
    WHERE target_user_id IS NOT NULL;

-- Penanda "sudah dibaca" PER PENGGUNA — bukan kolom boolean di baris pemberitahuan:
-- satu baris pemberitahuan bisa dilihat banyak orang, dan yang satu membacanya tak
-- berarti yang lain sudah.
CREATE TABLE inbox_notification_read (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenant (id),
    notification_id uuid        NOT NULL REFERENCES inbox_notification (id) ON DELETE CASCADE,
    user_id         uuid        NOT NULL,
    read_at         timestamptz NOT NULL DEFAULT now(),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
-- Dibaca sekali tetap sekali: klik ganda tak boleh menggandakan penanda.
CREATE UNIQUE INDEX ux_inbox_read_once ON inbox_notification_read (notification_id, user_id);
-- Hitungan "belum dibaca" milik seorang pengguna.
CREATE INDEX ix_inbox_read_user ON inbox_notification_read (tenant_id, user_id);

-- ------------------------------------------------------------
-- Row-Level Security (dua-lapis, sama dengan tabel bisnis lain)
-- ------------------------------------------------------------
DO
$$
    DECLARE
        t text;
    BEGIN
        FOREACH t IN ARRAY ARRAY ['inbox_notification', 'inbox_notification_read']
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

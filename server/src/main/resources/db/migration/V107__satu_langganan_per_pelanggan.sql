-- ============================================================
-- 1 pelanggan = 1 langganan. Satu baris, seumur hidup pelanggan.
--
-- V106 baru menutup pintu: ia melarang langganan KEDUA yang hidup, tapi membiarkan baris
-- TERMINATED menumpuk — jadi `customer → subscription` masih 1:N di skema, dan seluruh lapisan
-- di atasnya masih harus bertanya "yang mana?" tiap kali membaca. Pertanyaan itulah sumber
-- jawaban salah: `findOccupantsForOdps` memilih yang ACTIVE-dulu-baru-asal, portal menampilkan
-- daftar, dan operator masih melihat kata "tambah langganan" di layar.
--
-- Sekarang pintunya dicabut sekalian. Satu pelanggan memegang SATU baris langganan yang
-- statusnya berpindah-pindah (PENDING → ACTIVE ↔ ISOLATED → TERMINATED → berlangganan lagi).
-- Yang berhenti lalu kembali TIDAK mendapat baris baru; barisnya dihidupkan ulang. Itu bukan
-- penghematan baris, melainkan supaya yang menempel padanya ikut lestari:
--   * `subscriber_access` UNIQUE(tenant_id, subscription_id) → username PPPoE pelanggan tetap
--     sama setelah dia kembali, persis seperti yang diharapkan orang di lapangan;
--   * `invoice` UNIQUE(tenant_id, subscription_id, period_start) → bulan yang sudah tertagih
--     tak tertagih dua kali gara-gara putus lalu pasang lagi di bulan yang sama;
--   * `work_order.subscription_id` & `radius_session.subscription_id` tetap menunjuk riwayat
--     yang sama, jadi jejak teknisi dan jejak sesi tak terpotong jadi dua identitas.
--
-- Riwayat PAKET memang tak lagi berupa baris tersendiri — itu memang bukan tugasnya: invoice
-- sudah membekukan nama paket & harga per periode, dan audit log mencatat tiap perpindahan.
--
-- Pembersihan di bawah sengaja PELIT: hanya baris kembar yang benar-benar tak berjejak (tanpa
-- invoice, tanpa akun jaringan, tanpa sesi, tanpa work order) yang dihapus. Baris kembar yang
-- berjejak TIDAK ditebak-tebak nasibnya — migrasi berhenti dan menyebut pelanggannya, karena
-- memilih langganan mana yang "benar" adalah keputusan komersial, bukan keputusan migrasi.
--
-- RLS: koneksi Flyway tak pernah memasang GUC `app.tenant_id`, sedangkan `subscription` memakai
-- FORCE ROW LEVEL SECURITY — DML apa pun di sini akan mengenai NOL baris dengan sunyi (jebakan
-- yang sudah memakan V103, lihat V104). Karena itu RLS dimatikan sekeliling pembersihan. FORCE
-- tak ikut luntur: `relforcerowsecurity` kolom terpisah dari `relrowsecurity`.
-- ============================================================

ALTER TABLE subscription DISABLE ROW LEVEL SECURITY;

DO $$
DECLARE
    tersisa text;
BEGIN
    -- Pemenang per pelanggan: yang masih hidup (V106 menjamin paling banyak satu), kalau semua
    -- sudah berakhir maka yang paling baru. Sisanya kandidat hapus.
    DELETE FROM subscription s
    WHERE EXISTS (
              SELECT 1 FROM subscription o WHERE o.customer_id = s.customer_id AND o.id <> s.id
          )
      AND s.id <> (
              SELECT k.id FROM subscription k
              WHERE k.customer_id = s.customer_id
              ORDER BY (k.status <> 'TERMINATED') DESC, k.created_at DESC
              LIMIT 1
          )
      AND NOT EXISTS (SELECT 1 FROM invoice i           WHERE i.subscription_id = s.id)
      AND NOT EXISTS (SELECT 1 FROM subscriber_access a WHERE a.subscription_id = s.id)
      AND NOT EXISTS (SELECT 1 FROM radius_session r    WHERE r.subscription_id = s.id)
      AND NOT EXISTS (SELECT 1 FROM work_order w        WHERE w.subscription_id = s.id);

    SELECT string_agg(c.code || ' (' || d.jumlah || ' langganan)', ', ' ORDER BY c.code)
      INTO tersisa
      FROM (
          SELECT customer_id, count(*) AS jumlah
            FROM subscription GROUP BY customer_id HAVING count(*) > 1
      ) d
      JOIN customer c ON c.id = d.customer_id;

    IF tersisa IS NOT NULL THEN
        RAISE EXCEPTION
            'Masih ada pelanggan berlangganan ganda yang berjejak: %. Akhiri & pindahkan yang tak '
            'lagi dipakai (atau daftarkan sebagai pelanggan tersendiri) sebelum menjalankan ulang.',
            tersisa;
    END IF;
END $$;

-- Pengganti indeks parsial V106: bukan lagi "satu yang hidup", melainkan satu titik.
DROP INDEX IF EXISTS uq_subscription_live_per_customer;
CREATE UNIQUE INDEX uq_subscription_customer ON subscription (customer_id);

-- `ix_subscription_customer` (V2) kini murni beban tulis: indeks unik di atas sudah melayani
-- setiap pencarian per-pelanggan yang dulu dilayaninya.
DROP INDEX IF EXISTS ix_subscription_customer;

ALTER TABLE subscription ENABLE ROW LEVEL SECURITY;

-- ============================================================
-- 2FA operator (TOTP) — faktor kedua untuk akun yang memegang kendali jaringan
--
-- Password operator adalah kunci ke SELURUH data satu ISP: daftar pelanggan, tagihan,
-- kredensial PPPoE, sampai tombol putus-sambung layanan. Satu password yang bocor
-- (dipakai ulang di situs lain, tertinggal di WhatsApp grup, kena phishing) cukup untuk
-- semuanya, dan kebocoran seperti itu tak meninggalkan jejak apa pun sampai kerusakannya
-- terlihat. Faktor kedua memutus rantai itu: yang bocor cuma separuh kunci.
--
-- Dipasang OPT-IN per pengguna, bukan wajib serentak — memaksakannya ke seluruh operator
-- di satu deploy berarti ISP yang timnya belum siap akan mematikannya lagi seluruhnya.
-- ============================================================

-- ------------------------------------------------------------
-- Kolom TOTP di akun operator
--
-- `totp_secret` TERENKRIPSI (AES-GCM, kunci FTTH_ENCRYPTION_SECRET) — beda tajam dengan
-- password yang di-hash satu arah: server harus bisa MEMBACA rahasia ini tiap kali
-- memverifikasi kode, jadi hash tak mungkin dipakai. Konsekuensinya jujur: siapa pun yang
-- memegang dump database DAN kunci enkripsi bisa membangkitkan kode — sama seperti seluruh
-- rahasia lain yang bisa dibaca ulang di sistem ini (community string SNMP, kredensial OLT).
--
-- `totp_enabled_at` NULL sementara `totp_secret` terisi = pendaftaran yang belum
-- dikonfirmasi (QR sudah ditampilkan, kode pertama belum diverifikasi). Perbedaan itu yang
-- membuat orang tak bisa mengunci dirinya sendiri di luar akun gara-gara salah memindai QR.
--
-- `totp_last_step` menyimpan langkah waktu terakhir yang berhasil dipakai. Tanpa itu, kode
-- yang sama masih sah selama sisa jendela 30 detiknya — cukup bagi yang sempat mengintip
-- layar atau menyadap satu request untuk memakainya ulang.
-- ------------------------------------------------------------
ALTER TABLE app_user
    ADD COLUMN totp_secret     text,
    ADD COLUMN totp_enabled_at timestamptz,
    ADD COLUMN totp_last_step  bigint;

-- ------------------------------------------------------------
-- Kode pemulihan
--
-- Ponsel hilang/rusak/di-reset adalah kejadian biasa, dan tanpa jalan keluar 2FA berubah
-- dari pengaman jadi cara terkunci dari usaha sendiri. Admin tenant memang bisa
-- mengosongkan 2FA orang lain, tapi itu tak menolong ADMIN TERAKHIR sebuah ISP — kode ini
-- yang menolong.
--
-- Disimpan sebagai hash SHA-256, sama seperti refresh token: nilainya acak panjang (bukan
-- password yang bisa ditebak dari kamus), jadi hash cepat sudah memadai dan tak ada
-- gunanya menahan bcrypt di jalur login. Baris yang terpakai TIDAK dihapus — `used_at`
-- diisi supaya saat audit terlihat bahwa pemulihan pernah dipakai, dan kapan.
-- ------------------------------------------------------------
CREATE TABLE user_recovery_code (
    id         uuid PRIMARY KEY,
    tenant_id  uuid        NOT NULL REFERENCES tenant (id),
    user_id    uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    code_hash  varchar(64) NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
-- Jalur panas: cocokkan satu kode milik satu pengguna saat masuk.
CREATE UNIQUE INDEX ux_user_recovery_code ON user_recovery_code (user_id, code_hash);

-- ------------------------------------------------------------
-- Row-Level Security (dua-lapis, sama dengan tabel ber-tenant lain)
-- ------------------------------------------------------------
ALTER TABLE user_recovery_code ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_recovery_code FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON user_recovery_code
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

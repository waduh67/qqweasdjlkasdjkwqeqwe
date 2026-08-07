-- ============================================================
-- Instruksi bayar in-app (mode API Pivot) per-tagihan
--
-- Alur bayar pindah dari REDIRECT (halaman ter-host Pivot lewat `pay_url`) ke mode API:
-- Pivot mengembalikan instruksi bayar langsung — nomor Virtual Account atau string QRIS —
-- yang ditampilkan DI DALAM aplikasi (tanpa redirect). Instruksi disimpan per-tagihan agar
-- panel bayar bisa merender ulang tanpa memanggil gateway lagi.
--
--   pay_method     instrumen terpilih: VIRTUAL_ACCOUNT | QR (NULL = belum dipilih / charge lama).
--   va_*           Virtual Account: nomor, bank (channel), nama, kedaluwarsa.
--   qr_content     string QRIS mentah (dirender jadi kode QR di klien); qr_url gambar opsional.
--   *_expires_at   kedaluwarsa instrumen — lewat tempo → tenant/pelanggan buat charge baru.
--
-- Semua NULLABLE tanpa default: baris lama tetap valid (belum punya instruksi in-app), dan
-- charge REDIRECT lama tetap memakai `pay_url`. Diterapkan ke kedua alur tagihan:
--   invoice                     — tagihan pelanggan (tenant → pelanggan, ter-RLS).
--   tenant_subscription_invoice — tagihan langganan (platform → tenant, tanpa RLS).
-- ============================================================

ALTER TABLE invoice ADD COLUMN pay_method    varchar(20);
ALTER TABLE invoice ADD COLUMN va_channel    varchar(20);
ALTER TABLE invoice ADD COLUMN va_number     varchar(64);
ALTER TABLE invoice ADD COLUMN va_name       varchar(100);
ALTER TABLE invoice ADD COLUMN va_expires_at timestamptz;
ALTER TABLE invoice ADD COLUMN qr_content    text;
ALTER TABLE invoice ADD COLUMN qr_url        varchar(1024);
ALTER TABLE invoice ADD COLUMN qr_expires_at timestamptz;

ALTER TABLE tenant_subscription_invoice ADD COLUMN pay_method    varchar(20);
ALTER TABLE tenant_subscription_invoice ADD COLUMN va_channel    varchar(20);
ALTER TABLE tenant_subscription_invoice ADD COLUMN va_number     varchar(64);
ALTER TABLE tenant_subscription_invoice ADD COLUMN va_name       varchar(100);
ALTER TABLE tenant_subscription_invoice ADD COLUMN va_expires_at timestamptz;
ALTER TABLE tenant_subscription_invoice ADD COLUMN qr_content    text;
ALTER TABLE tenant_subscription_invoice ADD COLUMN qr_url        varchar(1024);
ALTER TABLE tenant_subscription_invoice ADD COLUMN qr_expires_at timestamptz;

-- Matriks persetujuan gudang yang dimiliki SERVER, bukan klien.
--
-- Sampai sekarang `InventoryApprovalService.request()` menerima seluruh kebijakan
-- (daftar tier, ambang, siapa approver-nya, berapa lama berlaku) dari body request.
-- Artinya siapa pun yang boleh mengajukan restock juga boleh MENGARANG kebijakannya:
-- kirim satu tier berambang 0 dengan dirinya sendiri sebagai approver, lalu setujui
-- sendiri lewat akun kedua. Seluruh guna approval sebagai kontrol kebocoran aset hilang,
-- dan tidak ada satu baris pun di basis data yang menunjukkan bahwa itu terjadi —
-- snapshot kebijakannya akan terlihat "sah" karena memang persis seperti yang diminta.
--
-- Dua tabel di bawah ini yang memindahkan keputusan itu ke server: request hanya menyebut
-- APA yang diminta (jenis + nilai), tier dan approver-nya dibaca dari sini.
CREATE TABLE inventory_approval_policy (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    -- Dinamai `approval_type`, bukan `movement_kind`, karena yang diajukan ke approval
    -- adalah nilai `InventoryApprovalType` — himpunan bagian dari `MovementKind` yang
    -- namanya SENGAJA identik (RESTOCK, ADJUSTMENT, LOSS, ...). Menyimpan MovementKind
    -- penuh akan mengundang baris untuk jenis mutasi yang tidak pernah lewat approval
    -- (ISSUE, TRANSFER) dan operator akan mengira mengaturnya berpengaruh, padahal tidak.
    approval_type varchar(32) NOT NULL,
    -- TTL detik, bukan interval: nilai ini dibaca ke `java.time.Duration` dan diadu dengan
    -- `expires_at`; menyimpannya sebagai interval membuat konversi bergantung pada
    -- interpretasi bulan/tahun Postgres yang tidak punya padanan pasti di Duration.
    expiry_seconds integer NOT NULL,
    emergency_allowed boolean NOT NULL DEFAULT false,
    active boolean NOT NULL DEFAULT true,
    CONSTRAINT inventory_approval_policy_type_uq UNIQUE (tenant_id, approval_type),
    CONSTRAINT inventory_approval_policy_type_ck CHECK (
        approval_type IN ('RESTOCK', 'ISSUE_EXCEPTION', 'ADJUSTMENT', 'LOSS', 'SCRAP', 'WRITE_OFF', 'COUNT_VARIANCE')
    ),
    -- Batas bawah 5 menit: TTL yang lebih pendek membuat permintaan kedaluwarsa sebelum
    -- approver sempat membuka notifikasinya, dan petugas gudang akan belajar mengakalinya
    -- dengan override darurat. Batas atas 30 hari supaya permintaan yang terlupakan tidak
    -- menggantung PENDING selamanya dan menyembunyikan barang yang tak pernah masuk.
    CONSTRAINT inventory_approval_policy_expiry_ck CHECK (expiry_seconds BETWEEN 300 AND 2592000)
);

CREATE TABLE inventory_approval_policy_tier (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    policy_id uuid NOT NULL REFERENCES inventory_approval_policy(id) ON DELETE CASCADE,
    tier_number integer NOT NULL,
    minimum_amount bigint NOT NULL,
    -- Peran yang berwenang (mis. 'Kepala Gudang'). Disimpan sebagai nama peran, BUKAN
    -- hanya daftar id: personel berganti, dan kebijakan yang menyebut orang akan diam-diam
    -- kehilangan approver-nya begitu orang itu resign — permintaan restock lalu menggantung
    -- PENDING sampai kedaluwarsa tanpa ada yang tahu penyebabnya.
    approver_role varchar(120) NOT NULL,
    -- Daftar id approver eksplisit sebagai PELENGKAP peran. Dipakai sebagai sumber kebenaran
    -- kalau direktori peran belum tersedia/kosong, supaya tenant tetap bisa memakai approval
    -- alih-alih terkunci total oleh tier yang tidak punya satu pun approver.
    approver_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    CONSTRAINT inventory_approval_policy_tier_uq UNIQUE (tenant_id, policy_id, tier_number),
    CONSTRAINT inventory_approval_policy_tier_number_ck CHECK (tier_number > 0),
    CONSTRAINT inventory_approval_policy_tier_amount_ck CHECK (minimum_amount >= 0),
    -- ATURAN: tier pertama WAJIB berambang 0. Kalau ambang terendah > 0, permintaan bernilai
    -- kecil menghasilkan NOL tier wajib: permintaannya lahir PENDING, `currentTier()` null,
    -- dan setiap approver yang mencoba memutuskan ditolak "approval is already complete".
    -- Permintaan itu tidak akan pernah bisa disetujui MAUPUN ditolak — barangnya menggantung
    -- sampai sweeper kedaluwarsa membunuhnya dan tidak ada yang mengerti kenapa.
    CONSTRAINT inventory_approval_policy_tier_floor_ck CHECK (tier_number > 1 OR minimum_amount = 0),
    CONSTRAINT inventory_approval_policy_tier_role_ck CHECK (length(btrim(approver_role)) > 0),
    CONSTRAINT inventory_approval_policy_tier_ids_ck CHECK (jsonb_typeof(approver_ids) = 'array')
);

-- Jalur baca panas adalah "kebijakan aktif tenant ini untuk jenis X, beserta tiernya",
-- dipanggil setiap kali seseorang mengajukan restock/penyesuaian.
CREATE INDEX inventory_approval_policy_tenant_idx ON inventory_approval_policy (tenant_id, approval_type, active);
CREATE INDEX inventory_approval_policy_tier_policy_idx ON inventory_approval_policy_tier (tenant_id, policy_id, tier_number);

ALTER TABLE inventory_approval_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_approval_policy FORCE ROW LEVEL SECURITY;
ALTER TABLE inventory_approval_policy_tier ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_approval_policy_tier FORCE ROW LEVEL SECURITY;

-- NULLIF supaya koneksi tanpa GUC `app.tenant_id` (Flyway, tooling) melihat NOL baris
-- alih-alih meledak saat cast '' ke uuid.
CREATE POLICY inventory_approval_policy_tenant_policy ON inventory_approval_policy
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY inventory_approval_policy_tier_tenant_policy ON inventory_approval_policy_tier
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- SENGAJA TANPA seed per tenant di migrasi ini.
--
-- Flyway jalan sebagai NOBYPASSRLS tanpa `app.tenant_id`, jadi menyisipkan baris default
-- per tenant menuntut DISABLE ROW LEVEL SECURITY plus membaca tabel milik modul `tenancy`
-- dari migrasi modul `inventory` — dan tenant yang lahir SETELAH migrasi ini tetap tidak
-- akan punya barisnya. Default 2 tier (Kepala Gudang -> Manajer Operasional) karena itu
-- hidup di kode (`InventoryApprovalPolicyService.DEFAULT_*`) dan berlaku untuk tenant mana
-- pun yang belum menyetel matriksnya; tabel ini hanya menyimpan OVERRIDE tenant.

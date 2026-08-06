---
name: netops-ui
description: House-style UI/UX untuk web NetOps Console (repo ftth) — React + design system token di web/src/index.css dan primitif di web/src/components/ui.tsx. Pakai skill ini setiap kali membangun/menyunting halaman, panel, atau komponen web di direktori web/. Berbeda dari plugin frontend-design (yang untuk desain greenfield yang khas): DI SINI tujuannya SERAGAM dengan sistem yang sudah ada, bukan tampil beda.
---

# NetOps Console — UI house-style

Aturan main: **selaras dengan sistem yang ada, jangan bikin gaya baru.** Halaman baru harus
terlihat seolah ditulis oleh orang yang sama yang menulis `TaxSettingsPage.tsx`. Sebelum menulis
komponen apa pun, cek apakah token/kelas/primitif yang dibutuhkan **sudah ada** — hampir selalu ada.

Copy, komentar kode, dan label UI **berbahasa Indonesia**, sentence case, kalimat aktif, istilah yang
dikenal operator (bukan istilah internal sistem). Uang = string (BigDecimal) di TypeScript.

## Sumber kebenaran (baca sebelum mendesain)

- **`web/src/index.css`** — semua token warna + kelas utilitas & komponen. JANGAN hardcode hex,
  JANGAN tambah font. Kalau butuh warna, pakai `var(--...)`.
- **`web/src/components/ui.tsx`** — primitif bersama: `Badge`, `StatusBadge`, `EmptyState`, `Modal`,
  `Tabs`, `Drawer`, `Toolbar`, `SearchInput`, `SkeletonRows`, `Spinner`, `ToastProvider`/`useToast`.
- **`web/src/pages/TaxSettingsPage.tsx`** — halaman rujukan kanonis untuk pola settings.
- **`web/src/components/Layout.tsx`** — sidebar + navigasi berkelompok + gating izin.

## Token & bahasa visual (terkunci)

- **Sidebar selalu gelap** (charcoal-navy `--sidebar-*`), lepas dari tema konten. Konten mengikuti
  tema terang/gelap via `data-theme` — jangan pernah kunci warna konten ke satu tema.
- **Aksen = violet/indigo** `--accent` (#4f46e5 terang / #818cf8 gelap). Ini satu-satunya warna
  brand; tombol utama, tautan aktif, fokus-ring semua turun dari sini.
- **Font** `Plus Jakarta Sans` (sudah global). Judul rapat: `letter-spacing` negatif, `font-weight`
  650–750. Jangan impor typeface lain.
- **Radius** `--radius` 12px (kartu), `--radius-sm` 8px (tombol/input), `--radius-lg` 16px.
- **Status BUKAN warna telanjang.** Selalu pasangkan warna dengan label/ikon. Nada status ada 6:
  `neutral · good · warning · serious · critical · accent` (tipe `Tone` di `ui.tsx`).
- Ini **enterprise SaaS**, biru-abu sejuk (Slate). BUKAN krem hangat/terracotta, BUKAN latar
  near-black + aksen acid-green — itu default AI generik yang justru harus dihindari di sini.

## Kelas utilitas yang WAJIB dipakai (jangan tulis ulang dengan style inline)

| Kelas | Guna |
|---|---|
| `.card` / `.card.pad-0` / `.card.clickable` | wadah panel; padding & shadow konsisten |
| `.card-head` + `.card-body` | kartu dengan judul ber-border |
| `.stack` | kolom flex, gap 1rem (vertikal) |
| `.row` | baris flex, gap 0.6rem, tengah |
| `.spread` | baris flex `space-between` (mis. label ↔ nilai, footer aksi) |
| `.wrap` `.grow` | modifier flex-wrap / flex:1 |
| `.hr` | pemisah tipis antar-seksi di dalam satu kartu |
| `.page-title` + `.page-sub` | judul halaman + subjudul muted |
| `.muted` `.dim` `.error` | warna teks sekunder / tersier / kritis |
| `.badge` + `.good/.warning/.serious/.critical/.accent` | pil status (atau pakai `<Badge>`) |
| `.segment` | toggle bersegmen (lihat pola `Segmented` di TaxSettingsPage) |
| `.settings-page` | wrapper halaman formulir 1-kolom, max-width 880 terpusat |

Tombol: `<button className="primary">` (aksi utama), `"ghost"` (sekunder/ikon), `"danger"`
(destruktif), modifier `"small"` / `"icon-btn"`. Input/`select`/`textarea` sudah bergaya global —
cukup pakai elemen native. Label pakai `<label><span>…</span>…</label>` atau pola `FormRow`.

## Primitif bersama — pakai, jangan bikin tandingannya

- **Status domain → `<StatusBadge status="ACTIVE" />`** (auto memetakan nada + memperindah teks lewat
  `STATUS_TONE`). Untuk label bebas: `<Badge tone="accent">…</Badge>`. Jangan pernah menentukan warna
  status manual di tempat lain — `STATUS_TONE` di `ui.tsx` adalah satu-satunya sumber.
- **Dialog** `<Modal title onClose footer wide>` — sudah menangani scrim, Esc, fokus. Untuk panel
  samping pakai `<Drawer>`.
- **Kosong/nihil** `<EmptyState title hint icon>` — jangan tampilkan tabel/daftar kosong tanpa ini.
- **Umpan balik** `const toast = useToast()` → `toast.success/error/info(...)`. Sukses simpan, gagal
  muat, dsb. lewat toast; jangan `alert()`.
- **Muat** `<SkeletonRows>` / `<Spinner>` untuk keadaan loading, bukan teks "Loading…" telanjang
  (kecuali `<p className="muted">Memuat…</p>` singkat seperti di halaman settings).
- **Tabel padat** pakai `Tabs`, `Toolbar`, `SearchInput`, atau `DataTable` yang sudah ada.

## Pola halaman settings (tiru `TaxSettingsPage.tsx` persis)

Untuk setiap halaman "setelan per-tenant":

1. Wrapper `<div className="stack settings-page">`, dibuka `.page-title` + `.page-sub`.
2. **Pisahkan `saved` (yang berlaku) dari `form` (suntingan).** Muat sekali, salin ke form via
   fungsi `toForm(view)`.
3. **Kartu status "Berlaku sekarang"** memakai `<Badge>` menampilkan kebijakan yang benar-benar aktif.
4. **Dirty-tracking**: hitung `changes: FieldChange[]` (label/from/to) via `useMemo`. `dirty =
   changes.length > 0`.
5. **Tombol simpan mati** sampai `dirty && valid`. Sediakan "Batalkan" (kembalikan `form` ke `saved`).
6. **Konfirmasi diff**: klik simpan → `<Modal>` menampilkan daftar `from → to` (coret nilai lama),
   plus `Callout` peringatan bila mengaktifkan sesuatu yang berdampak (mis. mulai menagih PPN).
7. **Izin**: `const { can } = useCan()`. Tampilkan read-only bila tak punya izin `*.manage`;
   `disabled={!manage}` pada tiap kontrol. Cermin izin server, jangan hanya sembunyikan.
8. **Tarif/persen**: server memakai pecahan (0.11), UI menampilkan persen (11) — konversi di tepi
   (`pctFromFraction`/`fractionFromPct`), tampilkan validasi inline yang ramah.

## Navigasi (Layout.tsx)

Menu dikelompokkan per alur kerja di `GROUPS` (Jaringan · Layanan Pelanggan · Lapangan ·
Administrasi). Item baru: tambah ke grup yang tepat dengan `permission` yang cocok — item difilter
`can(permission)`, cermin RBAC server. Rute di `App.tsx` dibungkus `<RequirePermission>`.

## Lantai kualitas (selalu)

- Responsif hingga mobile; `flex-wrap` pada baris yang bisa sempit.
- Fokus keyboard terlihat (sudah via `:focus-visible` global) — jangan matikan outline.
- `aria-label` pada tombol ikon; `role`/`aria-*` pada segmen, tab, dialog (contoh ada di `ui.tsx`).
- Hormati `prefers-reduced-motion`; animasi hemat, mikro-interaksi ≤ ~150ms (ikut token transisi).
- Verifikasi sebelum selesai: `cd web && npm run lint && npm run build` harus hijau.

## Anti-pola (jangan)

- ❌ Hex/rgb hardcode, font baru, atau CSS baru padahal token/kelas sudah ada.
- ❌ Warna status manual di luar `STATUS_TONE`.
- ❌ `alert()`/`confirm()` native — pakai `Modal` + `useToast`.
- ❌ Menyembunyikan kontrol karena izin tanpa juga menonaktifkan/menandai (bocor niat, bukan aman).
- ❌ Gaya "khas/eksperimental" ala plugin frontend-design di dalam repo ini — di sini seragam menang.

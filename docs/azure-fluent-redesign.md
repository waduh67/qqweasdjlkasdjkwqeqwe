# Redesign UI/UX — Microsoft Azure Portal / Fluent Design System

Dokumen ini adalah **design spec + panduan implementasi** untuk migrasi tampilan
konsol `web/` agar meniru **Microsoft Azure Portal** memakai **Fluent UI v9**
(`@fluentui/react-components`). Jadikan acuan saat membuat halaman/komponen baru.

## Prinsip

1. **Enterprise-grade, padat, terbaca.** Kepadatan data tinggi tapi rapi — tipografi
   Segoe UI, border halus, hover state profesional.
2. **Bukan sekadar warna.** Alur UX ikut berubah: command bar, DataGrid, dan **blade
   panel geser-kanan** menggantikan modal terpusat & form kartu-inline.
3. **Satu sistem.** Semua warna/spacing lewat token; komponen struktural dipakai ulang.
4. **Aksesibilitas.** Kontras teks ≥4.5:1, focus ring terlihat, `aria-label` untuk
   tombol ikon, navigasi keyboard penuh, `prefers-reduced-motion` dihormati.

## Design Tokens

### Warna (Fluent/Azure)
| Peran | Light | Dark | Catatan |
|---|---|---|---|
| Aksen (Azure Blue) | `#0078D4` | `#2899F5` | brand primary, active indicator |
| Aksen hover | `#106EBE` | `#62ABF5` | |
| Kanvas | `#F8F9FA` | `#1B1A19` | latar area konten |
| Permukaan (kartu) | `#FFFFFF` | `#201F1E` | |
| Border kuat | `#EDEBE9` | `#3B3A39` | garis tabel/kartu |
| Teks | `#1B1A19` | `#FFFFFF` | |
| Teks sekunder | `#484644` | `#D2D0CE` | |

Status (tetap, tak di-tema; selalu dipasangkan ikon/label — tak pernah warna saja):
`good #10B981`, `warning #F59E0B`, `serious #F97316`, `critical #EF4444`.

Sumber token:
- Token CSS lama (dipakai halaman yang belum migrasi): `web/src/index.css` `:root` /
  `[data-theme='dark']`.
- Tema Fluent (dipakai komponen `@fluentui/react-components`): `web/src/theme/azureTheme.ts`
  (`azureLight` / `azureDark`, brand ramp dipusatkan ke `#0078D4`).

### Bentuk & ruang
- **Radius:** 4px (default), 2px (kecil), 6px (besar) — Fluent jauh kurang bulat.
- **Grid spacing:** kelipatan **4/8px**.
- **Font:** `Segoe UI` (dengan fallback sistem).
- **Elevasi:** bayangan halus; flyout/menu pakai depth Fluent (shadow16/64).

## Arsitektur tema

`ThemeProvider` (`web/src/theme/ThemeProvider.tsx`) adalah **sumber kebenaran tunggal**:
menyimpan pilihan di `localStorage['ftth.theme']`, menstempel `data-theme` pada `<html>`
(dipakai token index.css lama), sekaligus memberi `FluentProvider` tema `azureLight`/
`azureDark`. `ThemeToggle` cukup memanggil `toggle()` dari `useTheme()`.

Nesting provider di `App.tsx`:
```
ThemeProvider → FluentProvider(azureLight|azureDark)
  → BrowserRouter → ToastProvider → Routes
```

## Anatomi komponen (target)

### 1. App shell
- **Left-nav TERANG** collapsible (expanded: ikon+teks; collapsed: ikon saja), section
  divider, **active-rib biru `#0078D4`**, hover state. Sumber item = array `GROUPS` di
  `Layout.tsx` / `PlatformLayout.tsx` (permission-filtered via `useCan()`), kontrak
  collapse `localStorage['ftth.sidebarCollapsed']`.
- **Global header** persist: brand, search, ThemeToggle, user chip, logout, EnvSwitcher.
- **Breadcrumb + PageHeader**: hierarki di atas tiap konten halaman + judul + subtitle +
  slot command bar.

### 2. CommandBar (toolbar aksi)
- **`+ Create` primary di paling KIRI.** Aksi sekunder (Delete/Export/Refresh) berurutan
  ke kanan, tiap tombol berikon (lucide-react). **Delete disabled** bila belum ada baris
  terpilih.

### 3. DataGrid
- Kolom-1: **checkbox** multi-select. Kolom-2: **menu aksi kiri** (`…`) berisi
  Edit/Delete/View Details. Header sortable dengan indikator. Loading = **Shimmer**.
  Empty state ilustratif + tombol **Create**. API `Column<T>` (`DataTable.tsx`)
  dipertahankan; selection & rowActions opsional.

### 4. Blade (panel geser-kanan) — untuk SEMUA form
- **Bukan modal terpusat.** Slide dari kanan.
- Ukuran: **sm** 400–500px (form <5 field), **lg**/**full** 85–100% (form kompleks/tab).
- Anatomi: header (judul + `X`), body scroll, **footer sticky** (Save primary di KIRI,
  Cancel kanan). ESC menutup; bila form kotor (dirty) minta konfirmasi.

## Peta migrasi (ringkas per phase)
- **Phase 0** ✅ Deps, tema Azure, FluentProvider, remap token index.css, docs ini.
- **Phase 1** Shell: AzureNav, AzureHeader, PageHeader/breadcrumb.
- **Phase 2** CommandBar + DataGrid.
- **Phase 3** Blade + Field/FormSection; migrasi ~14 halaman CRUD.
- **Phase 4** ✅ Kontrol form Fluent + feedback/validasi: keadaan kontrol (disabled/
  invalid/checkbox aksen Azure), token `--danger`, dan validasi klien inline lewat
  `Field` (`error`/`required` + `aria-invalid`) — eksemplar di Pelanggan & Role.
- **Phase 5** Realm lain, halaman non-tabel, cleanup, changelog.

## Panduan membuat halaman baru
1. Bungkus judul dengan `PageHeader` (breadcrumb + title + CommandBar slot).
2. Aksi list → `CommandBar` (`+ Create` kiri).
3. Daftar data → `DataTable` (selection + rowActions bila perlu).
4. Form create/edit → `Blade` (pilih size sesuai kompleksitas), field via `Field`.
5. Warna/spacing → token; jangan hardcode hex di komponen.

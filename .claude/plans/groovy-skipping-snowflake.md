# Redesign UI/UX → Microsoft Azure Portal (Fluent UI v9)

## Context

Frontend `web/` (React 19 + TS + Vite 8) saat ini memakai bahasa desain "Enterprise
SaaS" (Slate/Indigo/Violet) dengan sidebar charcoal, form campur (modal terpusat +
kartu inline), dan CSS custom penuh (`index.css` ~1790 baris, token terpusat). User
ingin seluruh aplikasi **di-redesign meniru Microsoft Azure Portal / Fluent Design
System** — bukan hanya warna, tapi juga alur UX: command bar, DataGrid padat, dan
**blade panel geser-kanan** untuk semua form (bukan modal terpusat). Referensi visual:
screenshot Azure "Virtual machines" (left-nav terang, command bar `+ Create` di kiri,
tabel dengan checkbox + menu aksi, breadcrumb).

### Keputusan yang sudah dikunci (dari user)
1. **Pakai library asli `@fluentui/react-components` v9** (bukan sekadar restyle CSS).
2. **Left-nav TERANG ala Azure** dengan active-rib biru `#0078D4`.
3. **Pertahankan light + dark** — dua tema Fluent (Azure light + Azure dark), toggle &
   `localStorage` yang ada dipertahankan.
4. **Branch baru** untuk semua perubahan (jangan sentuh `main`).
5. Plan disusun **per-phase** (Phase 0..5). Deliverable termasuk **update CHANGELOG.md**
   + **docs md** (`docs/azure-fluent-redesign.md`) sebagai design spec.

### Aset eksisting yang DIPAKAI ULANG (jangan bikin dari nol)
- `web/src/components/ui.tsx` → `Drawer` (panel geser-kanan) = fondasi **Blade**;
  `Modal`, `ConfirmDialog`, `Toolbar`, `SearchInput`, `EmptyState`, `SkeletonRows`,
  `StatusBadge`/`STATUS_TONE`, `Tabs`, `ToastProvider`/`useToast`.
- `web/src/components/DataTable.tsx` → API `Column<T>` (cell + sortValue) dipakai 16
  halaman; **dipertahankan & diperluas**, bukan diganti total.
- `GROUPS` array di `Layout.tsx` (L46-99) & `PlatformLayout.tsx` (L35-66) = sumber
  kebenaran nav (to/label/icon/permission/group) → langsung feed ke Fluent Nav.
- Kontrak collapse sidebar: `localStorage['ftth.sidebarCollapsed']` + class
  `.app.sidebar-collapsed` (Layout.tsx L107-120).
- Tema: `ThemeToggle.tsx` set `data-theme` di `<html>` + `localStorage['ftth.theme']`.
- `useCan()` (`src/auth/useCan.ts`) untuk filter nav & command bar per-permission.
- `Combobox.tsx` / `MultiCombobox.tsx` (typeahead async) untuk field lookup.

### Risiko & mitigasi (WAJIB dibaca sebelum eksekusi)
- **Peer-dep React 19:** Fluent v9 mendeklarasikan peer React 18. Install dengan
  `npm i @fluentui/react-components @fluentui/react-icons --legacy-peer-deps` (atau
  tambahkan `overrides` di package.json). Uji `npm run dev` + `npm run build` segera di
  Phase 0 sebelum lanjut. Jika ada breakage runtime, pin ke versi Fluent v9 stabil
  terbaru yang sudah lolos React 19.
- **Bentrok CSS global:** `index.css` menata elemen telanjang (`button`, `input`,
  `table`, `a`, heading) — ini akan bocor ke komponen Fluent (Griffel). Mitigasi:
  di Phase 0 **scope ulang** selektor elemen global (bungkus di bawah class `.legacy`
  atau ubah ke class spesifik) sehingga hanya halaman belum-migrasi yang terpengaruh;
  komponen Fluent memakai class Griffel sendiri.
- **Ukuran migrasi:** 37 halaman. Strategi = bangun komponen shared dulu (shell,
  CommandBar, DataGrid, Blade, Field), lalu migrasi halaman bertahap; app tetap jalan
  di tiap phase.

---

## Phase 0 — Fondasi: branch, dependency, tema Azure, FluentProvider

Tujuan: pondasi teknis siap, app masih tampil normal.

- Buat branch: `git checkout -b feat/azure-fluent-redesign`.
- Install: `@fluentui/react-components`, `@fluentui/react-icons` (+ `--legacy-peer-deps`).
- **Brand ramp Azure** (`src/theme/azureTheme.ts` baru): `createLightTheme` /
  `createDarkTheme` dari `BrandVariants` yang dipusatkan ke Azure blue `#0078D4`
  (ramp 10 tingkat 10→160). Export `azureLight`, `azureDark`.
- **FluentProvider** di `src/App.tsx`: bungkus di dalam `ToastProvider`, pilih tema
  dari state tema aktif. Buat `src/theme/ThemeProvider.tsx` (context tipis) yang membaca
  `localStorage['ftth.theme']` + `data-theme` supaya `ThemeToggle` lama tetap jalan dan
  FluentProvider ikut ganti `azureLight`/`azureDark`. Set `<body>` font ke Segoe UI.
- **Scope selektor global** di `index.css`: netralkan `button/input/select/table/a/h*`
  telanjang (ubah ke class atau bungkus `.legacy`) agar tak bocor ke Fluent.
- Buat **docs spec**: `docs/azure-fluent-redesign.md` (isi: prinsip Azure/Fluent, token
  warna `#0078D4`/`#F8F9FA`/`#EDEBE9`, grid 4/8px, radius 2-4px, anatomi shell/command
  bar/DataGrid/blade — jadikan sumber acuan phase berikut).

Files: `web/package.json`, `web/src/theme/azureTheme.ts` (baru),
`web/src/theme/ThemeProvider.tsx` (baru), `web/src/App.tsx`, `web/src/index.css`,
`web/src/components/ThemeToggle.tsx`, `docs/azure-fluent-redesign.md` (baru).

Verifikasi: `npm run dev` jalan tanpa error; `npm run build` sukses; toggle light/dark
mengganti tema Fluent; halaman lama masih terbaca.

## Phase 1 — App shell: left-nav terang, global header, breadcrumb

Tujuan: kerangka Azure (nav kiri terang collapsible + top bar + breadcrumb) terpasang
di `Layout` & `PlatformLayout`.

- `src/components/AzureNav.tsx` (baru): left-nav TERANG dari `GROUPS`, memakai Fluent
  `NavDrawer`/`NavCategory`/`NavItem` (atau komponen setara), ikon `@fluentui/react-icons`,
  active-rib biru `#0078D4`, section divider, state expanded/collapsed (ikon-only) pakai
  kontrak `ftth.sidebarCollapsed` yang ada. Tetap permission-filtered via `useCan()`.
- `src/components/AzureHeader.tsx` (baru): top bar persist — brand kiri, search global
  (opsional pakai `SearchInput`), kanan: `ThemeToggle`, user chip, logout, `EnvSwitcher`.
- `src/components/PageHeader.tsx` (baru): breadcrumb (dari route tree/`useLocation`) +
  judul + subtitle + slot command bar. Ganti pola `<h1 class="page-title">` yang
  di-hardcode tiap halaman secara bertahap.
- Refactor `Layout.tsx` & `PlatformLayout.tsx` memakai `AzureNav` + `AzureHeader`;
  pertahankan grid & `FLUSH_ROUTES` (mis. `/map`).

Files: `web/src/components/{AzureNav,AzureHeader,PageHeader}.tsx` (baru),
`web/src/components/Layout.tsx`, `web/src/components/PlatformLayout.tsx`,
`web/src/components/icons.tsx` (map ke react-icons bila perlu).

Verifikasi: nav terang tampil, collapse/expand jalan & persist, active item ter-rib biru,
breadcrumb muncul di tiap halaman, dark mode tetap benar.

## Phase 2 — CommandBar + DataGrid ala Azure

Tujuan: toolbar aksi + tabel padat Azure.

- `src/components/CommandBar.tsx` (baru): Fluent `Toolbar`. **`+ Create` primary
  di paling KIRI**, aksi sekunder (Delete/Export/Refresh) berurutan ke kanan, tiap
  tombol ada ikon Fluent (`Add`, `Delete`, `ArrowDownload`, `ArrowClockwise`). Logika
  disabled: `Delete` nonaktif bila `selectedCount === 0`.
- Perluas `DataTable.tsx` (pertahankan API `Column<T>`, tambah opsional):
  `selection` + `onSelectionChange` (kolom checkbox paling kiri), `rowActions` (kolom
  ke-2: menu `…`/`MoreHorizontal` Fluent `Menu` berisi Edit/Delete/View Details),
  re-skin header/sort dengan indikator Fluent, loading pakai Fluent `Shimmer`
  (ganti `SkeletonRows`), empty-state ilustratif + tombol CTA `Create`.
- Terapkan CommandBar + DataGrid baru di halaman contoh dulu: `UsersPage`,
  `CustomersPage`, `CatalogPage`, `InventoryPage` (pola `.spread` + primary button →
  CommandBar).

Files: `web/src/components/{CommandBar,DataTable}.tsx`,
`web/src/pages/{Users,Customers,Catalog,Inventory}Page.tsx`, `web/src/components/ui.tsx`
(Shimmer/empty helpers).

Verifikasi: pilih baris → Delete aktif; menu aksi kiri buka Edit/Delete/View; sort
carets; shimmer saat loading; empty state punya tombol Create.

## Phase 3 — Blade system (panel geser-kanan) untuk SEMUA form

Tujuan: hilangkan modal terpusat & form kartu-inline untuk create/edit; semua jadi blade.

- `src/components/Blade.tsx` (baru) di atas Fluent `Drawer`/`OverlayDrawer` (promosi dari
  `Drawer` lama): props `size: 'sm' | 'lg' | 'full'` (sm 400-500px; full 85-100%),
  header (judul + `X`), body scroll, **sticky footer** (Save primary di KIRI, Cancel
  kanan), ESC-dismiss dengan konfirmasi bila dirty. Reuse `.scrim` + a11y `role="dialog"`.
- `src/components/form/` (baru): `Field` (label + required + helper + error inline),
  `FormSection`/`fieldset` — mengisi celah "tak ada wrapper form" di semua halaman.
- Migrasi ~14 halaman CRUD dari `Modal`/kartu-inline → `Blade`:
  - Modal → Blade: `CustomersPage`, `WorkOrdersPage`, `InvoicesPage`, `TenantSubscriptionModal`.
  - Kartu-inline → Blade: `UsersPage`, `CatalogPage` (kompleks → `size=full`),
    `InventoryPage`, `RolesPage`, `AreasPage`, `BngPage`, `VpnPage`, `VpnServersPage`.
  - Form kecil (<5 field) → `size=sm`; form kompleks/bertab → `size=lg`/`full`.

Files: `web/src/components/Blade.tsx` (baru), `web/src/components/form/*` (baru),
`web/src/components/ui.tsx` (deprecate `Modal` untuk form; sisakan untuk konfirmasi),
~14 file di `web/src/pages/`.

Verifikasi: klik Create/Edit → blade geser dari kanan; footer sticky saat scroll; form
kompleks (Catalog) full-width nyaman; ESC saat dirty minta konfirmasi.

## Phase 4 — Kontrol form Fluent + feedback

Tujuan: input native → komponen Fluent, validasi & toast konsisten.

- Ganti `<input>/<select>/checkbox` inline → Fluent `Input`, `Dropdown`/`Combobox`,
  `Switch`, `DatePicker`, `SpinButton`, `Textarea` di halaman CRUD.
- Bungkus `Combobox`/`MultiCombobox` eksisting agar tampil selaras Fluent.
- Toast: petakan `ToastProvider`/`useToast` ke Fluent `Toaster`/`useToastController`
  (atau re-skin) — `aria-live` sopan, auto-dismiss.
- Validasi inline: pesan error di bawah field (via `Field`), fokus ke field invalid
  pertama saat submit gagal; tombol submit loading state.

Files: `web/src/pages/*` (CRUD), `web/src/components/{Combobox,MultiCombobox}.tsx`,
`web/src/components/ui.tsx` (toast).

Verifikasi: semua kontrol Fluent, validasi tampil di field, keyboard & a11y jalan.

## Phase 5 — Realm lain, halaman non-tabel, cleanup, docs & changelog

Tujuan: konsistensi penuh + rapikan.

- Platform shell (`/platform/*`) parity: sudah dapat `AzureNav`/`AzureHeader` di Phase 1;
  pastikan halaman `TenantsPage`, `PlatformBillingSettingsPage`, `VpnServersPage` konsisten.
- Halaman non-tabel: dashboards (`DashboardPage`, `PlatformDashboardPage`), detail
  (`CustomerDetailPage`, `OltDetailPage`, `WorkOrderDetailPage`, `SubscriptionPage`),
  settings (`Tax/PaymentGateway/PlatformBilling/Notification`), wizard
  (`ExpressPsb/Import*/Provisioning`), `LoginPage`/`SignupPage` → styling Fluent (Card,
  spacing 4/8, breadcrumb). Portal (`src/portal/*`) opsional/lowest-priority.
- Cleanup: buang blok `index.css` yang sudah mati (violet accent lama, sidebar dark,
  modal lama), hapus duplikasi dark-theme block.
- **Update `CHANGELOG.md`**: entri fitur redesign Azure/Fluent (ringkas per-phase).
- **Finalisasi `docs/azure-fluent-redesign.md`**: lengkapi screenshot/anatomi final,
  daftar komponen baru, panduan pakai Blade/CommandBar/DataGrid untuk halaman baru.

Files: `web/src/pages/*` (sisanya), `web/src/index.css`, `CHANGELOG.md`,
`docs/azure-fluent-redesign.md`.

Verifikasi: `npm run build` + `npm run lint` bersih; semua realm konsisten Azure;
tak ada sisa styling violet/charcoal; dark mode benar; docs & changelog terupdate.

---

## Verifikasi menyeluruh (end-to-end)
1. `cd web && npm run dev` → telusuri: Dashboard, Users, Customers, Catalog, Inventory,
   Invoices, Incidents, Platform/Tenants. Cek: nav terang collapsible, breadcrumb,
   command bar (`+ Create` kiri, Delete disabled tanpa seleksi), DataGrid (checkbox +
   menu aksi kiri + sort + shimmer + empty CTA), blade geser-kanan (footer sticky, sm/lg/
   full, ESC-dirty-confirm).
2. Toggle light/dark → tema Fluent Azure benar di dua mode (kontras teks ≥4.5:1).
3. `npm run build` sukses; `npm run lint` (oxlint) bersih.
4. Uji a11y ringkas: fokus keyboard di nav/command bar/blade, `aria-label` ikon-only,
   focus ring terlihat.
5. Permission gating: login non-admin → nav & command action tersaring `useCan()`.

## Catatan eksekusi
- Kerjakan di branch `feat/azure-fluent-redesign`; commit per-phase.
- Tiap phase harus tetap `npm run build` hijau sebelum lanjut.
- Jangan hapus `index.css` sekaligus — pensiunkan bertahap seiring migrasi halaman.

# Existing subscriber migration

Branch: `feat/customer-migration-20261016`, based on main `a22041fd`.

Customers now has two entry actions: **PSB baru** for a new installation and **Migrasi pelanggan** for an existing service. Migration supports manual entry, CSV and MikroTik. The grouped resource blade keeps the customer list mounted underneath it.

Migration preserves ACTIVE/SUSPENDED business state, optional service start date and coordinates, negotiated price and the first invoice date. It creates neither a work order nor a RADIUS account. Disabled MikroTik accounts become suspended subscriptions. A source identity or request replay cannot create another customer or overwrite an already recorded service.

Billing is initially unmanaged unless the operator supplies a first invoice date. That date determines the first invoice; subsequent months follow the recurring billing day, using the last day of a shorter month for dates such as 31. Migration does not prorate a historical installation. Deferred billing can be started from the customer's subscription section. A new subscription after termination restores ordinary billing behavior.

## Jadwal penagihan saat migrasi

**Mulai penagihan di aplikasi ini** menentukan tanggal aplikasi mulai menerbitkan tagihan. Pilih jadwal berikutnya yang belum diterbitkan di sistem lama; aplikasi tidak memeriksa riwayat tagihan sistem lama secara otomatis.

Untuk CSV, pilihan awal **Sesuai CSV** memakai jadwal masing-masing pelanggan:

| Kolom | Isi | Contoh |
| --- | --- | --- |
| `first_invoice_on` | Mulai penagihan di aplikasi ini, `YYYY-MM-DD` | `2026-11-05` |
| `next_billing` | Tanggal tagih rutin setiap bulan, 1–31 | `5` |

Pelanggan lain dapat memakai tanggal 20 pada kedua kolom tersebut. Jika `first_invoice_on` kosong, penagihan belum dikelola. Jika hanya `next_billing` kosong, jadwal bulanan mengikuti tanggal mulai penagihan.

Pilihan **Sesuai CSV, dengan tanggal bawaan** menyediakan nilai pengganti untuk setiap kolom yang kosong. Nilai yang sudah diisi pada CSV tetap digunakan. Memilih kembali **Sesuai CSV** menghentikan pengiriman kedua tanggal bawaan, termasuk nilai yang sebelumnya sempat diisi. Pratinjau menampilkan tanggal mulai dan tanggal bulanan yang akan dipakai; aturan ini juga berlaku ketika membuka kembali impor tersimpan.

Contoh: tagihan 5 Oktober sudah diterbitkan di sistem lama, lalu data pelanggan dimigrasikan pada 20 Oktober. Isi `first_invoice_on=2026-11-05` dan `next_billing=5` agar aplikasi ini mulai menagih pada 5 November. Tanggal 29–31 jatuh pada hari terakhir jika bulan berikutnya lebih pendek.

## Implementation and validation history

Legacy credentials are encrypted and never returned in previews, reports or customer exports. The separate **Siapkan RADIUS** action in the customer's network section uses the saved credentials or an explicit replacement. It respects the existing active/isolated state and does not remove local MikroTik accounts or change router authentication settings. Its reviewed effect is preparing the RADIUS account, not completing a router cutover.

CSV staging retains the existing asynchronous promotion flow with independent row transactions, durable request identity, a safe preview and a recovery URL. Partially failed rows do not roll back successful rows. Status/report access respects current tenant/area authority. Exports include migrated customers before RADIUS preparation and retain code, status, price and billing fields without credentials.

The follow-up screenshot `RixusMDXK9xn` showed map-panel commands wrapping into three rows. ODP and the other map panels now keep their primary action and cable action in one row; remaining actions use the overflow menu. Moving an ODP through that menu remains functional.

Validation:
- Production Kotlin and web builds passed; web lint has no errors.
- 50 focused backend tests passed: migration replay/scope, credential encryption, explicit RADIUS, CSV partial failure/recovery, export/report safety, first-invoice/short-month billing and existing PSB behavior.
- 28 focused frontend tests passed, including PSB/migration navigation and command-bar overflow.
- 48 customer-form/resource-layer browser cases passed in Chromium/Firefox at desktop/mobile widths (44 initial passes plus four updated toolbar-assertion rechecks).
- Four real-map browser cases passed for one-row ODP commands and relocation through the menu. Desktop/mobile screenshots are retained; a final visual check found a mobile Site/POP overlap addressed by the follow-up below.
- The general ModularityTests structure check has an existing failure: an isolated checkout of base main `a22041fd` reproduced exactly the same 320 normalized violation groups, with zero added/removed groups. Do not suppress that check or report the entire backend suite as green.

Operator example: create the internet package, then open Customers → Migrasi pelanggan → Input manual. Select Aktif for a subscriber already online, choose their package/price, and choose the first unpaid billing date or Belum kelola tagihan. Leave unknown coordinates empty. Review and migrate. Location can be added from the map; billing and RADIUS preparation are separate actions in customer details.

Private evidence/recovery: `.omo/runtime/customer-migration/` (ignored). No credentials or production exports are committed. Implementation merged in PR #74 as main `77046b7a`; final CI passed. Actions deployment and exact live verification are recorded in the PR release notes.

Map follow-up: the detail panel has an opaque background so map controls cannot show through it. At mobile widths the map toolbar and Site/POP picker hide while a detail panel is open and return with the same site selection after closing. Browser regression also checks every visible command is clickable, not just aligned. The four follow-up cases pass in Chromium and Firefox at 375px/1440px; production web build and lint also pass. Final screenshots show no overlap.

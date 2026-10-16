# Existing subscriber migration

Branch: `feat/customer-migration-20261016`, based on main `a22041fd`.

Customers now has two entry actions: **PSB baru** for a new installation and **Migrasi pelanggan** for an existing service. Migration supports manual entry, CSV and MikroTik. The grouped resource blade keeps the customer list mounted underneath it.

Migration preserves ACTIVE/SUSPENDED business state, optional service start date and coordinates, negotiated price and the first invoice date. It creates neither a work order nor a RADIUS account. Disabled MikroTik accounts become suspended subscriptions. A source identity or request replay cannot create another customer or overwrite an already recorded service.

Billing is initially unmanaged unless the operator supplies a first invoice date. That date determines the first invoice; subsequent months follow the recurring billing day, using the last day of a shorter month for dates such as 31. Migration does not prorate a historical installation. Deferred billing can be started from the customer's subscription section. A new subscription after termination restores ordinary billing behavior.

Legacy credentials are encrypted and never returned in previews, reports or customer exports. The separate **Siapkan RADIUS** action in the customer's network section uses the saved credentials or an explicit replacement. It respects the existing active/isolated state and does not remove local MikroTik accounts or change router authentication settings. Its reviewed effect is preparing the RADIUS account, not completing a router cutover.

CSV staging retains the existing asynchronous promotion flow with independent row transactions, durable request identity, a safe preview and a recovery URL. Partially failed rows do not roll back successful rows. Status/report access respects current tenant/area authority. Exports include migrated customers before RADIUS preparation and retain code, status, price and billing fields without credentials.

The follow-up screenshot `RixusMDXK9xn` showed map-panel commands wrapping into three rows. ODP and the other map panels now keep their primary action and cable action in one row; remaining actions use the overflow menu. Moving an ODP through that menu remains functional.

Validation:
- Production Kotlin and web builds passed; web lint has no errors.
- 48 focused backend tests passed: migration replay/scope, credential encryption, explicit RADIUS, CSV partial failure/recovery, export safety, first-invoice/short-month billing and existing PSB behavior.
- 16 focused frontend tests passed.
- 48 customer-form/resource-layer browser cases passed in Chromium/Firefox at desktop/mobile widths (44 initial passes plus four updated toolbar-assertion rechecks).
- Four real-map browser cases passed for one-row ODP commands and relocation through the menu. Desktop/mobile Firefox screenshots were inspected.
- The general ModularityTests structure check has an existing failure: an isolated checkout of base main `a22041fd` reproduced exactly the same 320 normalized violation groups, with zero added/removed groups. Do not suppress that check or report the entire backend suite as green.

Operator example: create the internet package, then open Customers → Migrasi pelanggan → Input manual. Select Aktif for a subscriber already online, choose their package/price, and choose the first unpaid billing date or Belum kelola tagihan. Leave unknown coordinates empty. Review and migrate. Location can be added from the map; billing and RADIUS preparation are separate actions in customer details.

Private evidence/recovery: `.omo/runtime/customer-migration/` (ignored). No credentials or production exports are committed. Local implementation and validation are complete; PR/CI, main merge, Actions deployment and exact live revision verification remain pending at this checkpoint.

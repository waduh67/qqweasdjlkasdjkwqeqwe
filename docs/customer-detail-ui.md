# Customer detail layout

Customer details use the existing resource blade and section navigation. All thirteen sections share the same content hierarchy: section heading, available commands, compact filters where needed, and properties, a table, or a chart. Network account edits and password resets open a child blade; closing it restores the customer section and leaves the originating list or map mounted.

- Overview groups customer identity, subscription and network information.
- Subscription, network accounts, warehouse assets, ONU monitoring and portal access put commands before labelled properties or records.
- Connection paths, nearby customers, invoices, work orders and activity use single-line table cells. Nearby customers, invoices and activity filter immediately. Invoice payment commands apply to one selected payable invoice.
- Optical metrics and traffic use flat statistics and a chart. Traffic range changes clear the previous range while loading; failed reads have an explicit state.
- ACS separates device properties, WiFi, connected devices, diagnostics, firmware and maintenance. Existing permissions and confirmation dialogs remain in force.
- Filters override the resource form's horizontal label layout so both labels and inputs remain usable at desktop and mobile sizes. Commands wrap visibly on mobile.

Validation: production web build; focused customer component and import regression tests; Chromium and Firefox at 375 and 1440 pixels in light and dark themes. Browser fixtures populate every section, exercise instant filters and nested account edits, check usable filter widths and page overflow, and cover read-only access and failed traffic requests. Tests reject all customer/network writes. Private test screenshots and logs are in `.omo/runtime/customer-detail-azure/` and `web/test-results/`.

The namespace change is a subsequent, separate checkpoint.

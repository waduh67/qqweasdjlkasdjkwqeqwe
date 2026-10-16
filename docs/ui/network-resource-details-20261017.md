# Network and incident details

Network details use the same retained resource layer and icon navigation as customer details. Closing a detail returns to the existing list or map. Editing an OLT, ODC, ODP, joint box or ODF opens a nested layer; closing it restores the parent.

- OLT: overview, PON ports, customer ONUs, OLT ONU inventory, new ONUs, diagnostics, management access and location. Permissions still determine available operations.
- ODC/ODP: overview, splitters, customer ports (ODP), connections, fiber paths and location.
- Joint box/ODF: overview, connections, fiber paths and location.
- Site/POP: overview and location. The map inspection lists its OLTs in a plain table.
- Cable: overview, cores, route and OTDR. Core colors, selection and editor behavior are unchanged.

Sections load when first visited and retain their state on subsequent visits. Switching sections does not discard a core selection or connection draft. Property fields use a shared label/value layout; tables keep their own horizontal scrolling area. On narrow screens a section picker replaces the navigation column.

Incident details have one action bar, overview and activity tabs, and a plain alarm table. Acknowledging or resolving an incident refreshes its status without closing the detail. Customer notifications open a nested message/review flow; sending requires the final “Kirim pemberitahuan” action.

Validation covers deferred section loading, retained drafts, permission and subscription restrictions, SNMP polling, incident read/write failures, nested editing, and the interactive splicing workflow. Browser checks run in Chromium and Firefox at 375px and 1440px, with additional narrow-panel splicing coverage. All writes in UI tests use mocks.

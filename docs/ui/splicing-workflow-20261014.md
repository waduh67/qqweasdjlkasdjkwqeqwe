# Splicing workflow, 2026-10-14

Branch: `fix/splicing-workflow-20261014`, based on main `500b0143`.

The former two identical grids made a cable look connectable to itself and offered splitter outputs while the operator was trying to feed an ODP. The replacement shows a connection list with a command bar, local search, and a separate cable-inventory tab. Creation runs in the existing retained Azure-style resource blade: connection, details, review. Work orders, method, loss and notes are secondary fields; cable attachment management and bulk connections have separate actions. Existing edit, disconnect, work-order association, and attachment protection are preserved. Table content is one-line text.

Connection choices are scoped by purpose: cable to splitter input, splitter output to cable, cable continuation, cascading splitters, ODF rear termination, or front-side patching. The same cable/point cannot appear as a destination. Compatibility checks exclude same-splitter loops, output-to-output, input-to-input, feeding a splitter back into its own supply cable, and invalid ODF/PON combinations. The normal ODP flow starts with cable to splitter input. Cable drawing direction is not treated as proof of optical signal direction; continuation/cascade/output work remains explicit.

An additional frontend bug blocked `connectedElsewhere` cores even though the backend correctly allows connecting the other end in the current closure. Availability now follows local connection occupancy, sheath access and damage status. A remote connection is displayed as information, never claimed to prove an active PON signal.

Bulk 1:1 now matches equal core numbers and lists every proposed pair in review. Occupied/damaged positions are omitted without shifting later numbers. This action is explicitly for two cables; single ODF termination and patching remain available through their dedicated creation purposes.

No schema migration or production topology edits. The existing backend write endpoints, permissions, audit/work-order handling and transaction rules remain authoritative.

## Validation and recovery

Local verification:
- 15 targeted tests passed for the selection matrix, local versus remote occupancy, purpose reset, permissions, bulk numbering, loss validation, save failure, and stale reads.
- Full frontend report: 984 tests passed in 169 files. The local shell was subsequently reported as terminated (143); require a clean full CI result before merging.
- 10 Firefox/Chromium browser scenarios passed, including 375/1440 px layout, retained parent without navigation, exact review/save payload, joint-box continuation, failure/retry, editing, disconnecting and read-only access.
- Production build, lint (existing unrelated warnings only), and browser-spec TypeScript check passed.
- Desktop/mobile Firefox screenshots reviewed; local evidence is in the private runtime directory.

Remaining: CI, merge, Actions deployment and live verification. Final CI/release results belong in the PR.

Evidence under `.omo/runtime/cable-ux-review/` is private and ignored. Keep code and this handoff committed/pushed. Update this document and the PR with final checks and release references. Production deployment must use the existing GitHub Actions workflow; do not manually deploy via SSH.

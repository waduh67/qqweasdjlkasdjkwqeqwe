CI run 36260966532 at `6bfb13374db245e0f4d192c94ade42fdb07cd613` failed its focused gate. Actual authenticated XML contains 326 unique focused cases in 50 suites: 324 passed, two failed, no errors or skips. The second XML directory is an identical copy, not a full run.

Both failures are `WarehouseContractTest`: state expectations at line 153 omit `EXPIRED` (Receipt, Transfer and Count), and error expectations at line 166 omit `DRAFT_EXPIRED(409)`. Exact tested source and raw assertion metadata confirm stale expectations for the intended C7 additions. This is not an observed runtime expiry behavior failure.

Actual protocol XML passed contract 2, SNMP 25 and collector 170 cases, with no errors/failures/skips. The private log records 25/25 fresh executed tasks and the three module Test tasks. Each public receipt matches the authenticated raw report hashes.

All 155 raw archive members and 135 XML files were verified. Full server/fresh/historical gates were skipped. Fourteen nonserver jobs succeeded, but their raw evidence is handled separately. Acceptance correctly failed. No local QA, aggregate CI pass or final F4 approval is claimed.

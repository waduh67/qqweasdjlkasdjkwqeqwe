# F1 bounded C8 source recheck

Validation base `99ad992db31a6922ec1adf1957af81bf747426e6` plus current uncommitted C8 changes. This follow-up covers the latest ownership read, current creation UI and corrected browser helper. No QA or product/test edits were performed. Exact inspected source and safe evidence-record hashes are in `f1-c8-current-source.json`.

**Fresh source findings: none.** No new blocker found in this bounded recheck. This is not final F1 approval and does not close real-browser, full regression or retained-draft expiry requirements.

Compared with the prior 13-source `f1-asset-exception-fix-source.json`, only these files changed:

- `server/src/main/kotlin/com/duluin/ftth/inventory/application/service/InventoryAssetTitleService.kt`: `forCustomer` now uses `customers.lockForException(customerId, assignment, current.fence)` at line 65. The existing ownership-read loop still locks WO/assignment before the late customer lock, and its returned customer fields are authorized against the locked current area. No new requester or evidence permission is imposed on this existing read; customer.onu.view remains its role gate. Title/loss submission and replay corrections from the prior review remain present.
- `server/src/test/kotlin/com/duluin/ftth/customer/CustomerAssetExceptionContextIT.kt`: the late customer-area race now covers both CONTEXT and OWNERSHIP endpoints, with ownership positive coverage also added. The tests wait for the actual row-lock conflict and reject disclosure after the area move. Existing four fresh/replay proposal race cases remain.
- `web/e2e/warehouse/asset-exception-journey.ts`: requester prefix changed from `Pengaju kantor` to `Pengaju`. `helpers.identity` directly uses the lowercased prefix in email generation, so this removes the reported whitespace validation failure. Real UI role/user creation and office-proposal/independent-rejection assertions remain; no fixture bypass, mock or skip was added.

Current creation components/API binding and loss approval.view continuation gates retain the previously reviewed hashes. They still capture the selected assignment/title/evidence and immutable retry command, require a permitted original requester to continue, and link to the source approval workflow. No further source changes in those paths required reassessment.

Available safe evidence records were read and hashed: `local-asset-exception-server-r2.json`, `local-asset-exception-server-r3.json`, `local-asset-exception-web-r2.json`, and the F2 executed-verification MD/JSON. They report R2 **87 tests / 4 suites**, R3 **13 / 2**, and web **18**, all with zero failures/errors/skips. F2 independently records actual report/log verification and current R3 source-map matching. This F1 follow-up did not reread raw runtime reports and does not present that work as its own execution or raw verification.

Keep the broader R2 result and the current R3 result separate: the ownership production read changed between them, and the 73-case ownership suite was not rerun in R3. They are not a combined 100-case current regression. Browser R1 failed during requester setup; the corrected R2 browser run was active at review time. Its result, complete current regression and final F1–F4 evidence remain outstanding.

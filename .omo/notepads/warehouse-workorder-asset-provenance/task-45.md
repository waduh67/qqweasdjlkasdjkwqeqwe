## Task45 working draft after task44 physical verification

Still IN PROGRESS. Do not mark45 done. No browser results yet for these drafts.
Current product drafts add CustomerAreaField to CustomersPage (the old form had no
area picker), WorkOrderSignatureUpload to the evidence panel (old UI could only
read/delete signatures), automatic proof option reload after evidence changes,
accessible photo-file label, and hide evidence mutations once QA approved.
Server signature endpoint is existing PUT multipart file+signerName+optional
correctionReason; do not invent stock or bypass current assignment checks.

New browser numeric-journey.ts uses UI-only tenant/master/customer/role/scope/receipt,
1000m+10ONU,100m+1ONU issue, separate technician ACKs,use82.500m,actual LOAN install,
signed customer handover,return17.500m,intake/inspection,technical proof,independent
QA and917.500m/9ONU stock read. PNG is copied from the existing valid server fixture.
warehouse-empty-tenant.spec.ts executes that flow in both existing real projects.
Existing helpers/catalog/fulfillment gained optional customer setup, extra user roles
and location custodian. Existing issue PREVENTIVE defaults remain.
Drafts are not yet checked: likely selector/permission/state failures must be fixed
against real UI, without route mocks or SQL physical seeds. qa.sh browser builds real
server+web, asserts JSON readiness, then runs desktop1280 and mobile375 sequentially.

Private runner .omo/runtime/numeric-browser-r1.sh waits host lock after the populated
178.6 upgrade, archives upgrade JUnit privately, checks TypeScript/targeted lint and
5 existing web unit files, then runs the new spec. Session75763. Log same basename.
It refuses unless the prior upgrade has4 passing tests and delta178.6. Check actual
status before launching more QA. All wrapper cleanup retains both DB/MinIO volumes.

Remaining45: finish both-project numericjourney; customer-assets.spec.ts actual
sale/swap/removal/inspection/same-asset reuse and repaired sold RMA to original
customer; exceptions.spec.ts count stale,approval denial,legacy cutover,tablet768
andthemes. Extend issue/returns tests for actual Material Saya flows if not covered
by the new main helpers/spec. Save source-matched safe proof; no raw traces/XML/env.
Then46 full server/web/KMP with known175.21 historical projection fixture fix;
47 runbooks/preflight;48 required CI andnegativegateproof;F1–F4 current-artifact audits.
No subagents authorized. Never final at a checkpoint; continue full goal.

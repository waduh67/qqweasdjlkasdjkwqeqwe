## Current checkpoint: completed removal response; task45 still in progress

Follows 7a5a9ac5; locate containing commit with git log -1. Goal ACTIVE.
Tasks1–44 DONE,45 IN PROGRESS,46–48/F1–F4 OPEN. CONTINUE after commit/push.
No subagents authorized. Branch work/warehouse-completion -> origin/feat/warehouse-workorder.

Product fix: removeCustomerAsset now reads {operationId,retired,replacement:null},
validates retired assignment/customer/asset/time against cloned original binding.
Lost-response retry keeps original bytes/key even after caller mutation. Red1/13,
green20tests/2files PASS3.98s; TypeScript+targeted lint+diff check PASS. Safe proof:
.omo/evidence/warehouse-workorder-asset-provenance/task45/customer-removal-web-verification.json.

Real browser customer-assets r5: 4tests,2PASS/2FAIL,385.6s. Loan swap/reset/same-unit
reuse A->B passed desktop+mobile. Sale removal/recovery/vendor repair/reset/RMA ACK
passed both; final authorization409 SOURCE_NOT_VERIFIED. Read-only DB confirms real
source is valid; SKU category is NULL. RmaDeploymentStore derives createsOnu=false,
but V175_125 warehouse_assert_rma_execution compares false IS DISTINCT FROM NULL
for optional category. Need regression and forward-only V178.7 correction (not yet
written/run). ONU browser fixture must explicitly set category ONU through UI and
assert actual ONU episode creation; existing numeric UI proof establishes assignment
but its fixture omitted category. Do not hide generic-category bug by only fixing fixture.

Active runners: legacy-ui-build-r1 session16469 now building V172 historical app
in private detached checkout .omo/runtime/warehouse-legacy-ui-base at abaecd9e;
exceptions-browser-r2 session74347 queued under host fd8 lock, fixes rejection label
Kembalikan untuk perbaikan. customer-assets r5 session25236 finished browser phase;
its stdout was accidentally redirected away; authoritative private JSON report valid.
Exceptions r2 archives r5 report/artifacts on acquiring lock. Never print auth traces.
All runners retain volumes and stop only owned processes. No schema edits made yet.

Uncommitted compiling browser drafts cover asset/loan/exception workflows and extended
issue/returns; latest extended issue/returns NOT RUN. Legacy new files in
web/e2e/warehouse-legacy plus config are only initial old-UI fixture scaffolding;
cutover/restart test and runner NOT YET implemented. Keep these drafts and finish.
Need create old tenant/customer/manual ONU through actual V172 application, then
forward upgrade same isolated schema and approve/finalize in new UI. No SQLstock seeds.

Remaining45: explicit ONU category and current numeric+asset reruns, exceptions stale
recount, issue/returns, actual legacy cutover browser. Then46 full server/web/KMP
regressions including unresolved WorkOrderMaterialUsageITProjectionUpgrade175.21
fixture incompatibility;47 source-matched docs/preflight;48 requiredCI;F1–F4 current
artifact audits. No native/hardware claim. V178.6 immutable in both QA environments;
next178.7. New/default marker remains98b38fbf54518f766065f31955d52574; retained OLD
abc63ab0045a7096566cf763c8f477fc remains available. Env/logs/traces private only.

## Task39 material workbench backend verified — frontend in progress

Added InventoryMaterialWorkbenchApi with current WO plan/use revisions, bounded own
custody choices and immutable named usage history. Custody matches actual accepted
receipt and use/return/handover descendants against current own ISSUED physical balance;
current location/area topology filters run before totals/pages. No cost/raw posting
DTOs, no broad IAM requests, no writes on GET. Context contains latest usage identity,
not another actor's raw source lines. Canonical report-use/correct-use remain unchanged.

5tests/3suites PASS3m22s: new MaterialWorkbenchIT2, existing UsageLifecycle2 and
LifecycleDelta1. Proof task39/material-workbench-verification.json. QA cleanup done.
Current uncommitted web work: materialExecution API, WO Material section with planning,
usage/positive correction form, history and independent close; tsc pending. Remaining39:
meaningful API/UI tests, named obligation references, frozen QA review, rework form,
all required rejection/reassign/cancel/no-material cases, final gate. Task40 own-custody
page/ack/return/scanner/offline follows; then41–48/F1–F4. Long-run goal ACTIVE.
Task38 COMPLETE at5ed6df25; fullweb249/44, build/lint green before39 additions.
No migrations. Never claim whole39 finished from this backend checkpoint.


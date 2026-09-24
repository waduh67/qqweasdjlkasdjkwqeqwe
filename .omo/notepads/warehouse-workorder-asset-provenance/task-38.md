# Task38 implementation notes

## Policy settings prerequisite for task37 browser

2026-09-25: task38 settings policy work began while37 disposition verification runs.
Existing settings route was unavailable, so a real all-UI discrepancy browser cannot
configure independent policy until this component is shipped. Tasks37 and38 remain open.
New policy/details resolves saved policy users/role/location names. Bounded
policy/approvers accepts unique repeated locationId, USER or ROLE, query/page/size;
requires approval.manage and current scope for every location; only active independent
approvers with all selected warehouse/area permissions are returned. Source actor and
its active delegates are excluded. IAM public authority directory now includes role
names (internal only). No ordinary IAM directory permission required for this picker.
Two tests pending plus old PolicyIT andPolicyITRoleAuthority regressions. Private
wrapper .omo/runtime/policy-workbench-server.sh; log policy-workbench-server.log.
Next UI: typed saved policy, edit rules/tiers/user+role choices, location list, actual
revision capture, diff confirmation, stale reload, independent reviewer guidance.
Use existing WarehouseScopePanel/catalog access for grants. Delegation UI, overview,
replenishment and reports still belong to the remaining task38 work.
No migrations. Do not claim task38 or37 complete yet.

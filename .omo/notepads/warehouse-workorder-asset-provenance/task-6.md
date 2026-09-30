# Task 6 notes

CurrentAuthorityPersistence reads JDBC state after a shared tenant epoch lock, avoiding stale JWT permissions and cached JPA entities. Exclusive mutation fences are transaction synchronization handles; only PostgreSQL rows are authoritative. Repeated increments within the same transaction collapse to one update and rollback together.

Hooks added: UserService create/update/assignAccess/setEnabled/delete; RoleService create/update/delete; AreaService create/update/delete; AdminProvisioner changed role/new user paths; TwoFactorService writes. TenantScopedAuthenticator locks before loading user to prevent a TOTP save from restoring stale authority fields.

WarehousePreparedCommand is internal, not an HTTP DTO. Actor/session comes from CurrentAuthorityApi. Operation fields supplied by task5's PostingOperation are replaced, not trusted. Canonical JSON rejects duplicate keys, trailing values and floating point quantities; property order is sorted recursively. Original receipt is read back from immutable operation storage after posting. Replay authorizes current actor/scopes first and never invokes posting again.

V174.6 adds inventory_command_identity and inventory_outbox_delivery. Outbox payload is unchanged. Delivery uses DB-clock leases, random owner/token, eight attempts, capped delay and explicit terminal states. Inbox primitive only accepts a persisted outbox event tied to APPLIED posting and an originating cutover epoch; trusted local callback and receipt share transaction. It is not a public HTTP callback API.

Open integration: no outbox dispatcher/fulfillment consumer wiring has been added; existing legacy consumers do not yet enter WarehouseCommandService. Global permission catalog sync and InventoryApprovalService delegation remain unfenced. Do not mark task6 complete or start dependent waves on this checkpoint.

## Completion supersedes checkpoint

All listed integration gaps are closed in b6c7535cac3fe9edd33e40fcc68becba49c72752. Dispatcher claims/finishes in short transactions and calls the typed port outside locks. Actual fulfillment consumer writes append-only provenance observation with inventory inbox receipt in one transaction; it does not enable settlement or repeat stock consumption. Legacy user commands now require current authority and use immutable authorized replay; anonymous legacy fulfillment consumption is explicitly reconciled rather than impersonating a technician.

Global permission changes fence all tenant epochs (including suspended), administrative mutations reauthorize against current DB permissions, repeated hooks keep one epoch while invalidating intermediate handles, delegation grants are unavailable fail-closed until durable policy setup. WO/roster revisions and document references are locked and checked on execute/replay. Barrier tests cover IAM/area/scope/catalog/reassignment/document races and bounded lock conflicts.

Final proof:39 exact concurrency tests twice;453 combined regression tests;0 failures/skips. XML/HTML archived before clean. Separate Spring/JVM probe MANUAL_PASS, lost ACK restart3 events=3 delivered=3 inbox=3 observations without duplicate posting. Clean no-cache bootJar and production bytecode inspected; temporary source/classes removed. Task-owned services/containers/network stopped, temporary schemas0, volumes retained, clean pushed worktree. DoneClaim.json and changed-files.txt contain the complete handoff.

## AV6 verifier correction receipt

The prior completion above was challenged by AV6-01/02/03 and legacy lock order. These were independently reproduced before production edits and corrected in six normally pushed commits ending9fffd37284a9c939afcf12d187b5ea8d2de73b04. WorkOrder mutation/preflight helpers now consume returned fenced CurrentAuthority, including old/new and null-area restrictions. Refresh/logout pass only token hash into the fenced worker; persisted token row locks and atomic active-token consumption prevent revocation and duplicate-rotation races. Dispatcher catches and redacts failures per tenant, continuing later healthy tenants. Legacy locks all planned references before operation key, then rechecks immutable identity/hash/scope/references under key.

Final correction evidence:61 exact tests twice and475 combined tests,0failures/skips; archived XML/HTML. AV6_MANUAL_PASS proves unchanged WO status, zero refresh replacements after revocation, later-tenant delivery/inbox/effect=1 and operation key free while document-blocked replay becomes stale. Clean no-cache build and javap/artifact inspection passed; migrations unchanged; temporary schemas0 and owned resources stopped with volumes retained. Latest detailed receipt: task-6/av6-corrections/DoneClaim.json. Independent verifier rerun remains the orchestrator's approval step, not a claim made by this worker.

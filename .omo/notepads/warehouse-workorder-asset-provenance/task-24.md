# Task24 Execution Notes

## 2026-09-17 Start Checkpoint

- Verified clean task-owned worktree on work/warehouse-resume-20260916, HEAD and live remote feat/warehouse-workorder both49e47396993c46b03ff48d6a8258af17db9068d6. Protected checkout untouched.
- Full501-line plan, current handoff, task23 confirmation and recent learnings/issues read. Task23 is independently complete; task24 remains unchecked. No GPON/vendor or previous closed review work is reopened.
- Current substep: trace exact PSB/CSV, customer ONU, legacy material, Subscriber360 and portal contracts, then execute baseline characterization before production changes and genuine missing-behavior reds.
- Planned scope: inventory-owned bounded exact-unit V2 query, additive customer provenance and Subscriber360 V2 data, separate portal-safe serialization, strict existing/new source gates and cohesive compatibility suites. No migration expected; source ceiling175.112 awaits live verification.
- No tests/build/live HTTP have run for task24 yet. Prior task23 counts are not task24 evidence. Historical schema catalog and NetworkEndToEnd raw-registration fixture issues remain task46 unless directly affected.
- Resource registration before use: isolated runner-owned PostgreSQL/MinIO containers and network will be removed with test-environment.sh down; existing volumes/images/data retained. qa.sh stop owns only registered API PIDs. Any additional JVM/API/temp resources must be registered in local task24 evidence before launch and cleaned before return. No production/device/network calls.
- Delivery: scope-only coherent commits using commit-scoped authorized identity, immediate normal explicit-refspec push/live SHA verification. No environment, evidence, build output or Boulder staging; no task24 checkbox change or task25 work.

## First Green Implementation Checkpoint, Incomplete

- Baseline39 ran with3 failures: new PSB fixture omitted actor area (corrected request), existing OnboardingExpressPsbIT area scenario lacks actor area grant (preexisting), and real customer import failed because MigrationFulfillmentInboxJpaEntity inherited a nonexistent updated_at column. Inbox mapping now matches existing schema without migration or weakened RLS.
- Genuine repeat red6/4: missing V2 bean, missing legacy/current provenance and repeated inbox import failure. Original logs/XML retained. Initial material-test compilation failure is separately retained, not behavioral red.
- Current selected green26/0 failures/0 errors/0 skips: compatibility8, Subscriber3603, portal12, Modularity3. XML archived before next run. Exact current suite repeat and wider regressions/clean artifact/live HTTP still pending, not a DoneClaim.
- Source confirms CustomerApiService new serial delegates to OnuService's existing typed409 gate; same-customer operational reuse retained. No source gate implementation or task23 blob reopened. Separate existing portal DTOs are pinned by exact serialization fields.
- New V2 query reads immutable measured facts plus real deployment assignment/document/posting lineage in a bounded single SQL snapshot. Deployment never emitted legacy material facts; do not fabricate them. Legacy adapter was unregistered and nullable Subscriber360 silently hid its facet; now bound and required, preserving Int facts in materialHistory while exposing explicit materialHistoryV2 page.
- Mixed scenario proves legacy37 unchanged, deployed1EA and82500MM/82.500M in V2, separate customer/tenant, stable pages and high-offset totals. New OnuView fields project persisted customer-owned provenance and nullable asset/assignment/retirement references, with no fake legacy origin.
- Live Flyway ceiling175.112 confirmed by isolated test boot; no SQL edits or new migration. Current substep: checkpoint coherent implementation/tests, then expand adversarial/CSV and rawAPI coverage, targeted regressions, clean bootJar, real isolated HTTP/DB, cleanup, final pending-review claim.

## Expanded CSV And Regression Checkpoint, Live Proof Pending

- Exact task24 command now13/0/0/0. Final selected regression91/0/0/0 includes those13, live seed1, CustomerWarehouseProvenance31 (existing installed-onu/stale/revocation/race guards), PSB4, Subscriber3604, portal12, import15, CSV parser4, commit2, legacy warehouse2 and Modularity3. All XML archived before reruns; no unexpected skips.
- Multipart tests exposed existing batch.updated_at and staging/error timestamp mapping mismatch. Three owner entities now use a cohesive tenant/id-only JPA base with explicit batch.createdAt. Outbox/credential mappings and SQL unchanged.
- Actual background CSV promotion then failed because CustomerService.create demanded an interactive principal despite the scheduler installing TenantContext. Direct row-import red confirms the missing principal error; customer creation now uses required server tenant context, preserving HTTP permissions/RLS and existing nullable system audit actor. No source gate or arbitrary legacy flag added.
- Original CSV85/2 and21/1 failures, direct principal3/2 red, and constructor compile failure are retained and not counted PASS. Existing PSB auto-BRAS fixture now grants the actor the area it requests, via actual IAM API; authorization was not weakened.
- Query-failure propagation, denied facets, invalid V2 pages, raw CustomerApi serial denial, same-customer legacy lookup, foreign/readonly/manual-link denial and malformed CSV failure/replay now have direct tests.
- Source checkpoint01991143 precedes this correction batch. Current step: immediately checkpoint corrections/tests, clean build, launch bounded isolated17880 HTTP proof against real workflow-seeded fixture, archive source/artifact identity, clean owned resources. Live seed manifest is registered mode0600 local-only and must be deleted. No task24 completion claim yet.

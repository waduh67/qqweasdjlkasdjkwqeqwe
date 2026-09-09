---
slug: warehouse-workorder-asset-provenance
status: plan-complete-reviewed
intent: clear
review_required: true
plan_path: .omo/plans/warehouse-workorder-asset-provenance.md
plan_sha256: 8174717a5aef816669843309f5d32b38f14295e1a9ab6e893f66a531abb6561b
review_round_id: warehouse-r5-20260906-84c9da71
round_status: approved
pending-action: hand off the unchanged reviewed plan for separately user-started worker execution; no further owner interview or planning review required unless scope or plan bytes change
review:
  momus:
    status: approved
    workspace_root: /home/fajar/ftth/qqweasdjlkasdjkwqeqwe
    runtime_home: null
    target: .omo/plans/warehouse-workorder-asset-provenance.md
    round_id: warehouse-r5-20260906-84c9da71
    plan_sha256: 8174717a5aef816669843309f5d32b38f14295e1a9ab6e893f66a531abb6561b
    launch_id: warehouse-r5-momus-320bc7
    session: ses_f86870addffewYh75K5GNFHrbJ
    result: OKAY; descriptor-relative no-follow intake SHA256 and112918bytes matched, full binding echo and final revalidation confirmed
  independent:
    status: approved
    workspace_root: /home/fajar/ftth/qqweasdjlkasdjkwqeqwe
    runtime_home: null
    target: .omo/plans/warehouse-workorder-asset-provenance.md
    round_id: warehouse-r5-20260906-84c9da71
    plan_sha256: 8174717a5aef816669843309f5d32b38f14295e1a9ab6e893f66a531abb6561b
    launch_id: warehouse-r5-independent-51ceab
    session: ses_f86870740ffe1fmRzqjSc6ZZIy
    result: APPROVE; exact digest-verified artifact reviewed, complete scope and verification dependencies, matching literal bindings echoed
approach: one durable warehouse authority integrated with existing WO and customer installation history, real actionable UI and nonzero real-backend acceptance
---

# Draft: warehouse-workorder-asset-provenance

## Current handoff / compaction checkpoint

- Authoritative resume artifact: THIS draft; final plan content is `.omo/plans/warehouse-workorder-asset-provenance.md` (48 implementation tasks, 8 waves, 4 final gates).
- Policies and permission to write/review the plan are already approved. Do NOT repeat the business interview or ask for approval again: preserve existing data; warehouse origin for all new customer devices; LOAN/SALE with LOAN default; no BYOD.
- Implementation has NOT started in this planning session. No product, Git branch, database, or remote-server mutations were performed. Prior chat claims about finished warehouse implementation do not apply to this new plan.
- Review state is now `plan-complete-reviewed` / round5 `approved`. Native Momus ses_f86870addffewYh75K5GNFHrbJ OKAY and independent Oracle ses_f86870740ffe1fmRzqjSc6ZZIy APPROVE both completed secure intake and echoed the exact current round/digest bindings. Earlier inconclusive rounds below are historical and superseded.
- Current immutable candidate SHA-256: `8174717a5aef816669843309f5d32b38f14295e1a9ab6e893f66a531abb6561b`, 112918 bytes, 498 lines, verified via descriptor-safe read. Changing plan bytes invalidates candidate review receipts.
- Terminal is available through read-only helpers; parent has no direct shell. Round5 native Momus successfully executed the required secure intake. No terminal/permission installation or permission weakening occurred.
- Next action: separately user-started `/start-work warehouse-workorder-asset-provenance` in task-owned worktree (or --make-pr); no more policy interview. --ship implies reviewed merge and requires conscious execution authorization because main auto-deploys. Do not implement in Prometheus/planner mode.
- Requested future feature branch recorded in earlier draft: `feat/warehouse-workorder`; branch creation/pull are NOT verified here. Worker must inspect actual Git/dirty state and use a task-owned worktree; preserve `.omo` and user changes.
- No pending/running background tasks remain from this planner. Compaction does not require keeping a background worker alive.

## Components (topology ledger)
<!-- Lock the SHAPE before depth. One row per top-level component that can succeed or fail independently. -->
<!-- id | outcome (one line) | status: active|deferred | evidence path -->
| id | outcome | status | evidence |
| W1 | masters, UoM, lots/reels, serialized assets, source receipts | active | prior draft follow-up findings |
| W2 | durable stock posting, operations, approvals and reconciliation | active | InventoryMovementLedgerService; InventoryApprovalService |
| W3 | existing WO demand, custody, use and material settlement | active | WorkOrderService; FulfillmentContracts |
| W4 | customer asset provenance and installation/reuse history | active | OnuService; CustomerApiService; monitoring provisioning paths |
| W5 | separate logistics navigation and end-to-end web actions | active | WarehouseOperationsPage; Layout; CustomerDetailPage |
| W6 | preservation migration, real tests and release gates | active | V144-V147; playwright.config; deploy workflow |

## Open assumptions (announced defaults)
<!-- Record any default you adopt instead of asking, so the user can veto it at the gate. -->
<!-- assumption | adopted default | rationale | reversible? -->
- One durable ledger; reservations do not move goods; issue transfers custody; physical use posts once; QA does not debit stock twice.
- Exact integer millimetres internally for length; metre decimal strings externally; devices are integer each.
- Inventory tracks custody, location, condition and legal ownership independently.
- Responsive web is the delivered field UI. Existing KMP modules gain aligned shared contracts/actions/tests, not a claim that a new Android/iOS binary has shipped.

## Findings (cited - path:lines)

Detailed evidence and five read-only research receipts remain in `.omo/drafts/field-operations-warehouse-orders.md:49-142`; previous completed-plan receipts are not this plan's approval.
- InventoryApiService.kt:66 returns empty allocations; InventoryMovementLedgerService.kt:12-29 uses memory; DurableInventoryFulfillmentService.kt:28-47 bypasses authoritative projection updates.
- WorkOrderService.kt:169-175 omits material settlement and applies subscription effects to unrelated jobs.
- OnuService.kt:46-127 has serial-only intake/topology lifecycle disconnected from warehouse; monitoring manual and automatic provision also call CustomerApi.provisionOnu.
- WarehouseOperationsPage lacks receipt/issue/master mutations; CI server test job is commented out.
- Metis gap analysis completed: session `ses_f88b12efcffepI69PlcJDa2h1z`. Incorporated physical-use-before-QA, sale handover/RMA, exact lengths/remnants, durable outcomes, module direction, active assignment history, and real blank-tenant QA.
- Terminal capability verified by read-only helper `ses_f88acc35effecefXdInzhe5wpu`: functions.bash; node /usr/bin/node v22.23.2. Parent still has no direct shell.
- Template provenance: helper ran official exported buildDraft/buildPlanSkeleton functions from scaffold-plan.mjs with clear/reviewRequired and returned exact stdout; parent wrote that generated template via apply_patch. No hand-designed alternative headers. The script's filesystem-writing CLI was NOT invoked because companion roles must remain read-only. Record this transport deviation honestly.

## Decisions (with rationale)

- User approval: `lanjutt`, reaffirmed `lanjut bg`, after the combined policy confirmation. Authorizes plan writing/review only.
- Preserve existing data; no reset even though user says not yet production. Reconcile legacy, do not manufacture receipts.
- ALL new customer device assignments need warehouse provenance; no BYOD intake.
- LOAN and SALE supported, LOAN default. Sale intent is distinct from completed title transfer; RMA does not automatically reacquire title.
- TDD + PostgreSQL HTTP + non-mocked inventory browser acceptance approved with the brief.

## Scope IN

Complete WMS physical operations and reporting, WO materials and non-material jobs, customer device lifecycle, separate menu, existing shared mobile contracts, preservation/migration and CI proof.

## Scope OUT (Must NOT have)

No general ERP/purchasing/AP/refunds/tax engine, second WO/ledger, BYOD, destructive legacy reset, automatic provisioning bypass, fake PASS for empty/skipped tests, production changes from this planner.

## Open questions

None for the owner. Implementation mechanics are decided in the plan. Runtime limitations must be surfaced as BLOCKED in execution, not owner interview or successful QA.

## Approval gate
status: approved
approval_scope: write one final plan and perform dual high-accuracy review
approval_text: lanjutt / lanjut bg
next: finish plan, bind its content hash, launch one Momus and one independent Oracle, incorporate findings, verify exact final artifact

## Review rounds

User explicitly renewed high-accuracy review with `eh high akurasi review dulu lah`; review_required remains true and approved business scope unchanged. Round5 initialized after fresh secure helper ses_f8688f8a5ffeuZxuBNvxFQjHw2 verified unchanged hash8174717a5aef816669843309f5d32b38f14295e1a9ab6e893f66a531abb6561b/112918bytes/48+4rows. This fresh paired review is newly user-requested; previous capabilities/receipts are not treated as approval. If native Momus still lacks secure-intake tooling, report INCONCLUSIVE immediately instead of ordinary-Read fallback or endless retries. Only plan artifacts may be edited.

### Current final receipt — round5 APPROVED

- round_identity: warehouse-r5-20260906-84c9da71
- workspace_root: /home/fajar/ftth/qqweasdjlkasdjkwqeqwe
- runtime_home: null
- target: .omo/plans/warehouse-workorder-asset-provenance.md
- artifact_identity: 8174717a5aef816669843309f5d32b38f14295e1a9ab6e893f66a531abb6561b
- momus: launch warehouse-r5-momus-320bc7; session ses_f86870addffewYh75K5GNFHrbJ; OKAY; secure intake and literal binding matched.
- independent: launch warehouse-r5-independent-51ceab; session ses_f86870740ffe1fmRzqjSc6ZZIy; APPROVE; secure intake and literal binding matched.
- Parent recorded launch receipts before accepting terminal verdicts. Final live canonical/no-follow/same-descriptor hash validation from helper ses_f8688f8a5ffeuZxuBNvxFQjHw2 matched112918bytes,48implementation+4finalrows,officialheaders and no placeholders; NO DRIFT.
- Plan file was NOT edited after this approved digest. Only this resume draft was updated. Plan-level high-accuracy review is COMPLETE; implementation and deployment are NOT performed or certified.
- Historical rejected/inconclusive rounds below are retained for audit only and must not be read as the current blocker.

Round warehouse-r1-20260906-9eacb872 initialized after completed plan: 48 implementation tasks, 4 final-verifier tasks, official headings, no unresolved placeholders, R13 existing-reference correction verified. Secure descriptor-chain hash read receipt: helper ses_f88acc35effecefXdInzhe5wpu, sha256 eb49a2a06d873586c7047583006f2e50eba33a5f0daaf16ece3b0128e189d0bb. Both review lanes are read-only and will receive that exact artifact. Synchronous tool launch receipts become available on tool return; record in-flight receipt before accepting each terminal result. No implementation started.

Round1 results received from exact tool sessions above. Momus reported source-verified dependency and cutover blockers; all findings will be incorporated, but missing terminal binding echo is recorded as inconclusive rather than accepted approval. Oracle echoed secure intake and returned seven concrete changes. No implementation or approval handoff is allowed until both fresh reviewers unconditionally approve the revised same-hash plan.

Round1 history: Momus ses_f8886ce00ffef9dDjbx9LLT4B2 (changes requested; binding echo incomplete), independent ses_f8886cb44ffeQYsXe3GqPahGON (changes requested, full binding matched). Both were against eb49a2a06d873586c7047583006f2e50eba33a5f0daaf16ece3b0128e189d0bb.

Round2 fixes: stockIdentityId includes cable segments, atomic split/reservation conservation and continuous-cut tests; IAM current-authority epoch/fences and replay actor checks; inventory label snapshots and explicit legacy409 eliminate inverse module calls; delayed telemetry attributed by episode/time and ACS snapshots reset across assignments; cutover infrastructure moved before consumers with narrow VALIDATING reconciliation and exclusive legacy writer/outbox fence; exact isolated qa.sh runner overrides hardcoded test YAML, stages browser dependencies and includes materials+macOS compile; legacy serialized count and V2 bulk units separated, currency-separated cost numerator/denominator; positive customer-owned RMA return. Moved inspection/scopes into M02, material plan into M02, M01 cutover+IAM epoch. Full structure/existing-path check: 490 lines,48tasks,4final,99 existing refs PASS, secure hash2db83d97d78074afec674d69b24a862224a830f1a6ffefafdcb2d1791f470e06. New reviewers must inspect this digest, not old receipts.

Round2 receipts: Momus ses_f88675c50ffeEEv0yISRhYn1lV OKAY; independent ses_f886754ddffenVca3Te5bRAFpb CHANGES_REQUESTED. Both secure digest/binding echoes matched. No final handoff; any subsequent plan amendment invalidates both round2 approvals for the new digest.

Round3 amendments: inventory-owned InventoryWorkOrderValidationPort implemented by workorder adapter (workorder repos only; no command-service circular dependency), invoked in local transaction for ALL direct/customer/orchestrated authorization consumers before inventory locks. Direct-endpoint original-JWT revoke/reassign/cancel tests required. Packaged migration boot separated from interactive per-tenant admission: legacy nullable/candidate fields and VERIFIED partial indexes; unconditional inventory_identity_claim plus candidate groups reserve ambiguous serials against theft by new stock; only migration owner creates legacy staged rows; API reconciliation promotes under tenant fence; mixed tenant-stage full-boot/restart QA. Hash8174717a5aef816669843309f5d32b38f14295e1a9ab6e893f66a531abb6561b,112918bytes,498lines,48+4rows,99existingrefs checked by secure helper. Fresh pair required.

Round3 receipts: Momus ses_f884f99c8ffezFzd7OYjQMzhKo INCONCLUSIVE (initial ordinary Read; no verified digest); independent ses_f884f972bffezCyQGMgftG4eia APPROVE with full binding. No plan content change needed. Round marked inconclusive, cannot combine this Oracle approval with an older Momus receipt. Retry a fresh paired round against same verified bytes with exact descriptor-read command supplied; do not claim dual review completed yet.

Round4 initialized with unchanged digest8174717a5aef816669843309f5d32b38f14295e1a9ab6e893f66a531abb6561b (112918bytes), live-revalidated by helper. Fresh nativeMomus+Oracle pair, exact safe-read code supplied to prevent repeating ordinary-Read intake error. No code or policy changes.

Round4 receipts: native Momus ses_f8846aafaffeXuEdFBiXc55AW5 returned content OKAY but expressly did NOT execute secure no-follow/SHA256 intake; independent Oracle ses_f8846a39bffegFZRtjpyPcASvr APPROVE after secure intake. Preserve both opinions but the required aggregate remains INCONCLUSIVE, not approved. Two consecutive Momus integrity-intake failures exhaust this session's capability-retry attempts. Do not loop more reviewers, substitute old Momus approval, claim tool capability provisioned for Momus, or tell the user the full high-accuracy gate passed. Plan content is complete and has no outstanding substantive reviewer-requested changes; only mandatory Momus artifact binding blocks handoff. No implementation/product/remote/Git mutations were performed. Reopen in a planning session where native Momus has the required read-only shell, validate unchanged bytes and launch a fresh paired round.

Final saved-artifact validation by helper ses_f88acc35effecefXdInzhe5wpu: canonical/no-follow/regular-file checks PASS; live SHA2568174717a5aef816669843309f5d32b38f14295e1a9ab6e893f66a531abb6561b matches112918bytes/498lines;48sequentialimplementation and4finalrows atcolumnzero, no placeholders. This does NOT replace Momus's missing required intake. Inform user of complete plan and remaining review tooling blocker accurately.
<!-- When exploration is exhausted and unknowns are answered, set status: awaiting-approval. -->
<!-- That durable record is the loop guard: on a later turn read it and resume at the gate instead of re-running exploration. -->

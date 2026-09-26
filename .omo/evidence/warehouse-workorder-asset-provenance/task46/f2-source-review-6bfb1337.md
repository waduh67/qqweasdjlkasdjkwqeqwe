# F2 source and executed-evidence addendum

**No additional blocker within the reviewed scope; final F2 remains pending.**

Reviewed HEAD `6bfb13374db245e0f4d192c94ade42fdb07cd613` with product unchanged from `9d540107fc2fd47a3b02312371c9e65df75acb83`. Independently verified the 9d→6b delta contains only CI, docs and evidence. At final capture the root has staged evidence updates; no product change was present. This reviewer changed only private notes under warehouse-final-f2-review and invoked no Java, Gradle, Docker, npm, browser or database QA.

The companion JSON binds the later changed server Kotlin files (69), web product files (33), three new migrations, directly rechecked unchanged boundaries, and selected executable fixtures/harnesses. Diff review does not mean every complete unchanged file was reread. The earlier preliminary preserves the broad feature inventory reconciliation: 1,516 of the prior 1,646 hashes unchanged, 130 changed, no missing source. Current-source closure of F2-01 and F2-02 was directly rechecked; no old source verdict was simply promoted.

## Source conclusions

- **Posting and exact units:** production searches still locate movement, leg and projection inserts only in canonical PostingDocuments, PostingStock and PostingProjection. WarehousePostingService requires matching cutover and a surrounding transaction, positive checked quantities, per-SKU/lot/unit conservation and physical-identity conservation. CONSUME/DEPLOY requires technician-held ISSUED source legs and explicit destination semantics. StockQuantity uses checked add/subtract/multiply; the web quantity codec uses BigInt with the signed-64-bit mutation limit and exact millimetres. C7 adds no second stock authority.
- **Current authorization and replay:** retained legacy queries use current fenced permissions and canonical warehouse/area/site visibility. Transfer/count edits require current source and new-target access and actor ownership; original historical command responses are returned only after current authorization. Transfer history filters each retained route, preventing a revised draft from disclosing its former inaccessible locations. COUNT edits preserve blind quantity projection and revalidate assigned active counters.
- **Maker-checker:** WarehousePolicyEvaluationService derives excluded requester/custodian/counter identities from source, rejects missing independent candidates and unknown/incompatible cost bases, and computes value as exact rational BigInteger arithmetic. WarehouseApprovalAuthority rechecks active directory access, direct/delegated identities, used decision actors, delegator exclusions and live grants. Platform privilege does not bypass those exclusions. Draft expiry guards decision and posting boundaries and recovery retains a durable original conflict.
- **Same physical asset and customer privacy:** deployment consumption validates current WO/actor/customer/assignment revisions and creates a new assignment/episode while retaining the physical asset ID. CustomerAssetInstallationStore appends installation identity; it does not rename the old customer episode. CustomerObservationService attributes by half-open episode intervals and historical path, while its store advances only the current unretired episode revision. Portal customer identity comes from the session and maps to a minimal DTO. The later office exception paths lock/recheck customer area after assignment/asset locking, including replay; the context contains named WO/signature metadata without storage references or digest. Source fixtures assert fresh/replay and read denial after a deterministic customer-row wait.
- **Draft clocks and lifecycle:** V178.12 was read in full and still hashes to `add301336feaf41c740f5d6026206500aed07c6bae7c20f168d7aa4ae11adca3`. It takes the legacy relation barrier before baseline time, captures only database-time creation/accepted saves, pins policy, keeps protected side tables and original responses, rejects missing activity, and prevents unmaterialized expired writes. C7 specialized readers overlay expiry without changing sealed approval source snapshots. The worker and pending approval termination are atomic. Submitted plans, dispatched transfers, started counts and posted controls retain their active workflow. No applied pre-existing migration was modified in the later delta.
- **UI and fixtures:** runtime decoders preserve old original-response compatibility while requiring expiry metadata for terminal current responses. Current screens suppress terminal actions and retain usable history/new-proposal paths. Receipt reload exits an expired editor. Count paging retains the entire entry set. Reviewed real-clock fixtures cover accepted renewal, app DML tampering, source-lock wait past deadline, current-scope revocation, worker/decision competition, specialized loss/title/return/replacement/adjustment families, and admitted positive controls. Upgrade fixtures start the pinned older JVM, require normal child closure before migration, compare original response strings, and preserve retained source facts. The legacy baseline relation barrier models a writer lock; it does not claim an in-flight old HTTP write.

No new material defect was established. The prior non-blocking observation about broad visible-WO lock coverage remains an existing source-review observation, not a demonstrated deadlock. No new debt is classified as a release blocker.

## Fresh independent evidence authentication

The new local authentication JSON parses actual XML testcase children, declared counts and report hashes, and checks source captures and runner hashes. All missing-input counts are zero. Changed and added inputs below are relative to current source and keep older results bounded.

| Run | Actual result | Captured inputs | Changed | Added within captured trees |
| --- | --- | ---: | ---: | ---: |
| draft-expiry-backfill-r2 | 12/13 passed | 2625 | 1 | 0 |
| draft-expiry-tenant-r1 | 7/7 passed | 2625 | 0 | 0 |
| draft-expiry-web-full-r1 | 607 passed / 117 files | 523 | 0 | 0 |
| draft-expiry-browser-r2 | 2 passed | 2437 | 0 | 0 |
| draft-expiry-receipt-r1 | 98/98 passed | 2607 | 19 | 15 |
| draft-expiry-families-r2 | 37/40 passed | 2611 | 8 | 11 |
| draft-expiry-material-r1 | 7/7 passed | 2611 | 7 | 11 |
| draft-expiry-specialized-r3 | 18/18 passed | 2620 | 4 | 2 |
| draft-expiry-upgrade-r2 | 7/7 passed | 2618 | 7 | 7 |

These are separate executions. The 40-case family run retains three failures, and backfill retains its sole tenant-fixture failure. Later passing rows do not change either failed process into a pass. The complete web run is an actual unfiltered 607-assertion result with all 523 current web/src inputs matching; E2E typecheck, lint and build appear in the fail-fast runner/log, whose recorded exit is 0. Browser R2 has exactly two successful single attempts, desktop/mobile, with no unexpected, flaky or skipped tests and all 2,437 inputs matching. Runner/cleanup codes for those older local wrappers remain parent-recorded; report/source/hash verification is independent.

Fresh offline protocol authentication independently verifies **197 tests / 35 suites**, all successful: contract 2, SNMP 25 including all eight GponDocumentedProfileTest cases, collector 170. The 130 captured source/build inputs match before/after and current checkout. All three Test tasks execute with `--rerun-tasks --no-build-cache`; the log has 25 actionable/25 executed and BUILD SUCCESSFUL, and the retained exit file is 0. The exact documentation-backed test assertions were read; documentation distinguishes Huawei mirrored MIB semantics, unverified ZTE assumptions and unavailable FiberHome defaults. This closes the current offline proof gap only. Physical GPON certification remains explicitly deferred.

The current F3 main matrix and asset-wire authentication files were read as evidence from the independent F3 owner. They describe 24 actual browser cases split across an interrupted 18-case attempt and a six-case continuation. This reviewer has not independently parsed those raw traces and does not issue F3 acceptance; extra/restart/legacy completion is still controlled by that reviewer.

## Required before final F2

1. Authenticate the complete current-source unfiltered server process and nonzero ModularityTests from current CI, with successful process completion and source identity. Release reviewer confirms the latest fully authenticated complete run is still historical 99ad992d; current 6b CI is pending. Never sum focused passes into an unfiltered result.
2. Reconcile current shared KMP, native compile, image, packaged upgrade and browser gates through current CI/F4; compilation is not native runtime/release certification.
3. Receive the independent F3 completed runtime/extra/restart/legacy assessment and reconcile its exact source/artifact hashes.
4. Re-review and revalidate any further affected source or workflow changes before writing the final f2-quality-security.md verdict.

Only safe counts, hashes and bounded source conclusions are retained here. Raw logs, XML/system-out, credentials and browser trace payloads remain private. No deployment, publication or main merge is authorized by this note.

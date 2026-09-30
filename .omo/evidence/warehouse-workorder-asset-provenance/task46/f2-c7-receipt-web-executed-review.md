# F2 bounded C7 receipt/web evidence review

**Verified at captured sources; C7 and final F2/F3 remain open.** Reviewer ran no QA, changed no product and did not touch V178.12. Exact hashes and later source changes are in the accompanying JSON.

- Receipt R1: independently parsed **98 passed / 4 suites**, with no failures/errors/skips: **5 real-clock receipt + 28 form-state + 62 scope/catalog + 3 module** cases. XML testcase/declared counts, report hashes, runner hash and the **2,607-input manifest** match the proof. The log records a successful build; the root proof records runner and cleanup exit `0`. V178.12 matches its applied immutable SHA-256 `add301336feaf41c740f5d6026206500aed07c6bae7c20f168d7aa4ae11adca3`.
- Web R2: independently parsed **88 passed assertions / 12 files**; aggregate counters agree and nothing is failed/pending/todo. Report, runner, 523-input inventory and the proof's selected source hashes match. The log shows E2E typecheck and successful production build, with **101 located lint warnings**. The sequential fail-fast runner and root-recorded exit `0` support the four successful stages. This is mocked-HTTP UI/codec evidence.
- Current scope differs: **13 server product files changed and 4 test/fixture files were added** after receipt R1; **10 web files changed** after web R2. None are missing. These passes do not validate those later changes or represent full current regression. E2E source files are outside the web/src manifest.

The five receipt cases meaningfully cover scheduler-disabled expiry denial, unchanged original replay, reads that do not renew, a pinned policy, one activity per accepted save, denial of application policy/activity tampering, zero physical effects and a save blocked past its actual DB deadline. The new source-reviewed cases add **4 transfer/count, 3 material-plan and 2 approval** cases, including latest-plan retention, already-started/submitted positive controls and competing workers/late decisions. Their execution belongs to the root's current run and is not inferred here.

Highest-value remaining runtime checks:

1. Complete the current family/approval tests and specialized web rerun with source hashes.
2. Exercise a real **V178.11→V178.12 upgrade**: retained DRAFT documents/plans with old/future/non-finite timestamps, live pending approvals and posted/started/submitted controls; verify bounded legacy clocks, pinned policy, unchanged history/no effects and old-writer handoff. A fresh successful migration does not prove backfill.
3. Use real short-clock specialized proposals across supplier-replacement receipt, loss/scrap, reversal, asset loss, title correction, return title, adjustment and opening balance. Check terminal detail/list state, late fresh-command/approval rejection, exact original replay, immutable snapshots and zero stock/title/assignment/recovery effects, alongside admitted positive controls. Do not waive uncovered families.
4. Check current permission/warehouse/area revocation on expired reads and replay, plus both sides of decisive save/post/expiry races where distinct lock paths remain uncovered.
5. Run a real browser against an opened draft that expires before save, then a specialized proposal: stable conflict, terminal reload, unavailable actions, preserved history and a usable next step.

Raw runtime reports, logs and traces remain private. No final acceptance is granted by this note.

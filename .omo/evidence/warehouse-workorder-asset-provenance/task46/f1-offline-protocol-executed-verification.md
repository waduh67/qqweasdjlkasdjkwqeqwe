# Task23 current offline execution evidence closure

Only the current offline-evidence gap is closed. This is not final F1, aggregate CI acceptance or hardware certification.

At clean commit `6bfb13374db245e0f4d192c94ade42fdb07cd613`, independently read-only parsing verified:

- Contract: 2 tests / 1 suite.
- SNMP: 25 tests / 3 suites, including all 8 `GponDocumentedProfileTest` cases.
- Collector: 170 tests / 31 suites.
- Total: 197 tests / 35 suites; zero failures, errors or skips. Actual testcase children agree with suite totals.

All 35 raw report hashes match `counts.json` and the corresponding per-module CI verifier receipts, which identify the same tested commit and PASSED status. All 130 input hashes agree before/after, with the current checkout and exact Git archive bytes. They include every one of the 90 tracked protocol-module source files and 40 other captured source/build inputs, including simulator/src, build-logic/src, Gradle and module/root build files. `docs/gpon-profile-evidence.md` is not included in these 130 runtime inputs; documentation was separately reviewed.

The retained runner uses `--rerun-tasks --no-build-cache`. The log identifies all three module Test tasks as executed, ends BUILD SUCCESSFUL and records 25 actionable tasks / 25 executed. The retained exit receipt is 0. Aggregate `classes`/`testClasses` lifecycle tasks marked UP-TO-DATE do not indicate cached Test execution.

The required server workflow now runs all three modules and checks each module's nonzero JUnit reports. Its aggregate CI acceptance remains pending and is not inferred from this local execution. The older a028c6a9 SNMP25/collector22 claims remain unverified; this current proof closes the gap without reconstructing those historical reports.

The sibling `f1-offline-protocol-executed-verification.json` records safe input/report/runner/log hashes and counts. Raw XML/system-out, traces and fixture records were not copied. This reviewer executed no QA or product edits. Physical GPON capture/model/firmware certification remains owner-deferred, and no hardware-validation claim is made.

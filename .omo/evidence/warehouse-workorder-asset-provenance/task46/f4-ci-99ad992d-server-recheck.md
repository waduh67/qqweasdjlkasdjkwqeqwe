Completed server artifact verification for CI run 36238965245 at `99ad992db31a6922ec1adf1957af81bf747426e6`. All fifteen jobs completed successfully. This review validates this committed snapshot only; uncommitted C8/C7 changes, release approval and final F4 remain outside this result. No local build, browser, database or other QA was executed.

Actual testcase-level results:

- Full modern server: **3,810 tests in 616 suites**, zero failures/errors/skips.
- Focused compatibility: **325 tests in 50 suites**, zero failures/errors/skips; the class set exactly matches that commit's workflow. The later full-suite reports differ from and follow the focused reports, so cached focused XML was not counted as a new full run.
- Historical application upgrades: **seven tests**, with every executed class matching the pinned runner and the complete current SQL chain.
- Historical projection regression: **one test**, preserving its explicit 175.21 to 175.22 scope.

The fresh-database `WarehouseMigrationITBoot` cases passed. Their actual XML records 366 migrations into an empty owned database and a separate retained-data upgrade of 169 baseline plus 197 new migrations, both reaching 178.11. The hashed test source requires unchanged retained identities/units/links and a repeated migration with zero work. These two cases are already included in the modern count above.

Authenticated artifact 10907322969 matched the GitHub ZIP digest and size. All six ZIP members, all 690 decrypted members and all 674 XML reports were checked privately. Safe receipts match their actual report hashes/counts; the full log contains six successful builds and both historical gate completion markers, with no failed build. No decrypted log, XML or fixture content is included here.

All **378** recorded historical source inputs match exact 99ad Git bytes. Each historical pin retains its original migration bytes, all 366 current migrations are present, and the separate projection migration/regression hashes match. `f4-99ad992d-source-input-hashes.json` binds 3,282 source files; the adjacent JSON records archive/report/source hashes and the detailed assertions. The already verified same-commit nonserver proof binds the actual tested image/JAR/configuration to browser and legacy receipts. Together those artifacts cover 22 browser, six legacy-browser, 584 web and 44 shared tests, two native compile tasks and eight preflight probes. Native runtime/release certification is not claimed.

The prior cc47 catalog failure is absent: `WarehouseSchemaITSelectiveTiming`, COUNT/TRANSFER draft and upgrade suites, and legacy scope/reservation suites pass in both focused and modern reports. This completes the requested source-bound 99ad artifact review. Final F4 remains pending for the intended completed feature and release candidate.

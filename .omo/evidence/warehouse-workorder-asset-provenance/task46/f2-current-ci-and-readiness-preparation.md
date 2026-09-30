# F2 current CI authentication and readiness preparation

**The code quality/security review passes at application source 1306b65c. No blocking finding remains within the reviewed scope. This is not yet an activation approval or final host acceptance.**

CI run `36265279847` is now independently authenticated as completed successfully at `1306b65c34167b2d48f4817ce72990fed3b85aa3`, with all 16 jobs successful. F2 read the prepared F4 downloader and verifier source, downloaded the exact current server artifact, authenticated/decrypted it, compared archive members with extracted bytes, parsed the actual XML and checked the relevant source bindings. No QA, Java, Gradle, browser, Docker, SSH or product mutation was performed. The existing F4 author could not resume, so execution of these prepared artifact checks is explicitly attributed to F2.

The canonical safe server proof is `warehouse-f4-review/.omo/evidence/warehouse-workorder-asset-provenance/task46/f4-ci-1306b65c-server-recheck.json`, SHA256 `4374abfa2aea7a4ad3ae1308055d9991f96e277c440b129d1bf228488a0d1dbc`.

| Actual execution | Result |
| --- | --- |
| Unfiltered modern server | 3,850 tests / 628 suites, no failures/errors/skips |
| Separate focused compatibility | 326 tests / 50 suites, no failures/errors/skips |
| Historical application upgrades | 7 tests passed |
| Historical projection upgrade | 1 test passed |
| Offline contract / SNMP / collector | 2 / 25 / 170 tests passed; 25 of 25 actionable tasks freshly executed |
| ModularityTests in modern execution | 3 tests passed |

These counts are separate executions, not an aggregate formed from focused runs. Every focused class also has a distinct later full-suite report; workflow source deletes the copied focused XML before calling the unfiltered `qa.sh server` command. The archive contains 721 authenticated XML files and 761 raw members. Successful fresh-database migration counts are 367, then historical 169 plus the remaining 198. All 367 migration bytes and current historical helper/source inputs match the tested Git revision.

The pinned-old draft upgrade descriptor matches all 2,595 captured inputs from application `1d14f4b9`. The archive authenticates two transfer handoffs, two count handoffs and one expiry handoff, including successful old-process exit before current migration, descriptor/seed hashes and execution of the current upgrade suites. This closes the formerly pending current full-server/Modularity/historical/protocol evidence requirement. Earlier failed and cancelled runs retain their original outcomes.

Current validation HEAD `f50bdd762db1d09c0dec0aa4b09fc967d73f3665` changes only evidence after deployment commit `db09c3c2`. Relative to the 3,693-input 1306 manifest, only the five previously reviewed deployment/docs paths differ; the three new shared-proxy files remain separately identified. Application/build/test/workflow inputs and all 367 migrations are unchanged. The earlier F2 source reviews of posting authority, quantities, authorization/privacy, replay, maker-checker, asset reuse and C7 concurrency/recovery therefore remain source-bound. F3's completed browser review and current F4 nonserver/image authentication retain their exact proof identities.

F2 read and hash-reconciled the later independent F4 network and backup assessments. Network R3 passes its isolated gateway/login/relative OpenAPI, direct signed S3 roundtrip, CWMP denial/private-NBI and RADIUS configuration checks; R1/R2 failures remain preserved. The actual host foundation receipt records pinned FreeRADIUS running as `freeradius -f`, closing the previously noted debug logging item for this host. Network evidence does not claim TLS, forwarded scheme, real RADIUS authentication/accounting, Drive preservation or application activation. The backup drill proves a non-replacing restore of the platform-only fixture, matching migrations, owner/runtime separation, RLS and clock privileges; it is not populated production disaster-recovery evidence. These raw runtime results remain attributed to F4, not reparsed by F2.

The activation helper source was directly read and matches SHA256 `87f7e0f55c82bad649b3d5ed615b7e5b18227992bfe664ecd649095d5c0c3ed7`. Before mutation it requires the exact 13 host-file hashes, six source-bound proof files, completed current CI and four readiness records bound to the same identities. It preserves unrelated edge configuration, limits activation to the managed FTTH block, and attempts validated reload of the previous maintenance block on caught failure. The existing 14-case synthetic recovery evidence remains bounded to its mocked scope; actual host success/recovery is not inferred.

The private VPN preparation/install/patch helpers were also read. Their current hashes match the reviewed design: separate preparation and install decisions, repeated host conflict checks, exact single-block correction to the existing empty-cleanup defect, retained original/patched installer bytes, certificate checks, private credentials and post-install callback/firewall checks. The generic 1306 application template remains unchanged. Actual rendered-installer approval and VPN runtime evidence remain separate and are not granted by this preparation.

At capture, `release-gate-candidate.json` still contained five proof hashes, without the new server proof or independent readiness records. Root will add this server proof and request a distinct F2 `READY_FOR_ACTIVATION` receipt bound to that exact six-proof candidate. This note does not satisfy or bypass the helper's gate. Subsequent public HTTPS/login, existing Drive behavior, actual effective settings, initial backups, runtime identities and final restart/readiness evidence remain required for final composed-host acceptance. Physical GPON/device certification stays explicitly deferred.

The companion JSON contains current evidence hashes, helper hashes and exact authentication provenance. Raw logs, XML/system-out, archive payloads, environments, keys and credentials remain private.

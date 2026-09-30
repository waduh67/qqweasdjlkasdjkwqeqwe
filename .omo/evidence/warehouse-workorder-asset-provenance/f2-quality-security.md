# F2 quality and security — final verdict

**APPROVE. No blocking quality or security finding remains within the reviewed warehouse implementation and authorized composed Azure-host scope.**

This verdict binds application/image source `1306b65c34167b2d48f4817ce72990fed3b85aa3`, deployment configuration `db09c3c2b37ad29b07464b05cd02895265f99a0d`, completed CI run `36265279847`, the approved 13-file host configuration, and the final production artifacts recorded in `f2-quality-security.json`. At source capture, metadata HEAD was `13849768623c7cb6ad652d6c4c0e720b741b66d4`; the sole working correction was the reviewed operations-document timezone sentence. Its corrected SHA256 is `78a823a806847567d09f25b742b2e85f7a1a56c8499d20337bca14056869ff7b`.

Application/build/test/workflow inputs and all 367 migrations remain identical to tested application source. The eight reviewed deployment files remain identical to db09. Later changes comprise evidence and the operations runbook. The canonical server proof SHA256 is `4374abfa2aea7a4ad3ae1308055d9991f96e277c440b129d1bf228488a0d1dbc`.

## Quality and security findings

The retained source review establishes one canonical stock-posting authority, checked exact quantities and unit conservation, current tenant/warehouse/area/assignment/customer authorization, immutable operation replay, independent maker-checker decisions and same-physical-asset reuse across separate installation episodes. Prior legacy visibility/reservation findings were directly rechecked as closed. Customer observations remain attributed to their installation interval; portal reads derive customer identity from the session. The latest contract expectations and two UI display corrections preserve the reviewed behavior.

C7 retains database-owned draft lifetime, immutable original responses, current access checks before replay, expiry admission guards, atomic worker/approval termination and recovery of durable outcomes. The source review includes new-volume/backfill barriers, current-scope revocation, pinned-old JVM handoffs, startup rejection for missing clock capability and protected-history deletion behavior. Policy-only empty tenants may be deleted; tenants with protected history must use Suspend. Applied migration bytes were not edited.

The actual deployment separates the migration/database owner from warehouse_app. Runtime has no owner membership, superuser/BYPASSRLS privileges or schema CREATE; protected clock tables retain restricted access. The shared gateway joins the existing edge network while backend/database/storage/NBI remain unpublished on the FTTH network. Production validation stays enabled, demo seeding is disabled and secrets remain private. The actual host uses non-debug FreeRADIUS operation. The optional mail-health override affects the unconfigured environment SMTP indicator only.

No new material defect was established. The low-severity operations-document finding is corrected: actual backup schedules are application 02:30 and RADIUS 03:00 **WIB (UTC+7)**, with 14-day retention. The initial recovery archive remains immutable, including its original document version; the corrected runbook is separately bound above.

## Authenticated current regression

| Execution | Actual successful result |
| --- | --- |
| Unfiltered modern server | 3,850 tests / 628 suites |
| Separate focused compatibility | 326 tests / 50 suites |
| Historical application upgrades | 7 tests |
| Historical projection upgrade | 1 test |
| ModularityTests in the full execution | 3 tests |
| Offline contract / SNMP / collector | 2 / 25 / 170 tests; all 25 actionable tasks freshly executed |
| Current CI browser / legacy browser | 24 / 6 cases |
| Current full web | 607 assertions / 117 files |
| Current shared / native compilation | 44 tests / 2 compile tasks |

All 16 current CI jobs completed successfully. F2 independently downloaded, authenticated and reparsed the final server archive using the source-reviewed prepared F4 tools: 721 XML reports and 761 raw archive members. Declared counts agree with actual testcase children; accepted reports contain no failures, errors or skips. Focused results are retained separately and have distinct earlier hashes/timestamps than their full-suite counterparts. They were not summed into an unfiltered PASS.

Fresh migration evidence executes 367 migrations on an empty database and 169 plus 198 on the retained-data path. All 2,595 captured old-application inputs match pinned `1d14f4b9`; two transfer, two count and one expiry handoff prove successful old-process closure before current migration. The current image/configuration/migration payloads and all 19 ordered server/web layers remain authenticated through original CI archives and actual destination re-exports.

Independent F3 also completed its separate 36-case workflow/edge/restart/legacy review and eight expected preflight probes. Its source-bound evidence and failed/interrupted attempts remain retained. Later public-production browser smoke is an additional bounded check: R1 remains FAIL because of a private exact-label selector; R2 passes its corrected selectors; R3 additionally waits for settled drawer transitions, exercises open/close and checks mobile overflow. F2 inspected both R3 screenshots and matched source hashes, receipts, exit files and empty recorded error/business-mutation lists.

## Final production evidence

F2 authenticated the actual activation archive, all six release-proof hashes and all four readiness-record bindings. Public login/HTML bodies were read privately. The managed FTTH edge block changed from maintenance to the gateway; all unrelated Caddy bytes remained identical. Drive response bodies before and after activation match, with successful public HTTPS checks. The activation logs contain successful initial application and RADIUS backups.

The separately approved rendered VPN installer matches its recorded original, exact host-only patch, helper and installed configuration hashes. Its actual installation archive contains 14 authenticated files and exit 0. The saved forwards/routes callback bodies match the expected empty-peer markers; the executed helper reports fresh conflict checks, active VPN services, private key/token permissions and idempotent tunnel firewall setup.

The persistence archive contains 14 authenticated files and exit 0. In 40.758 seconds, it restarts the same backend container using the same image and records a changed process start time. F2 parsed the actual before/after login and hub responses: the administrator, sole hub, PKI and zero-peer state are unchanged. The runtime schema remains 367 successful migrations through 178.12. Existing VPN sync/readiness, both public sites and refreshed application/RADIUS backups pass the executed assertions; raw logs contain both backup completions.

The final host snapshot binds the exact approved files and records 13 running containers with restart policies, active/enabled Docker and VPN units, three tunnel firewall rules, the intended route, private service bindings and preserved unrelated proxy configuration. F2 independently hashed the off-host recovery archive: all 66 regular files are safe archive members; all 13 release host files, all 15 VPN files and all 12 backup files match the final snapshot. Its five database dumps have custom-format headers and private modes. The actual release proof/approval files also match their approved hashes.

The external OpenVPN log independently shows the installed hub CA chain, `CN=server` verification, a TLS 1.3 control channel and the expected `AUTH_FAILED` response for invalid credentials. Exit 0 alone was not used as success. The client used no tunnel interface or route mutations and created no production peer.

Detailed final evidence is in `f2-production-final-authentication.json` and `f2-activation-public-browser-authentication.json`. Some role/schema/systemctl/iptables results are supported by reviewed executed assertions and retained safe receipts because separate raw outputs were not saved. That limitation is preserved; no missing raw evidence is invented.

## Nonblocking debt and operating limits

- **Generic VPN installer:** its pre-existing empty-match cleanup defect remains in the application template. This host uses the independently reviewed exact correction. Forward-fix the generic template before using it for another new host; retain the original/patch/install records.
- **Email:** SMTP is unconfigured. Recovery mail and email alerts require real platform SMTP settings and an actual delivery check. Application health UP does not prove email delivery.
- **Backups:** scheduled local app/RADIUS backups and an authenticated initial off-host recovery copy exist. Scheduled off-host automation and whole-host/populated-production restoration are not established. This recovery copy excludes MinIO object content, the Mongo volume, Docker image payloads and unrelated Drive data.
- **Onboarding and certification:** real subscriber RADIUS accounting, successful production-peer traffic, client forwarding and relevant Azure UDP/NSG paths remain to be verified when used. No Azure-account NSG change is claimed. The operations guide identifies the required ports and source restrictions.

Physical GPON/OLT/MikroTik certification is explicitly deferred. No whole-VPS reboot was performed on the shared host; backend restart, enabled services and firewall idempotence are the actual persistence evidence. Native compilation does not certify runtime/distribution. The isolated restore drill covers the platform-only fixture rather than a populated production disaster-recovery exercise.

## Review provenance and acceptance boundary

F2 performed source analysis and retained-artifact authentication, including the fresh current server archive, production activation/browser artifacts, VPN/persistence archives, final snapshot, recovery copy and TLS log. It ran no application QA, SSH, Docker or deployment commands and made no product edits. Earlier independent F4 nonserver/image/network/backup and rendered-PKI work is retained with exact attribution. After the original F4 reviewer became unavailable, the independent workflow reviewer covered later F3/F4 duties; four distinct live final reviewer agents are not claimed.

All historical failures, cancellations and interrupted attempts remain unchanged. This final verdict supersedes the earlier pending F2 conclusions for the exact reviewed composition; their original records remain available. User acceptance remains a separate final step. Main-branch merge and registry publication are outside this approval.

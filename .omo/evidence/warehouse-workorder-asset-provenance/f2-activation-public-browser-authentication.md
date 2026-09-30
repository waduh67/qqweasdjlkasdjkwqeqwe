# F2 activation and public browser authentication

**Production activation and the retained public-browser R3 evidence pass this bounded review. Final F2 host acceptance still awaits the VPN installation and persistence results. No substantive blocker was found in the persistence-checker source.**

F2 authenticated the actual activation archive against the transfer digest, compared all ten regular archive members with extracted bytes and matched the safe receipt and retained exit 0. Its release gate hashes match all six proof files and all four independent readiness receipts. Each readiness receipt binds the same code `1306b65c`, deployment `db09c3c2`, helper and 13 host-file hashes. The executed gate/helper identities match those reviewed before activation.

The activation receipt records successful production readiness, non-owner warehouse_app, 367 successful migrations through 178.12, restricted clock ACLs, private login, exact private service bindings and image checks, then public HTTPS/login and preserved Drive availability. F2 parsed the retained public login body without exposing its tokens, checked the returned HTML and verified the before/after Drive bodies are identical. F2 independently compared both complete edge configurations: only the managed FTTH block changed from maintenance to the gateway; the existing `{$DOMAIN}` site and its app:8080 upstream remained byte-identical. Raw logs contain one successful app backup and one successful RADIUS backup, with no backup-failure markers.

These are real activation artifacts. Role/ACL/schema/inspect assertions remain evidenced by the executed helper and its safe receipt because separate raw SQL/inspect outputs were not retained. Public HTTPS checks use normal certificate validation; this does not establish an independently captured TLS handshake or forwarded-scheme behavior.

The three public-browser attempts retain their source, result and exit identities:

| Attempt | Result | Evidence boundary |
| --- | --- | --- |
| R1 | FAIL / exit 1 | Exact accessible-label selector timed out; original failure remains preserved. |
| R2 | PASS / exit 0 | Private selectors accept the required-field asterisk; login, dashboard, reload/session refresh, mobile render and logout pass. |
| R3 | PASS / exit 0 | Adds settled drawer-open/close and no-horizontal-overflow assertions at 390 px. |

R1→R2 changes only the two private accessible-label selectors. R3 adds actual CSS-transition settling and drawer interaction, without changing application source or images. Both successful receipts match their source hashes and contain empty saved JavaScript-error, authenticated-API-error and business-mutation lists. The runner uses public HTTPS with certificate-error bypass disabled. F2 personally inspected R3's desktop and mobile screenshots: the empty platform dashboard is readable and has no material visual blocker. Only authentication/session operations were observed; no business mutation was recorded.

Browser authentication here consists of the completed source-bound receipts, exit files, saved lists and actual screenshots. There is no claim to have reparsed an independent browser trace or HTTP report that was not retained. R3 does not relabel R1 as successful or replace the prior separate 36-case F3 workflow review.

`verify-production-persistence.py`, SHA256 `6a56202f1b64adccd6af231797ebfa030a75a74c9a3220b0509cbb63b11e939d`, was read in full. It requires successful activation and VPN installation receipts, then restarts only the authorized FTTH backend. It checks the same container/image with a new process start time, public login and exact user/hub persistence, current runtime schema, existing VPN sync/service/listener state, both public sites and refreshed backups. It introduces no proxy/configuration replacement or database restore. Existing VPN sync and backup commands remain the authorized operational mutations. Actual execution has not yet been authenticated by this review.

The next required evidence is the actual VPN installation proof and the persistence check with final image/schema, backup, VPN and Drive outcomes. Physical router/GPON certification and host reboot remain outside the claimed test scope. No QA, SSH, Docker execution or product edits were performed by F2; raw logs, credentials, login responses and private fixture data remain private.

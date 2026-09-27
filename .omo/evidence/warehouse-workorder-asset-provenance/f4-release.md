F4 release review — APPROVE

Reviewed 2026-09-27T09:26:15.755393+00:00. No blocking finding remains for the composed release and actual
authorized host deployment authenticated here. Owner acceptance of the overall
warehouse work remains pending. This report supersedes the pending decision in
the earlier F4 preliminary/preparation reports for the identities below; their
historical observations, failures and scope limits remain unchanged.

Application, build, tests, workflows and 367 migrations are bound to
`1306b65c34167b2d48f4817ce72990fed3b85aa3`. Deployment configuration is bound to
`db09c3c2b37ad29b07464b05cd02895265f99a0d`. The later operations document is a separate
documentation change, SHA256 `78a823a806847567d09f25b742b2e85f7a1a56c8499d20337bca14056869ff7b`.
Current source checkpoint at review is `13849768623c7cb6ad652d6c4c0e720b741b66d4`; later evidence and
runbook commits do not imply another application build or a new CI execution.

The reviewer `/root/workflow_final_review` covers both F3 and F4 independently of
the implementing root. The original separate F4 reviewer could not resume and
a replacement could not be spawned. This explicit handoff retains prior F4
source/image/runtime reviews, fresh server evidence authenticated by F2 and
reparsed by F1, and this reviewer's independent archive/XML/source checks. Four
distinct live reviewers are not claimed.

CI run `36265279847` completed with all 16 jobs successful. I rehashed the
current server archive and 761 extracted files and parsed all 721 XML reports:
3850 modern tests/628 suites, 326 focused/50, seven historical/7, one projection/1
and 197 protocol/35, with zero failures, errors or skips. Three modularity cases
are included in modern results; focused and full counts are not unique totals.
The six proof files and the readiness receipts were bound to the exact release
gate. Existing nonserver/image proofs and the separate F4 destination re-export
authentication preserve their original scope. Final host server/web IDs match
those authenticated destination images; all 13 runtime image IDs were checked
against their bound identities. No registry publication or main merge is claimed.

The activation archive's ten regular files match its transfer digest. The reviewed
helper and gate bind all 13 release files, six proofs and four role receipts. The
actual receipt reports warehouse_app without superuser/BYPASSRLS/owner membership
or schema-create rights, 367 successful migrations through 178.12 and restricted
clock-table ACLs. Real public login response identifies the expected platform
admin. Saved FTTH HTML, actual Caddy before/after bytes, identical Drive HTML and
the helper's successful HTTP assertions support the deployment result. Only the
managed FTTH proxy block changed. The subsequent public-browser R3 source, exit,
error lists and two personally inspected screenshots support HTTPS login, refresh,
mobile drawer/render and logout. See [the bounded activation review](task46/f4-activation-browser-runtime-review.json).

The real rendered VPN installer was reconstructed byte for byte from the committed
template/renderer and recorded hub/PKI. Its saved patch changes exactly one legacy
DNAT cleanup block; certificates, key pair, callback domain, TCP1194 and
10.8.0.0/24 match. The installation approval binds the original, patched installer,
preparation receipt and helper hashes. The 14-file installation archive authenticates
its actual success, fresh conflict checks, callback marker bodies and logs. The
recovery archive's installed files exactly match the approved installer and helper.
OpenVPN, synchronization and firewall services are enabled/active; three scoped
tunnel rules are present and the reviewed helper's repetition did not grow them.
Key and node-token files are mode 0600. See [the rendered-installer review](task46/f4-rendered-vpn-install-review.json).

The external OpenVPN log establishes a real TCP1194 connection, CA chain, server
purpose/name verification and TLS 1.3, followed by AUTH_FAILED. OpenVPN 2.7.7 exits
zero after this soft authentication failure; exit 0 alone is not a pass. The client
uses dev-null/no-route/no-ifconfig settings. No production peer was created and no
successful subscriber or physical MikroTik test is claimed.

The 14-file persistence archive authenticates the same backend container and image
with a changed start timestamp. Actual before/after login and hub response bodies
preserve the platform identity, one PKI-ready hub and zero peers. The schema stays
at 178.12 with 367 migrations. VPN synchronization, its listener and FTTH/Drive HTTPS
remain successful according to the reviewed helper and final snapshot. New app
and RADIUS backups were produced after restart.

Final snapshot `2026-09-27T09:18:57.754389+00:00` records 13 running containers, healthy where a
healthcheck exists, all with unless-stopped restart policy. Four recorded systemd
services are enabled and active. The final 13 release files, 15 VPN files and 12
backup files match the private recovery archive's actual bytes. Five custom-format
PostgreSQL dumps have PGDMP signatures; the post-VPN app and RADIUS dumps are
3149170 and 21306 bytes. Recovery archive SHA256 is
`ed7cae5b055190d985b39b84fa4f374e36cfa76fce9db1279d731ae534dbe957` (66 regular members).
It remains private; no credentials, PKI, SQL dumps or raw installer were copied
into Git by this review.

F2 identified a runbook timezone error. I independently verified actual
BACKUP_TZ=Asia/Jakarta from the private environment backup. The repository and
active host runbooks now agree on 02:30/03:00 WIB (UTC+7), 14-day retention, with
actual sync receipt `06d98ff413fd6caad6749906b0d3d4d4f2e82d594054e36be45aa4e40ee28147`.
The immutable recovery archive retains the earlier UTC-description document; use
the corrected active/repository runbook. No runtime setting changed for this fix.

The following limits remain part of approval:

- Owner acceptance is pending. Physical GPON/OLT/MikroTik certification, native
  runtime/distribution, successful customer VPN/RADIUS traffic and unused Azure
  UDP/forwarding reachability are not established. SMTP remains unconfigured.
- A full host reboot was not performed. Boot enablement, firewall idempotence and
  the same-image backend restart are the tested boundaries.
- The generic application VPN installer still has its known cleanup defect. The
  reviewed host-only patch is installed here; it does not fix installers for other
  hosts. Preserve the original, patch and exact approval.
- Some SQL, Docker-inspect and HTTP-status outputs were retained as helper
  assertions/receipts rather than separate raw command captures. Raw bodies, logs,
  installed files and the final snapshot were independently checked where present.
- The earlier isolated fixture restore passed. These final production dumps and
  the entire recovery package were not restored. The package omits image payloads,
  MinIO contents, Mongo volumes and unrelated Drive data; it is not a complete
  offsite backup service or whole-host disaster-recovery certification.
- Existing failure/cancellation records remain preserved. No application code,
  migration, plan checkbox or product data was changed by this final reviewer;
  no QA, SSH, Docker, installer or browser execution was repeated for this review.

Safe evidence: [final authentication](task46/f3-f4-final-host-authentication.json)
SHA256 `e09930b0ec09da14345614900dc723c4641b70c188792a2fea61a9bb9ace4a01` and [F3 workflow approval](f3-workflow.md).

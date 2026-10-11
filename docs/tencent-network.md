# Tencent network services

The FTTH application remains on the mini PC. Its network node is
`ubuntu@43.157.224.131` (Ubuntu 24.04, 2 vCPU, 2 GB RAM), shared with existing
applications. Only the `ftth-network` Compose project, dedicated ACS nginx site,
`wg-quick@ftth-link`, and FTTH OpenVPN services belong to this deployment.

| Service | Address |
|---|---|
| Device VPN | `vpn.ftth.karuhundeveloper.com:1194`, OpenVPN TCP |
| VPN device subnet | `10.208.67.0/24` |
| ACS | `https://acs.ftth.karuhundeveloper.com/` |
| ACS compatibility / firmware | TCP 7547 / 7567 |
| Private VPS address | `10.254.67.1` |
| Private mini PC address | `10.254.67.2` |
| Private RADIUS database | `10.254.67.1:5432/radius` |
| Private ACS API | `http://10.254.67.1:7557` |

The independent WireGuard link uses UDP 51820 and persistent keepalive from the
mini PC. It does not use FTTH VPN account authentication. PostgreSQL and the ACS
API bind only to the private VPS address; MongoDB has no published port. RADIUS
uses host networking so the host firewall and original NAS source addresses
remain effective. Device RADIUS traffic is allowed through the OpenVPN interface;
direct public RADIUS needs a separate source restriction for the intended NAS.

The VPS already serves other websites on nginx 80/443. ACS adds a named virtual
host, uses webroot certificate validation, and reloads nginx after validation.
It must not replace existing sites, firewall defaults, or Docker projects.
The memory limits and single GenieACS worker per process are for this small,
shared host; size it again before adding a large device fleet.

HTTPS CWMP uses nginx's RFC 7239 `Forwarded` header, trusted only from the local
Docker gateway, so GenieACS correctly challenges with Basic over TLS. Plain
CWMP on 7547 reaches GenieACS directly and uses Digest. Do not put the plain
endpoint behind an HTTP proxy: GenieACS ties its Digest nonce to the TCP socket,
and replacing that connection makes correct ONT credentials fail. Keep the
trusted proxy addresses in sync if changing the network's Docker subnet.

## Configuration and recovery

- Node files: `/opt/ftth-network`; `.env` is mode 0600 and never committed.
- WireGuard: `/etc/wireguard/ftth-link.conf` and `ftth-link.key` on both hosts.
- OpenVPN: `/etc/openvpn/server`, `openvpn-server@server`, `ftth-vpn-sync.timer`.
- Original RADIUS dump: `/opt/ftth-network/backups/legacy-radius.dump`.
- Application, ACS connection settings and VPN PKI remain in the main FTTH database.
- Do not delete the original mini PC volumes during migration or rollback.

GitHub Actions `deploy-network` builds GenieACS, checks isolated PostgreSQL,
MongoDB, RADIUS and an authenticated CWMP session, then transfers the tested
image to Tencent. Production activation validates HTTPS and direct CWMP again.
`TENCENT_HOST`, `TENCENT_USER`, `TENCENT_SSH_KEY`, and `TENCENT_KNOWN_HOSTS`
are repository secrets; application deployment continues to use its existing
mini PC secrets. No node credentials are included in the release artifact.
Pure network changes do not rebuild the application.

At boot, `ftth-network.service` starts this Compose project after WireGuard and
Docker, so private port bindings have an address. An OpenVPN systemd drop-in
restores this hub's forwarding/NAT rules when OpenVPN starts. Do not reboot the
shared VPS just to apply a network release.

Release records and previous configuration are stored under
`/opt/ftth-network/releases/<commit>`; `current/activation.json` identifies the
active release. Configuration is restored automatically if activation fails.
The initial core connection backup is
`/opt/ftth/backups/network-config-20261011T112507Z` on the mini PC. It includes the
original environment and resolved Compose file. `configure-core.py` handles
resolved Compose files whose entrypoint otherwise overrides environment values.

`ftth-network-backup.timer` runs daily at 03:15 UTC (10:15 WIB), retaining local
archives for 14 days and copying them over WireGuard to
`/opt/ftth/backups/tencent` on the mini PC (30 days). Its SSH key can only upload
archives through `receive-backup.sh`. Backups contain databases, service
configuration and VPN keys; protect them as credentials. The main FTTH database
continues to use its existing backup job.

`ftth-radius-clients.timer` checks the NAS registry every 30 seconds and restarts
only this FreeRADIUS container after a change. Existing PPPoE sessions continue;
new authentication requests can briefly retry during the restart.

The mini PC's old `genieacs-cwmp`, `genieacs-nbi`, `genieacs-fs`,
`genieacs-mongo`, `freeradius`, and `backup-radius` services are stopped.
Their volumes and the old RADIUS database are retained for rollback. Normal
application releases update only server/web and do not restart these services;
a manual full-stack `compose up` may start them again.

The stock VPN installer called an empty legacy-NAT cleanup pipeline with
`set -euo pipefail`, which stopped first-time installation before systemd startup.
The cleanup now tolerates no matches. Package installation defaults to reporting
pending unrelated service restarts instead of restarting shared applications.

The managed OpenVPN hub still validates new device connections against FTTH.
Moving the hub does not remove that dependency when the mini PC is offline.

## Installation checkpoint — 2026-10-11

DNS points both hostnames directly to Tencent. WireGuard, OpenVPN, RADIUS and ACS
are running. The actual FTTH RADIUS database test and ACS connection test pass.
A temporary OpenVPN client authenticated through the platform callback. RADIUS
accepted correct SQL credentials and rejected an incorrect password. Both ACS
endpoints accepted an authenticated Inform, persisted the device and rejected
incorrect credentials. Probe accounts and devices are removed after testing.
The first database/configuration backup was copied successfully to the mini PC.

PR #57 tracks CI and production activation. Private local evidence is under
`.omo/runtime/tencent` in the `work/tencent-network-20261011` worktree.
Credentials are kept outside Git. Physical MikroTik/ONT commissioning still
requires applying the generated VPN account and ONT settings to the devices.

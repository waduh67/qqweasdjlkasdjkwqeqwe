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

## Configuration and recovery

- Node files: `/opt/ftth-network`; `.env` is mode 0600 and never committed.
- WireGuard: `/etc/wireguard/ftth-link.conf` and `ftth-link.key` on both hosts.
- OpenVPN: `/etc/openvpn/server`, `openvpn-server@server`, `ftth-vpn-sync.timer`.
- Original RADIUS dump: `/opt/ftth-network/backups/legacy-radius.dump`.
- Application, ACS connection settings and VPN PKI remain in the main FTTH database.
- Do not delete the original mini PC volumes during migration or rollback.

The stock VPN installer called an empty legacy-NAT cleanup pipeline with
`set -euo pipefail`, which stopped first-time installation before systemd startup.
The cleanup now tolerates no matches. Package installation defaults to reporting
pending unrelated service restarts instead of restarting shared applications.

The managed OpenVPN hub still validates new device connections against FTTH.
Moving the hub does not remove that dependency when the mini PC is offline.

## Installation checkpoint — 2026-10-11

DNS points both hostnames directly to Tencent. The platform VPN hub is registered
and its daemon is active. PostgreSQL/ACS installation, private-link reachability,
platform connections, backup automation, and deployment verification are in
progress. Private local evidence is under `.omo/runtime/tencent` in the
`work/tencent-network-20261011` worktree. Credentials are kept outside Git.

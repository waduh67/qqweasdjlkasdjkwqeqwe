# Hosted network simulator

The hosted demo runs the regular FTTH application with its own accounts, databases,
object storage, ACS and protocol simulators. The tenant is named **Simulator FTTH**
and the application header identifies it as `simulator`.

| Component | Demo behavior |
| --- | --- |
| OLT | Five HSGQ SNMP agents, 2 PON × 8 ONU each; healthy, weak signal and offline examples |
| BRAS | Virtual NAS creates RADIUS accounting sessions and traffic; supports UDP Disconnect/CoA responses |
| ONT | One TR-069 device reports to GenieACS; linked to Budi Lab by serial number |
| Topology | Budi Lab plus 50 Tebet customers, 2 sites, 3 ODC, 10 ODP and street-following fiber routes |
| Warehouse | Receipt, issue, technician acceptance and installation records for demo ONUs |

This is protocol simulation, not a RouterOS VM or a physical PPPoE access network.
CoA acknowledges the session command; generated traffic currently follows a synthetic
profile rather than actual packet shaping. The ZTE device in the map is topology data;
the five HSGQ devices are the ones backed by SNMP agents.

## Deploy through GitHub Actions

Use **Actions → deploy-simulator → Run workflow**. The workflow uses the existing
`VPS_HOST`, `VPS_USER`, `VPS_SSH_KEY` and `VPS_KNOWN_HOSTS` secrets; local SSH access
is not required.

- `inspect` reads host capacity and existing containers without changing services.
- `prepare` tests the simulator and installer, resolves application images from a
  successful production run, and builds the four simulator support images.
- `deploy` additionally installs the isolated `ftth-sim` project, restores or keeps
  its data, exercises device commands, and tests the public application in Chromium.

The mini PC needs **8 GB allocated to Docker Desktop** and at least 12 GB free disk.
Changing Docker Desktop's memory setting restarts its containers. Configure this
before deploying. The installer refuses to start services on an undersized engine.

For Cloudflare Tunnel, publish `sim.karuhundeveloper.com` to
`http://localhost:18080`. This single-level hostname works with the normal wildcard
certificate; `sim.ftth.karuhundeveloper.com` needs a certificate covering that deeper
hostname. No additional inbound router or firewall ports are needed.

On the mini PC, state lives in `~/ftth-simulator/private`, release sources in
`~/ftth-simulator/releases/<commit>`, and `~/ftth-simulator/current` points to the
last successful release. The private `compose.json` pins deployed images and is
automatically used by `manage.py`. Existing simulator data is backed up before an
update and after successful verification. Containers from other projects are
checked before and after deployment.

`application_run` selects a successful run of the production `deploy` workflow;
empty means the latest successful run. Its published-image artifact must still be
available, and its server/web source must match the selected simulator source.
Application images are reused by immutable digest, without rebuilding them.

For a new host, `recovery_release` can name a GitHub release containing
`simulator-backup.tar.gz.age`. Its age identity is stored only in the Actions secret
`SIMULATOR_RECOVERY_AGE_KEY` and a private operator recovery file. Never upload the
unencrypted backup or the identity to a release or artifact. The installer journals
each restored service, refuses recovery after an application container exists,
and never replays the original backup after an installation is complete.

The 2026-10-01 backup was fully restored into an isolated test project on
2026-10-04: PostgreSQL, RADIUS, MongoDB and MinIO restored, with 51 customers,
50 installed ONUs and 450 inventory movements. App SQL helpers require `public`
in the restore session's search path; the installer retains `pg_catalog` first and
does not change the stored function definitions.

## Original VPS deployment (2026-10-01)

Open <https://sim.ftth.karuhundeveloper.com> and sign in as
`admin@karuhundeveloper.com`. Use tenant `simulator` if a tenant selector is shown.
The password is separate from the production login. Retrieve only the demo login
password from the server:

```sh
ssh -i ~/.ssh/id_ed25519 fajar@70.153.16.143 \
  "sed -n 's/^FTTH_SIM_ADMIN_PASSWORD=//p' /opt/ftth-simulator/private/simulator.env"
```

- **Aset jaringan → OLT:** open an `OLT-LAB-*` device to inspect its PONs and ONU readings.
- **Pelanggan → Budi Lab:** inspect the access session and traffic; **Reset Login**
  disconnects the virtual NAS session, then it reconnects automatically.
- **ACS / TR-069:** inspect the linked device `C0FD84050205` and issue a reboot.
- **Peta:** explore the Tebet customers, fiber routes and distribution devices.
- **Stok & Perangkat:** inspect the receipt, handover and installation history.

There are 51 customer records and 50 installed warehouse ONTs. The 80 SNMP readings
belong to the five virtual OLTs; they are a separate monitoring fixture, not 80
additional customer installations. The seeded installations are provisional, so
customer lifecycle labels may still show prospect until the normal QA/activation flow.

## Install

Requirements: Docker Engine, Compose **2.24.4+** (supports `!override`), Python 3.
Run Docker commands as an operator with daemon access. This setup does not replace
the existing production project.

```sh
python3 deploy/simulator/manage.py init \
  --domain sim.ftth.karuhundeveloper.com \
  --email admin@karuhundeveloper.com \
  --edge-network ruang-foto_default
python3 deploy/simulator/manage.py build
python3 deploy/simulator/manage.py up
python3 deploy/simulator/manage.py seed
```

Omit `--edge-network` when the host proxy forwards to `127.0.0.1:18080` directly.
For an existing Docker Caddy proxy, add the following site to its existing config,
validate it, then reload Caddy:

```caddyfile
sim.ftth.karuhundeveloper.com {
    reverse_proxy ftth-sim-gateway:80
}
```

Create a DNS-only A record pointing to the host. The edge proxy obtains its own
TLS certificate. Only existing web ports 80/443 are needed. No additional public
SNMP, RADIUS, CWMP, ACS NBI or database ports are opened.

`init` writes `.env.simulator` with mode 600 and refuses to overwrite it. It contains
the tenant admin login (`FTTH_SIM_ADMIN_EMAIL` / `FTTH_SIM_ADMIN_PASSWORD`) and a
separate platform login. Both use generated passwords. Do not commit this file.
Use `--env-file /private/path/simulator.env` before any command to store it elsewhere.

Production secret validation stays enabled. The built-in weak demo bootstrap is
disabled; the installer creates the simulator tenant through the platform API.
The simulator network has no external route; payment providers, email delivery and
physical network access are therefore unavailable. Only the HTTP gateway joins the
edge network. Browser map tiles still load normally.

## Operate and recover

```sh
python3 deploy/simulator/manage.py status
python3 deploy/simulator/manage.py logs
python3 deploy/simulator/manage.py stop
python3 deploy/simulator/manage.py up
python3 deploy/simulator/manage.py backup --output /private/backups/simulator-20261001
```

`stop` retains data. Containers restart automatically after a host reboot. Seed
commands reuse resources by their natural keys and can be repeated. Never use the
local lab's `make lab-down` to manage this hosted instance.

The installer journals warehouse commands under `seed-state/` beside the private
env file, including the original idempotency key inputs and a separate demo
technician credential. Keep this directory for interrupted installs. The backup
command includes it. Do not run local lab seeds concurrently against the same tenant.

The backup command saves private, per-service snapshots of PostgreSQL database `ftth`, RADIUS database
`radius`, GenieACS Mongo database `genieacs`, MinIO data, plus the private env file.
The encryption secret is required to recover stored credentials. Preserve the Git
commit and container image IDs with each deployment. Restore into a fresh simulator
project; do not restore any of these volumes over production.

## Verification

```sh
python3 -m unittest discover -s deploy/simulator -p 'test_*.py'
./gradlew :simulator:test --no-daemon --no-parallel --max-workers=2
python3 deploy/simulator/verify.py --exercise-controls
```

After seeding, allow roughly one minute for polling. Confirm five OLTs have 80 ONU
readings, customer PPPoE sessions have increasing counters, the ONT appears in ACS,
and resetting one demo customer's login closes the session and reconnects it.
Keep this mutation check strictly within tenant `simulator`.

The hosted deployment was checked on 2026-10-01:

- 18 simulator JVM tests, 6 installer/recovery tests and 2 incident-correlation
  boundary tests passed. Customer session alarms no longer block network incidents.
- Live SNMP polling returned 16 ONUs from each of five OLTs. RADIUS accounting
  produced traffic, Reset Login disconnected/reconnected a session, and TR-069
  reboot returned success.
- Repeating the seed preserved 51 customers, 50 installed ONUs and 450 inventory
  movements without creating duplicates.
- Firefox opened customers, inventory, BRAS, ACS and warehouse stock without
  JavaScript errors or HTTP 5xx responses. The map passed in Chromium; this VPS's
  headless Firefox has no working WebGL driver, so its map was not verified.
- PostgreSQL backup archives and the compressed Mongo/MinIO snapshots were checked
  for readability. The subsequent complete restore rehearsal is described above.

The source is installed at `/opt/ftth-simulator/source`; private state, deployment
receipts and backups are under `/opt/ftth-simulator/private`. Use the explicit env
path for operations on this host:

```sh
sudo python3 /opt/ftth-simulator/source/deploy/simulator/manage.py \
  --env-file /opt/ftth-simulator/private/simulator.env status
```

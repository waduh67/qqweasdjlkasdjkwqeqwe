# Hosted network simulator

The hosted demo runs the regular FTTH application with its own accounts, databases,
object storage, ACS and protocol simulators. The tenant is named **Simulator FTTH**
and the application header identifies it as `simulator`.

| Component | Demo behavior |
| --- | --- |
| OLT | Five HSGQ SNMP agents, 2 PON × 8 ONU each; healthy, weak signal and offline examples |
| BRAS | Virtual NAS creates RADIUS accounting sessions and traffic; supports UDP Disconnect/CoA responses |
| ONT | One TR-069 device reports to GenieACS; linked to Budi Lab by serial number |
| Topology | POP Tebet, 3 ODC, 10 ODP, 50 customers and street-following fiber routes |

This is protocol simulation, not a RouterOS VM or a physical PPPoE access network.
CoA acknowledges the session command; generated traffic currently follows a synthetic
profile rather than actual packet shaping. The ZTE device in the map is topology data;
the five HSGQ devices are the ones backed by SNMP agents.

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
```

`stop` retains data. Containers restart automatically after a host reboot. Seed
commands reuse resources by their natural keys and can be repeated. Never use the
local lab's `make lab-down` to manage this hosted instance.

Back up project `ftth-sim` separately: PostgreSQL database `ftth`, RADIUS database
`radius`, GenieACS Mongo database `genieacs`, MinIO data, plus the private env file.
The encryption secret is required to recover stored credentials. Preserve the Git
commit and container image IDs with each deployment. Restore into a fresh simulator
project; do not restore any of these volumes over production.

## Verification

```sh
python3 -m unittest discover -s deploy/simulator -p 'test_*.py'
./gradlew :simulator:test --no-daemon --no-parallel --max-workers=2
```

After seeding, allow roughly one minute for polling. Confirm five OLTs have 80 ONU
readings, customer PPPoE sessions have increasing counters, the ONT appears in ACS,
and resetting one demo customer's login closes the session and reconnects it.
Keep this mutation check strictly within tenant `simulator`.

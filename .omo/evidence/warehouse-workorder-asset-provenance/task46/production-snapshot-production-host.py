#!/usr/bin/env python3
"""Read-only final configuration snapshot; never serializes environment secrets."""
import datetime, hashlib, importlib.util, json, os, re, subprocess
from pathlib import Path

os.umask(0o077)
root = Path('/opt/ftth')
private = root / 'setup-private'
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
gate = json.loads((private / 'release-approved.json').read_text())
spec = importlib.util.spec_from_file_location('activation', private / 'activate-production.py')
activation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(activation)
activation.verify_gate(root, private, gate, private / 'activate-production.py')

def run(args):
    return subprocess.check_output(args, text=True, stderr=subprocess.PIPE, timeout=60).strip()

report = {'status': 'PASS', 'atUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
          'sourceSha256': sha(Path(__file__)), 'testedCodeCommit': gate['testedCodeCommit'],
          'deploymentCommit': gate['deploymentCommit'], 'all13ReleaseHostFilesStillMatch': True,
          'hostConfigurationSha256': gate['hostConfigurationSha256'], 'containers': {}}
identifiers = run([str(root / 'ftth-compose'), 'ps', '-q']).splitlines()
containers = json.loads(run(['docker', 'inspect', *identifiers]))
for container in containers:
    service = container['Config']['Labels']['com.docker.compose.service']
    value = {'containerId': container['Id'], 'imageId': container['Image'],
             'state': container['State']['Status'], 'restartCount': container['RestartCount'],
             'restartPolicy': container['HostConfig']['RestartPolicy']['Name'],
             'health': container['State'].get('Health', {}).get('Status'),
             'networks': sorted(container['NetworkSettings']['Networks']),
             'hostPortBindings': container['HostConfig']['PortBindings']}
    assert value['state'] == 'running' and value['health'] in (None, 'healthy')
    assert value['restartPolicy'] == 'unless-stopped'
    report['containers'][service] = value
assert set(report['containers']) == {'server', 'web', 'caddy', 'backup', 'backup-radius',
    'postgres', 'radius-db', 'minio', 'genieacs-mongo', 'freeradius', 'genieacs-cwmp', 'genieacs-nbi', 'genieacs-fs'}
report['systemd'] = {}
for service in ['docker.service', 'openvpn-server@server.service', 'ftth-vpn-sync.timer', 'ftth-vpn-firewall.service']:
    value = {field: run(['systemctl', method, service]) for field, method in [('active', 'is-active'), ('enabled', 'is-enabled')]}
    assert value == {'active': 'active', 'enabled': 'enabled'}
    report['systemd'][service] = value
files = [Path('/usr/local/sbin/ftth-vpn-firewall'), Path('/etc/sysctl.d/99-ftth-vpn.conf'),
    Path('/etc/systemd/system/ftth-vpn-firewall.service'), Path('/etc/systemd/system/ftth-vpn-sync.service'),
    Path('/etc/systemd/system/ftth-vpn-sync.timer'), Path('/etc/systemd/system/openvpn-server@server.service.d/ftth-firewall.conf')]
files += sorted(Path('/etc/openvpn/server').glob('*'))
report['vpnFiles'] = {str(path): {'sha256': sha(path), 'mode': oct(path.stat().st_mode & 0o777)} for path in files if path.is_file()}
rules = run(['iptables-save'])
report['tunnelFirewallRules'] = sorted(line for line in rules.splitlines() if line.startswith('-A ') and '10.8.0.0/24' in line)
assert len(report['tunnelFirewallRules']) == 3
report['vpnRoutes'] = json.loads(run(['ip', '-j', '-4', 'route', 'show', '10.8.0.0/24']))
report['ipForward'] = run(['sysctl', '-n', 'net.ipv4.ip_forward'])
assert report['ipForward'] == '1'
report['tcpListeners'] = run(['ss', '-H', '-lnt'])
report['ufw'] = run(['ufw', 'status'])
report['backupFiles'] = {str(path.relative_to(root)): {'sha256': sha(path), 'bytes': path.stat().st_size,
    'mode': oct(path.stat().st_mode & 0o777)} for path in sorted((root / 'backups').rglob('*')) if path.is_file()}
for target in ['app', 'radius']:
    assert any(name.startswith('backups/' + target + '/') and name.endswith('.dump') and value['bytes'] > 0
               for name, value in report['backupFiles'].items())
edge = Path('/opt/ruang-foto/Caddyfile').read_bytes()
prior_path = Path((private / 'activation-latest.txt').read_text().strip()) / 'edge-before.Caddyfile'
prior = prior_path.read_bytes()
assert re.sub(activation.PATTERN, b'', edge, flags=re.S) == re.sub(activation.PATTERN, b'', prior, flags=re.S)
assert re.findall(activation.PATTERN, edge, re.S) == [activation.OWNED_BLOCK]
report['edgeSha256'] = hashlib.sha256(edge).hexdigest()
report['otherProxyBytesStillPreserved'] = True
report['privateFileModes'] = {name: oct((private / name).stat().st_mode & 0o777)
    for name in ['admin-credentials.txt', 'connection-settings.txt', 'vpn-registration.json']}
assert all(mode == '0o600' for mode in report['privateFileModes'].values())
(private / 'production-host-final.json').write_text(json.dumps(report, indent=2) + '\n')
print('Final host snapshot PASS: 13 containers, release bindings, VPN, backups and unrelated proxy preserved.')

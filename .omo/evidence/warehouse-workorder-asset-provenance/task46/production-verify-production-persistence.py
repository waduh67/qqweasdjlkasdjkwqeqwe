#!/usr/bin/env python3
"""Restart only the authorized FTTH backend and verify its persisted hub and schema."""
import datetime, hashlib, json, os, secrets, subprocess, time, urllib.request
from pathlib import Path

os.umask(0o077)
root = Path('/opt/ftth')
private = root / 'setup-private'
activation = Path((private / 'activation-latest.txt').read_text().strip())
installation = Path((private / 'vpn-install-latest.txt').read_text().strip())
assert json.loads((activation / 'verification.json').read_text())['status'] == 'PASS'
assert json.loads((installation / 'verification.json').read_text())['status'] == 'PASS'
registration = json.loads((private / 'vpn-registration.json').read_text())
identity = json.loads((private / 'loaded-image-identities.json').read_text())
env = dict(line.split('=', 1) for line in (root / '.env').read_text().splitlines())
evidence = private / ('persistence-' + secrets.token_hex(6))
evidence.mkdir(mode=0o700)
(private / 'persistence-latest.txt').write_text(str(evidence) + '\n')
log = (evidence / 'runner.log').open('a')
report = {'status': 'RUNNING', 'startedAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
          'testedCodeCommit': identity['codeCommit'], 'hubId': registration['id'],
          'physicalRouterTested': False, 'hostRebootTested': False,
          'sourceSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}

def run(args, capture=False, input=None):
    result = subprocess.run(args, input=input, text=True, stdout=subprocess.PIPE if capture else log,
                            stderr=subprocess.PIPE if capture else subprocess.STDOUT, timeout=180)
    if result.returncode:
        if capture:
            log.write(result.stderr)
            log.flush()
        raise RuntimeError('Command failed; see private log: ' + args[0])
    return result.stdout if capture else None

def compose(*args, capture=False, input=None):
    return run([str(root / 'ftth-compose'), *args], capture, input)

def container():
    identifier = compose('ps', '-q', 'server', capture=True).strip()
    return json.loads(run(['docker', 'inspect', identifier], capture=True))[0]

def request(path, name, body=None, bearer=None):
    target = evidence / (name + '.response')
    config = evidence / (name + '.curl')
    lines = ['silent', 'show-error', 'max-time = 30',
             'url = ' + json.dumps('https://ftth.karuhundeveloper.com' + path),
             'output = ' + json.dumps(str(target)), 'write-out = "%{http_code}"']
    if bearer:
        lines.append('header = ' + json.dumps('Authorization: Bearer ' + bearer))
    if body is not None:
        source = evidence / (name + '.request')
        source.write_text(json.dumps(body))
        lines += ['header = "Content-Type: application/json"', 'data-binary = ' + json.dumps('@' + str(source))]
    config.write_text('\n'.join(lines) + '\n')
    status = int(run(['curl', '--config', str(config)], capture=True))
    assert status == 200, name + ' did not return200'
    return json.loads(target.read_text())

def read_hub(label):
    login = request('/api/auth/login', label + '-login', {
        'email': env['FTTH_PLATFORM_ADMIN_EMAIL'], 'password': env['FTTH_PLATFORM_ADMIN_PASSWORD']})
    assert login['user']['platformAdmin'] and login['user']['email'] == env['FTTH_PLATFORM_ADMIN_EMAIL']
    hubs = request('/api/vpn/servers', label + '-hubs', bearer=login['accessToken'])
    hub = next(hub for hub in hubs if hub['id'] == registration['id'])
    expected = {key: registration[key] for key in ['id', 'name', 'host', 'port', 'protocol', 'tunnelCidr', 'pkiReady']}
    assert all(hub[key] == value for key, value in expected.items())
    return {'userId': login['user']['id'], 'hub': expected, 'peerCount': hub['peerCount']}

try:
    before = container()
    assert before['Image'] == identity['services']['server']['destinationImageId']
    prior = read_hub('before')
    report['before'] = {'containerId': before['Id'], 'imageId': before['Image'],
                        'startedAt': before['State']['StartedAt'], **prior}
    (evidence / 'progress.json').write_text(json.dumps(report, indent=2) + '\n')
    compose('restart', 'server')
    deadline = time.monotonic() + 300
    while time.monotonic() < deadline:
        after = container()
        address = after['NetworkSettings']['Networks']['ftth_default']['IPAddress']
        try:
            with urllib.request.urlopen('http://' + address + ':8080/actuator/health', timeout=5) as response:
                if response.status == 200 and json.load(response)['status'] == 'UP':
                    break
        except (OSError, ValueError):
            pass
        time.sleep(3)
    else:
        raise RuntimeError('Backend readiness timed out after same-image restart')
    assert after['Id'] == before['Id'] and after['Image'] == before['Image']
    assert after['State']['StartedAt'] != before['State']['StartedAt']
    following = read_hub('after')
    assert following == prior
    report['after'] = {'containerId': after['Id'], 'imageId': after['Image'],
                       'startedAt': after['State']['StartedAt'], **following}
    report['sameImageRestartAndPersistedHubPassed'] = True
    query = "SELECT json_build_object('role',current_user,'migrations',(SELECT count(*) FROM flyway_schema_history WHERE version IS NOT NULL),'failed',(SELECT count(*) FROM flyway_schema_history WHERE NOT success),'latest',(SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1));"
    result = compose('exec', '-T', 'postgres', 'sh', '-c',
        'PGPASSWORD="$FTTH_DB_PASSWORD" exec psql -X -h 127.0.0.1 -U "$FTTH_DB_USER" -d "$FTTH_DB_NAME" -v ON_ERROR_STOP=1 -At',
        capture=True, input=query)
    report['schemaAfterRestart'] = json.loads(result)
    assert report['schemaAfterRestart'] == {'role': 'warehouse_app', 'migrations': 367, 'failed': 0, 'latest': '178.12'}
    run(['systemctl', 'start', 'ftth-vpn-sync.service'])
    assert run(['systemctl', 'show', 'ftth-vpn-sync.service', '--property=ExecMainStatus', '--value'], capture=True).strip() == '0'
    for service in ['openvpn-server@server.service', 'ftth-vpn-sync.timer', 'ftth-vpn-firewall.service']:
        assert run(['systemctl', 'is-active', service], capture=True).strip() == 'active'
    assert '1194' in run(['ss', '-H', '-lnt', 'sport = :1194'], capture=True)
    report['vpnRemainsActiveAndSyncPassed'] = True
    for domain in ['ftth.karuhundeveloper.com', 'drive.karuhundeveloper.com']:
        assert run(['curl', '-sS', '--max-time', '30', '--output', '/dev/null', '--write-out', '%{http_code}',
                    'https://' + domain], capture=True) == '200'
    report['ftthAndExistingDriveHttpsStatus'] = 200
    compose('exec', '-T', 'backup', 'sh', '/opt/backup/backup.sh')
    compose('exec', '-T', 'backup-radius', 'sh', '/opt/backup/backup.sh')
    report['postVpnAppAndRadiusBackupsPassed'] = True
    report['status'] = 'PASS'
except Exception as error:
    report['status'] = 'FAIL'
    report['failureType'] = type(error).__name__
    report['failure'] = str(error)
finally:
    report['finishedAtUtc'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    (evidence / 'verification.json').write_text(json.dumps(report, indent=2) + '\n')
    (evidence / 'exit-code.txt').write_text('0\n' if report['status'] == 'PASS' else '1\n')
    log.close()
print('Production persistence ' + report['status'] + '; private evidence ' + str(evidence))
raise SystemExit(0 if report['status'] == 'PASS' else 1)

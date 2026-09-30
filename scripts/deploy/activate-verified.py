#!/usr/bin/env python3
"""Update app image pins through an installed Azure Compose wrapper, retaining its topology."""
import fcntl
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time


def command(args):
    return subprocess.check_output(args, text=True, stderr=subprocess.PIPE, timeout=180).strip()


def atomic(path, data):
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
        temporary = stream.name
    os.replace(temporary, path)


def encode(value):
    return (json.dumps(value, indent=2) + '\n').encode()


def app_references(proof, commit):
    assert re.fullmatch(r'[a-f0-9]{40}', commit) and proof['commit'] == commit, 'wrong release commit'
    assert set(proof['images']) == {'server', 'web'}, 'expected server and web images'
    refs = {service: value['registryDigest'] for service, value in proof['images'].items()}
    for service, value in refs.items():
        assert re.fullmatch(r'ghcr\.io/[a-z0-9_-]+/ftth-' + service + r'@sha256:[a-f0-9]{64}', value), 'immutable image required'
    return refs


def updated_pins(old, references):
    pins = json.loads(old)
    for service, reference in references.items():
        config = pins.setdefault('services', {}).setdefault(service, {})
        config.update(image=reference, pull_policy='never')
    # Retained host Compose predates main's public Tripay URL configuration.
    environment = pins['services']['server'].setdefault('environment', {})
    assert isinstance(environment, dict), 'server environment must be a mapping'
    environment['FTTH_SITE_ADDRESS'] = '${FTTH_SITE_ADDRESS:?public site address required}'
    environment['FTTH_PRODUCTION'] = '${FTTH_PRODUCTION:-true}'
    return encode(pins)


def snapshot(run):
    ids = run(['docker', 'ps', '-aq']).splitlines()
    assert ids, 'no containers'
    result = {}
    for container in json.loads(run(['docker', 'inspect', *ids])):
        labels = container['Config'].get('Labels') or {}
        key = labels.get('com.docker.compose.service') if labels.get('com.docker.compose.project') == 'ftth' else container['Name']
        assert key not in result, 'duplicate service'
        result[key] = {key: container[key] for key in ('Id', 'Image', 'RestartCount')}
        result[key]['state'] = {key: container['State'][key] for key in ('Running', 'StartedAt')}
    assert {'server', 'web'}.issubset(result), 'installed app services required'
    return result


def activate(base, release, commit, run=command):
    assert release.parent == base / 'releases' and release.name == commit, 'unexpected release directory'
    assert (base / 'ftth-compose').is_file(), 'installed Compose wrapper required'
    references = app_references(json.loads((release / 'published.json').read_text()), commit)
    pins_path = base / 'docker-compose.images.json'
    old = pins_path.read_bytes()
    replacement = updated_pins(old, references)
    before = snapshot(run)
    assert all(before[service]['state']['Running'] for service in ('server', 'web')), 'app not running'
    expected = {}
    for service, reference in references.items():
        run(['docker', 'pull', reference])
        expected[service] = run(['docker', 'image', 'inspect', reference, '--format', '{{.Id}}'])
    assert pins_path.read_bytes() == old, 'pins changed during pull'
    (release / 'pins-before.json').write_bytes(old)
    compose = [str(base / 'ftth-compose')]
    receipt = {'commit': commit, 'before': before, 'references': references, 'status': 'ACTIVATING'}
    atomic(release / 'activation.json', encode(receipt))
    atomic(pins_path, replacement)
    try:
        run(compose + ['config', '--quiet'])
        run(compose + ['up', '-d', '--no-deps', '--pull', 'never', 'server', 'web'])
        for attempt in range(30):
            try:
                health = json.loads(run(compose + ['exec', '-T', 'server', 'sh', '-c', 'wget -qO- http://127.0.0.1:8080/actuator/health']))
                if health.get('status') == 'UP':
                    break
            except Exception:
                pass
            if attempt == 29:
                raise RuntimeError('new backend did not become healthy')
            time.sleep(2)
        after = snapshot(run)
        assert set(after) == set(before), 'container set changed'
        assert all(after[service]['Image'] == expected[service] and after[service]['state']['Running'] for service in references), 'tested images not running'
        assert all(after[key] == value for key, value in before.items() if key not in references), 'non-app container changed'
        assert pins_path.read_bytes() == replacement, 'pins changed during activation'
        receipt.update(status='PASS', after=after)
    except Exception:
        receipt['status'] = 'ROLLBACK_REQUIRES_ATTENTION'
        # Do not overwrite a subsequent operator's image-pin changes.
        assert pins_path.read_bytes() == replacement, 'foreign pin change prevents rollback'
        atomic(pins_path, old)
        run(compose + ['up', '-d', '--no-deps', '--pull', 'never', 'server', 'web'])
        restored = snapshot(run)
        assert all(restored[key]['Image'] == before[key]['Image'] and restored[key]['state']['Running'] for key in references), 'old images not restored'
        receipt.update(status='ROLLED_BACK', after=restored)
        raise
    finally:
        atomic(release / 'activation.json', encode(receipt))
    return receipt


if __name__ == '__main__':
    os.umask(0o077)
    assert os.geteuid() == 0, 'root required for installed image pins'
    base = Path('/opt/ftth')
    with (base / '.release.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        result = activate(base, Path(sys.argv[1]).resolve(), sys.argv[2])
        print('Application activation:', result['status'])

#!/usr/bin/env python3
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time

spec = importlib.util.spec_from_file_location('verified', Path(__file__).with_name('activate-verified.py'))
verified = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verified)


def command(args):
    return subprocess.check_output(args, text=True, stderr=subprocess.PIPE, timeout=900).strip()


def compose(base, source):
    return ['docker', 'compose', '--project-directory', str(base), '--env-file', str(base / '.env'), '-f', str(source)]


def escape_config(value):
    if isinstance(value, str):
        return value.replace('$', '$$')
    if isinstance(value, list):
        return [escape_config(item) for item in value]
    if isinstance(value, dict):
        return {key: escape_config(item) for key, item in value.items()}
    return value


def runtime_config(config, references, image_configs):
    for service, reference in references.items():
        target = config['services'][service]
        target.update(image=reference, pull_policy='never')
        environment = target.get('environment', {})
        empty = [key for key, value in environment.items() if value == '']
        if not empty:
            continue
        if any(not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', key) for key in empty):
            raise ValueError('Invalid environment variable name')
        original = image_configs[service]
        entrypoint = target.get('entrypoint')
        if entrypoint is None:
            entrypoint = original.get('Entrypoint') or []
        args = target.get('command')
        if args is None:
            args = original.get('Cmd') or []
        if not isinstance(entrypoint, list) or not isinstance(args, list) or not entrypoint + args:
            raise ValueError('Exec-form image startup required')
        target['environment'] = {key: value for key, value in environment.items() if key not in empty}
        target['entrypoint'] = ['/bin/sh', '-c', 'export ' + ' '.join(key + "=''" for key in empty) + '; exec "$@"', 'ftth-env']
        target['command'] = entrypoint + args
    return verified.encode(escape_config(config))


def database(base):
    values = dict(line.split('=', 1) for line in (base / '.env').read_text().splitlines() if '=' in line and not line.startswith('#'))
    name = values.get('FTTH_DB_NAME', '').strip().strip("'\"")
    if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', name):
        raise ValueError('FTTH_DB_NAME required')
    return name


def schema(base, run):
    sql = "SELECT COALESCE(json_agg(row_to_json(h) ORDER BY installed_rank)::text,'[]') FROM public.flyway_schema_history h"
    return run(compose(base, base / 'docker-compose.prod.yml') + ['exec', '-T', 'postgres', 'psql', '-U', 'postgres', '-d', database(base), '-At', '-c', sql])


def wait_ready(run):
    for attempt in range(60):
        try:
            backend = json.loads(run(['curl', '--fail', '--silent', '--max-time', '5', 'http://127.0.0.1:8080/actuator/health']))
            gateway = json.loads(run(['curl', '--fail', '--silent', '--max-time', '5', 'http://127.0.0.1:3080/actuator/health/readiness']))
            if backend.get('status') == gateway.get('status') == 'UP':
                return
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired, json.JSONDecodeError):
            pass
        if attempt < 59:
            time.sleep(3)
    raise RuntimeError('Backend and web gateway did not become healthy')


def activate(base, release, commit, checksum, run=command):
    if release.parent != base / 'releases' or release.name != commit:
        raise ValueError('Unexpected release directory')
    source = release / 'docker-compose.prod.yml'
    if hashlib.sha256(source.read_bytes()).hexdigest() != checksum:
        raise ValueError('Reviewed Compose checksum differs')
    references = verified.app_references(json.loads((release / 'published.json').read_text()), commit)
    installed = base / 'docker-compose.prod.yml'
    old = installed.read_bytes()
    before = verified.snapshot(run)
    if any(not before[key]['state']['Running'] for key in references):
        raise RuntimeError('Installed app services are not running')
    expected, image_configs = {}, {}
    for service, reference in references.items():
        run(['docker', 'pull', reference])
        image = json.loads(run(['docker', 'image', 'inspect', reference]))[0]
        if image['Config'].get('Labels', {}).get('org.opencontainers.image.revision') != commit:
            raise ValueError('Image revision differs from release')
        expected[service] = image['Id']
        image_configs[service] = image['Config']
    replacement = runtime_config(json.loads(run(compose(base, source) + ['config', '--format', 'json'])), references, image_configs)
    candidate = release / 'runtime.json'
    verified.atomic(candidate, replacement)
    run(compose(base, candidate) + ['config', '--quiet'])
    history = schema(base, run)
    if installed.read_bytes() != old:
        raise RuntimeError('Installed Compose changed during preparation')
    verified.atomic(release / 'compose-before.yml', old)
    verified.atomic(release / 'env-before', (base / '.env').read_bytes())
    dump = compose(base, installed) + ['exec', '-T', 'postgres', 'pg_dump', '-U', 'postgres', '-d', database(base), '-Fc']
    inspect_dump = compose(base, installed) + ['exec', '-T', 'postgres', 'pg_restore', '--list']
    dump_path = shlex.quote(str(release / 'database-before.dump'))
    run(['bash', '-c', 'set -euo pipefail; umask 077; ' + shlex.join(dump) + ' > ' + dump_path + '; ' + shlex.join(inspect_dump) + ' < ' + dump_path + ' > /dev/null'])
    receipt = {'commit': commit, 'references': references, 'before': before, 'status': 'ACTIVATING'}
    verified.atomic(release / 'activation.json', verified.encode(receipt))
    verified.atomic(installed, replacement)
    try:
        run(compose(base, installed) + ['up', '-d', '--no-deps', '--pull', 'never', 'server', 'web'])
        wait_ready(run)
        after = verified.snapshot(run)
        if set(before) != set(after) or any(after[key] != value for key, value in before.items() if key not in references):
            raise RuntimeError('A container outside server and web changed')
        if any(after[key]['Image'] != expected[key] or not after[key]['state']['Running'] for key in references):
            raise RuntimeError('Verified images are not running')
        if installed.read_bytes() != replacement:
            raise RuntimeError('Installed Compose changed during activation')
        receipt.update(status='PASS', after=after)
    except (subprocess.SubprocessError, ValueError, RuntimeError, AssertionError):
        receipt['status'] = 'RECOVERY_REQUIRED'
        if installed.read_bytes() == replacement and schema(base, run) == history:
            verified.atomic(installed, old)
            run(compose(base, installed) + ['up', '-d', '--no-deps', '--pull', 'never', 'server', 'web'])
            restored = verified.snapshot(run)
            if all(restored[key]['Image'] == before[key]['Image'] and restored[key]['state']['Running'] for key in references):
                receipt.update(status='ROLLED_BACK', after=restored)
        raise
    finally:
        verified.atomic(release / 'activation.json', verified.encode(receipt))
    return receipt


if __name__ == '__main__':
    os.umask(0o077)
    base = Path('/opt/ftth').resolve()
    with (base / '.release.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        try:
            result = activate(base, Path(sys.argv[1]).resolve(), sys.argv[2], sys.argv[3])
            print('Application activation:', result['status'])
        except (subprocess.SubprocessError, ValueError, RuntimeError, AssertionError) as error:
            print('Application activation failed:', type(error).__name__, file=sys.stderr)
            sys.exit(1)

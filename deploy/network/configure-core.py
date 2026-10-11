#!/usr/bin/env python3
"""Apply network settings to the mini PC's native runtime without rebuilding images."""
import fcntl
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
from urllib.request import ProxyHandler, build_opener

ALLOWED = {
    'FTTH_CPE_GENIEACS_BASE_URL', 'FTTH_CPE_GENIEACS_USERNAME', 'FTTH_CPE_GENIEACS_PASSWORD',
    'FTTH_CPE_PUBLIC_HOST', 'FTTH_CPE_ONT_ACS_USERNAME', 'FTTH_CPE_ONT_ACS_PASSWORD',
    'FTTH_CPE_ONT_CR_USERNAME', 'FTTH_CPE_ONT_CR_PASSWORD', 'FTTH_CPE_CWMP_AUTH_MODE',
    'FTTH_RADIUS_PUBLIC_HOST',
}


def patch_config(config, settings):
    if not settings or not settings.keys() <= ALLOWED:
        raise ValueError('Unexpected network setting')
    if any(not isinstance(value, str) or '\n' in value or "'" in value for value in settings.values()):
        raise ValueError('Expected single-line settings')
    server = config['services']['server']
    server['environment'].update({key: value.replace('$', '$$') for key, value in settings.items()})
    entry = server.get('entrypoint', [])
    if len(entry) >= 3 and entry[:2] == ['/bin/sh', '-c'] and entry[2].startswith('export '):
        if not re.fullmatch(r'''export (?:[A-Za-z_][A-Za-z0-9_]*='' ?)+; exec "\$\$@"''', entry[2]):
            raise ValueError('Unrecognized runtime export wrapper')
        remaining = [key for key in re.findall(r"([A-Za-z_][A-Za-z0-9_]*)=''", entry[2]) if key not in settings]
        entry[2] = ('export ' + ' '.join(key + "=''" for key in remaining) + '; ' if remaining else '') + 'exec "$$@"'
    return config


def main():
    os.umask(0o077)
    base = Path('/opt/ftth').resolve()
    settings = json.loads(Path(sys.argv[1]).read_text())
    with (base / '.release.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        installed, env = base / 'docker-compose.prod.yml', base / '.env'
        original, original_env = installed.read_bytes(), env.read_bytes()
        config = patch_config(json.loads(original), settings)
        backup = base / 'backups' / ('network-config-' + time.strftime('%Y%m%dT%H%M%SZ', time.gmtime()))
        backup.mkdir(mode=0o700)
        (backup / 'compose-before.json').write_bytes(original)
        (backup / 'env-before').write_bytes(original_env)
        log = (backup / 'configure.log').open('w')

        def run(args):
            subprocess.run(args, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=180)

        def compose(source):
            return ['docker', 'compose', '--project-directory', str(base), '--env-file', str(env), '-f', str(source)]

        candidate = backup / 'compose-after.json'
        candidate.write_text(json.dumps(config, indent=2) + '\n')
        lines = [line for line in original_env.decode().splitlines() if line.split('=', 1)[0] not in settings]
        lines += [key + "='" + value + "'" for key, value in settings.items()]
        run(compose(candidate) + ['config', '--quiet'])
        try:
            env.write_text('\n'.join(lines) + '\n')
            installed.write_bytes(candidate.read_bytes())
            run(compose(installed) + ['up', '-d', '--no-deps', '--pull', 'never', 'server'])
            http = build_opener(ProxyHandler({}))
            for attempt in range(60):
                try:
                    with http.open('http://127.0.0.1:8080/actuator/health/readiness', timeout=2) as response:
                        if json.load(response).get('status') == 'UP':
                            print('PASS: network settings applied; FTTH readiness UP; backup:', backup)
                            return
                except (OSError, ValueError):
                    pass
                time.sleep(2)
            raise RuntimeError('FTTH readiness timed out')
        except Exception:
            env.write_bytes(original_env)
            installed.write_bytes(original)
            run(compose(installed) + ['up', '-d', '--no-deps', '--pull', 'never', 'server'])
            raise


if __name__ == '__main__':
    main()

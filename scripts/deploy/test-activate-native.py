import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('native', Path(__file__).with_name('activate-native.py'))
native = importlib.util.module_from_spec(spec)
spec.loader.exec_module(native)
COMMIT = 'a' * 40
REFS = {name: 'ghcr.io/team/ftth-' + name + '@sha256:' + char * 64
        for name, char in [('server', 'b'), ('web', 'c')]}
IDS = {name: 'sha256:' + char * 64 for name, char in [('server', 'd'), ('web', 'e')]}


class NativeActivationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.release = self.base / 'releases' / COMMIT
        self.release.mkdir(parents=True)
        self.old = native.verified.encode({'services': {name: {'image': 'old-' + name}
                                                       for name in ('server', 'web', 'postgres')}})
        (self.base / 'docker-compose.prod.yml').write_bytes(self.old)
        (self.base / '.env').write_text(chr(10).join(['FTTH_DB_NAME=ftth', 'SECRET=literal$TOKEN', '']))
        self.source = b'services: {}' + bytes([10])
        (self.release / 'docker-compose.prod.yml').write_bytes(self.source)
        self.checksum = hashlib.sha256(self.source).hexdigest()
        (self.release / 'published.json').write_bytes(native.verified.encode({
            'commit': COMMIT, 'images': {name: {'registryDigest': ref} for name, ref in REFS.items()}}))
        self.images = {name: 'old-' + name for name in ('server', 'web', 'postgres')}
        self.commands = []
        self.unhealthy = False
        self.migrate = False
        self.wrong_image = False
        self.history = '[]'

    def run_command(self, args):
        self.commands.append(args)
        if args[:3] == ['docker', 'ps', '-aq']:
            return chr(10).join(self.images)
        if args[:2] == ['docker', 'inspect']:
            return json.dumps([{'Id': name + '-id', 'Name': '/' + name, 'Image': image,
                               'RestartCount': 0, 'State': {'Running': True, 'StartedAt': 'same'},
                               'Config': {'Labels': {'com.docker.compose.project': 'ftth',
                                                     'com.docker.compose.service': name}}}
                              for name, image in self.images.items()])
        if args[:3] == ['docker', 'image', 'inspect']:
            name = next(name for name, ref in REFS.items() if ref == args[3])
            return json.dumps([{'Id': IDS[name], 'Config': {
                'Labels': {'org.opencontainers.image.revision': COMMIT},
                'Entrypoint': ['java', '-jar', '/app.jar'], 'Cmd': None}}])
        if args[-3:] == ['config', '--format', 'json']:
            return json.dumps({'services': {
                'server': {'environment': {'EMPTY': '', 'SECRET': 'literal$TOKEN'}}, 'web': {},
                'postgres': {'image': 'old-postgres'}}})
        if 'psql' in args:
            return self.history
        if 'up' in args:
            config = json.loads((self.base / 'docker-compose.prod.yml').read_bytes())
            for name in REFS:
                ref = config['services'][name]['image']
                self.images[name] = IDS[name] if ref == REFS[name] else ref
            if self.migrate:
                self.history = '[{"version":"180"}]'
            if self.wrong_image and self.images['server'] == IDS['server']:
                self.images['server'] = 'unexpected-image'
        if args[0] == 'curl':
            return json.dumps({'status': 'DOWN' if self.unhealthy else 'UP'})
        return ''

    def activate(self):
        return native.activate(self.base, self.release, COMMIT, self.checksum, self.run_command)

    def test_verified_apps_activate_without_restarting_database(self):
        result = self.activate()
        self.assertEqual(result['status'], 'PASS')
        self.assertEqual(self.images, dict(IDS, postgres='old-postgres'))
        up = next(args for args in self.commands if 'up' in args)
        self.assertEqual(up[up.index('up'):], ['up', '-d', '--no-deps', '--pull', 'never', 'server', 'web'])
        installed = json.loads((self.base / 'docker-compose.prod.yml').read_bytes())
        self.assertEqual(installed['services']['server']['environment']['SECRET'], 'literal$$TOKEN')
        self.assertEqual((self.release / 'env-before').read_bytes(), (self.base / '.env').read_bytes())
        self.assertEqual((self.release / 'compose-before.yml').read_bytes(), self.old)

    def test_empty_environment_is_exported_when_original_program_starts(self):
        config = {'services': {'server': {'environment': {'EMPTY': '', 'SECRET': '$literal'}}}}
        image = {'Entrypoint': ['/bin/sh', '-c'], 'Cmd': ['test "' + '$' + '{EMPTY+x}" = x && test -z "$EMPTY" && printf "%s" "$SECRET"']}
        rendered = json.loads(native.runtime_config(config, {'server': REFS['server']}, {'server': image}))
        service = rendered['services']['server']
        env = dict(os.environ, EMPTY='inherited-value')
        env.update({key: value.replace('$$', '$') for key, value in service['environment'].items()})
        args = [part.replace('$$', '$') for part in service['entrypoint'] + service['command']]
        result = subprocess.run(args, env=env, check=True, capture_output=True, text=True)
        self.assertEqual(result.stdout, '$literal')

    @patch.object(native.time, 'sleep')
    def test_health_failure_restores_images_when_schema_is_unchanged(self, _):
        self.unhealthy = True
        with self.assertRaisesRegex(RuntimeError, 'healthy'):
            self.activate()
        self.assertEqual((self.base / 'docker-compose.prod.yml').read_bytes(), self.old)
        self.assertEqual(self.images, {name: 'old-' + name for name in ('server', 'web', 'postgres')})
        self.assertEqual(json.loads((self.release / 'activation.json').read_bytes())['status'], 'ROLLED_BACK')

    @patch.object(native.time, 'sleep')
    def test_health_failure_does_not_restart_old_app_after_migration(self, _):
        self.unhealthy = self.migrate = True
        with self.assertRaisesRegex(RuntimeError, 'healthy'):
            self.activate()
        self.assertEqual(sum('up' in args for args in self.commands), 1)
        self.assertEqual(json.loads((self.release / 'activation.json').read_bytes())['status'], 'RECOVERY_REQUIRED')

    def test_healthy_response_cannot_accept_an_unverified_image(self):
        self.wrong_image = True
        with self.assertRaisesRegex(RuntimeError, 'images'):
            self.activate()
        self.assertEqual((self.base / 'docker-compose.prod.yml').read_bytes(), self.old)
        self.assertEqual(json.loads((self.release / 'activation.json').read_bytes())['status'], 'ROLLED_BACK')

    def test_compose_checksum_failure_cannot_change_running_apps(self):
        (self.release / 'docker-compose.prod.yml').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'checksum'):
            self.activate()
        self.assertEqual(self.commands, [])
        self.assertEqual((self.base / 'docker-compose.prod.yml').read_bytes(), self.old)


if __name__ == '__main__':
    unittest.main()

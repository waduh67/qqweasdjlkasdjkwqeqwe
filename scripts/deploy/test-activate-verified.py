import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('release', Path(__file__).with_name('activate-verified.py'))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
COMMIT = 'a' * 40
REFS = {name: 'ghcr.io/team/ftth-' + name + '@sha256:' + char * 64 for name, char in [('server', 'b'), ('web', 'c')]}
IDS = {name: 'sha256:' + char * 64 for name, char in [('server', 'd'), ('web', 'e')]}


class ActivationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.folder = self.base / 'releases' / COMMIT
        self.folder.mkdir(parents=True)
        (self.base / 'ftth-compose').touch()
        self.old = release.encode({'services': {'web': {'image': 'old-web', 'pull_policy': 'never'}, 'caddy': {'image': 'old-caddy'}}})
        (self.base / 'docker-compose.images.json').write_bytes(self.old)
        (self.folder / 'published.json').write_bytes(release.encode({'commit': COMMIT, 'images': {key: {'registryDigest': ref} for key, ref in REFS.items()}}))
        self.commands = []
        self.images = {'server': 'old-server', 'web': 'old-web', 'caddy': 'old-caddy'}
        self.fail_health = False
        self.foreign_change = False

    def run_command(self, args):
        self.commands.append(args)
        if args[:3] == ['docker', 'ps', '-aq']:
            return '\n'.join(self.images)
        if args[:2] == ['docker', 'inspect']:
            return json.dumps([{'Id': key + '-id', 'Name': '/' + key, 'Image': value, 'RestartCount': 0,
                               'State': {'Running': True, 'StartedAt': 'same'},
                               'Config': {'Labels': {'com.docker.compose.project': 'ftth', 'com.docker.compose.service': key}}}
                              for key, value in self.images.items()])
        if args[:3] == ['docker', 'image', 'inspect']:
            return IDS[next(key for key, value in REFS.items() if value == args[3])]
        if 'up' in args:
            pins = json.loads((self.base / 'docker-compose.images.json').read_bytes())
            for key in ('server', 'web'):
                image = pins['services'].get(key, {}).get('image', 'old-' + key)
                self.images[key] = IDS[key] if image == REFS[key] else image
        if 'exec' in args:
            if self.foreign_change:
                (self.base / 'docker-compose.images.json').write_bytes(b'{"operator":"changed"}')
            return json.dumps({'status': 'DOWN' if self.fail_health else 'UP'})
        return ''

    def test_updates_only_app_pins_and_keeps_proxy_topology(self):
        result = release.activate(self.base, self.folder, COMMIT, self.run_command)
        self.assertEqual(result['status'], 'PASS')
        pins = json.loads((self.base / 'docker-compose.images.json').read_bytes())
        self.assertEqual(pins['services']['caddy'], {'image': 'old-caddy'})
        self.assertEqual(pins['services']['server']['environment']['FTTH_PRODUCTION'], '${FTTH_PRODUCTION:-true}')
        self.assertIn('FTTH_SITE_ADDRESS', pins['services']['server']['environment'])
        up = next(args for args in self.commands if 'up' in args)
        self.assertEqual(up[1:], ['up', '-d', '--no-deps', '--pull', 'never', 'server', 'web'])
        self.assertEqual(self.images, dict(IDS, caddy='old-caddy'))
        self.assertEqual((self.folder / 'pins-before.json').read_bytes(), self.old)

    @patch.object(release.time, 'sleep')
    def test_unhealthy_backend_restores_original_pins_and_images(self, _):
        self.fail_health = True
        with self.assertRaisesRegex(RuntimeError, 'healthy'):
            release.activate(self.base, self.folder, COMMIT, self.run_command)
        self.assertEqual((self.base / 'docker-compose.images.json').read_bytes(), self.old)
        self.assertEqual(self.images, {'server': 'old-server', 'web': 'old-web', 'caddy': 'old-caddy'})
        self.assertEqual(json.loads((self.folder / 'activation.json').read_bytes())['status'], 'ROLLED_BACK')

    @patch.object(release.time, 'sleep')
    def test_foreign_pin_changes_are_not_overwritten_on_failure(self, _):
        self.fail_health = self.foreign_change = True
        with self.assertRaisesRegex(AssertionError, 'foreign pin'):
            release.activate(self.base, self.folder, COMMIT, self.run_command)
        self.assertEqual(json.loads((self.base / 'docker-compose.images.json').read_bytes()), {'operator': 'changed'})
        self.assertEqual(json.loads((self.folder / 'activation.json').read_bytes())['status'], 'ROLLBACK_REQUIRES_ATTENTION')

    def test_unverified_or_mutable_references_cannot_change_pins(self):
        proof = json.loads((self.folder / 'published.json').read_bytes())
        for wrong in [dict(proof, commit='f' * 40), dict(proof, images={'web': {'registryDigest': 'ghcr.io/team/ftth-web:latest'}})]:
            (self.folder / 'published.json').write_bytes(release.encode(wrong))
            with self.assertRaises(AssertionError):
                release.activate(self.base, self.folder, COMMIT, self.run_command)
            self.assertEqual((self.base / 'docker-compose.images.json').read_bytes(), self.old)
            self.assertEqual(self.commands, [])

    def test_foreign_pin_changes_prevent_success_even_when_apps_are_healthy(self):
        self.foreign_change = True
        with self.assertRaisesRegex(AssertionError, 'foreign pin'):
            release.activate(self.base, self.folder, COMMIT, self.run_command)
        self.assertEqual(json.loads((self.base / 'docker-compose.images.json').read_bytes()), {'operator': 'changed'})
        self.assertEqual(json.loads((self.folder / 'activation.json').read_bytes())['status'], 'ROLLBACK_REQUIRES_ATTENTION')
        self.assertEqual(sum('up' in args for args in self.commands), 1)


if __name__ == '__main__':
    unittest.main()

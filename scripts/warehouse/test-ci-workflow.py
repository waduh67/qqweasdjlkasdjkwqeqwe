"""Check release dependencies and execute the acceptance gate with failing prerequisites."""
import json
import fnmatch
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
REQUIRED = {"server", "web", "shared", "browser", "legacy-upgrade", "native", "images"}


def dependencies(job):
    value = job.get("needs", [])
    return {value} if isinstance(value, str) else set(value)


class WorkflowGateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.warehouse = yaml.safe_load((ROOT / ".github/workflows/warehouse.yml").read_text())["jobs"]
        cls.deploy = yaml.safe_load((ROOT / ".github/workflows/deploy.yml").read_text())["jobs"]
        cls.images = yaml.safe_load((ROOT / ".github/workflows/images.yml").read_text())["jobs"]
        cls.ci = yaml.safe_load((ROOT / ".github/workflows/ci.yml").read_text())["jobs"]

    def test_publish_requires_routine_checks_and_tested_images(self):
        self.assertEqual(self.deploy["verification"]["uses"], "./.github/workflows/ci.yml")
        self.assertEqual(self.deploy["image-verification"]["uses"], "./.github/workflows/images.yml")
        self.assertEqual(dependencies(self.deploy["build-and-push"]), {"verification", "image-verification"})
        self.assertEqual(dependencies(self.deploy["deploy"]), {"build-and-push"})
        for job in ("build-and-push", "deploy", "verification", "image-verification"):
            self.assertNotIn("if", self.deploy[job])
        self.assertFalse(any(job.get("uses") == "./.github/workflows/warehouse.yml"
                             for job in self.deploy.values()))
        self.assertEqual(self.warehouse["images"]["uses"], "./.github/workflows/images.yml")
        self.assertEqual(dependencies(self.warehouse["acceptance"]), REQUIRED)

    def test_simulator_only_changes_do_not_skip_production_code_releases(self):
        workflow = yaml.safe_load((ROOT / '.github/workflows/deploy.yml').read_text())
        triggers = workflow.get('on', workflow.get(True, {}))
        ignored = triggers['push']['paths-ignore']
        def excluded(path):
            return any(fnmatch.fnmatchcase(path, pattern) for pattern in ignored)
        for path in ('server/src/main/kotlin/App.kt', 'web/src/App.tsx', 'deploy/docker-compose.prod.yml',
                     '.github/workflows/deploy.yml', 'gradle.properties'):
            self.assertFalse(excluded(path), path)
        for path in ('deploy/simulator/install_release.py', 'docker-compose.simulator.yml',
                     '.github/workflows/deploy-simulator.yml'):
            self.assertTrue(excluded(path), path)

    def test_warehouse_runs_only_on_manual_requests(self) -> None:
        workflow = yaml.safe_load((ROOT / ".github/workflows/warehouse.yml").read_text())
        self.assertEqual(set(workflow.get("on", workflow.get(True, {}))), {"workflow_dispatch"})

    def test_native_compilation_automatically_runs_only_for_mobile_changes(self) -> None:
        workflow = yaml.safe_load((ROOT / ".github/workflows/mobile-materials.yml").read_text())
        triggers = workflow.get("on", workflow.get(True, {}))
        self.assertEqual(set(triggers), {"pull_request", "workflow_dispatch", "workflow_call"})
        self.assertEqual(triggers["pull_request"]["branches"], ["main"])
        paths = triggers["pull_request"]["paths"]
        self.assertIn("mobile/**", paths)
        self.assertNotIn("web/**", paths)
        self.assertNotIn("server/**", paths)

    def test_routine_web_checks_keep_all_unit_tests_with_bounded_workers(self) -> None:
        runs = [step["run"] for step in self.ci["web"]["steps"] if "run" in step]
        self.assertEqual(runs, ["npm ci", "npm run lint", "npm test -- --maxWorkers=2", "npm run build"])

    def test_no_required_gate_can_be_ignored_or_conditionally_skipped(self):
        for name in REQUIRED:
            self.assertNotIn("if", self.warehouse[name])
        def inspect(node):
            if isinstance(node, dict):
                self.assertNotIn("continue-on-error", node)
                for value in node.values():
                    inspect(value)
            elif isinstance(node, list):
                for value in node:
                    inspect(value)
        inspect(self.warehouse)
        inspect(self.deploy)
        inspect(self.images)
        inspect(self.ci)
        for jobs in (self.images, self.ci):
            for job in jobs.values():
                self.assertNotIn("if", job)

    def test_all_browser_scenarios_and_native_compilation_are_required(self):
        matrix = self.warehouse["browser"]["strategy"]
        self.assertFalse(matrix["fail-fast"])
        self.assertEqual(set(matrix["matrix"]["spec"]),
                         {"setup", "receiving", "provenance", "issue", "returns", "exceptions", "warehouse-empty-tenant", "customer-assets", "draft-expiry"})
        self.assertEqual(self.warehouse["native"]["uses"], "./.github/workflows/mobile-materials.yml")
        full = next(step for step in self.warehouse["server"]["steps"]
                    if step.get("name") == "Full server, fresh migrations and historical upgrade")
        self.assertNotIn("if", full)
        self.assertIn("scripts/warehouse/qa.sh server >", full["run"])
        self.assertNotIn("--tests", full["run"])

    def test_actual_acceptance_script_rejects_failure_skip_cancel_and_missing_gate(self):
        script = self.warehouse["acceptance"]["steps"][0]["run"]
        good = {name: {"result": "success"} for name in REQUIRED}
        def execute(results):
            return subprocess.run(["bash", "-e", "-c", script],
                                  env=dict(os.environ, GATE_RESULTS=json.dumps(results)), capture_output=True).returncode
        self.assertEqual(execute(good), 0)
        for name in sorted(REQUIRED):
            for state in ("failure", "skipped", "cancelled"):
                with self.subTest(gate=name, state=state):
                    changed = good | {name: {"result": state}}
                    self.assertNotEqual(execute(changed), 0)
            with self.subTest(missing=name):
                self.assertNotEqual(execute({key: value for key, value in good.items() if key != name}), 0)

    def test_publication_consumes_tested_images_without_rebuilding_them(self):
        steps = self.deploy["build-and-push"]["steps"]
        downloader = next(step for step in steps if step.get("uses", "").startswith("actions/download-artifact@"))
        self.assertEqual(downloader["with"]["name"], "warehouse-tested-images-${{ github.sha }}")
        publisher = next(step for step in steps if "publish-images.py" in step.get("run", ""))
        self.assertNotIn("if", publisher)
        self.assertLess(steps.index(downloader), steps.index(publisher))
        for step in steps:
            if step.get("uses", "").startswith("docker/build-push-action@"):
                self.assertEqual(step["with"]["context"], "./docker/genieacs")
        image_steps = self.images["images"]["steps"]
        builds = [step for step in image_steps if step.get("uses", "").startswith("docker/build-push-action@")]
        self.assertEqual(len(builds), 2)
        for step in builds:
            self.assertTrue(step["with"]["load"])
            self.assertFalse(step["with"].get("push", False))
        smoke = next(step for step in image_steps if "image-smoke.sh" in step.get("run", ""))
        save = next(step for step in image_steps if "docker save" in step.get("run", ""))
        self.assertLess(image_steps.index(smoke), image_steps.index(save))
        upload = next(step for step in image_steps if step.get("with", {}).get("name", "").startswith("warehouse-tested-images-"))
        self.assertNotIn("if", upload)
        self.assertLess(image_steps.index(save), image_steps.index(upload))
        ssh = next(step for step in self.deploy["deploy"]["steps"] if step.get("name") == "Deploy lewat SSH")
        deploy_script = ssh["run"]
        self.assertIn("IMAGE_TAG='$GITHUB_SHA'", deploy_script)
        self.assertTrue({"FTTH_SERVER_IMAGE", "FTTH_WEB_IMAGE", "FTTH_COMPOSE_SHA256"}.issubset(ssh["env"]))
        self.assertIn("cloudflared access ssh", str(self.deploy["deploy"]["steps"]))
        self.assertNotIn("--remove-orphans", deploy_script)
        self.assertIn("sha256sum --check --status", deploy_script)
        self.assertIn('--project-directory /opt/ftth --env-file /opt/ftth/.env -f "$release_compose"', deploy_script)
        compose = (ROOT / "deploy/docker-compose.prod.yml").read_text()
        self.assertIn("image: ${FTTH_SERVER_IMAGE:-", compose)
        self.assertIn("image: ${FTTH_WEB_IMAGE:-", compose)

    def test_topology_detection_failures_cannot_enter_native_deployment(self):
        script = next(step['run'] for step in self.deploy['deploy']['steps'] if step.get('name') == 'Deploy lewat SSH')
        with tempfile.TemporaryDirectory() as folder:
            stub = Path(folder) / 'ssh'
            calls = Path(folder) / 'calls'
            stub.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "$SSH_CALLS"\nexit "$SSH_EXIT"\n')
            stub.chmod(0o700)
            for status in ('1', '255'):
                with self.subTest(ssh_status=status):
                    calls.unlink(missing_ok=True)
                    result = subprocess.run(['bash', '-c', script], capture_output=True,
                                            env=dict(os.environ, PATH=folder + os.pathsep + os.environ['PATH'],
                                                     SSH_CALLS=str(calls), SSH_EXIT=status, VPS_HOST='test-host', GHCR_USER='test-user'))
                    self.assertNotEqual(result.returncode, 0)
                    self.assertEqual(calls.read_text().splitlines(), ['test-host bash -s'])

    def test_native_deploy_does_not_require_passwordless_sudo(self):
        script = next(step['run'] for step in self.deploy['deploy']['steps'] if step.get('name') == 'Deploy lewat SSH')
        with tempfile.TemporaryDirectory() as folder:
            stub = Path(folder) / 'ssh'
            calls = Path(folder) / 'calls'
            stub.write_text(
                '#!/bin/sh\n'
                'printf "%s\\n" "$*" >> "$SSH_CALLS"\n'
                'case "$*" in *sudo*) echo "sudo: interactive authentication is required" >&2; exit 1;; esac\n'
                'if [ "$2" = "bash -s" ]; then cat >/dev/null; printf "native\\n"; fi\n'
            )
            stub.chmod(0o700)
            result = subprocess.run(
                ['bash', '-c', script], capture_output=True, text=True,
                env=dict(os.environ, PATH=folder + os.pathsep + os.environ['PATH'],
                         SSH_CALLS=str(calls), VPS_HOST='test-host', GHCR_USER='test-user',
                         GHCR_PAT='fixture-token', GITHUB_SHA='a' * 40,
                         FTTH_SERVER_IMAGE='ghcr.io/team/ftth-server@sha256:' + 'b' * 64,
                         FTTH_WEB_IMAGE='ghcr.io/team/ftth-web@sha256:' + 'c' * 64,
                         FTTH_COMPOSE_SHA256='d' * 64),
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            commands = calls.read_text().splitlines()
            self.assertEqual(len(commands), 5)
            self.assertEqual(commands[0], 'test-host bash -s')
            self.assertIn('login ghcr.io', commands[2])
            self.assertIn("IMAGE_TAG='" + 'a' * 40 + "'", commands[3])
            self.assertIn('registry-auth', commands[4])

    def test_registry_credentials_use_the_scoped_actions_token(self):
        job = self.deploy['deploy']
        step = next(step for step in job['steps'] if step.get('name') == 'Deploy lewat SSH')
        self.assertEqual(job['permissions']['packages'], 'read')
        self.assertEqual(step['env']['GHCR_PAT'], '${{ secrets.GITHUB_TOKEN }}')
        self.assertEqual(step['env']['GHCR_USER'], '${{ github.actor }}')


if __name__ == "__main__":
    unittest.main()

"""Check release dependencies and execute the acceptance gate with failing prerequisites."""
import json
import os
import subprocess
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

    def test_publish_and_deploy_require_the_entire_reusable_warehouse_workflow(self):
        self.assertEqual(self.deploy["warehouse-verification"]["uses"], "./.github/workflows/warehouse.yml")
        self.assertEqual(dependencies(self.deploy["build-and-push"]), {"warehouse-verification"})
        self.assertEqual(dependencies(self.deploy["deploy"]), {"build-and-push"})
        for job in ("build-and-push", "deploy", "warehouse-verification"):
            self.assertNotIn("if", self.deploy[job])
        self.assertEqual(dependencies(self.warehouse["acceptance"]), REQUIRED)

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

    def test_all_browser_scenarios_and_native_compilation_are_required(self):
        matrix = self.warehouse["browser"]["strategy"]
        self.assertFalse(matrix["fail-fast"])
        self.assertEqual(set(matrix["matrix"]["spec"]),
                         {"setup", "receiving", "provenance", "issue", "returns", "exceptions", "warehouse-empty-tenant", "customer-assets"})
        self.assertEqual(self.warehouse["native"]["uses"], "./.github/workflows/mobile-materials.yml")
        commands = "\n".join(step.get("run", "") for step in self.warehouse["server"]["steps"])
        self.assertIn("scripts/warehouse/qa.sh server >", commands)
        self.assertNotIn("--tests", commands)

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
        image_steps = self.warehouse["images"]["steps"]
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
        ssh = next(step for step in self.deploy["deploy"]["steps"] if step.get("uses", "").startswith("appleboy/ssh-action@"))
        deploy_script = ssh["with"]["script"]
        self.assertIn("export IMAGE_TAG='${{ github.sha }}'", deploy_script)
        self.assertEqual(ssh["with"]["envs"], "FTTH_SERVER_IMAGE,FTTH_WEB_IMAGE,FTTH_COMPOSE_SHA256")
        self.assertIn("sha256sum --check --status", deploy_script)
        self.assertIn('--project-directory /opt/ftth --env-file /opt/ftth/.env -f "$release_compose"', deploy_script)
        compose = (ROOT / "deploy/docker-compose.prod.yml").read_text()
        self.assertIn("image: ${FTTH_SERVER_IMAGE:-", compose)
        self.assertIn("image: ${FTTH_WEB_IMAGE:-", compose)


if __name__ == "__main__":
    unittest.main()

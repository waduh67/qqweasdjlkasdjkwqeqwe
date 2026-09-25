"""Check release dependencies and execute the acceptance gate with failing prerequisites."""
import json
import os
import subprocess
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
REQUIRED = {"server", "web", "shared", "browser", "legacy-upgrade", "native"}


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


if __name__ == "__main__":
    unittest.main()

"""Negative release-gate checks; these fixtures contain no business or credential data."""
import copy
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("ci-results.py")
SPEC = importlib.util.spec_from_file_location("warehouse_ci_results", SCRIPT)
RESULTS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RESULTS)
PROJECTS = ["warehouse-desktop", "warehouse-mobile"]


class ReleaseResultsTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def xml(self, tests=1, failures=0, errors=0, skipped=0, cases='<testcase name="guard"/>'):
        self.root.joinpath("TEST-Guard.xml").write_text(
            f'<testsuite name="warehouse.Guard" tests="{tests}" failures="{failures}" '
            f'errors="{errors}" skipped="{skipped}">{cases}</testsuite>')

    def browser(self, report=None):
        if report is None:
            report = {"stats": {"expected": 2, "unexpected": 0, "skipped": 0, "flaky": 0},
                      "suites": [{"specs": [{"ok": True, "tests": [
                          {"projectName": project, "status": "expected", "results": [{"status": "passed"}]}
                          for project in PROJECTS]}]}]}
        self.root.joinpath("browser.json").write_text(json.dumps(report))
        return copy.deepcopy(report)

    def assertBrowserRejected(self, report):
        self.browser(report)
        with self.assertRaises(RESULTS.InvalidResults):
            RESULTS.playwright(self.root / "browser.json", PROJECTS)

    def test_success_requires_actual_cases_in_both_browser_projects(self):
        self.xml()
        self.assertEqual(RESULTS.junit(self.root)[1]["tests"], 1)
        self.browser()
        self.assertEqual(RESULTS.playwright(self.root / "browser.json", PROJECTS)[1]["projects"],
                         {project: 1 for project in PROJECTS})

    def test_missing_browser_or_junit_reports_block_the_gate(self):
        with self.assertRaises(RESULTS.InvalidResults):
            RESULTS.junit(self.root)
        with self.assertRaises(RESULTS.InvalidResults):
            RESULTS.playwright(self.root / "missing.json", PROJECTS)

    def test_kotlin_multiplatform_suite_preserves_its_target_suffix(self):
        self.xml()
        path = self.root / "TEST-Guard.xml"
        path.write_text(path.read_text().replace('name="warehouse.Guard"', 'name="MaterialQuantityTest[jvm]"'))
        self.assertEqual(RESULTS.junit(self.root)[1]["suites"][0]["suite"], "MaterialQuantityTest[jvm]")

    def test_zero_tests_block_the_gate(self):
        self.xml(tests=0, cases="")
        with self.assertRaises(RESULTS.InvalidResults):
            RESULTS.junit(self.root)
        self.assertBrowserRejected({"stats": {"expected": 0, "unexpected": 0, "skipped": 0, "flaky": 0}})

    def test_failed_migration_assertion_and_skipped_tests_block_the_gate(self):
        for counts, child in [({"failures": 1}, "failure"), ({"errors": 1}, "error"), ({"skipped": 1}, "skipped")]:
            with self.subTest(child=child):
                self.xml(**counts, cases=f'<testcase name="migration"><{child}/></testcase>')
                with self.assertRaises(RESULTS.InvalidResults):
                    RESULTS.junit(self.root)

    def test_success_headers_cannot_hide_missing_or_failed_cases(self):
        for cases in ("", '<testcase name="migration"><failure/></testcase>'):
            with self.subTest(cases=cases):
                self.xml(cases=cases)
                with self.assertRaises(RESULTS.InvalidResults):
                    RESULTS.junit(self.root)

    def test_browser_failure_skip_flakiness_and_retry_block_the_gate(self):
        for key in ("unexpected", "skipped", "flaky"):
            with self.subTest(key=key):
                report = self.browser()
                report["stats"][key] = 1
                self.assertBrowserRejected(report)
        report = self.browser()
        report["suites"][0]["specs"][0]["tests"][0]["results"].append({"status": "passed"})
        self.assertBrowserRejected(report)

    def test_missing_mobile_execution_and_inconsistent_totals_block_the_gate(self):
        report = self.browser()
        report["suites"][0]["specs"][0]["tests"].pop()
        self.assertBrowserRejected(report)
        report["stats"]["expected"] = 1
        self.assertBrowserRejected(report)

    def test_global_browser_error_blocks_an_apparently_green_report(self):
        report = self.browser()
        report["errors"] = [{"message": "private diagnostic"}]
        self.assertBrowserRejected(report)

    def test_vitest_requires_all_declared_cases_to_execute_successfully(self):
        valid = {"success": True, "numTotalTests": 1, "numPassedTests": 1,
                 "testResults": [{"assertionResults": [{"status": "passed"}]}]}
        source = self.root / "vitest.json"
        source.write_text(json.dumps(valid))
        self.assertEqual(RESULTS.vitest(source)[1]["tests"], 1)
        for delta in ({"success": False}, {"numTotalTests": 0}, {"numPendingTests": 1},
                      {"numPassedTests": 0}, {"testResults": []}):
            with self.subTest(delta=delta):
                source.write_text(json.dumps(valid | delta))
                with self.assertRaises(RESULTS.InvalidResults):
                    RESULTS.vitest(source)

    def test_public_proof_omits_raw_output_and_error_details(self):
        marker = "PRIVATE_HTTP_BODY_MUST_NOT_BE_PUBLISHED"
        self.xml(cases=f'<testcase name="guard"/><system-out>{marker}</system-out>')
        output = self.root / "proof.json"
        command = [sys.executable, str(SCRIPT), "junit", "--source", str(self.root),
                   "--output", str(output), "--commit", "a" * 40]
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0)
        self.assertNotIn(marker, output.read_text() + result.stdout + result.stderr)
        self.assertEqual(json.loads(output.read_text())["status"], "PASSED")
        self.xml(failures=1, cases=f'<testcase name="guard"><failure>{marker}</failure></testcase>')
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn(marker, output.read_text() + result.stdout + result.stderr)
        self.assertEqual(json.loads(output.read_text())["status"], "FAILED")


if __name__ == "__main__":
    unittest.main()

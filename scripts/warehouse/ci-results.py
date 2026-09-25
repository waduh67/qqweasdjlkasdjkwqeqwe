#!/usr/bin/env python3
"""Publish allowlisted test counts and hashes; raw HTTP/XML/trace data stays private."""
import argparse
import hashlib
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


class InvalidResults(Exception):
    pass


def require(condition, reason):
    if not condition:
        raise InvalidResults(reason)


def integer(value):
    require(isinstance(value, (int, str)) and not isinstance(value, bool), "Invalid test count")
    try:
        count = int(value)
    except ValueError as error:
        raise InvalidResults("Invalid test count") from error
    require(str(count) == str(value) and count >= 0, "Invalid test count")
    return count


def digest(path):
    checksum = hashlib.sha256()
    with path.open("rb") as content:
        while block := content.read(1024 * 1024):
            checksum.update(block)
    return checksum.hexdigest()


def junit(source):
    files = sorted(source.rglob("TEST-*.xml"))
    require(files, "JUnit reports are missing")
    suites = []
    for path in files:
        root = ET.parse(path).getroot()
        require(root.tag == "testsuite", "Unsupported JUnit report")
        name = root.attrib.get("name", "")
        require(re.fullmatch(r"[A-Za-z0-9_.$:-]+(?:\[[A-Za-z0-9_-]+\])?", name), "Invalid JUnit suite identity")
        counts = {key: integer(root.attrib.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}
        require(counts["tests"] > 0, "A JUnit suite executed zero tests")
        require(counts["failures"] == counts["errors"] == counts["skipped"] == 0,
                "JUnit contains failures, errors, or skipped tests")
        cases = root.findall("testcase")
        require(len(cases) == counts["tests"] and all(
            case.find("failure") is None and case.find("error") is None and case.find("skipped") is None
            for case in cases), "JUnit totals do not match successful executed cases")
        suites.append({"suite": name, **counts})
    return files, {"tests": sum(suite["tests"] for suite in suites), "suites": suites}


def playwright(source, projects):
    require(source.is_file(), "Playwright report is missing")
    report = json.loads(source.read_text())
    require(not report.get("errors"), "Playwright reported a global error")
    stats = report.get("stats", {})
    expected = integer(stats.get("expected", 0))
    require(expected > 0, "Playwright executed zero successful tests")
    require(all(integer(stats.get(key, -1)) == 0 for key in ("unexpected", "skipped", "flaky")),
            "Playwright contains failures, skips, or flaky tests")
    counts = {}

    def visit(suite):
        for spec in suite.get("specs", []):
            require(spec.get("ok") is True, "A Playwright specification did not pass")
            for test in spec.get("tests", []):
                project = test.get("projectName", "")
                results = test.get("results", [])
                require(project in projects, "Unexpected Playwright project")
                require(test.get("status") == "expected" and len(results) == 1
                        and results[0].get("status") == "passed", "Playwright test did not pass without retries")
                counts[project] = counts.get(project, 0) + 1
        for child in suite.get("suites", []):
            visit(child)

    visit(report)
    require(sum(counts.values()) == expected, "Playwright totals do not match executed cases")
    require(all(counts.get(project, 0) > 0 for project in projects), "A required browser project executed no tests")
    return [source], {"tests": expected, "projects": counts}


def vitest(source):
    require(source.is_file(), "Vitest report is missing")
    report = json.loads(source.read_text())
    total = integer(report.get("numTotalTests", 0))
    require(report.get("success") is True and total > 0, "Vitest did not execute a successful test suite")
    require(all(integer(report.get(key, 0)) == 0 for key in
                ("numFailedTests", "numPendingTests", "numTodoTests", "numFailedTestSuites", "numPendingTestSuites")),
            "Vitest contains failures, skips, or unfinished tests")
    cases = [case for suite in report.get("testResults", []) for case in suite.get("assertionResults", [])]
    require(integer(report.get("numPassedTests", 0)) == total == len(cases)
            and all(case.get("status") == "passed" for case in cases), "Vitest totals do not match executed cases")
    return [source], {"tests": total, "testFiles": len(report["testResults"])}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=("junit", "playwright", "vitest"))
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--project", action="append")
    parser.add_argument("--artifact", type=Path, help="Optional exact application artifact whose bytes were tested")
    args = parser.parse_args()
    proof = {"kind": args.kind, "status": "FAILED"}
    try:
        require(re.fullmatch(r"[a-f0-9]{40}", args.commit), "A full commit identity is required")
        proof["commit"] = args.commit
        handlers = {"junit": lambda: junit(args.source), "vitest": lambda: vitest(args.source),
                    "playwright": lambda: playwright(args.source, args.project or ["warehouse-desktop", "warehouse-mobile"])}
        files, counts = handlers[args.kind]()
        proof.update(counts)
        # Only hashes of source reports are public. No system-out, HTTP response,
        # error message, attachment, login, or trace is copied into the proof.
        proof["reportSha256"] = [digest(path) for path in files]
        if args.artifact:
            require(args.artifact.is_file(), "The tested application artifact is missing")
            proof["artifactSha256"] = digest(args.artifact)
        proof["status"] = "PASSED"
    except InvalidResults as error:
        proof["reason"] = str(error)
    except (OSError, ValueError, ET.ParseError, TypeError, AttributeError, KeyError):
        proof["reason"] = "Missing or malformed test results"
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(proof, indent=2) + "\n")
    print(f"{proof['status']}: {args.kind} verification")
    return 0 if proof["status"] == "PASSED" else 1


if __name__ == "__main__":
    sys.exit(main())

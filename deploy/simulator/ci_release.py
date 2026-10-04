#!/usr/bin/env python3
"""Resolve tested application images and assemble immutable simulator releases."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys

from install_release import check_manifest


def gh(*args):
    return json.loads(subprocess.check_output(["gh", *args]))


def application():
    run_id = os.environ.get("APPLICATION_RUN", "")
    if not run_id:
        runs = gh("run", "list", "--workflow", "deploy.yml", "--branch", "main", "--status", "success", "--limit", "1", "--json", "databaseId")
        if not runs:
            raise ValueError("No successful application release exists")
        run_id = str(runs[0]["databaseId"])
    if not run_id.isdigit():
        raise ValueError("Invalid application run ID")
    run = gh("api", f"repos/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{run_id}")
    if run["conclusion"] != "success" or run["head_branch"] != "main" or run["path"] != ".github/workflows/deploy.yml":
        raise ValueError("Application images must come from a successful main deployment")
    commit = run["head_sha"]
    if not re.fullmatch(r"[a-f0-9]{40}", commit):
        raise ValueError("Invalid application commit")
    subprocess.run(["git", "merge-base", "--is-ancestor", commit, "HEAD"], check=True)
    subprocess.run(["git", "diff", "--exit-code", commit, "HEAD", "--", "server", "web", "contract", "snmp", "collector", "build-logic", "gradle", "*.gradle.kts", "gradle.properties"], check=True)
    subprocess.run(["gh", "run", "download", run_id, "--name", "warehouse-published-images-" + commit, "--dir", "application"], check=True)
    proof = json.loads(Path("application/published.json").read_text())
    if proof["commit"] != commit or set(proof["images"]) != {"server", "web"}:
        raise ValueError("Application proof does not match its run")
    for service, image in proof["images"].items():
        prefix = re.escape("ghcr.io/" + os.environ["GITHUB_REPOSITORY_OWNER"].lower() + "/ftth-" + service)
        if not re.fullmatch(prefix + r"@sha256:[a-f0-9]{64}", image["registryDigest"]):
            raise ValueError("Unexpected application image")
    Path("application/run.json").write_text(json.dumps({"id": run_id, "url": run["html_url"], "commit": commit}))


def manifest():
    proof = json.loads(next(Path("artifacts").glob("simulator-application-*/published.json")).read_text())
    components = {}
    for file in Path("artifacts").glob("simulator-image-*/image.json"):
        components.update(json.loads(file.read_text()))
    if set(components) != {"agent", "storage", "acs", "cpe"}:
        raise ValueError("Incomplete simulator image build")
    images = {name: info["registryDigest"] for name, info in proof["images"].items()}
    images.update({"simulator": components["agent"], "minio": components["storage"],
                   "genieacs-cwmp": components["acs"], "genieacs-nbi": components["acs"], "genieacs-sim": components["cpe"]})
    result = {"commit": os.environ["GITHUB_SHA"], "applicationCommit": proof["commit"], "images": images}
    check_manifest(result)
    Path("manifest.json").write_text(json.dumps(result, indent=2))


if __name__ == "__main__":
    {"application": application, "manifest": manifest}[sys.argv[1]]()

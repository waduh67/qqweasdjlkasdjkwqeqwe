#!/usr/bin/env python3
"""Exercise packaged Docker images through the staging HTTP gateway."""
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
from urllib.error import HTTPError
from urllib.request import Request

import http_support


def main():
    http_support.check(os.environ.get("WAREHOUSE_QA") == "true", "Use image-smoke.sh")
    phase, directory = sys.argv[1:]
    run = Path(directory)
    http_support.check(run.parent == Path(__file__).resolve().parents[2] / ".omo/runtime"
                       and re.fullmatch(r"warehouse-image-[a-f0-9]{32}", run.name), "Expected owned image run")
    # Both HTML and API traffic traverse the same proxy used by this staging probe.
    http_support.BASE = "http://127.0.0.1:14188"
    with http_support.HTTP.open(http_support.BASE + "/") as response:
        http_support.check(response.status == 200 and response.headers.get_content_type() == "text/html",
                           "Web image did not serve its HTML bundle")
        html = response.read().decode()
    assets = re.findall(r'(?:src|href)="(/assets/[^"<>]+\.(?:js|css))"', html)
    http_support.check(any(asset.endswith(".js") for asset in assets), "Built JavaScript bundle missing")
    for asset in assets:
        with http_support.HTTP.open(http_support.BASE + asset) as response:
            http_support.check(response.status == 200 and response.headers.get_content_type() != "text/html"
                               and len(response.read()) > 0, "Static asset route returned HTML or empty content")
    headers = {"Content-Type": "application/json"}
    headers.update({f"X-Header-Limit-Probe-{index}": "a" * 4000 for index in range(3)})
    header_request = Request(http_support.BASE + "/api/auth/login", data=b'{"email":"","password":""}',
                             headers=headers, method="POST")
    try:
        header_response = http_support.HTTP.open(header_request, timeout=25)
    except HTTPError as failure:
        header_response = failure
    with header_response:
        http_support.check(header_response.status == 400
                           and header_response.headers.get_content_type() == "application/problem+json",
                           "12 KB request headers were rejected before application validation")
        header_problem = json.load(header_response)
    http_support.check({"email", "password"}.issubset(header_problem.get("errors", {})),
                       "Large-header request did not reach login validation")
    spec = importlib.util.spec_from_file_location("warehouse_wave5", Path(__file__).with_name("wave5-http.py"))
    journey = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(journey)
    state_path = run / "state.json"
    if phase == "before-restart":
        state = journey.before_restart()
        with state_path.open("x") as output:
            json.dump(state, output)
    elif phase == "after-restart":
        state = json.loads(state_path.read_text())
        journey.after_restart(state)
    else:
        raise ValueError("Unknown image smoke phase")
    proof = {"phase": phase, "staticAssets": len(assets), "persistedReplays": len(state["replays"]),
             "persistedReads": len(state["reads"]), "stockKinds": len(state["stocks"]),
             "realHttp": True, "sqlBusinessSeeding": False, "largeRequestHeaders": True}
    (run / f"{phase}.json").write_text(json.dumps(proof, indent=2) + "\n")
    print("PASS: image gateway serves the real bundle and warehouse HTTP journey")


if __name__ == "__main__":
    main()

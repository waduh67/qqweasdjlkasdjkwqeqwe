#!/usr/bin/env python3
"""Real HTTP assertions for qa.sh replenishment; no third-party Python packages."""
import json
import os
from pathlib import Path
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import ProxyHandler, Request, build_opener
from uuid import uuid4

BASE = "http://127.0.0.1:17880"
WAREHOUSE = "/api/v1/warehouse"
ROOT = WAREHOUSE + "/replenishments"
HTTP = build_opener(ProxyHandler({}))


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def request(method, path, token=None, body=None, key=None, expected=200, timeout=25):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if method != "GET":
        headers["Idempotency-Key"] = key or str(uuid4())
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    try:
        response = HTTP.open(Request(BASE + path, data, headers, method=method), timeout=timeout)
    except HTTPError as failure:
        response = failure
    with response:
        status = response.status
        check("application/json" in response.headers.get("Content-Type", ""), f"{method} {path}: non-JSON response")
        value = json.load(response)
    check(status == expected, f"{method} {path}: expected {expected}, got {status}; code={value.get('code') if isinstance(value, dict) else None}")
    return value


def ready():
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        try:
            health = request("GET", "/actuator/health/warehouse", timeout=2)
            components = health.get("components", {})
            if (health.get("status") == "UP" and set(components) == {"db", "diskSpace", "ping"}
                    and all(component.get("status") == "UP" for component in components.values())):
                return
        except (OSError, URLError, AssertionError, ValueError):
            pass
        time.sleep(1)
    raise AssertionError("Packaged server JSON readiness timed out")


def login(email):
    slug = email.split("@", 1)[1].removesuffix(".test")
    return request("POST", "/api/auth/login", body={"tenantSlug": slug, "email": email, "password": "secret12345"})["accessToken"]


def accept_body(suggestion):
    return {"expectedRevision": suggestion["revision"], "expectedRuleRevision": suggestion["ruleRevision"], "quantityBase": suggestion["quantityBase"]}


def stock(token, sku, location):
    return request("GET", f"{WAREHOUSE}/stock?skuId={sku}&locationId={location}", token)


def recompute(token, rule):
    return request("POST", f"{ROOT}/rules/{rule['id']}/recompute", token, {"expectedRevision": rule["revision"]})


def stale_receipt(seed):
    token = login(seed["staleEmail"])

    def master(resource, body):
        if resource == "locations":
            body["areaId"] = seed["staleArea"]
        return request("POST", f"{WAREHOUSE}/{resource}", token, body, expected=201)["id"]

    supplier = master("suppliers", {"code": "LIVE-SUP", "name": "Live supplier"})
    inspection = master("locations", {"code": "LIVE-QA", "name": "Live inspection", "kind": "QUARANTINE"})
    sku = master("skus", {"code": "LIVE-CABLE", "name": "Live cable", "tracking": "LOT", "baseUnit": "MM", "inspectionRequired": False})
    rule = request("POST", ROOT + "/rules", token, {
        "skuId": sku, "locationId": seed["staleLocation"], "baseUnit": "MM",
        "minimumBase": "50000", "maximumBase": "100000", "targetBase": "100000",
        "packageMultipleBase": "25000", "leadTimeDays": 7,
    })
    suggestion = recompute(token, rule)
    check(suggestion["quantityBase"] == "100000", "Empty stock should suggest 100000 MM")
    receipt = request("POST", WAREHOUSE + "/receipts", token, {
        "supplierId": supplier, "externalReference": "LIVE-RESTOCK",
        "sourceLocationId": seed["staleSource"], "inspectionLocationId": inspection,
        "lines": [{"skuId": sku, "quantityBase": "100000", "lotCode": "LIVE-LOT"}],
    }, expected=201)
    path = f"{WAREHOUSE}/receipts/{receipt['id']}"
    received = request("POST", path + "/receive", token, {"expectedRevision": receipt["revision"]})
    detail = request("GET", path, token)
    line = detail["lines"][0]
    request("POST", path + "/putaway", token, {
        "expectedRevision": received["revision"], "destinationLocationId": seed["staleLocation"],
        "lines": [{"lineId": line["id"], "stockIdentityId": line["pieces"][0]["stockIdentityId"], "baseUnit": "MM", "quantityBase": "100000"}],
    })
    before = stock(token, sku, seed["staleLocation"])
    check(before["items"][0]["available"]["quantityBase"] == "100000", "Real putaway must supply 100000 MM")
    failure = request("POST", f"{ROOT}/requests/{suggestion['id']}/accept", token, accept_body(suggestion), expected=409)
    check(failure["code"] == "STALE_REVISION", "Stale acceptance must return STALE_REVISION")
    check(stock(token, sku, seed["staleLocation"]) == before, "Stale acceptance changed physical stock")
    resolved = recompute(token, rule)
    check(resolved["state"] == "FULFILLED", "Restock must resolve the old window")
    print("PASS: real receipt/putaway supplies 100000 MM; stale acceptance returns 409 without changing stock")
    return {"sku": sku, "rule": rule, "stock": before, "resolved": resolved}


def before_restart(seed):
    token = login(seed["adminEmail"])
    check(request("GET", "/api/me", token)["tenantId"] == seed["tenant"], "Fixture tenant identity mismatch")
    before = stock(token, seed["skuId"], seed["locationId"])
    check(before["items"][0]["physical"]["quantityBase"] == "60000", "Expected physical 60000 MM")
    rule = request("POST", ROOT + "/rules", token, json.loads(seed["ruleBody"]))
    suggestion = recompute(token, rule)
    for field, expected in {"availableBase": "40000", "reservedBase": "20000", "confirmedInboundBase": "40000", "quantityBase": "75000"}.items():
        check(suggestion[field] == expected, f"Unexpected {field}")
    check(recompute(token, rule) == suggestion, "Repeated scan changed the same shortage window")
    acceptance = accept_body(suggestion)
    key = str(uuid4())
    path = f"{ROOT}/requests/{suggestion['id']}/accept"
    accepted = request("POST", path, token, acceptance, key)
    check(accepted["acceptedAt"] is not None, "Request was not accepted")
    check(request("POST", path, token, acceptance, key) == accepted, "Acceptance replay changed the original response")
    request("POST", path, token, {**acceptance, "quantityBase": "1"}, key, expected=409)
    viewer = login(seed["viewerEmail"])
    request("POST", path, viewer, acceptance, key, expected=403)
    request("POST", ROOT + "/rules", token, {**json.loads(seed["ruleBody"]), "locationId": seed["foreignLocationId"]}, expected=404)
    check(stock(token, seed["skuId"], seed["locationId"]) == before, "Planning or replay changed stock")
    print("PASS: physical 60000, reserved 20000, available 40000, inbound 40000 -> request 75000 MM; replay and authorization verified")
    return {"rule": rule, "path": path, "acceptance": acceptance, "key": key, "accepted": accepted, "stock": before, "stale": stale_receipt(seed)}


def after_restart(seed, state):
    token = login(seed["adminEmail"])
    check(request("POST", state["path"], token, state["acceptance"], state["key"]) == state["accepted"], "Restart lost the original acceptance response")
    check(recompute(token, state["rule"]) == state["accepted"], "Restart duplicated or changed the accepted window")
    check(stock(token, seed["skuId"], seed["locationId"]) == state["stock"], "Restart/replay changed stock")
    requests = request("GET", f"{ROOT}/requests?locationId={seed['locationId']}&skuId={seed['skuId']}", token)
    check(requests["totalElements"] == 1, "Restart created duplicate requests")
    stale_token = login(seed["staleEmail"])
    check(stock(stale_token, state["stale"]["sku"], seed["staleLocation"]) == state["stale"]["stock"], "Restart lost received stock")
    check(request("GET", f"{ROOT}/rules/{state['stale']['rule']['id']}", stale_token) == state["stale"]["rule"], "Restart lost replenishment rule")
    check(request("GET", f"{ROOT}/requests/{state['stale']['resolved']['id']}", stale_token) == state["stale"]["resolved"], "Restart lost the resolved shortage window")
    print("PASS: fresh JVM preserves original acceptance, one shortage window, and received physical stock")


def main():
    check(os.environ.get("WAREHOUSE_QA") == "true", "Use qa.sh replenishment")
    check(os.environ.get("SPRING_DATASOURCE_URL") == "jdbc:postgresql://127.0.0.1:25432/warehouse_test", "Expected owned test database")
    phase, manifest, state_file = sys.argv[1:]
    seed = json.loads(Path(manifest).read_text())
    ready()
    if phase == "before-restart":
        state = before_restart(seed)
        with open(state_file, "x") as output:
            json.dump(state, output)
    elif phase == "after-restart":
        after_restart(seed, json.loads(Path(state_file).read_text()))
    else:
        raise ValueError("Unknown verification phase")


if __name__ == "__main__":
    main()

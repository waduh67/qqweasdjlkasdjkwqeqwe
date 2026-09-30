#!/usr/bin/env python3
"""Transfer/count acceptance over real HTTP, starting from public tenant signup."""
import json
import os
from pathlib import Path
import sys
from uuid import uuid4

from http_support import WAREHOUSE, check, login, ready, request


class Journey:
    def __init__(self, state=None):
        self.state = state or {"actors": {}, "replays": [], "reads": []}
        self.tokens = {}

    def token(self, actor):
        if actor not in self.tokens:
            identity = self.state["actors"][actor]
            self.tokens[actor] = login(identity["email"], identity["slug"])
        return self.tokens[actor]

    def call(self, method, path, actor="admin", body=None, key=None, expected=200):
        return request(method, path, self.token(actor), body, key, expected)

    def remember(self, path, body, actor="admin", expected=200):
        key = str(uuid4())
        result = self.call("POST", path, actor, body, key, expected)
        check(self.call("POST", path, actor, body, key, expected) == result, "Immediate replay changed response")
        self.state["replays"].append({"path": path, "actor": actor, "body": body, "key": key, "expected": expected, "result": result})
        return result

    def keep_read(self, path, actor="admin"):
        result = self.call("GET", path, actor)
        self.state["reads"].append({"path": path, "actor": actor, "result": result})
        return result

    def master(self, resource, body):
        if resource == "locations":
            body["areaId"] = self.state["area"]
        return self.call("POST", f"{WAREHOUSE}/{resource}", body=body, expected=201)

    def positions(self, sku):
        page = self.call("GET", f"{WAREHOUSE}/stock/positions?skuId={sku}&size=100")
        check(page["totalElements"] == len(page["items"]), "Fixture positions exceed one page")
        return page["items"]

    def quantities(self, sku, expected):
        totals = {}
        for row in self.positions(sku):
            dimension = (row["locationId"], row["status"])
            totals[dimension] = totals.get(dimension, 0) + int(row["physical"]["quantityBase"])
        check(totals == expected, f"Unexpected physical distribution: {totals}")


def signup(journey, actor):
    suffix = uuid4().hex[:12]
    email = f"{actor}@{suffix}.test"
    response = request("POST", "/api/signup", body={"name": "Warehouse " + suffix, "adminEmail": email,
        "adminName": "Warehouse verifier", "adminPassword": "secret12345"}, expected=201)
    journey.state["actors"][actor] = {"email": email, "slug": response["slug"]}
    identity = journey.call("GET", "/api/me", actor)
    journey.state["actors"][actor]["id"] = identity["id"]
    return identity


def user(journey, name, permissions, locations):
    catalog = journey.call("GET", "/api/permissions")
    selected = [row["id"] for row in catalog if row["code"] in permissions]
    check(len(selected) == len(permissions), "Fixture permission missing from catalog")
    role = journey.call("POST", "/api/roles", body={"name": name, "permissionIds": selected}, expected=201)
    slug = journey.state["actors"]["admin"]["slug"]
    email = f"{name}@{slug}.test"
    created = journey.call("POST", "/api/users", body={"email": email, "name": name, "password": "secret12345",
        "roleIds": [role["id"]]}, expected=201)
    journey.state["actors"][name] = {"id": created["id"], "slug": slug, "email": email}
    journey.call("PUT", f"/api/users/{created['id']}/access", body={"roleIds": [role["id"]], "areaIds": [journey.state["area"]]})
    for location in locations:
        journey.call("PUT", f"{WAREHOUSE}/settings/scopes/{created['id']}/{location}", body={"expectedRevision": 0, "active": True})


def receive_stock(journey, code, tracking, unit, quantity):
    sku = journey.master("skus", {"code": code, "name": code, "tracking": tracking, "baseUnit": unit, "inspectionRequired": False})
    line = {"skuId": sku["id"], "quantityBase": quantity, "cost": {"totalMinor": "500000", "currency": "IDR"}}
    line.update({"serials": [{"serial": code + "-SERIAL"}]} if tracking == "SERIAL" else {"lotCode": code + "-LOT"})
    receipt = journey.master("receipts", {"supplierId": journey.state["supplier"], "externalReference": code,
        "sourceLocationId": journey.state["source"], "inspectionLocationId": journey.state["inspection"], "lines": [line]})
    path = f"{WAREHOUSE}/receipts/{receipt['id']}"
    received = journey.call("POST", path + "/receive", body={"expectedRevision": receipt["revision"]})
    detail = journey.call("GET", path)
    piece = detail["lines"][0]
    identity = piece["pieces"][0]["stockIdentityId"]
    journey.call("POST", path + "/putaway", body={"expectedRevision": received["revision"],
        "destinationLocationId": journey.state["warehouse"], "lines": [{"lineId": piece["id"], "stockIdentityId": identity,
            "baseUnit": unit, "quantityBase": quantity}]})
    positions = journey.positions(sku["id"])
    check(len(positions) == 1 and positions[0]["available"]["quantityBase"] == quantity, "Actual receipt/putaway did not supply stock")
    return {"sku": sku["id"], "identity": identity, "balance": positions[0]["id"], "unit": unit, "quantity": quantity}


def configure(journey):
    admin = signup(journey, "admin")
    journey.state["tenant"] = admin["tenantId"]
    area = journey.call("POST", "/api/areas", body={"code": "MAIN", "name": "Main area"}, expected=201)
    journey.state["area"] = area["id"]
    journey.call("PUT", f"/api/users/{admin['id']}/access", body={"roleIds": admin["roleIds"], "areaIds": [area["id"]]})
    for name, kind in {"source": "TRANSIT", "inspection": "QUARANTINE", "warehouse": "WAREHOUSE",
                       "destination": "WAREHOUSE", "transit": "TRANSIT", "lost": "LOST"}.items():
        code = "RECEIPT_SOURCE" if name == "source" else name.upper()
        journey.state[name] = journey.master("locations", {"code": code, "name": name.title(), "kind": kind,
            "issueEligible": kind == "WAREHOUSE"})["id"]
    journey.state["warehouse"] = journey.master("locations", {"code": "BIN", "name": "Serviceable bin", "kind": "BIN",
        "parentLocationId": journey.state["warehouse"], "issueEligible": True})["id"]
    journey.state["supplier"] = journey.master("suppliers", {"code": "SUP", "name": "Supplier"})["id"]
    locations = [journey.state[name] for name in ("warehouse", "destination", "transit", "lost")]
    user(journey, "receiver", {"inventory.transfer.view", "inventory.transfer.manage", "inventory.approval.view",
        "inventory.approval.request", "inventory.approval.decide"}, locations)
    user(journey, "counter", {"inventory.count.view", "inventory.count.manage"}, [journey.state["warehouse"]])
    user(journey, "reviewer", {"inventory.approval.view", "inventory.approval.decide"}, locations)
    signup(journey, "foreign")
    stocks = {"cable": receive_stock(journey, "CABLE", "LOT", "MM", "100000"),
        "missing": receive_stock(journey, "MISSING", "LOT", "MM", "100000"),
        "parts": receive_stock(journey, "PARTS", "BULK", "EA", "100"),
        "serial": receive_stock(journey, "ONU", "SERIAL", "EA", "1")}
    journey.state["stocks"] = stocks
    rules = [{"operation": operation, "tiers": [{"minimumMinor": "1", "userIds": [journey.state["actors"]["reviewer"]["id"]], "roleIds": []}]}
             for operation in ("ADJUSTMENT", "COUNT_VARIANCE")]
    journey.call("PUT", WAREHOUSE + "/settings/policy", body={"expectedRevision": 0, "currency": "IDR", "expiryHours": 24,
        "warehouseIds": locations, "rules": rules})


def transfer(journey, stock, quantity=None):
    body = {"sourceLocationId": journey.state["warehouse"], "destinationLocationId": journey.state["destination"],
        "transitLocationId": journey.state["transit"], "receiverId": journey.state["actors"]["receiver"]["id"],
        "reason": "Verified relocation", "lines": [{"stockIdentityId": stock["identity"], "sourceBalanceId": stock["balance"],
            "quantityBase": quantity or stock["quantity"], "baseUnit": stock["unit"]}]}
    return journey.master("transfers", body)


def delivery_body(draft, revision, quantity):
    return {"expectedRevision": revision, "evidenceReference": "SIGNED-DELIVERY", "lines": [
        {"lineId": draft["lines"][0]["id"], "quantityBase": quantity, "baseUnit": draft["lines"][0]["baseUnit"]}]}


def transfers(journey):
    stock = journey.state["stocks"]["cable"]
    draft = transfer(journey, stock)
    path = f"{WAREHOUSE}/transfers/{draft['id']}"
    journey.quantities(stock["sku"], {(journey.state["warehouse"], "AVAILABLE"): 100000})
    journey.remember(path + "/dispatch", {"expectedRevision": 0})
    journey.call("POST", path + "/cancel", body={"expectedRevision": 1}, expected=409)
    journey.call("POST", path + "/receive", "receiver", delivery_body(draft, 1, "101000"), expected=409)
    partial = journey.remember(path + "/receive", delivery_body(draft, 1, "60000"), "receiver")
    check(partial["state"] == "PART_RECEIVED" and partial["lines"][0]["inTransitBase"] == "40000", "Partial receipt lost transit remainder")
    journey.quantities(stock["sku"], {(journey.state["destination"], "AVAILABLE"): 60000, (journey.state["transit"], "IN_TRANSIT"): 40000})
    complete = journey.remember(path + "/receive", delivery_body(draft, 2, "40000"), "receiver")
    check(complete["state"] == "RECEIVED", "Transfer failed to finish")
    journey.quantities(stock["sku"], {(journey.state["destination"], "AVAILABLE"): 100000})
    journey.call("GET", path, "foreign", expected=404)
    journey.keep_read(path)
    journey.keep_read(path + "/history")

    stock = journey.state["stocks"]["missing"]
    draft = transfer(journey, stock)
    path = f"{WAREHOUSE}/transfers/{draft['id']}"
    journey.call("POST", path + "/dispatch", body={"expectedRevision": 0})
    journey.call("POST", path + "/receive", "receiver", delivery_body(draft, 1, "60000"))
    report = journey.remember(path + "/discrepancy", {"expectedRevision": 2, "action": "LOST", "destinationLocationId": journey.state["lost"],
        "reason": "Signed missing remainder", "evidenceReference": "LOSS-REPORT"}, "receiver")
    approval = journey.call("POST", WAREHOUSE + "/approvals/request", "receiver",
        {"sourceDocumentId": report["resolutionDocumentId"], "sourceRevision": 0}, expected=201)
    decision = {"requestId": approval["requestId"], "expectedRevision": 0, "decision": "APPROVE"}
    journey.call("POST", WAREHOUSE + "/approvals/decide", "receiver", decision, expected=403)
    journey.remember(WAREHOUSE + "/approvals/decide", decision, "reviewer")
    journey.quantities(stock["sku"], {(journey.state["destination"], "AVAILABLE"): 60000, (journey.state["lost"], "LOST"): 40000})
    journey.keep_read(path)
    journey.keep_read(path + "/history")
    print("PASS: transfer100000/receive60000/transit40000, exact final receipt, independent lost remainder, and foreign access rejection")


def measured(journey, stock, quantity):
    draft = journey.master("counts", {"locationId": journey.state["warehouse"], "partialLocation": True, "reason": "Blind physical check",
        "entries": [{"balanceId": stock["balance"], "counterId": journey.state["actors"]["counter"]["id"]}]})
    path = f"{WAREHOUSE}/counts/{draft['id']}"
    journey.call("POST", path + "/start", body={"expectedRevision": 0})
    blind = journey.call("GET", path, "counter")
    check(not any(key in json.dumps(blind) for key in ("quantityBase", "bookQuantity", "priorQuantity", "expectedQuantity")), "Counter can see book quantity")
    journey.call("GET", path + "/review", "counter", expected=403)
    body = {"expectedRevision": 1, "balanceId": stock["balance"], "quantityBase": quantity, "reason": "Physically counted", "documentReference": "SIGNED-COUNT"}
    journey.remember(path + "/observe", body, "counter")
    return path


def count_approval(journey, path):
    submitted = journey.call("POST", path + "/submit", body={"expectedRevision": 2})
    check(submitted["state"] == "SUBMITTED", "Variance must await independent approval")
    approval = journey.call("POST", WAREHOUSE + "/approvals/request", body={"sourceDocumentId": submitted["id"], "sourceRevision": submitted["revision"]}, expected=201)
    return {"requestId": approval["requestId"], "expectedRevision": 0, "decision": "APPROVE"}


def counts(journey):
    for name in ("serial", "parts"):
        stock = journey.state["stocks"][name]
        history = f"{WAREHOUSE}/stock/positions/{stock['balance']}/history"
        before = journey.call("GET", history)
        path = measured(journey, stock, stock["quantity"])
        posted = journey.remember(path + "/submit", {"expectedRevision": 2})
        check(posted["state"] == "POSTED", "Unchanged count must finish without movement")
        check(journey.call("GET", history) == before, "Unchanged count changed movement history")
        journey.keep_read(path)
        journey.keep_read(path + "/history")
    stock = journey.state["stocks"]["parts"]
    path = measured(journey, stock, "80")
    decision = count_approval(journey, path)
    journey.call("POST", WAREHOUSE + "/approvals/decide", body=decision, expected=403)
    journey.remember(WAREHOUSE + "/approvals/decide", decision, "reviewer")
    journey.quantities(stock["sku"], {(journey.state["warehouse"], "AVAILABLE"): 80, (journey.state["warehouse"], "LOST"): 20})
    journey.keep_read(path)
    journey.keep_read(path + "/review", "reviewer")

    path = measured(journey, stock, "70")
    stale = count_approval(journey, path)
    relocation = transfer(journey, stock, "10")
    journey.call("POST", f"{WAREHOUSE}/transfers/{relocation['id']}/dispatch", body={"expectedRevision": 0})
    failure = journey.remember(WAREHOUSE + "/approvals/decide", stale, "reviewer", expected=409)
    check(failure["code"] == "COUNT_STALE", "Movement after observation did not invalidate approval")
    current = journey.call("GET", path)
    check(current["state"] == "RECOUNT_REQUIRED", "Stale count did not require recount")
    recount = journey.call("POST", path + "/recount", body={"expectedRevision": current["revision"]})
    observed = journey.call("POST", path + "/observe", "counter", {"expectedRevision": recount["revision"], "balanceId": stock["balance"],
        "quantityBase": "70", "reason": "Recount after relocation", "documentReference": "RECOUNT"})
    final = journey.remember(path + "/submit", {"expectedRevision": observed["revision"]})
    check(final["state"] == "POSTED", "Unchanged recount did not finish")
    check(len(journey.keep_read(path + "/history")) == 2, "Recount overwrote original observation")
    journey.keep_read(path)
    journey.quantities(stock["sku"], {(journey.state["warehouse"], "AVAILABLE"): 70, (journey.state["warehouse"], "LOST"): 20,
        (journey.state["transit"], "IN_TRANSIT"): 10})
    print("PASS: blind serial/bulk counts, zero movement when unchanged, independent100->80 adjustment and movement-stale approval/recount")


def before_restart():
    journey = Journey()
    configure(journey)
    transfers(journey)
    counts(journey)
    for stock in journey.state["stocks"].values():
        journey.keep_read(f"{WAREHOUSE}/stock/positions?skuId={stock['sku']}&size=100")
    return journey.state


def after_restart(state):
    journey = Journey(state)
    for replay in state["replays"]:
        actual = journey.call("POST", replay["path"], replay["actor"], replay["body"], replay["key"], replay["expected"])
        check(actual == replay["result"], "Fresh JVM changed an original command response")
        changed = {**replay["body"], "expectedRevision": 999999}
        failure = journey.call("POST", replay["path"], replay["actor"], changed, replay["key"], expected=409)
        check(failure["code"] == "IDEMPOTENCY_CONFLICT", "Changed payload did not conflict with original key")
    for read in state["reads"]:
        check(journey.call("GET", read["path"], read["actor"]) == read["result"], "Restart/replay changed persisted stock, evidence or document history")
    print(f"PASS: fresh JVM replays{len(state['replays'])} original responses and preserves{len(state['reads'])} stock/document/history snapshots")


def main():
    check(os.environ.get("WAREHOUSE_QA") == "true", "Use qa.sh wave5")
    check(os.environ.get("SPRING_DATASOURCE_URL") == "jdbc:postgresql://127.0.0.1:25432/warehouse_test", "Expected owned test database")
    phase, filename = sys.argv[1:]
    ready()
    if phase == "before-restart":
        state = before_restart()
        with open(filename, "x") as output:
            json.dump(state, output)
    elif phase == "after-restart":
        after_restart(json.loads(Path(filename).read_text()))
    else:
        raise ValueError("Unknown verification phase")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Verify the hosted demo through its application API, including optional device commands."""
import argparse
import json
from pathlib import Path
import time
import urllib.error
import urllib.request
from manage import ROOT, read_settings


def verify(path, exercise_controls=False):
    settings = read_settings(path)
    base = f"http://127.0.0.1:{settings['FTTH_SIM_HTTP_PORT']}"
    token = None

    def request(method, route, body=None):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        req = urllib.request.Request(base + route, method=method, headers=headers,
                                     data=json.dumps(body).encode() if body is not None else None)
        with urllib.request.urlopen(req, timeout=45) as response:
            data = response.read()
            return json.loads(data) if data else None

    def await_value(read, valid, label, timeout=180):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            value = read()
            if valid(value):
                return value
            time.sleep(3)
        raise RuntimeError(label + " did not reach the expected state")

    token = request("POST", "/api/auth/login", {"tenantSlug": settings["FTTH_SIM_TENANT"],
        "email": settings["FTTH_SIM_ADMIN_EMAIL"], "password": settings["FTTH_SIM_ADMIN_PASSWORD"]})["accessToken"]
    me = request("GET", "/api/me")
    if me["tenantSlug"] != "simulator":
        raise RuntimeError("Device exercises are restricted to the simulator tenant")
    report = {"tenant": "simulator", "olts": []}
    olts = request("GET", "/api/olts?size=100")["content"]
    for olt in olts:
        if not olt["code"].startswith("OLT-LAB-"):
            continue
        result = await_value(lambda: request("POST", f"/api/monitoring/olts/{olt['id']}/poll"),
                             lambda value: value["reachable"], "SNMP " + olt["code"])
        inventory = request("GET", f"/api/monitoring/olts/{olt['id']}/onus")
        if not result["reachable"] or len(inventory["onus"]) != 16:
            raise RuntimeError("Unexpected SNMP result for " + olt["code"])
        report["olts"].append({"code": olt["code"], "onus": 16})
    if len(report["olts"]) != 5:
        raise RuntimeError("The five OLT simulators are not all registered")
    print("PASS: five OLTs, 80 ONUs through SNMP", flush=True)
    report["customers"] = request("GET", "/api/customers?size=100")["totalElements"]
    customer = next(row for row in request("GET", "/api/customers?query=LAB-001")["content"] if row["code"] == "LAB-001")
    access = request("GET", f"/api/bng/access?customerId={customer['id']}")[0]
    root = "/api/bng/access/" + access["id"]
    session = await_value(lambda: request("GET", root + "/session"),
                         lambda value: value["online"] and value["nasIp"] == "172.30.0.10", "PPPoE session")
    traffic = await_value(lambda: request("GET", root + "/traffic"),
                         lambda value: (value.get("currentDownMbps") or 0) > 0, "Traffic counters")
    report["pppoe"] = {"online": True, "nasIp": session["nasIp"], "downMbps": traffic["currentDownMbps"]}
    print("PASS: PPPoE session and live traffic", flush=True)
    cpe = await_value(lambda: request("GET", f"/api/cpe/devices?customerId={customer['id']}"),
                      lambda value: len(value) == 1 and value[0]["online"], "TR-069 device")[0]
    health = request("GET", "/api/cpe/acs/health")
    if health["status"] != "ONLINE":
        raise RuntimeError("ACS is unavailable")
    report["tr069"] = {"serial": cpe["serialNumber"], "online": cpe["online"]}
    print("PASS: TR-069 device linked to the demo customer", flush=True)
    if exercise_controls:
        request("POST", root + "/reset-login")
        await_value(lambda: request("GET", root + "/session"), lambda value: not value["online"], "DAE disconnect")
        changed = await_value(lambda: request("GET", root + "/session"), lambda value: value["online"], "DAE reconnect")
        report["resetLogin"] = {"observedOffline": True, "reconnected": True,
                                "uptimeBefore": session["uptimeSeconds"], "uptimeAfter": changed["uptimeSeconds"]}
        print("PASS: Reset Login closed and reconnected the virtual NAS session", flush=True)
        reboot = request("POST", f"/api/cpe/devices/{cpe['id']}/reboot")
        if reboot["status"] != "SUCCESS":
            raise RuntimeError("TR-069 reboot failed")
        report["reboot"] = {"status": reboot["status"], "actionId": reboot["id"]}
        print("PASS: TR-069 reboot command", flush=True)
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env.simulator")
    parser.add_argument("--exercise-controls", action="store_true", help="Reset Budi Lab's login and reboot its virtual CPE")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = verify(args.env_file.resolve(), args.exercise_controls)
    if args.output:
        args.output.write_text(json.dumps(result, indent=2) + "\n")
    else:
        print(json.dumps(result, indent=2))

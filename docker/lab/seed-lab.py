#!/usr/bin/env python3
# ============================================================================
# Seed lab peniru-protokol: daftarkan OLT + BRAS + pelanggan + CPE ke SIMULATOR
# lewat API app (sebagai admin tenant demo). Setelah ini app langsung "melihat"
# perangkat palsu seolah nyata:
#   • OLT HSGQ  → server polling SNMP ke 172.30.0.10:1161 (IP statis simulator)
#   • BRAS      → server tembak DAE ke 172.30.0.10:3799 (isolir/Reset Login/CoA)
#   • akun PPPoE budi@isp.net → simulator memunculkan sesi radacct hidup
#   • ONU serial C0FD84050205 → ONLINE di OLT sekaligus tertaut ke CPE/ONT genieacs-sim
#
# Dijalankan oleh `make lab` (setelah stack sehat) atau manual:
#   BASE=http://localhost:8000 python3 docker/lab/seed-lab.py
#
# Idempoten: aman diulang — resource yang sudah ada dipakai ulang, bukan digandakan.
# HANYA untuk lab. Simulator & seed ini TAK PERNAH di-deploy ke produksi.
# ============================================================================
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("BASE", "http://localhost:8000").rstrip("/")
COMPOSE = os.environ.get("COMPOSE", "docker compose -f docker-compose.lab.yml")

SIM_IP = "172.30.0.10"
SIM_OLT_PORTS = [1161, 1162, 1163, 1164, 1165]
DAE_SECRET = "testing123"
ONU_SERIAL = "C0FD84050205"

TENANT = "demo"
EMAIL = "admin@demo.ftth"
PASSWORD = "admin12345"


def say(msg):
    print(f"\n\033[1m▶ {msg}\033[0m", flush=True)


def info(msg):
    print(f"  {msg}", flush=True)


def warn(msg):
    print(f"  \033[1;33m⚠ {msg}\033[0m", flush=True)


def die(msg):
    print(f"\033[1;31m✗ {msg}\033[0m", file=sys.stderr)
    sys.exit(1)


class Api:
    def __init__(self, base):
        self.base = base
        self.token = None

    def call(self, method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("Content-Type", "application/json")
        if self.token:
            req.add_header("Authorization", f"Bearer {self.token}")
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                raw = resp.read().decode("utf-8", "replace")
                return resp.status, json.loads(raw) if raw else None
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", "replace")
            try:
                parsed = json.loads(raw)
            except Exception:
                parsed = raw
            return e.code, parsed
        except Exception as e:
            die(f"gagal menghubungi {self.base}{path}: {e}")

    def login(self):
        status, body = self.call("POST", "/api/auth/login", {
            "tenantSlug": TENANT,
            "email": EMAIL,
            "password": PASSWORD
        })
        if status != 200 or not isinstance(body, dict) or "accessToken" not in body:
            die(f"login gagal (HTTP {status}) → {body}")
        self.token = body["accessToken"]

    def ensure(self, label, post_path, payload, find_path, match_fn):
        status, body = self.call("POST", post_path, payload)
        if 200 <= status < 300:
            if isinstance(body, dict) and "id" in body:
                return body["id"]
            die(f"{label}: respons sukses tanpa id → {body}")
        if status == 409:
            get_status, get_body = self.call("GET", find_path)
            if 200 <= get_status < 300:
                matched_id = match_fn(get_body)
                if matched_id:
                    return matched_id
            die(f"{label} sudah ada tapi tak ketemu saat lookup ({find_path}) → {get_body}")
        die(f"{label} GAGAL (HTTP {status}) → {body}")


def set_reachability(nas_id):
    cmd = f"{COMPOSE} exec -T postgres psql -U postgres -d ftth -qtAc \"UPDATE nas SET reachability='DIRECT' WHERE id='{nas_id}';\""
    try:
        ret = subprocess.run(cmd, shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if ret.returncode == 0:
            info("reachability=DIRECT (server layani DAE langsung ke simulator)")
        else:
            warn("gagal set reachability=DIRECT via psql — isolir/Reset Login akan tertunda PENDING.")
    except Exception as e:
        warn(f"gagal set reachability via psql: {e}")


def main():
    api = Api(BASE)

    say("Login admin@demo.ftth")
    api.login()
    info("token ok")

    say("Paket 50/10 (catalog)")
    plan_payload = {
        "name": "Paket Lab 50/10",
        "description": "lab",
        "price": 150000,
        "downMbps": 50,
        "upMbps": 10
    }
    plan_id = api.ensure(
        "Paket",
        "/api/catalog/plans",
        plan_payload,
        "/api/catalog/plans",
        lambda data: next((item["id"] for item in (data if isinstance(data, list) else []) if item.get("name") == "Paket Lab 50/10"), None)
    )
    info(f"planId={plan_id}")

    say("Site POP Lab")
    site_payload = {
        "code": "SITE-LAB",
        "name": "POP Lab",
        "address": "Jl. Lab No.1",
        "location": {"longitude": 106.8272, "latitude": -6.1751}
    }
    site_id = api.ensure(
        "Site",
        "/api/sites",
        site_payload,
        "/api/sites?query=SITE-LAB",
        lambda data: next((item["id"] for item in (data.get("content", []) if isinstance(data, dict) else []) if item.get("code") == "SITE-LAB"), None)
    )
    info(f"siteId={site_id}")

    for idx, port in enumerate(SIM_OLT_PORTS, start=1):
        code = f"OLT-LAB-{idx}"
        name = f"OLT Lab {idx} (simulator)"
        say(f"OLT HSGQ palsu #{idx} → SNMP {SIM_IP}:{port}")
        olt_payload = {
            "siteId": site_id,
            "code": code,
            "name": name,
            "vendor": "HSGQ",
            "managementIp": SIM_IP,
            "snmpCommunity": "public",
            "snmpPort": port,
            "snmpEnabled": True,
            "snmpVersion": "V2C"
        }
        olt_id = api.ensure(
            code,
            "/api/olts",
            olt_payload,
            f"/api/olts?query={code}",
            lambda data, c=code: next((item["id"] for item in (data.get("content", []) if isinstance(data, dict) else []) if item.get("code") == c), None)
        )
        info(f"oltId #{idx} = {olt_id}")

    say("Pelanggan Budi Lab")
    cust_payload = {
        "code": "LAB-001",
        "name": "Budi Lab",
        "address": "Jl. Lab No.1",
        "location": {"longitude": 106.8272, "latitude": -6.1751}
    }
    cust_id = api.ensure(
        "Pelanggan",
        "/api/customers",
        cust_payload,
        "/api/customers?query=LAB-001",
        lambda data: next((item["id"] for item in (data.get("content", []) if isinstance(data, dict) else []) if item.get("code") == "LAB-001"), None)
    )
    info(f"customerId={cust_id}")

    say("Langganan + aktifkan")
    status, sub_resp = api.call("GET", f"/api/customers/{cust_id}/subscription")
    sub_id = sub_resp.get("id") if (status == 200 and isinstance(sub_resp, dict)) else None
    if not sub_id:
        status, sub_resp = api.call("PUT", f"/api/customers/{cust_id}/subscription", {"planId": plan_id})
        if not (200 <= status < 300) or not isinstance(sub_resp, dict) or "id" not in sub_resp:
            die(f"buat langganan gagal (HTTP {status}) → {sub_resp}")
        sub_id = sub_resp["id"]
    info(f"subscriptionId={sub_id}")

    status, act_resp = api.call("POST", f"/api/customers/subscriptions/{sub_id}/activate")
    act_status = act_resp.get("status", "?") if isinstance(act_resp, dict) else "?"
    info(f"status langganan: {act_status}")

    say(f"ONU serial {ONU_SERIAL} (untuk tautan CPE)")
    status, onus = api.call("GET", f"/api/customers/{cust_id}/onus")
    onu_id = None
    if isinstance(onus, list):
        onu_id = next((o["id"] for o in onus if o.get("serialNumber") == ONU_SERIAL), None)
    if not onu_id:
        status, onu_resp = api.call("POST", f"/api/customers/{cust_id}/onus", {
            "serialNumber": ONU_SERIAL,
            "model": "SIM-ONT"
        })
        if not (200 <= status < 300) or not isinstance(onu_resp, dict) or "id" not in onu_resp:
            die(f"buat ONU gagal (HTTP {status}) → {onu_resp}")
        onu_id = onu_resp["id"]
    info(f"onuId={onu_id}  (CPE tertaut otomatis pada siklus sinkron berikutnya, ~30 dtk)")

    say(f"BRAS → DAE {SIM_IP}:{DAE_SECRET}@3799")
    bras_payload = {
        "name": "BRAS Lab (simulator)",
        "vendor": "MIKROTIK",
        "address": SIM_IP,
        "nasIdentifier": None,
        "coaSecret": DAE_SECRET,
        "collectorId": None,
        "enabled": True,
        "apiUsername": None,
        "apiSecret": None,
        "apiPort": None,
        "apiUseTls": False
    }
    nas_id = api.ensure(
        "BRAS",
        "/api/bng/nas",
        bras_payload,
        "/api/bng/nas",
        lambda data: next((item["id"] for item in (data if isinstance(data, list) else []) if item.get("name") == "BRAS Lab (simulator)"), None)
    )
    info(f"nasId={nas_id}")
    set_reachability(nas_id)

    say("Provision akun PPPoE budi@isp.net di BRAS itu")
    status, accs = api.call("GET", f"/api/bng/access?customerId={cust_id}")
    acc_id = accs[0]["id"] if (isinstance(accs, list) and len(accs) > 0 and "id" in accs[0]) else None
    if not acc_id:
        status, acc_resp = api.call("POST", "/api/bng/access", {
            "subscriptionId": sub_id,
            "username": "budi@isp.net",
            "secret": "rahasia123",
            "planId": plan_id,
            "nasId": nas_id
        })
        if not (200 <= status < 300) or not isinstance(acc_resp, dict) or "id" not in acc_resp:
            die(f"provision akun gagal (HTTP {status}) → {acc_resp}")
        acc_id = acc_resp["id"]
    info(f"accessId={acc_id}")

    say("SELESAI seeding lab")
    info("Sesi PPPoE + trafik muncul dalam ~1 menit (provision radcheck → simulator dial → poll radacct).")
    info("Coba isolir/Reset Login di detail pelanggan → server tembak DAE ke simulator (SUCCESS).")


if __name__ == "__main__":
    main()

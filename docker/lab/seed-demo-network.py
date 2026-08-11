#!/usr/bin/env python3
# ============================================================================
# Seed jaringan demo "kaya lapangan" ke tenant demo: POP → OLT → ODC → ODP →
# pelanggan, lengkap dengan jalur kabel yang mengikuti jalan sungguhan.
#
# Bedanya dengan seed-lab.sh: yang itu MENYAMBUNGKAN app ke simulator protokol
# (SNMP/RADIUS/TR-069) memakai segelintir perangkat; yang ini MENGISI petanya
# dengan topologi berukuran nyata — 1 POP, 3 kabinet, 10 ODP berantai, 50
# pelanggan — supaya halaman peta, heatmap kapasitas, telusur pelanggan, dan
# blast radius punya bahan yang bentuknya seperti jaringan ISP betulan.
#
#   BASE=http://localhost:8080 python3 docker/lab/seed-demo-network.py
#
# Idempoten: yang sudah ada dipakai ulang (kode aset jadi kunci alami), jadi aman
# diulang setelah `make lab-update`. HANYA untuk lab/dev — tak pernah ke produksi.
#
# Geometri kabel di demo-network.json bukan garis lurus antar-titik melainkan
# hasil pencarian rute jalan (OSRM) di Tebet, Jakarta Selatan, lalu dipotong per
# ruas. Alasannya sama dengan di lapangan: serat digantung di tiang menyusuri
# jalan, jadi panjang & sudut belokannya ikut jalan — dan panjang itulah yang
# nanti dipakai app menghitung anggaran redaman serta kebutuhan haspel.
# ============================================================================
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

BASE = os.environ.get("BASE", "http://localhost:8080").rstrip("/")
DATA = Path(__file__).with_name("demo-network.json")

TENANT = "demo"
EMAIL = "admin@demo.ftth"
PASSWORD = "admin12345"

# BRAS lab (dibuat seed-lab.sh). Ada → akun PPPoE tiap pelanggan ikut dibuat dan
# virtual-NAS simulator akan mendial-kan sesinya; tak ada → bagian akses dilewati
# dan topologinya tetap utuh (seed ini tak menuntut simulator hidup).
NAS_NAME = "BRAS Lab (simulator)"

SITE = {"code": "POP-TBT", "name": "POP Tebet",
        "address": "Jl. Tebet Raya, Jakarta Selatan",
        "location": {"longitude": 106.850998, "latitude": -6.226155}}
OLT = {"code": "OLT-TBT-01", "name": "OLT Tebet 01", "vendor": "ZTE", "model": "ZXA10 C320"}
# Label port ala ZTE C320 (rak/slot/port). Tiga terpakai feeder, sisanya cadangan
# tumbuh — kabinet berikutnya tinggal colok tanpa nambah kartu.
PON_LABELS = [f"1/1/{n}" for n in range(1, 9)]


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
    """Klien REST seadanya: hanya yang dibutuhkan seeding, tanpa dependensi luar."""

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
            with urllib.request.urlopen(req, timeout=60) as res:
                raw = res.read()
                return res.status, (json.loads(raw) if raw else None)
        except urllib.error.HTTPError as err:
            raw = err.read()
            try:
                return err.code, json.loads(raw)
            except ValueError:
                return err.code, {"raw": raw.decode(errors="replace")}
        except OSError as err:
            die(f"tak bisa menghubungi {self.base}{path} ({err}) — stack sudah jalan?")

    def ok(self, method, path, body=None, what=""):
        status, res = self.call(method, path, body)
        if status // 100 != 2:
            die(f"{what or path} GAGAL (HTTP {status}) → {res}")
        return res

    def login(self):
        res = self.ok("POST", "/api/auth/login",
                      {"tenantSlug": TENANT, "email": EMAIL, "password": PASSWORD}, "login")
        self.token = res["accessToken"]

    def ensure(self, what, path, body, find_path, match):
        """POST dulu; 409 berarti sudah ada → cari lewat [find_path] dan pakai yang lama.

        Kode aset adalah kunci alami yang dipegang operator di lapangan (tertulis di
        badan kotaknya), jadi ia pula yang dipakai mengenali "ini sudah pernah dibuat".
        """
        status, res = self.call("POST", path, body)
        if status // 100 == 2:
            return res["id"], True
        if status != 409:
            die(f"{what} GAGAL (HTTP {status}) → {res}")
        found = self.ok("GET", find_path, what=f"cari {what}")
        rows = found.get("content", found) if isinstance(found, dict) else found
        for row in rows:
            if match(row):
                return row["id"], False
        die(f"{what} sudah ada tapi tak ketemu saat lookup ({find_path})")


def by_code(code):
    return lambda row: row.get("code") == code


def main():
    topo = json.loads(DATA.read_text())
    api = Api(BASE)

    say(f"Login {EMAIL} @ {BASE}")
    api.login()
    info("token ok")

    # ---- katalog -----------------------------------------------------------
    say("Katalog paket")
    plans = []
    existing = {p["name"]: p["id"] for p in api.ok("GET", "/api/catalog/plans")}
    for plan in topo["plans"]:
        pid = existing.get(plan["name"])
        if pid is None:
            pid = api.ok("POST", "/api/catalog/plans", {
                "name": plan["name"], "description": plan["description"],
                "price": plan["price"], "downMbps": plan["downMbps"], "upMbps": plan["upMbps"],
                "serviceTypes": ["PPPOE"],
            }, plan["name"])["id"]
        plans.append(pid)
    info(f"{len(plans)} paket siap")

    # ---- POP + OLT ---------------------------------------------------------
    say(f"Site {SITE['code']} + OLT {OLT['code']}")
    site_id, _ = api.ensure("Site", "/api/sites", SITE,
                            f"/api/sites?query={SITE['code']}", by_code(SITE["code"]))
    # snmpEnabled=false: OLT ini inventaris topologi, bukan salah satu agen SNMP
    # simulator (yang sudah dipakai OLT-LAB-*). Menunjuk agen yang sama dari dua
    # OLT akan membuat ONU tiruan yang sama tercatat dua kali di kotak temuan.
    olt_id, _ = api.ensure("OLT", "/api/olts", {
        "siteId": site_id, "code": OLT["code"], "name": OLT["name"],
        "vendor": OLT["vendor"], "model": OLT["model"],
        "description": "OLT inventaris demo — monitoring SNMP sengaja dimatikan di lab.",
        "location": SITE["location"], "snmpEnabled": False,
    }, f"/api/olts?query={OLT['code']}", by_code(OLT["code"]))

    ports = {p["label"]: p["id"] for p in api.ok("GET", f"/api/olts/{olt_id}/pon-ports")}
    for label in PON_LABELS:
        if label not in ports:
            ports[label] = api.ok("POST", f"/api/olts/{olt_id}/pon-ports",
                                  {"label": label}, f"PON {label}")["id"]
    pon = [ports[label] for label in PON_LABELS]
    info(f"siteId={site_id} oltId={olt_id} · {len(pon)} PON port")

    # ---- ODC / joint box / ODP --------------------------------------------
    say("Kabinet (ODC), joint box, dan kotak (ODP)")
    node = {}
    for odc in topo["odcs"]:
        # Kabinet berisi satu modul 1:4 — pola paling lazim: 1:4 di kabinet + 1:8 di
        # kotak = 1:32 per PON, masih di dalam anggaran redaman GPON B+.
        node[odc["code"]] = api.ensure("ODC", "/api/odcs", {
            "code": odc["code"], "name": odc["name"], "address": odc["address"],
            "location": {"longitude": odc["location"][0], "latitude": odc["location"][1]},
            "ponPortId": pon[odc["ponPort"]], "splitterRatio": "1:4", "capacity": 8,
        }, f"/api/odcs?query={odc['code']}", by_code(odc["code"]))[0]

    for jb in topo["jointBoxes"]:
        # Haspel kabel panjangnya terbatas (±2 km); di titik habisnya serat disambung
        # di dalam closure. Karena itu joint box duduk di TENGAH satu feeder, bukan di ujung.
        node[jb["code"]] = api.ensure("Joint box", "/api/joint-boxes", {
            "code": jb["code"], "name": jb["name"], "address": jb["address"],
            "location": {"longitude": jb["location"][0], "latitude": jb["location"][1]},
            "trayCount": 2, "capacity": 24, "status": "ACTIVE",
        }, f"/api/joint-boxes?query={jb['code']}", by_code(jb["code"]))[0]

    for odp in topo["odps"]:
        node[odp["code"]] = api.ensure("ODP", "/api/odps", {
            "code": odp["code"], "name": odp["name"], "address": odp["address"],
            "location": {"longitude": odp["location"][0], "latitude": odp["location"][1]},
            "odcId": node[odp["odc"]], "splitterRatio": "1:8", "capacity": 8,
        }, f"/api/odps?query={odp['code']}", by_code(odp["code"]))[0]
    info(f"{len(topo['odcs'])} ODC · {len(topo['jointBoxes'])} JB · {len(topo['odps'])} ODP")

    # ---- kabel feeder & distribusi ----------------------------------------
    say("Kabel feeder & distribusi (mengikuti jalan)")
    node[OLT["code"]] = olt_id
    made = 0
    for cable in topo["cables"]:
        body = {
            "code": cable["code"], "name": cable["name"], "cableType": cable["cableType"],
            "coreCount": cable["coreCount"],
            "route": [{"longitude": x, "latitude": y} for x, y in cable["route"]],
            "fromKind": cable["fromKind"], "fromId": node[cable["from"]],
            "toKind": cable["toKind"], "toId": node[cable["to"]],
            "installation": cable["installation"], "ownership": "OWNED",
        }
        if "ponPort" in cable:
            body["fromPonPortId"] = pon[cable["ponPort"]]
        _, created = api.ensure(cable["code"], "/api/cables", body,
                                f"/api/cables?query={cable['code']}", by_code(cable["code"]))
        made += 1 if created else 0
    info(f"{len(topo['cables'])} kabel ({made} baru)")

    # ODC di balik joint box tak dapat uplink otomatis: kabel yang tiba di sana
    # berangkat dari JB, dan JB tak punya PON port. Sambungannya disetel di sini
    # supaya telusur pelanggan tetap tembus sampai OLT.
    for odc in topo["odcs"]:
        api.ok("PUT", f"/api/odcs/{node[odc['code']]}/uplink",
               {"targetId": pon[odc["ponPort"]]}, f"uplink {odc['code']}")

    # ---- pelanggan ---------------------------------------------------------
    nas = next((n["id"] for n in api.ok("GET", "/api/bng/nas") if n["name"] == NAS_NAME), None)
    if nas is None:
        warn(f"BRAS '{NAS_NAME}' belum ada — akun PPPoE dilewati (jalankan `make lab-seed` dulu).")

    say(f"{len(topo['customers'])} pelanggan: langganan, ONU, drop, akun PPPoE")
    stat = {"baru": 0, "isolir": 0, "psb": 0, "gangguan": 0}
    for cust in topo["customers"]:
        code = cust["code"]
        cid, created = api.ensure(code, "/api/customers", {
            "code": code, "name": cust["name"], "phone": cust["phone"],
            "address": cust["address"],
            "location": {"longitude": cust["location"][0], "latitude": cust["location"][1]},
        }, f"/api/customers?query={code}", by_code(code))
        stat["baru"] += 1 if created else 0
        state = cust.get("state")

        subs = api.ok("GET", f"/api/customers/{cid}/subscriptions")
        sub = subs[0]["id"] if subs else api.ok(
            "POST", f"/api/customers/{cid}/subscriptions",
            {"planId": plans[cust["plan"]]}, f"langganan {code}")["id"]

        if state == "PSB":
            # Pelanggan baru daftar: langganan masih PENDING dan ONU belum dipasang
            # teknisi, jadi belum ada port ODP terpakai maupun drop tergambar.
            stat["psb"] += 1
            continue
        api.ok("POST", f"/api/customers/subscriptions/{sub}/activate", None, f"aktivasi {code}")

        onus = api.ok("GET", f"/api/customers/{cid}/onus")
        onu = next((o for o in onus if o["serialNumber"] == cust["serial"]), None)
        onu_id = onu["id"] if onu else api.ok(
            "POST", f"/api/customers/{cid}/onus",
            {"serialNumber": cust["serial"], "model": cust["model"]}, f"ONU {code}")["id"]
        # Pemasangan ONU-lah yang menentukan "port ODP mana yang terpakai" — kabel drop
        # cuma menggambar jalurnya. Rx dicatat saat pasang: angka inilah yang nanti
        # dibandingkan teknisi ketika pelanggan mengeluh lambat.
        api.ok("POST", f"/api/customers/onus/{onu_id}/attach",
               {"odpId": node[cust["odp"]], "portNumber": cust["port"],
                "installRxPowerDbm": cust["rx"]}, f"pasang ONU {code}")
        status = cust.get("onuStatus", "ONLINE")
        api.ok("PUT", f"/api/customers/onus/{onu_id}/status", {"status": status}, f"status ONU {code}")
        if status != "ONLINE":
            stat["gangguan"] += 1

        api.ensure(f"drop {code}", "/api/cables", {
            "code": f"DROP-{cust['odp']}-P{cust['port']}"[:40],
            "name": f"Drop {cust['odp']} → {code}", "cableType": "DROP", "coreCount": 1,
            "route": [{"longitude": x, "latitude": y} for x, y in cust["drop"]],
            "fromKind": "ODP", "fromId": node[cust["odp"]], "fromPortNumber": cust["port"],
            "toKind": "CUSTOMER", "toId": cid,
            "installation": "AERIAL", "ownership": "OWNED",
        }, f"/api/cables?query=DROP-{cust['odp']}-P{cust['port']}",
            by_code(f"DROP-{cust['odp']}-P{cust['port']}"))

        if nas:
            acc = api.ok("GET", f"/api/bng/access?customerId={cid}")
            if not acc:
                user = code.lower().replace("-", "")
                api.ok("POST", "/api/bng/access", {
                    "subscriptionId": sub, "username": f"{user}@netops.id",
                    "secret": f"{user}rahasia", "planId": plans[cust["plan"]], "nasId": nas,
                }, f"akun PPPoE {code}")

        if state == "ISOLIR":
            # Isolir karena tunggakan: langganan yang dikunci, bukan seratnya. ONU tetap
            # ONLINE — pelanggan masih "nyala" tapi lalu lintasnya diarahkan ke halaman tagihan.
            api.ok("POST", f"/api/customers/subscriptions/{sub}/isolate", None, f"isolir {code}")
            stat["isolir"] += 1

    say("SELESAI")
    info(f"pelanggan baru {stat['baru']} · isolir {stat['isolir']} · "
         f"PSB menunggu instalasi {stat['psb']} · ONU bermasalah {stat['gangguan']}")
    info("Buka peta → cari POP-TBT. Heatmap kapasitas & telusur pelanggan sudah ada isinya.")
    if nas:
        info("Sesi PPPoE muncul dalam ~1 menit (virtual-NAS simulator mendial tiap akun).")


if __name__ == "__main__":
    main()

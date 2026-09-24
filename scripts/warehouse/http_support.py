"""HTTP helpers shared by the isolated packaged warehouse smoke tests."""
import json
import time
from urllib.error import HTTPError, URLError
from urllib.request import ProxyHandler, Request, build_opener
from uuid import uuid4

BASE = "http://127.0.0.1:17880"
WAREHOUSE = "/api/v1/warehouse"
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


def login(email, slug=None):
    slug = slug or email.split("@", 1)[1].removesuffix(".test")
    return request("POST", "/api/auth/login", body={"tenantSlug": slug, "email": email, "password": "secret12345"})["accessToken"]



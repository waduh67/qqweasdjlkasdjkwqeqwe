import importlib.util
import json
from pathlib import Path
import tempfile
import subprocess
import unittest

spec = importlib.util.spec_from_file_location("manage", Path(__file__).with_name("manage.py"))
manage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(manage)


class SimulatorInstallationTest(unittest.TestCase):
    def test_credentials_are_private_distinct_and_cannot_be_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sim.env"
            manage.initialize(path, "sim.example.org", "admin@example.org")
            original = path.read_bytes()
            values = manage.read_settings(path)
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
            secrets = [values[f"FTTH_SIM_{key}"] for key in manage.SECRET_KEYS]
            self.assertEqual(len(secrets), len(set(secrets)))
            with self.assertRaises(FileExistsError):
                manage.initialize(path, "sim.example.org", "admin@example.org")
            self.assertEqual(original, path.read_bytes())

    def test_shared_credentials_file_and_invalid_domain_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sim.env"
            with self.assertRaises(ValueError):
                manage.initialize(path, "https://sim.example.org/", "admin@example.org")
            self.assertFalse(path.exists())
            manage.initialize(path, "sim.example.org", "admin@example.org")
            path.chmod(0o644)
            with self.assertRaises(ValueError):
                manage.read_settings(path)

    def test_only_gateway_joins_optional_existing_edge(self):
        path = Path("/private/simulator.env")
        plain = manage.compose_args(path, {})
        self.assertEqual("ftth-sim", plain[plain.index("--project-name") + 1])
        self.assertNotIn(str(manage.ROOT / "deploy/simulator/compose.edge.yml"), plain)
        edge = manage.compose_args(path, {"FTTH_SIM_EDGE_NETWORK": "edge_default"})
        self.assertEqual(str(manage.ROOT / "deploy/simulator/compose.edge.yml"), edge[-1])

    def test_unready_api_fails_before_seed(self):
        with self.assertRaisesRegex(RuntimeError, "not ready"):
            manage.wait_ready("http://127.0.0.1:1", timeout=0)

    def test_rendered_compose_keeps_device_services_private_and_separates_database_roles(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sim.env"
            manage.initialize(path, "sim.example.org", "admin@example.org", "edge_default")
            values = manage.read_settings(path)
            result = subprocess.run(manage.compose_args(path, values) + ["config", "--format", "json"],
                                    check=True, text=True, capture_output=True)
            config = json.loads(result.stdout)
            self.assertEqual("ftth-sim", config["name"])
            self.assertTrue(config["networks"]["default"]["internal"])
            services = config["services"]
            for name, service in services.items():
                if name != "caddy":
                    self.assertFalse(service.get("ports"), name)
                    self.assertEqual(["default"], list(service["networks"]), name)
            self.assertEqual("127.0.0.1", services["caddy"]["ports"][0]["host_ip"])
            app = services["server"]["environment"]
            database = services["postgres"]["environment"]
            self.assertEqual("warehouse_app", app["FTTH_DB_USER"])
            self.assertNotEqual(app["FTTH_DB_USER"], app["SPRING_FLYWAY_USER"])
            self.assertTrue(app["SPRING_FLYWAY_PASSWORD"] == database["FTTH_DB_OWNER_PASSWORD"])
            self.assertTrue(app["FTTH_DB_PASSWORD"] == database["FTTH_DB_PASSWORD"])
            self.assertEqual("true", app["FTTH_PRODUCTION"])
            self.assertEqual("false", app["FTTH_SEED_DEMO"])
            simulator = services["simulator"]
            self.assertEqual(simulator["networks"]["default"]["ipv4_address"],
                             simulator["environment"]["FTTH_SIM_RADIUS_NAS_IP"])


if __name__ == "__main__":
    unittest.main()

import importlib.util
from pathlib import Path
import tempfile
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


if __name__ == "__main__":
    unittest.main()

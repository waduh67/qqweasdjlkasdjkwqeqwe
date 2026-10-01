import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("warehouse_seed", Path(__file__).resolve().parents[2] / "docker/lab/warehouse_seed.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SeedRecoveryTest(unittest.TestCase):
    def test_interrupted_command_replays_original_revision_and_key(self):
        class Api:
            base = "http://127.0.0.1:18080"
            token = "unused"
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {"FTTH_SIM_STATE_DIR": directory}):
            first = module.WarehouseSeed(Api())
            calls = []
            def interrupted(*args):
                calls.append(args)
                raise ConnectionError("Response lost after server committed")
            first.request = interrupted
            with self.assertRaises(ConnectionError):
                first.step("receive-serial-1", "POST", "/receipt/receive", {"expectedRevision": 0})
            second = module.WarehouseSeed(Api())
            def retry(*args):
                calls.append(args)
                return {"revision": 1}
            second.request = retry
            result = second.step("receive-serial-1", "POST", "/receipt/receive", {"expectedRevision": 1})
            self.assertEqual(calls[0], calls[1])
            self.assertEqual({"revision": 1}, result)
            self.assertEqual(0o600, second.path.stat().st_mode & 0o777)
            second.step("receive-serial-1", "POST", "/receipt/receive", {"expectedRevision": 8})
            self.assertEqual(2, len(calls), "Completed operations must not post again")

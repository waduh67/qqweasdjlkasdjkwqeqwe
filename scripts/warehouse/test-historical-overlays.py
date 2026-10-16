"""Exercise the actual historical overlay preflight against disposable Git repositories."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
RUNNER = ROOT / "scripts/warehouse/historical-upgrades.sh"
OVERLAY = RUNNER.read_text().split("<<'PY'\n", 1)[1].split("\nPY\n", 1)[0]
MIGRATIONS = "server/src/main/resources/db/migration"
HELPER = "server/src/test/resources/warehouse/task17-migration-inventory.txt"


class HistoricalOverlayTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.old, self.current = self.base / "old", self.base / "current"
        self.environment = dict(os.environ, GIT_MASTER="1")
        for repo in (self.old, self.current):
            repo.mkdir()
            self.git(repo, "init", "-q")
            self.write(repo, f"{MIGRATIONS}/V1__base.sql", "SELECT 1;\n")
        self.write(self.old, "server/src/main/kotlin/com/legacy/ftth/FtthApplication.kt", "package com.legacy.ftth\nclass FtthApplication\n")
        self.write(self.current, "server/src/main/kotlin/com/karuhun/ftth/FtthApplication.kt", "package com.karuhun.ftth\nclass FtthApplication\n")
        self.write(self.current, "scripts/warehouse/historical_namespace.py", (ROOT / "scripts/warehouse/historical_namespace.py").read_text())
        self.write(self.current, f"{MIGRATIONS}/V2__new.sql", "SELECT 2;\n")
        self.write(self.old, "server/src/test/kotlin/example/UpgradeIT.kt", "old fixture\n")
        self.write(self.current, "server/src/historicalTest/kotlin/example/UpgradeIT.kt", "historical fixture\n")
        for name in ("WarehouseSchemaDatabase", "WarehouseMigrationInventory"):
            self.write(self.current, f"server/src/test/kotlin/com/karuhun/ftth/inventory/{name}.kt", name + "\n")
        self.write(self.current, HELPER, "inventory helper\n")
        self.write(self.current, "scripts/warehouse/historical-upgrades.sh", RUNNER.read_text())
        for repo in (self.old, self.current):
            self.git(repo, "add", ".")
            self.git(repo, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.test", "commit", "-qm", "fixture")

    def git(self, repo, *arguments):
        return subprocess.run(["git", "-C", str(repo), *arguments], env=self.environment,
                              capture_output=True, text=True, check=True).stdout

    @staticmethod
    def write(repo, name, content):
        path = repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)

    def overlay(self, attempt, name="example.UpgradeIT"):
        destination = self.base / attempt
        destination.mkdir()
        result = subprocess.run(["python3", "-", str(self.old), str(self.current), str(destination), name],
                                input=OVERLAY, env=self.environment, capture_output=True, text=True)
        return result, destination

    def test_same_overlay_can_run_again_with_an_untracked_helper_directory(self):
        first, _ = self.overlay("first")
        self.assertEqual(first.returncode, 0, first.stderr)
        self.assertIn("?? server/src/test/resources/", self.git(self.old, "status", "--porcelain"))
        second, destination = self.overlay("second")
        self.assertEqual(second.returncode, 0, second.stderr)
        self.assertEqual((self.old / HELPER).read_bytes(), (self.current / HELPER).read_bytes())
        proof = json.loads((destination / "inputs.json").read_text())
        self.assertEqual(proof["sourceInputs"][HELPER], hashlib.sha256((self.current / HELPER).read_bytes()).hexdigest())
        self.assertEqual(proof["sourceInputs"]["scripts/warehouse/historical-upgrades.sh"],
                         hashlib.sha256(RUNNER.read_bytes()).hexdigest())
        self.assertEqual((self.old / f"{MIGRATIONS}/V1__base.sql").read_text(), "SELECT 1;\n")

    def test_extra_untracked_file_is_rejected_and_preserved(self):
        first, _ = self.overlay("first")
        self.assertEqual(first.returncode, 0, first.stderr)
        self.write(self.old, "server/src/test/resources/warehouse/unexpected.txt", "preserve this\n")
        second, _ = self.overlay("second")
        self.assertNotEqual(second.returncode, 0)
        self.assertIn("Unexpected historical worktree modifications", second.stderr)
        self.assertEqual((self.old / "server/src/test/resources/warehouse/unexpected.txt").read_text(), "preserve this\n")

    def test_changed_pinned_migration_is_rejected_without_rewriting_it(self):
        self.write(self.old, f"{MIGRATIONS}/V1__base.sql", "SELECT 999;\n")
        result, _ = self.overlay("changed")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Historical migration changed", result.stderr)
        self.assertEqual((self.old / f"{MIGRATIONS}/V1__base.sql").read_text(), "SELECT 999;\n")

    def test_package_rename_adapts_only_harness_and_preserves_historical_product(self):
        name = "com.karuhun.ftth.inventory.PackageUpgradeIT"
        source = "server/src/historicalTest/kotlin/com/karuhun/ftth/inventory/PackageUpgradeIT.kt"
        self.write(self.current, source, "package com.karuhun.ftth.inventory\nimport com.karuhun.ftth.FtthApplication\n")
        original = (self.old / "server/src/main/kotlin/com/legacy/ftth/FtthApplication.kt").read_bytes()
        result, destination = self.overlay("renamed", name)
        self.assertEqual(result.returncode, 0, result.stderr)
        proof = json.loads((destination / "inputs.json").read_text())
        self.assertEqual(proof["classes"], ["com.legacy.ftth.inventory.PackageUpgradeIT"])
        target = self.old / "server/src/test/kotlin/com/legacy/ftth/inventory/PackageUpgradeIT.kt"
        self.assertEqual(target.read_text(), "package com.legacy.ftth.inventory\nimport com.legacy.ftth.FtthApplication\n")
        self.assertEqual((self.old / "server/src/main/kotlin/com/legacy/ftth/FtthApplication.kt").read_bytes(), original)
        self.assertIn("com.karuhun.ftth", (self.current / source).read_text())
        second, _ = self.overlay("renamed-again", name)
        self.assertEqual(second.returncode, 0, second.stderr)


if __name__ == "__main__":
    unittest.main()

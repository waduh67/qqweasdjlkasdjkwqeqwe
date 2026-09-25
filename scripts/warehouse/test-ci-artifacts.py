"""Exercise encryption recovery and rejection boundaries without production data."""
import hashlib
import json
import os
import subprocess
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("ci-artifacts.py")
ROOT = SCRIPT.resolve().parents[2]


class EvidenceArchiveTest(unittest.TestCase):
    def setUp(self):
        (ROOT / ".omo/runtime").mkdir(parents=True, exist_ok=True)
        temporary = tempfile.TemporaryDirectory(prefix="archive-test-", dir=ROOT / ".omo/runtime")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.raw = self.root / "raw"
        self.raw.mkdir()
        self.identity = self.root / "identity.key"
        subprocess.run(["age-keygen", "--output", str(self.identity)], check=True, capture_output=True)
        recipient = subprocess.check_output(["age-keygen", "--y", str(self.identity)], text=True).strip()
        self.environment = dict(os.environ, WAREHOUSE_EVIDENCE_RECIPIENT=recipient)
        self.archive = self.root / "evidence.age"

    def run_archive(self, *paths, environment=None):
        return subprocess.run([sys.executable, str(SCRIPT), "--commit", "a" * 40, "--output", str(self.archive),
                               *map(str, paths)], env=environment or self.environment, capture_output=True, text=True)

    def test_raw_report_roundtrip_and_manifest_identity(self):
        report = self.raw / "report.xml"
        report.write_bytes(b"<testsuite><system-out>private fixture output</system-out></testsuite>")
        result = self.run_archive(self.raw)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("private fixture output", result.stdout + result.stderr)
        decoded = self.root / "decoded.tar.gz"
        subprocess.run(["age", "--decrypt", "--identity", str(self.identity), "--output", str(decoded), str(self.archive)], check=True)
        with tarfile.open(decoded) as bundle:
            self.assertEqual(len(bundle.getmembers()), 1)
            self.assertEqual(bundle.extractfile(bundle.getmembers()[0]).read(), report.read_bytes())
        proof = json.loads(self.archive.with_suffix(".age.json").read_text())
        self.assertEqual(proof["commit"], "a" * 40)
        self.assertEqual(proof["fileCount"], 1)
        self.assertEqual(proof["encryptedArchiveSha256"], hashlib.sha256(self.archive.read_bytes()).hexdigest())
        self.assertNotEqual(self.run_archive(self.raw).returncode, 0)

    def test_environment_files_are_rejected_before_encryption(self):
        (self.raw / "warehouse-test.env").write_text("PRIVATE_TEST_SETTING=test-only")
        result = self.run_archive(self.raw)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Environment files", result.stderr)
        self.assertFalse(self.archive.exists())

    def test_symlink_attachment_and_missing_recipient_are_rejected(self):
        (self.raw / "report.json").symlink_to(self.identity)
        result = self.run_archive(self.raw)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("cannot use symlinks", result.stderr)
        result = self.run_archive(self.raw, environment=dict(os.environ, WAREHOUSE_EVIDENCE_RECIPIENT=""))
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.archive.exists())


if __name__ == "__main__":
    unittest.main()

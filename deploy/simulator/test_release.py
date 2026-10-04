import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import Mock

from install_release import Installer, check_config, check_manifest, extract_backup


class ReleaseBoundaryTest(unittest.TestCase):
    def test_recovery_cannot_use_a_production_data_volume(self):
        config = {"name": "ftth-sim", "networks": {"default": {"internal": True}},
                  "volumes": {"pgdata": {"name": "ftth_pgdata"}}, "services": {}}
        with self.assertRaisesRegex(ValueError, "own project"):
            check_config(config)
        config["volumes"]["pgdata"] = {"name": "ftth-sim_pgdata", "external": True}
        with self.assertRaisesRegex(ValueError, "own project"):
            check_config(config)

    def test_recovery_rejects_traversal_and_links_before_writing(self):
        for name, kind in [("snapshot/../../escape", tarfile.REGTYPE), ("snapshot/env", tarfile.SYMTYPE)]:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                archive = root / "backup.tar.gz"
                with tarfile.open(archive, "w:gz") as output:
                    member = tarfile.TarInfo(name)
                    member.type = kind
                    if kind == tarfile.SYMTYPE:
                        member.linkname = "/etc/passwd"
                    output.addfile(member, io.BytesIO(b""))
                with self.assertRaisesRegex(ValueError, "Unsafe"):
                    extract_backup(archive, root / "restored")
                self.assertFalse((root / "restored").exists())

    def test_low_memory_stops_before_any_service_mutation(self):
        with tempfile.TemporaryDirectory() as folder:
            installer = Installer(Path(folder), {}, "sim.example.org", None)
            installer.run = Mock(return_value=str(3 * 1024 ** 3))
            with self.assertRaisesRegex(RuntimeError, "8 GB"):
                installer.preflight()
            self.assertEqual(installer.run.call_count, 1)
            self.assertEqual(installer.run.call_args.args[0][:2], ["docker", "info"])

    def test_restore_refuses_an_active_application(self):
        with tempfile.TemporaryDirectory() as folder:
            installer = Installer(Path(folder), {}, "sim.example.org", None)
            installer.state = {"phase": "RESTORING"}
            installer.run = Mock(return_value="running-container")
            with self.assertRaisesRegex(RuntimeError, "application container"):
                installer.restore(["docker", "compose", "-p", "ftth-sim"])
            self.assertEqual(installer.run.call_count, 1)
            self.assertIn("ps", installer.run.call_args.args[0])

    def test_completed_install_never_replays_backup(self):
        with tempfile.TemporaryDirectory() as folder:
            installer = Installer(Path(folder), {}, "sim.example.org", None)
            installer.state = {"phase": "COMPLETE"}
            installer.run = Mock()
            installer.restore(["docker", "compose", "-p", "ftth-sim"])
            installer.run.assert_not_called()

    def test_mutable_or_foreign_image_references_are_rejected(self):
        manifest = {"commit": "a" * 40, "applicationCommit": "b" * 40,
                    "images": {name: "ghcr.io/example/ftth-demo@sha256:" + "c" * 64
                               for name in ("server", "web", "simulator", "minio", "genieacs-cwmp", "genieacs-nbi", "genieacs-sim")}}
        check_manifest(manifest)
        for value in ["postgres:latest", "ghcr.io/example/ftth-server:latest", "https://example.org/image"]:
            with self.subTest(value=value):
                manifest["images"]["server"] = value
                with self.assertRaisesRegex(ValueError, "immutable"):
                    check_manifest(manifest)


if __name__ == "__main__":
    unittest.main()

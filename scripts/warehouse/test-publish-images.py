import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("publish_images", Path(__file__).with_name("publish-images.py"))
publication = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publication)


class PublicationProofTest(unittest.TestCase):
    def setUp(self):
        self.commit = "a" * 40
        self.proof = {"commit": self.commit, "sameImagesAfterRestart": True, "backendAndGatewayJsonReadiness": True,
                      "images": {"server": "sha256:" + "b" * 64, "web": "sha256:" + "c" * 64},
                      "phases": [{"phase": phase, "realHttp": True, "sqlBusinessSeeding": False,
                                  "stockKinds": 4, "persistedReplays": 20, "persistedReads": 12, "staticAssets": 2}
                                 for phase in ["before-restart", "after-restart"]]}

    def test_accepts_executed_read_write_and_restart_for_exact_images(self):
        self.assertEqual(publication.validate(self.proof, self.commit), self.proof["images"])

    def test_rejects_missing_readiness_restart_or_other_commit(self):
        for field, value in [("commit", "d" * 40), ("sameImagesAfterRestart", False),
                             ("backendAndGatewayJsonReadiness", False), ("images", {}), ("phases", [])]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                publication.validate(self.proof | {field: value}, self.commit)

    def test_rejects_zero_unexecuted_or_seeded_smoke_cases(self):
        for phase in [0, 1]:
            for field, value in [("realHttp", False), ("sqlBusinessSeeding", True), ("stockKinds", 0),
                                 ("persistedReads", 0), ("persistedReplays", 0), ("staticAssets", 0)]:
                with self.subTest(phase=phase, field=field), self.assertRaises(ValueError):
                    proof = copy.deepcopy(self.proof)
                    proof["phases"][phase][field] = value
                    publication.validate(proof, self.commit)

    def test_rejects_mutable_image_tags(self):
        for image in ["server", "web"]:
            with self.subTest(image=image), self.assertRaises(ValueError):
                proof = copy.deepcopy(self.proof)
                proof["images"][image] = "ftth:latest"
                publication.validate(proof, self.commit)

    def test_archive_digest_uses_actual_image_config_and_rejects_other_revision_or_tags(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "server.tar"
            config = {"config": {"Labels": {"org.opencontainers.image.revision": self.commit}}}
            def archive(tags, configuration):
                content = json.dumps(configuration).encode()
                manifest = [{"Config": "config.json", "RepoTags": tags, "Layers": []}]
                with tarfile.open(path, "w") as bundle:
                    for name, data in [("config.json", content), ("manifest.json", json.dumps(manifest).encode())]:
                        member = tarfile.TarInfo(name)
                        member.size = len(data)
                        bundle.addfile(member, io.BytesIO(data))
                return content
            tags = [f"warehouse-ci-server:{self.commit}"]
            content = archive(tags, config)
            self.assertEqual(publication.archive_config(path, "server", self.commit), "sha256:" + hashlib.sha256(content).hexdigest())
            archive(["ftth-server:latest"], config)
            with self.assertRaises(ValueError):
                publication.archive_config(path, "server", self.commit)
            archive(tags, {"config": {"Labels": {"org.opencontainers.image.revision": "d" * 40}}})
            with self.assertRaises(ValueError):
                publication.archive_config(path, "server", self.commit)


if __name__ == "__main__":
    unittest.main()

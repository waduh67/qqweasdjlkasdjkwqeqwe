#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile


def digest(path):
    checksum = hashlib.sha256()
    with path.open("rb") as content:
        while block := content.read(1024 * 1024):
            checksum.update(block)
    return "sha256:" + checksum.hexdigest()


def archive_config(path, service, commit):
    with tarfile.open(path) as bundle:
        manifests = json.load(bundle.extractfile("manifest.json"))
        if len(manifests) != 1 or manifests[0].get("RepoTags") != [f"warehouse-ci-{service}:{commit}"]:
            raise ValueError("Image archive has unexpected images or tags")
        member = bundle.getmember(manifests[0]["Config"])
        if not member.isfile() or member.size > 1024 * 1024:
            raise ValueError("Invalid image configuration member")
        content = bundle.extractfile(member).read()
        config = json.loads(content)
        if config.get("config", {}).get("Labels", {}).get("org.opencontainers.image.revision") != commit:
            raise ValueError("Archived image belongs to another source revision")
        return "sha256:" + hashlib.sha256(content).hexdigest()


def validate(proof, commit):
    if not re.fullmatch(r"[a-f0-9]{40}", commit) or proof.get("commit") != commit:
        raise ValueError("Tested image commit differs from release commit")
    if proof.get("sameImagesAfterRestart") is not True or proof.get("backendAndGatewayJsonReadiness") is not True:
        raise ValueError("Missing executed image restart or JSON readiness proof")
    images = proof.get("images", {})
    if set(images) != {"server", "web"} or any(not re.fullmatch(r"sha256:[a-f0-9]{64}", value) for value in images.values()):
        raise ValueError("Missing exact tested image identities")
    phases = proof.get("phases", [])
    if [phase.get("phase") for phase in phases] != ["before-restart", "after-restart"]:
        raise ValueError("Missing image smoke phase")
    for phase in phases:
        if not (phase.get("realHttp") is True and phase.get("sqlBusinessSeeding") is False
                and phase.get("stockKinds") == 4 and phase.get("persistedReplays", 0) > 0
                and phase.get("persistedReads", 0) > 0 and phase.get("staticAssets", 0) > 0):
            raise ValueError("Incomplete image smoke assertions")
    return images


def main():
    parser = argparse.ArgumentParser(description="Publish the already tested Docker images without rebuilding")
    parser.add_argument("directory", type=Path)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--prefix")
    parser.add_argument("--prepare", action="store_true", help="Bind the tested image proof to its saved Docker archives")
    args = parser.parse_args()
    proof = json.loads((args.directory / "verification.json").read_text())
    images = validate(proof, args.commit)
    if not args.prepare:
        if not re.fullmatch(r"ghcr.io/[a-z0-9][a-z0-9-]*", args.prefix or ""):
            parser.error("Expected the configured GHCR owner prefix")
        if os.environ.get("GITHUB_REF") != "refs/heads/main" or os.environ.get("GITHUB_SHA") != args.commit:
            parser.error("Publication requires the main-branch deployment run and its exact commit")
    archive_digests, config_digests = {}, {}
    for service, expected in images.items():
        archive = args.directory / f"{service}.tar"
        if not archive.is_file() or archive.is_symlink():
            parser.error("Tested image archive missing or unsafe")
        archive_digests[service] = digest(archive)
        config_digests[service] = archive_config(archive, service, args.commit)
    if args.prepare:
        for service, expected in images.items():
            actual = subprocess.check_output(["docker", "image", "inspect", "--format", "{{.Id}}",
                                               f"warehouse-ci-{service}:{args.commit}"], text=True).strip()
            if actual != expected:
                parser.error("Saved image tag no longer references the tested image")
        proof.update(archiveDigests=archive_digests, configDigests=config_digests)
        (args.directory / "verification.json").write_text(json.dumps(proof, indent=2) + "\n")
        print("PASS: saved image archives are bound to the executed smoke proof")
        return
    if proof.get("archiveDigests") != archive_digests or proof.get("configDigests") != config_digests:
        parser.error("Downloaded image archive differs from the tested artifact")
    # Verify BOTH loaded images before the first registry write.
    for service, expected in images.items():
        archive = args.directory / f"{service}.tar"
        subprocess.run(["docker", "load", "--input", str(archive)], check=True)
        actual = json.loads(subprocess.check_output(["docker", "image", "inspect", expected]))[0]
        if actual["Id"] != expected or actual["Config"]["Labels"].get("org.opencontainers.image.revision") != args.commit:
            parser.error("Loaded image differs from the tested image")
    published = {}
    for service, expected in images.items():
        reference = f"{args.prefix}/ftth-{service}:{args.commit}"
        subprocess.run(["docker", "tag", expected, reference], check=True)
        subprocess.run(["docker", "push", reference], check=True)
        manifest = json.loads(subprocess.check_output(["docker", "manifest", "inspect", reference]))
        if manifest.get("config", {}).get("digest") != config_digests[service]:
            raise ValueError("Published manifest config differs from tested image identity")
        digests = json.loads(subprocess.check_output(["docker", "image", "inspect", "--format", "{{json .RepoDigests}}", reference]))
        matching = [digest for digest in digests if digest.startswith(reference.rsplit(":", 1)[0] + "@sha256:")]
        if len(matching) != 1:
            raise ValueError("Published registry digest is missing or ambiguous")
        published[service] = {"imageId": expected, "configDigest": config_digests[service],
                              "reference": reference, "registryDigest": matching[0]}
    (args.directory / "published.json").write_text(json.dumps({"commit": args.commit, "images": published}, indent=2) + "\n")
    print("PASS: published registry manifests reference the exact tested image configurations")


if __name__ == "__main__":
    main()

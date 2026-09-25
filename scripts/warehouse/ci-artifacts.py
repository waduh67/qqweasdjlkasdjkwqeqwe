#!/usr/bin/env python3
"""Encrypt raw QA evidence before it leaves the runner; never include environment files."""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import tarfile
import tempfile
from pathlib import Path


def digest(path):
    checksum = hashlib.sha256()
    with path.open("rb") as content:
        while block := content.read(1024 * 1024):
            checksum.update(block)
    return checksum.hexdigest()


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--artifact", type=Path)
    parser.add_argument("paths", nargs="+", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    recipient = os.environ.get("WAREHOUSE_EVIDENCE_RECIPIENT", "").strip()
    if not re.fullmatch(r"[a-f0-9]{40}", args.commit):
        parser.error("A full commit identity is required")
    if not re.fullmatch(r"(?:age1[0-9a-z]+|ssh-ed25519 [A-Za-z0-9+/]+={0,2})", recipient):
        parser.error("Configure WAREHOUSE_EVIDENCE_RECIPIENT with the approved public recipient")
    if not shutil.which("age"):
        parser.error("age is required to retain private QA evidence")
    if args.output.suffix != ".age" or not args.output.resolve().is_relative_to(root):
        parser.error("Encrypted output must be an .age file inside the checkout")
    if args.output.exists() or args.output.with_suffix(".age.json").exists():
        parser.error("Use a fresh archive path; previous evidence must not be overwritten")
    files = set()
    for requested in args.paths:
        requested = requested if requested.is_absolute() else root / requested
        if requested.is_symlink() or not requested.resolve().is_relative_to(root):
            parser.error("Evidence must stay inside the checkout and cannot use symlinks")
        candidates = requested.rglob("*") if requested.is_dir() else [requested]
        for path in candidates:
            if not path.is_file():
                continue
            if path.is_symlink() or not path.resolve().is_relative_to(root):
                parser.error("Evidence must stay inside the checkout and cannot use symlinks")
            if path.suffix.lower() == ".env" or path.name.startswith(".env"):
                parser.error("Environment files must never enter the evidence archive")
            if path.suffix.lower() not in (".xml", ".json", ".zip", ".png", ".log", ".txt", ".webm"):
                continue
            files.add(path)
    if not files:
        parser.error("No raw QA evidence was produced")
    if args.artifact and not args.artifact.is_file():
        parser.error("The tested application artifact is missing")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    runtime = root / ".omo/runtime"
    runtime.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="warehouse-evidence-", dir=runtime) as temporary:
        archive = Path(temporary) / "evidence.tar.gz"
        with tarfile.open(archive, "w:gz") as bundle:
            for path in sorted(files):
                bundle.add(path, arcname=path.relative_to(root), recursive=False)
        subprocess.run(["age", "--encrypt", "--recipient", recipient, "--output", str(args.output), str(archive)], check=True)
    proof = {"commit": args.commit, "encryptedArchiveSha256": digest(args.output),
             "recipientSha256": hashlib.sha256(recipient.encode()).hexdigest(), "fileCount": len(files)}
    if args.artifact:
        proof["artifactSha256"] = digest(args.artifact)
    args.output.with_suffix(args.output.suffix + ".json").write_text(json.dumps(proof, indent=2) + "\n")
    print(f"PASS: encrypted {len(files)} raw evidence files; environment files excluded")


if __name__ == "__main__":
    main()

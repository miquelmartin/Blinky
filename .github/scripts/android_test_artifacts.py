import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

from verify_test_inventory import expected_tests


APKS = {
    "app-debug.apk": Path("app/build/outputs/apk/debug/app-debug.apk"),
    "app-debug-androidTest.apk": Path("app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"),
}


def identity():
    checkout = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True, timeout=10).strip()
    if checkout != os.environ["GITHUB_SHA"]:
        raise ValueError("Checkout does not match the workflow merge candidate")
    return {
        "run_id": os.environ["GITHUB_RUN_ID"],
        "attempt": os.environ["GITHUB_RUN_ATTEMPT"],
        "merge_sha": checkout,
        "head_sha": os.environ["PR_HEAD_SHA"],
        "base_sha": os.environ["PR_BASE_SHA"],
        "native_tests": [list(test) for test in sorted(expected_tests(Path("app/src/androidTest")))],
    }


def digest(path):
    if not path.is_file() or path.is_symlink() or path.stat().st_size == 0:
        raise ValueError(f"Missing, empty or non-regular APK: {path}")
    with path.open("rb") as apk:
        return hashlib.file_digest(apk, "sha256").hexdigest()


def produce(directory):
    directory.mkdir()
    manifest = identity()
    manifest["apks"] = {}
    for name, source in APKS.items():
        checksum = digest(source)
        shutil.copyfile(source, directory / name)
        manifest["apks"][name] = checksum
    (directory / "manifest.json").write_text(json.dumps(manifest, sort_keys=True) + "\n", encoding="utf-8")
    verify(directory)


def verify(directory):
    manifest_path = directory / "manifest.json"
    if manifest_path.is_symlink():
        raise ValueError("Artifact manifest must not be a symlink")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    expected = identity()
    expected["apks"] = {name: digest(directory / name) for name in APKS}
    if manifest != expected or {path.name for path in directory.iterdir()} != {*APKS, "manifest.json"}:
        raise ValueError("APK artifact provenance, inventory, file set or checksum mismatch")
    print(f"Verified both APKs for merge {expected['merge_sha']}, run {expected['run_id']}/{expected['attempt']}.")
    return {name: directory / name for name in APKS}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Bind test APKs to this exact PR merge candidate and workflow attempt.")
    parser.add_argument("operation", choices=("produce", "verify"))
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    (produce if args.operation == "produce" else verify)(args.directory)

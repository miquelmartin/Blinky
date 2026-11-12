import os
from pathlib import Path
import shutil
import subprocess
import time


temporary = Path(os.environ["RUNNER_TEMP"]).resolve(strict=True)
sdk = Path(os.environ["ANDROID_HOME"]).resolve(strict=True)
deadline = time.monotonic() + 145
staging = [sdk / ".temp", sdk / ".downloadIntermediates"]


def command(args, seconds):
    remaining = int(deadline - time.monotonic())
    if remaining <= 0:
        raise TimeoutError("Disk diagnostic deadline exceeded")
    result = subprocess.run(
        ["timeout", "--kill-after=2s", f"{min(seconds, remaining)}s", *map(str, args)],
        capture_output=True, text=True, timeout=min(seconds, remaining) + 5,
    )
    if result.returncode:
        print((result.stdout + result.stderr)[-6000:], flush=True)
        raise RuntimeError(f"Diagnostic command failed: {args[0]} exit={result.returncode}")
    return result.stdout


def describe(path):
    if not path.exists():
        print(f"path={path} absent", flush=True)
        return
    resolved = path.resolve(strict=True)
    if resolved != path or path.is_symlink():
        raise ValueError(f"Refusing redirected diagnostic path: {path}")
    info = path.stat()
    print(f"path={resolved} uid={info.st_uid} gid={info.st_gid} mode={oct(info.st_mode & 0o777)} device={info.st_dev}", flush=True)


def snapshot(label):
    free = shutil.disk_usage(temporary).free
    print(f"\n{label}: free_bytes={free}", flush=True)
    print(command(["df", "-B1", "--output=source,fstype,size,used,avail,target", temporary], 5), flush=True)
    print(command(["du", "-x", "-B1", "--max-depth=1", temporary], 10)[:12000], flush=True)
    for path in staging:
        describe(path)
    return free


describe(temporary)
describe(sdk)
before_entries = {}
for root in staging:
    describe(root)
    before_entries[root] = {path.name for path in root.iterdir()} if root.exists() else set()
before = snapshot("BEFORE SDK setup and after exact APK transfer")
try:
    print("Installing side-by-side cmdline-tools;23.0; successful progress suppressed.", flush=True)
    command([sdk / "cmdline-tools/latest/bin/sdkmanager", "--install", "cmdline-tools;23.0"], 60)
    print("Installing unchanged emulator/platform-tools/API37 Google APIs image; successful progress suppressed.", flush=True)
    command([sdk / "cmdline-tools/23.0/bin/sdkmanager", "--install", "emulator", "platform-tools",
             "system-images;android-37.0;google_apis;x86_64"], 150)
finally:
    deadline = time.monotonic() + 25
    after = snapshot("AFTER SDK setup attempt")
    print(f"same_job_free_space_delta_bytes={before - after}", flush=True)
    for root in staging:
        if not root.exists():
            continue
        if root.resolve(strict=True) != root or root.is_symlink():
            raise ValueError(f"Refusing redirected staging root: {root}")
        for path in sorted(root.iterdir()):
            if path.name in before_entries[root]:
                print(f"preexisting staging entry not scanned: {root}/{path.name}", flush=True)
                continue
            describe(path)
            if path.stat().st_uid != os.getuid():
                print(f"new entry not owned by current user; not scanned: {path}", flush=True)
                continue
            print("New staging entry, measurement only; no deletion authorization:", flush=True)
            print(command(["du", "-x", "-B1", "--max-depth=2", path], 10)[:12000], flush=True)
for package in (sdk / "cmdline-tools/23.0", sdk / "emulator",
                sdk / "system-images/android-37.0/google_apis/x86_64"):
    print(f"Installed package is NOT a cleanup candidate: {package}", flush=True)
    properties = package / "source.properties"
    for line in properties.read_text(encoding="utf-8").splitlines():
        if line.startswith(("Pkg.Revision=", "Pkg.BuildId=", "AndroidVersion.ApiLevel=")):
            print(line, flush=True)
print("Diagnostic only: no AVD, emulator, APK install, native tests, build, cleanup or verification gate executed.", flush=True)

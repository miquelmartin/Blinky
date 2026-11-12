import os
from pathlib import Path
import shutil
import stat
import subprocess


DOTNET = Path("/usr/share/dotnet")
OBSERVED_ANDROID_SETUP_BYTES = 7_041_073_152
MINIMUM_POST_SETUP_BYTES = 8 * 1024**3 + 16 * 1024**2
SETUP_HEADROOM_BYTES = 1024**3
MINIMUM_PRE_SETUP_BYTES = (
    MINIMUM_POST_SETUP_BYTES + OBSERVED_ANDROID_SETUP_BYTES + SETUP_HEADROOM_BYTES
)


def command(args, seconds):
    result = subprocess.run(
        ["timeout", "--kill-after=3s", f"{seconds}s", *map(str, args)],
        check=False,
        capture_output=True,
        text=True,
        timeout=seconds + 5,
    )
    if result.returncode:
        print(((result.stdout or "") + (result.stderr or ""))[-4000:], flush=True)
        raise RuntimeError(f"Command failed: {args[0]} exit={result.returncode}")
    return result.stdout


def mounted_paths():
    paths = set()
    for line in Path("/proc/self/mountinfo").read_text(encoding="utf-8").splitlines():
        fields = line.split()
        if len(fields) > 4:
            paths.add(fields[4].replace("\\040", " ").replace("\\011", "\t")
                      .replace("\\012", "\n").replace("\\134", "\\"))
    return paths


def validate_target_metadata(path, info, root_device, mounts):
    target = path.as_posix()
    if not stat.S_ISDIR(info.st_mode):
        raise RuntimeError(f"Refusing non-directory cleanup target: {path}")
    if info.st_uid != 0:
        raise RuntimeError(f"Refusing cleanup target not owned by root: uid={info.st_uid}")
    if info.st_dev != root_device:
        raise RuntimeError(f"Refusing cleanup target on a separate filesystem: {path}")
    nested_mounts = sorted(
        mount for mount in mounts
        if mount == target or mount.startswith(f"{target}/")
    )
    if nested_mounts:
        raise RuntimeError(f"Refusing mounted content under cleanup target: {nested_mounts}")


def removal_command():
    return ["sudo", "-n", "rm", "-rf", "--one-file-system", "--", DOTNET]


def require_safe_target():
    if not DOTNET.exists() or DOTNET.is_symlink():
        raise RuntimeError("Expected /usr/share/dotnet to be an existing real directory")
    if DOTNET.resolve(strict=True) != DOTNET:
        raise RuntimeError("Refusing redirected /usr/share/dotnet path")
    info = DOTNET.lstat()
    validate_target_metadata(DOTNET, info, os.stat("/").st_dev, mounted_paths())
    temporary = Path(os.environ["RUNNER_TEMP"]).resolve(strict=True)
    sdk = Path(os.environ["ANDROID_HOME"]).resolve(strict=True)
    if os.stat(temporary).st_dev != info.st_dev or os.stat(sdk).st_dev != info.st_dev:
        raise RuntimeError("Runner temp, Android SDK and .NET directory must share one filesystem")
    return info


def available_bytes():
    return shutil.disk_usage(Path(os.environ["RUNNER_TEMP"])).free


def main():
    if os.environ.get("RUNNER_OS") != "Linux":
        raise RuntimeError("Refusing cleanup outside a Linux GitHub-hosted runner")
    if os.environ.get("RUNNER_ENVIRONMENT") != "github-hosted":
        raise RuntimeError("Refusing cleanup outside a GitHub-hosted runner")
    release = {}
    for line in Path("/etc/os-release").read_text(encoding="utf-8").splitlines():
        key, separator, value = line.partition("=")
        if separator:
            release[key] = value.strip('"')
    if release.get("VERSION_ID") != "24.04":
        raise RuntimeError(f"Expected Ubuntu 24.04, got {release.get('VERSION_ID')!r}")

    info = require_safe_target()
    print(f"Verified cleanup target={DOTNET} uid={info.st_uid} device={info.st_dev}.", flush=True)
    before = available_bytes()
    print(f"Free bytes before cleanup: {before}", flush=True)
    print(command(["df", "-B1", "--output=source,fstype,size,used,avail,target",
                   os.environ["RUNNER_TEMP"]], 5), flush=True)
    print(command(["du", "-sx", "-B1", "--", DOTNET], 20), flush=True)
    command(["sudo", "-n", "true"], 5)
    command(removal_command(), 30)
    if DOTNET.exists() or DOTNET.is_symlink():
        raise RuntimeError("Exact .NET cleanup path still exists")

    after = available_bytes()
    reclaimed = after - before
    print(f"Free bytes after cleanup: {after}", flush=True)
    print(f"Bytes reclaimed from exact .NET directory: {reclaimed}", flush=True)
    if reclaimed <= 0:
        raise RuntimeError("Exact .NET cleanup did not reclaim disk space")
    print(command(["df", "-B1", "--output=source,fstype,size,used,avail,target",
                   os.environ["RUNNER_TEMP"]], 5), flush=True)
    if after < MINIMUM_PRE_SETUP_BYTES:
        raise RuntimeError(
            f"Refusing SDK setup: expected at least {MINIMUM_PRE_SETUP_BYTES} free bytes "
            f"(8 GiB + 16 MiB final guard, observed SDK footprint, and 1 GiB headroom); "
            f"observed {after}"
        )
    print("Sufficient measured cleanup headroom for the unchanged Android SDK setup.", flush=True)


if __name__ == "__main__":
    main()

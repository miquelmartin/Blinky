import os
from pathlib import Path
import shutil
import stat
import subprocess
import time


DOTNET = Path("/usr/share/dotnet")
TOTAL_SECONDS = 180
RESERVE_BYTES = 16 * 1024**2
MINIMUM_FREE_BYTES = 8 * 1024**3 + RESERVE_BYTES


def command(args, seconds, *, capture=True):
    remaining = int(_deadline - time.monotonic())
    if remaining <= 0:
        raise TimeoutError("Hosted disk preflight deadline exceeded")
    limit = min(seconds, remaining)
    result = subprocess.run(
        ["timeout", "--kill-after=3s", f"{limit}s", *map(str, args)],
        check=False,
        capture_output=capture,
        text=True,
        timeout=limit + 5,
    )
    if result.returncode:
        if capture:
            print(((result.stdout or "") + (result.stderr or ""))[-4000:], flush=True)
        raise RuntimeError(f"Command failed: {args[0]} exit={result.returncode}")
    return result.stdout if capture else ""


def check_host():
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


def mount_points():
    points = set()
    for line in Path("/proc/self/mountinfo").read_text(encoding="utf-8").splitlines():
        fields = line.split()
        if len(fields) > 4:
            points.add(fields[4].replace("\\040", " ").replace("\\011", "\t")
                       .replace("\\012", "\n").replace("\\134", "\\"))
    return points


def guard_dotnet_path():
    if not DOTNET.exists() or DOTNET.is_symlink():
        raise RuntimeError("Expected /usr/share/dotnet to be an existing real directory")
    resolved = DOTNET.resolve(strict=True)
    if resolved != DOTNET or not stat.S_ISDIR(DOTNET.lstat().st_mode):
        raise RuntimeError(f"Refusing unexpected .NET path type or resolution: {resolved}")
    info = DOTNET.stat()
    if info.st_uid != 0:
        raise RuntimeError(f"Refusing .NET directory not owned by root: uid={info.st_uid}")
    if info.st_dev != os.stat("/").st_dev:
        raise RuntimeError("Refusing .NET directory on a separate filesystem")
    nested_mounts = sorted(
        point for point in mount_points()
        if point == str(DOTNET) or point.startswith(f"{DOTNET}/")
    )
    if nested_mounts:
        raise RuntimeError(f"Refusing mounted content under .NET directory: {nested_mounts}")

    temporary = Path(os.environ["RUNNER_TEMP"]).resolve(strict=True)
    sdk = Path(os.environ["ANDROID_HOME"]).resolve(strict=True)
    if os.stat(temporary).st_dev != info.st_dev or os.stat(sdk).st_dev != info.st_dev:
        raise RuntimeError("Runner temp, Android SDK and .NET directory must share one filesystem")
    return info


def disk_snapshot(label):
    temporary = Path(os.environ["RUNNER_TEMP"])
    free = shutil.disk_usage(temporary).free
    print(f"{label}: runner_temp_free_bytes={free}", flush=True)
    print(command(["df", "-B1", "--output=source,fstype,size,used,avail,target", temporary], 5), flush=True)
    return free


def property(path, name, expected):
    values = []
    for line in path.read_text(encoding="utf-8").splitlines():
        key, separator, value = line.partition("=")
        if separator and key.strip() == name:
            values.append(value.strip())
    if values != [expected]:
        raise RuntimeError(f"{path}: expected exactly {name}={expected}, got {values}")
    print(f"{path.name}: {name}={expected}", flush=True)


def install_sdk():
    sdk = Path(os.environ["ANDROID_HOME"])
    bootstrap = sdk / "cmdline-tools/latest/bin/sdkmanager"
    tools = sdk / "cmdline-tools/23.0/bin"
    print("Installing side-by-side Android command-line tools 23.0.", flush=True)
    command([bootstrap, "--install", "cmdline-tools;23.0"], 60)
    property(tools.parent / "source.properties", "Pkg.Revision", "23.0")

    print("Installing emulator, platform-tools and API37 Google APIs image.", flush=True)
    command([tools / "sdkmanager", "--install", "emulator", "platform-tools",
             "system-images;android-37.0;google_apis;x86_64"], 140)
    property(sdk / "emulator/source.properties", "Pkg.Revision", "37.2.13")
    image = sdk / "system-images/android-37.0/google_apis/x86_64/source.properties"
    property(image, "Pkg.Revision", "7")
    if not (sdk / "platform-tools/adb").is_file():
        raise RuntimeError("Android platform-tools adb binary is missing after setup")
    platform_tools = sdk / "platform-tools/source.properties"
    if platform_tools.is_file():
        for line in platform_tools.read_text(encoding="utf-8").splitlines():
            if line.startswith("Pkg.Revision="):
                print(line, flush=True)


def main():
    global _deadline
    _deadline = time.monotonic() + TOTAL_SECONDS
    check_host()
    info = guard_dotnet_path()
    print(
        f"Approved cleanup target={DOTNET} uid={info.st_uid} "
        f"device={info.st_dev}; no other path is in scope.",
        flush=True,
    )
    before = disk_snapshot("BEFORE")
    print(command(["du", "-sx", "-B1", "--", DOTNET], 20), flush=True)
    command(["sudo", "-n", "true"], 5)
    command(["sudo", "-n", "rm", "-rf", "--one-file-system", "--", DOTNET], 30)
    if DOTNET.exists() or DOTNET.is_symlink():
        raise RuntimeError("Exact .NET cleanup path still exists")
    after_cleanup = disk_snapshot("AFTER_EXACT_DOTNET_CLEANUP")
    print(f"freed_bytes={after_cleanup - before}", flush=True)
    if after_cleanup <= before:
        raise RuntimeError("Exact cleanup did not increase available space")

    try:
        install_sdk()
    finally:
        after_setup = disk_snapshot("AFTER_API37_SDK_SETUP")
        print(f"cleanup_to_setup_space_delta_bytes={after_cleanup - after_setup}", flush=True)
    if after_setup < MINIMUM_FREE_BYTES:
        raise RuntimeError(
            f"API37 setup requires at least {MINIMUM_FREE_BYTES} bytes free "
            f"(8 GiB plus 16 MiB reserve); observed {after_setup}"
        )
    print(
        f"PASS: supported package revisions installed and {after_setup} bytes "
        "remain free; no APK, AVD, emulator or test was run.",
        flush=True,
    )


if __name__ == "__main__":
    main()

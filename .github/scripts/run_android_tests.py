import os
from pathlib import Path
import shutil
import signal
import subprocess
import time


def run(command, seconds, *, check=True, capture=False):
    try:
        return subprocess.run(
            ["timeout", "--kill-after=10s", f"{seconds}s", *map(str, command)],
            check=check,
            capture_output=capture,
            text=True,
            timeout=seconds + 15,
        )
    except subprocess.CalledProcessError as error:
        if capture:
            print(((error.stdout or "") + (error.stderr or ""))[-12000:], flush=True)
        raise


def wait_for_framework(adb, seconds=180, *, emulator=None):
    probes = [
        (["getprop", "sys.boot_completed"], lambda output: output.strip() == "1"),
        (["service", "check", "input"], lambda output: "found" in output and "not found" not in output),
        (["cmd", "input", "help"], lambda output: "keyevent" in output),
        (["cmd", "package", "path", "android"], lambda output: output.strip().startswith("package:")),
        (["settings", "get", "global", "device_provisioned"], lambda output: output.strip() in {"0", "1", "null"}),
    ]
    deadline = time.monotonic() + seconds
    ready_samples = 0
    last = ""
    while time.monotonic() < deadline:
        for command, ready in probes:
            if emulator is not None and emulator.poll() is not None:
                raise RuntimeError(f"Android emulator exited before readiness: exit={emulator.returncode}")
            remaining = int(deadline - time.monotonic())
            if remaining <= 0:
                raise TimeoutError(f"Android framework readiness deadline exceeded: {last}")
            result = run([*adb, "shell", *command], min(10, remaining), check=False, capture=True)
            last = f"{command}: exit={result.returncode}, {result.stdout.strip()} {result.stderr.strip()}"
            if result.returncode != 0 or not ready(result.stdout):
                ready_samples = 0
                break
        else:
            ready_samples += 1
            if ready_samples == 2:
                print("Android boot, input, package and settings services are ready.", flush=True)
                return
        time.sleep(2)
    raise TimeoutError(f"Android framework readiness deadline exceeded: {last}")


def require_property(path, name, expected):
    values = []
    for line in path.read_text(encoding="utf-8").splitlines():
        key, separator, value = line.partition("=")
        if separator and key.strip() == name:
            values.append(value.strip())
    if values != [expected]:
        raise ValueError(f"{path}: expected exactly {name}={expected}, got {values}")
    print(f"{path.name}: {name}={expected}", flush=True)


def configure_avd(path):
    settings = {
        "hw.cpu.ncore": "4",
        "hw.ramSize": "4096",
        "vm.heapSize": "576",
        "disk.dataPartition.size": "6G",
    }
    lines = [
        line for line in path.read_text(encoding="utf-8").splitlines()
        if line.partition("=")[0].strip() not in {*settings, "hw.heapSize"}
    ]
    path.write_text(
        "\n".join([*lines, *(f"{key}={value}" for key, value in settings.items())]) + "\n",
        encoding="utf-8",
    )
    for key, value in settings.items():
        require_property(path, key, value)


def require_storage(path):
    free = shutil.disk_usage(path).free
    print(f"AVD storage available: {free} bytes at {path}", flush=True)
    if free < 8 * 1024**3:
        raise RuntimeError(f"AVD storage requires at least 8 GiB free; available={free} bytes at {path}")


def main():
    sdk = Path(os.environ["ANDROID_HOME"])
    bootstrap = sdk / "cmdline-tools" / "latest" / "bin"
    tools = sdk / "cmdline-tools" / "23.0" / "bin"
    temporary = Path(os.environ["RUNNER_TEMP"])
    avd_home = temporary / "blinky-avd"
    avd_home.mkdir()
    os.environ["ANDROID_AVD_HOME"] = str(avd_home)
    adb = [str(sdk / "platform-tools" / "adb"), "-s", "emulator-5554"]
    emulator = None
    completed = False
    log_path = temporary / "blinky-emulator.log"
    try:
        print("Installing side-by-side Android command-line tools 23.0.", flush=True)
        run([bootstrap / "sdkmanager", "--install", "cmdline-tools;23.0"], 90, capture=True)
        properties = tools.parent / "source.properties"
        require_property(properties, "Pkg.Revision", "23.0")
        metadata_path = temporary / "blinky-avd-metadata.txt"
        metadata_path.write_text(properties.read_text(encoding="utf-8"), encoding="utf-8")
        print("Installing the API37 Google APIs image and emulator.", flush=True)
        run([tools / "sdkmanager", "--install", "emulator", "platform-tools",
             "system-images;android-37.0;google_apis;x86_64"], 180, capture=True)
        require_storage(avd_home)
        run([tools / "avdmanager", "create", "avd", "--force", "--name", "blinky-ci",
             "--package", "system-images;android-37.0;google_apis;x86_64",
             "--device", "pixel_2"], 30, capture=True)
        avd_metadata = avd_home / "blinky-ci.ini"
        with metadata_path.open("a", encoding="utf-8") as metadata:
            metadata.write("\nAVD target metadata:\n" + avd_metadata.read_text(encoding="utf-8"))
        require_property(avd_metadata, "target", "android-37.0")
        configure_avd(avd_home / "blinky-ci.avd" / "config.ini")
        with metadata_path.open("a", encoding="utf-8") as metadata:
            metadata.write("\nAVD configuration:\n" + (avd_home / "blinky-ci.avd" / "config.ini").read_text(encoding="utf-8"))
        with log_path.open("w", encoding="utf-8") as log:
            emulator = subprocess.Popen(
                [str(sdk / "emulator" / "emulator"), "-avd", "blinky-ci", "-port", "5554",
                 "-no-window", "-gpu", "swiftshader", "-no-snapshot", "-noaudio", "-no-boot-anim"],
                stdout=log, stderr=subprocess.STDOUT,
            )
        wait_for_framework(adb, emulator=emulator)
        effective = avd_home / "blinky-ci.avd" / "hardware-qemu.ini"
        with metadata_path.open("a", encoding="utf-8") as metadata:
            metadata.write("\nEffective emulator hardware:\n" + effective.read_text(encoding="utf-8"))
        for key, value in (("hw.cpu.ncore", "4"), ("hw.ramSize", "4096"), ("vm.heapSize", "576")):
            require_property(effective, key, value)
        run([*adb, "shell", "input", "keyevent", "82"], 10)
        for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            run([*adb, "shell", "settings", "put", "global", setting, "0.0"], 10)
        run([*adb, "shell", "df", "-h", "/data"], 10)
        run(["sh", "./gradlew", "--daemon", "--console=plain", ":app:connectedDebugAndroidTest"], 180)
        run(["python3", ".github/scripts/verify_test_inventory.py",
             "--source", "app/src/androidTest",
             "--reports", "app/build/outputs/androidTest-results/connected"], 15)
        completed = True
    finally:
        try:
            if not completed and emulator is not None:
                with (temporary / "blinky-crash.txt").open("w", encoding="utf-8") as log:
                    result = run([*adb, "logcat", "-b", "crash", "-d", "-t", "400"], 10, check=False, capture=True)
                    log.write(result.stdout + result.stderr)
                    print(f"Failure crash buffer captured (exit {result.returncode}).", flush=True)
                with (temporary / "blinky-logcat.txt").open("w", encoding="utf-8") as log:
                    result = run([*adb, "logcat", "-d", "-t", "400"], 10, check=False, capture=True)
                    log.write(result.stdout + result.stderr)
                    print(f"Failure logcat captured (exit {result.returncode}).", flush=True)
                if log_path.is_file():
                    print(log_path.read_text(encoding="utf-8", errors="replace")[-6000:], flush=True)
        finally:
            if emulator is not None and emulator.poll() is None:
                emulator.terminate()
                try:
                    emulator.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    emulator.kill()
                    emulator.wait(timeout=5)


def terminate(_signal, _frame):
    raise SystemExit(143)


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, terminate)
    main()

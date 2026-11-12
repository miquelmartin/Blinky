import os
from pathlib import Path
import signal
import subprocess
import time


def run(command, seconds, *, check=True, capture=False):
    return subprocess.run(
        ["timeout", "--kill-after=10s", f"{seconds}s", *map(str, command)],
        check=check,
        capture_output=capture,
        text=True,
        timeout=seconds + 15,
    )


def wait_for_framework(adb, seconds=180):
    probes = [
        (["getprop", "sys.boot_completed"], lambda output: output.strip() == "1"),
        (["getprop", "init.svc.bootanim"], lambda output: output.strip() == "stopped"),
        (["service", "check", "input"], lambda output: "found" in output and "not found" not in output),
        (["cmd", "input", "help"], lambda output: "keyevent" in output),
        (["cmd", "package", "path", "android"], lambda output: output.strip().startswith("package:")),
        (["settings", "get", "global", "device_provisioned"], lambda output: output.strip() == "1"),
    ]
    deadline = time.monotonic() + seconds
    ready_samples = 0
    last = ""
    while time.monotonic() < deadline:
        for command, ready in probes:
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


def main():
    sdk = Path(os.environ["ANDROID_HOME"])
    tools = sdk / "cmdline-tools" / "latest" / "bin"
    temporary = Path(os.environ["RUNNER_TEMP"])
    avd_home = temporary / "blinky-avd"
    avd_home.mkdir()
    os.environ["ANDROID_AVD_HOME"] = str(avd_home)
    adb = [str(sdk / "platform-tools" / "adb"), "-s", "emulator-5554"]
    emulator = None
    completed = False
    log_path = temporary / "blinky-emulator.log"
    try:
        print("Installing the API37 Google APIs image and emulator.", flush=True)
        run([tools / "sdkmanager", "--install", "emulator", "platform-tools",
             "system-images;android-37.0;google_apis;x86_64"], 180)
        run([tools / "avdmanager", "create", "avd", "--force", "--name", "blinky-ci",
             "--package", "system-images;android-37.0;google_apis;x86_64",
             "--device", "pixel_2"], 30)
        with (avd_home / "blinky-ci.avd" / "config.ini").open("a", encoding="utf-8") as config:
            config.write("\nhw.cpu.ncore=4\nhw.ramSize=2048M\nhw.heapSize=512M\ndisk.dataPartition.size=4G\n")
        with log_path.open("w", encoding="utf-8") as log:
            emulator = subprocess.Popen(
                [str(sdk / "emulator" / "emulator"), "-avd", "blinky-ci", "-port", "5554",
                 "-no-window", "-gpu", "swiftshader_indirect", "-no-snapshot", "-noaudio", "-no-boot-anim"],
                stdout=log, stderr=subprocess.STDOUT,
            )
        wait_for_framework(adb)
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

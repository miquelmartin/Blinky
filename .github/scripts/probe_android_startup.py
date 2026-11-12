import os
from pathlib import Path
import signal
import subprocess
import threading
import time

from run_android_tests import run, wait_for_framework


ROOT = Path(os.environ["RUNNER_TEMP"])
STOP = threading.Event()


def snapshot(command, log, seconds=4):
    result = run(command, seconds, check=False, capture=True)
    log.write(f"\n{time.time():.3f} {command} exit={result.returncode}\n")
    log.write(result.stdout + result.stderr)
    log.flush()


def monitor(adb, emulator):
    with (ROOT / "probe-resources.txt").open("w") as log:
        while not STOP.is_set():
            log.write(f"\nemulator_poll={emulator.poll()}\n")
            snapshot(["ps", "-eo", "pid,ppid,rss,comm", "--sort=-rss"], log)
            for name in ("/proc/meminfo", "/sys/fs/cgroup/memory.events",
                         "/sys/fs/cgroup/memory.current", "/sys/fs/cgroup/memory.max"):
                path = Path(name)
                log.write(f"\n{name}\n{path.read_text() if path.is_file() else 'not present'}\n")
            snapshot([*adb, "shell", "echo PIDS; pidof system_server; pidof zygote64; "
                      "echo BOOT; getprop sys.boot_completed; "
                      "echo MEMORY; cat /proc/meminfo; echo UPTIME; cat /proc/uptime"], log)
            STOP.wait(5)


def main():
    sdk = Path(os.environ["ANDROID_HOME"])
    tools = sdk / "cmdline-tools" / "latest" / "bin"
    home = ROOT / "probe-avd"
    home.mkdir()
    os.environ["ANDROID_AVD_HOME"] = str(home)
    adb = [str(sdk / "platform-tools" / "adb"), "-s", "emulator-5554"]
    emulator = None
    logcat = None
    thread = None
    try:
        run([tools / "sdkmanager", "--install", "emulator", "platform-tools",
             "system-images;android-37.0;google_apis;x86_64"], 180, capture=True)
        run([tools / "avdmanager", "create", "avd", "--force", "--name", "blinky-probe",
             "--package", "system-images;android-37.0;google_apis;x86_64",
             "--device", "pixel_2"], 30, capture=True)
        with (home / "blinky-probe.avd" / "config.ini").open("a") as config:
            config.write("\nhw.cpu.ncore=4\nhw.ramSize=2048M\nhw.heapSize=512M\ndisk.dataPartition.size=4G\n")
        with (ROOT / "probe-versions.txt").open("w") as log:
            snapshot([sdk / "emulator" / "emulator", "-version"], log)
            log.write((sdk / "system-images" / "android-37.0" / "google_apis" / "x86_64" / "source.properties").read_text())
        with (ROOT / "probe-emulator-kernel.txt").open("w") as log:
            emulator = subprocess.Popen(
                [str(sdk / "emulator" / "emulator"), "-avd", "blinky-probe", "-port", "5554",
                 "-no-window", "-gpu", "swiftshader_indirect", "-no-snapshot", "-noaudio",
                 "-no-boot-anim", "-show-kernel"],
                stdout=log, stderr=subprocess.STDOUT,
            )
        thread = threading.Thread(target=monitor, args=(adb, emulator), daemon=True)
        thread.start()
        run([*adb, "wait-for-device"], 90)
        with (ROOT / "probe-live-logcat.txt").open("w") as log:
            logcat = subprocess.Popen(
                ["timeout", "--kill-after=5s", "240s", *adb, "logcat", "-b", "all", "-v", "threadtime"],
                stdout=log, stderr=subprocess.STDOUT,
            )
        wait_for_framework(adb)
        print("Startup-only observation: 60 seconds; NO APK builds, installs or tests.", flush=True)
        time.sleep(60)
    finally:
        STOP.set()
        try:
            if emulator is not None:
                with (ROOT / "probe-crash-and-kernel.txt").open("w") as log:
                    for command in [
                        [*adb, "logcat", "-b", "crash", "-d", "-t", "2000"],
                        [*adb, "shell", "dmesg"],
                        [*adb, "shell", "getprop", "sys.boot.reason"],
                        [*adb, "shell", "ls", "-l", "/data/tombstones"],
                        ["dmesg", "--level=err,warn"],
                    ]:
                        snapshot(command, log, 5)
        finally:
            if thread is not None:
                thread.join(timeout=8)
            for process in (logcat, emulator):
                if process is not None and process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)


def terminate(_signal, _frame):
    raise SystemExit(143)


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, terminate)
    main()

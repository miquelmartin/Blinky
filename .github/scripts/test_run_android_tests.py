import subprocess
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

from run_android_tests import configure_avd, main, require_storage, run, wait_for_framework


class AndroidReadinessTests(unittest.TestCase):
    def response(self, output, code=0):
        return Mock(stdout=output, stderr="", returncode=code)

    def healthy(self, provisioned="1\n"):
        return [self.response(value) for value in [
            "1\n", "Service input: found\n", "keyevent KEYCODE\n",
            "package:/system/framework/framework-res.apk\n", provisioned,
        ]]

    def prepare_tool_output(self, root, args, target="android-37.0"):
        if "cmdline-tools;23.0" in args:
            tools = root / "cmdline-tools" / "23.0"
            tools.mkdir(parents=True)
            (tools / "source.properties").write_text("Pkg.Revision=23.0\n", encoding="utf-8")
        if Path(args[0]).name == "avdmanager":
            home = root / "blinky-avd"
            (home / "blinky-ci.avd").mkdir()
            (home / "blinky-ci.ini").write_text(f"target={target}\n", encoding="utf-8")
            (home / "blinky-ci.avd" / "config.ini").write_text(
                "hw.cpu.ncore=2\nhw.ramSize=2G\nvm.heapSize=228M\n"
                "disk.dataPartition.size=10G\nhw.cpu.ncore=4\nhw.heapSize=512M\n"
                "image.sysdir.1=system-images/android-37.0/google_apis/x86_64/\n"
                "target=android-37.0\n", encoding="utf-8",
            )
            (home / "blinky-ci.avd" / "hardware-qemu.ini").write_text(
                "hw.cpu.ncore = 4\nhw.ramSize = 4096\nvm.heapSize = 576\n", encoding="utf-8",
            )

    def test_resource_generation_removes_duplicates_preserves_image_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "blinky-avd").mkdir()
            self.prepare_tool_output(root, ["avdmanager"])
            path = root / "blinky-avd" / "blinky-ci.avd" / "config.ini"
            configure_avd(path)
            expected = (
                "image.sysdir.1=system-images/android-37.0/google_apis/x86_64/\n"
                "target=android-37.0\nhw.cpu.ncore=4\nhw.ramSize=4096\n"
                "vm.heapSize=576\ndisk.dataPartition.size=6G\n"
            )
            self.assertEqual(expected, path.read_text())
            configure_avd(path)
            self.assertEqual(expected, path.read_text())

    @patch("run_android_tests.shutil.disk_usage")
    def test_storage_capacity_rejects_observed_insufficient_space_and_accepts_headroom(self, usage):
        usage.return_value.free = int(5830.48 * 1024**2)
        with self.assertRaisesRegex(RuntimeError, "at least 8 GiB"):
            require_storage(Path("."))
        usage.return_value.free = 8 * 1024**3
        require_storage(Path("."))

    @patch("run_android_tests.run")
    def test_exited_emulator_fails_without_readiness_or_adb_wait(self, execute):
        emulator = Mock(returncode=1)
        emulator.poll.return_value = 1
        with self.assertRaisesRegex(RuntimeError, "exited before readiness: exit=1"):
            wait_for_framework(["adb"], emulator=emulator)
        execute.assert_not_called()

    @patch("run_android_tests.time.sleep")
    @patch("run_android_tests.run")
    def test_boot_property_alone_is_not_readiness(self, execute, sleep):
        execute.side_effect = [
            self.response("1\n"),
            self.response("Service input: not found\n"),
            *self.healthy(), *self.healthy(),
        ]
        wait_for_framework(["adb"])
        self.assertEqual(12, execute.call_count)
        self.assertEqual(2, sleep.call_count)

    @patch("run_android_tests.time.sleep")
    @patch("run_android_tests.run")
    def test_framework_restart_requires_two_new_complete_samples(self, execute, sleep):
        execute.side_effect = [
            *self.healthy(), self.response("", 224), *self.healthy(), *self.healthy(),
        ]
        wait_for_framework(["adb"])
        self.assertEqual(16, execute.call_count)

    @patch("run_android_tests.time.sleep")
    @patch("run_android_tests.run")
    def test_ready_services_do_not_require_boot_animation_or_setup_wizard_state(self, execute, sleep):
        for value in ("0\n", "null\n"):
            with self.subTest(value=value):
                execute.side_effect = [*self.healthy(value), *self.healthy(value)]
                wait_for_framework(["adb"])
        self.assertFalse(any("init.svc.bootanim" in call.args[0] for call in execute.call_args_list))

    @patch("run_android_tests.time.monotonic", side_effect=[0, 0, 0, 181])
    @patch("run_android_tests.time.sleep")
    @patch("run_android_tests.run")
    def test_unready_framework_fails_at_deadline(self, execute, sleep, clock):
        execute.return_value = self.response("0\n")
        with self.assertRaisesRegex(TimeoutError, "readiness deadline"):
            wait_for_framework(["adb"])
        self.assertEqual(1, execute.call_count)

    @patch("run_android_tests.subprocess.run")
    def test_commands_have_process_group_deadline_and_timeout_is_failure(self, execute):
        execute.side_effect = subprocess.CalledProcessError(124, ["timeout"])
        with self.assertRaises(subprocess.CalledProcessError):
            run(["sdkmanager"], 180)
        self.assertEqual(
            ["timeout", "--kill-after=10s", "180s", "sdkmanager"], execute.call_args.args[0],
        )
        self.assertTrue(execute.call_args.kwargs["check"])

    @patch("run_android_tests.wait_for_framework", side_effect=TimeoutError("not ready"))
    @patch("run_android_tests.subprocess.Popen")
    @patch("run_android_tests.run")
    def test_failed_startup_still_terminates_emulator_when_diagnostics_fail(self, execute, start, wait):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            start.return_value.poll.return_value = None

            def command(args, seconds, **kwargs):
                if "logcat" in args:
                    raise OSError("diagnostic failed")
                self.prepare_tool_output(root, args)
                return self.response("")

            execute.side_effect = command
            with patch.dict(os.environ, {"ANDROID_HOME": directory, "RUNNER_TEMP": directory}), \
                    patch("run_android_tests.require_storage"):
                with self.assertRaisesRegex(OSError, "diagnostic failed"):
                    main()
            start.return_value.terminate.assert_called_once()
            start.return_value.wait.assert_called_once_with(timeout=5)

    @patch("run_android_tests.wait_for_framework")
    @patch("run_android_tests.subprocess.Popen")
    @patch("run_android_tests.run")
    def test_native_execution_and_inventory_must_both_finish_before_cleanup(self, execute, start, wait):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            start.return_value.poll.return_value = None

            def command(args, seconds, **kwargs):
                self.prepare_tool_output(root, args)
                return self.response("")

            execute.side_effect = command
            with patch.dict(os.environ, {"ANDROID_HOME": directory, "RUNNER_TEMP": directory}), \
                    patch("run_android_tests.require_storage"):
                main()
            commands = [call.args[0] for call in execute.call_args_list]
            self.assertEqual(root / "cmdline-tools" / "latest" / "bin" / "sdkmanager", commands[0][0])
            self.assertEqual(root / "cmdline-tools" / "23.0" / "bin" / "sdkmanager", commands[1][0])
            self.assertEqual(root / "cmdline-tools" / "23.0" / "bin" / "avdmanager", commands[2][0])
            self.assertEqual(1, sum(":app:connectedDebugAndroidTest" in cmd for cmd in commands))
            self.assertIn("verify_test_inventory.py", str(commands[-1]))
            start.return_value.terminate.assert_called_once()

    @patch("run_android_tests.subprocess.Popen")
    @patch("run_android_tests.run")
    def test_misparsed_dotted_avd_target_never_launches_emulator(self, execute, start):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            def command(args, seconds, **kwargs):
                self.prepare_tool_output(root, args, target="android-0")
                return self.response("")

            execute.side_effect = command
            with patch.dict(os.environ, {"ANDROID_HOME": directory, "RUNNER_TEMP": directory}), \
                    patch("run_android_tests.require_storage"):
                with self.assertRaisesRegex(ValueError, "target=android-37.0"):
                    main()
            start.assert_not_called()
            self.assertIn("target=android-0", (root / "blinky-avd-metadata.txt").read_text())

    @patch("run_android_tests.require_storage")
    @patch("run_android_tests.wait_for_framework")
    @patch("run_android_tests.subprocess.Popen")
    @patch("run_android_tests.run")
    def test_effective_hardware_mismatch_blocks_native_execution(self, execute, start, wait, storage):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            start.return_value.poll.return_value = None

            def command(args, seconds, **kwargs):
                self.prepare_tool_output(root, args)
                if Path(args[0]).name == "avdmanager":
                    (root / "blinky-avd" / "blinky-ci.avd" / "hardware-qemu.ini").write_text(
                        "hw.cpu.ncore=2\nhw.ramSize=4096\nvm.heapSize=576\n", encoding="utf-8",
                    )
                return self.response("")

            execute.side_effect = command
            with patch.dict(os.environ, {"ANDROID_HOME": directory, "RUNNER_TEMP": directory}):
                with self.assertRaisesRegex(ValueError, "hw.cpu.ncore=4"):
                    main()
            self.assertFalse(any(":app:connectedDebugAndroidTest" in call.args[0]
                                 for call in execute.call_args_list))
            start.return_value.terminate.assert_called_once()


if __name__ == "__main__":
    unittest.main()

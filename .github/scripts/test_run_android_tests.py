import subprocess
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

from run_android_tests import main, run, wait_for_framework


class AndroidReadinessTests(unittest.TestCase):
    def response(self, output, code=0):
        return Mock(stdout=output, stderr="", returncode=code)

    def healthy(self):
        return [self.response(value) for value in [
            "1\n", "stopped\n", "Service input: found\n", "keyevent KEYCODE\n",
            "package:/system/framework/framework-res.apk\n", "1\n",
        ]]

    @patch("run_android_tests.time.sleep")
    @patch("run_android_tests.run")
    def test_boot_property_alone_is_not_readiness(self, execute, sleep):
        execute.side_effect = [
            self.response("1\n"), self.response("stopped\n"),
            self.response("Service input: not found\n"),
            *self.healthy(), *self.healthy(),
        ]
        wait_for_framework(["adb"])
        self.assertEqual(15, execute.call_count)
        self.assertEqual(2, sleep.call_count)

    @patch("run_android_tests.time.sleep")
    @patch("run_android_tests.run")
    def test_framework_restart_requires_two_new_complete_samples(self, execute, sleep):
        execute.side_effect = [
            *self.healthy(), self.response("", 224), *self.healthy(), *self.healthy(),
        ]
        wait_for_framework(["adb"])
        self.assertEqual(19, execute.call_count)

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
                if args[0] == "sdkmanager":
                    (root / "blinky-avd" / "blinky-ci.avd").mkdir()
                return self.response("")

            execute.side_effect = command
            with patch.dict(os.environ, {"ANDROID_HOME": directory, "RUNNER_TEMP": directory}):
                with self.assertRaisesRegex(OSError, "diagnostic failed"):
                    main()
            start.return_value.terminate.assert_called_once()
            start.return_value.wait.assert_called_once_with(timeout=5)


if __name__ == "__main__":
    unittest.main()

import base64
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

from prepare_android_release import BUILD_SECRETS, PACKAGE, REQUIRED_SECRETS, main, prepare, validate_secrets


def environment() -> dict[str, str]:
    return {
        "APK_SIGNING_CERT": base64.b64encode(b"unit-test-keystore").decode("ascii"),
        "APK_KEY_PASSWORD": "unit-test-password",
        "APK_KEY_ALIAS": "unit-test-alias",
        "GOOGLE_SERVICES_JSON": json.dumps({
            "project_info": {"project_id": "unit-test-project"},
            "client": [{"client_info": {"android_client_info": {"package_name": PACKAGE}}}],
        }),
        "GOOGLE_PLAY_INTERNAL_SERVICE_ACCOUNT_JSON": json.dumps({
            "type": "service_account", "private_key": "unit-test-placeholder-not-a-key",
            "client_email": "unit-test@example.invalid", "project_id": "unit-test-project",
        }),
    }


class ReleaseConfigurationTests(unittest.TestCase):
    def test_required_secrets_are_named_without_values(self):
        for name in REQUIRED_SECRETS:
            values = environment()
            del values[name]
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, name):
                validate_secrets(values)

    def test_invalid_certificate_is_rejected(self):
        for certificate in ["not base64!", "\u00e9", " "]:
            values = environment()
            values["APK_SIGNING_CERT"] = certificate
            with self.subTest(certificate=certificate), self.assertRaises(ValueError):
                validate_secrets(values)

    def test_valid_secret_shapes_are_accepted(self):
        certificate, client, play = validate_secrets(environment())
        self.assertEqual(certificate, b"unit-test-keystore")
        self.assertEqual(client["client"][0]["client_info"]["android_client_info"]["package_name"], PACKAGE)
        self.assertEqual(play["type"], "service_account")

    def test_wrapped_keystore_base64_is_accepted(self):
        values = environment()
        encoded = values["APK_SIGNING_CERT"]
        values["APK_SIGNING_CERT"] = encoded[:8] + "\r\n" + encoded[8:] + "\n"
        certificate, _, _ = validate_secrets(values)
        self.assertEqual(certificate, b"unit-test-keystore")

    def test_build_only_needs_no_play_credentials(self):
        values = environment()
        del values["GOOGLE_PLAY_INTERNAL_SERVICE_ACCOUNT_JSON"]
        _, _, play = validate_secrets(values, build_only=True)
        self.assertIsNone(play)
        with self.assertRaisesRegex(ValueError, "GOOGLE_PLAY_INTERNAL_SERVICE_ACCOUNT_JSON"):
            validate_secrets(values)

    def test_build_only_does_not_parse_ambient_play_credentials(self):
        values = environment()
        values["GOOGLE_PLAY_INTERNAL_SERVICE_ACCOUNT_JSON"] = "must-not-be-parsed"
        _, _, play = validate_secrets(values, build_only=True)
        self.assertIsNone(play)

    def test_build_only_still_requires_all_signing_and_client_secrets(self):
        for name in BUILD_SECRETS:
            values = environment()
            del values[name]
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, name):
                validate_secrets(values, build_only=True)

    def test_build_only_rejects_private_key_as_client_config(self):
        values = environment()
        values["GOOGLE_SERVICES_JSON"] = values["GOOGLE_PLAY_INTERNAL_SERVICE_ACCOUNT_JSON"]
        with self.assertRaisesRegex(ValueError, "never a private"):
            validate_secrets(values, build_only=True)

    def test_cli_defaults_to_internal_and_selects_build_only_explicitly(self):
        for arguments, build_only in [([], False), (["--build-only"], True)]:
            with self.subTest(arguments=arguments), \
                    patch("prepare_android_release.prepare") as prepare_call, \
                    contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(main(arguments), 0)
            prepare_call.assert_called_once_with(os.environ, build_only=build_only)

    def test_private_key_cannot_be_used_as_client_config(self):
        for value in [
            {"type": "service_account"},
            {"private_key": "unit-test-placeholder"},
            {"nested": [{"private_key": "unit-test-placeholder"}]},
        ]:
            values = environment()
            values["GOOGLE_SERVICES_JSON"] = json.dumps(value)
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "never a private"):
                validate_secrets(values)

    def test_invalid_client_shapes_and_package_are_rejected(self):
        for client in [
            [], {}, {"project_info": {}, "client": []},
            {"project_info": {"project_id": "unit-test"}, "client": None},
            {"project_info": {"project_id": "unit-test"}, "client": [{"client_info": None}]},
            {"project_info": {"project_id": "unit-test"},
             "client": [{"client_info": {"android_client_info": {"package_name": "org.other.app"}}}]},
        ]:
            values = environment()
            values["GOOGLE_SERVICES_JSON"] = json.dumps(client)
            with self.subTest(client=client), self.assertRaises(ValueError):
                validate_secrets(values)

    def test_invalid_play_credentials_are_rejected(self):
        for credentials in [{}, [], {"type": "authorized_user"}, {"type": "service_account", "private_key": ""}]:
            values = environment()
            values["GOOGLE_PLAY_INTERNAL_SERVICE_ACCOUNT_JSON"] = json.dumps(credentials)
            with self.subTest(credentials=credentials), self.assertRaises(ValueError):
                validate_secrets(values)

    def test_malformed_json_error_does_not_echo_secret(self):
        for name in ("GOOGLE_SERVICES_JSON", "GOOGLE_PLAY_INTERNAL_SERVICE_ACCOUNT_JSON"):
            values = environment()
            values[name] = "unit-test-private-value"
            with self.assertRaises(ValueError) as raised:
                validate_secrets(values)
            self.assertNotIn(values[name], str(raised.exception))

    def test_non_actions_execution_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "only in GitHub Actions"):
            prepare(environment())

    def test_prepare_uses_password_environment_and_private_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "app").mkdir()
            values = dict(os.environ, **environment(), RUNNER_TEMP=directory, GITHUB_WORKSPACE=directory,
                          GITHUB_ENV=str(root / "github-env"))
            with patch("prepare_android_release.subprocess.run", return_value=Mock(returncode=0)) as run:
                prepare(values)
            arguments = run.call_args.args[0]
            self.assertIn("-storepass:env", arguments)
            self.assertNotIn(values["APK_KEY_PASSWORD"], arguments)
            self.assertEqual(run.call_args.kwargs["env"]["APK_KEY_PASSWORD"], values["APK_KEY_PASSWORD"])
            self.assertTrue((root / "blinky-upload-key.p12").is_file())
            self.assertTrue((root / "blinky-play-service-account.json").is_file())
            self.assertTrue((root / "app" / "google-services.json").is_file())
            exports = (root / "github-env").read_text(encoding="utf-8")
            self.assertIn("APK_SIGNING_STORE_FILE=", exports)
            self.assertIn("PLAY_SERVICE_ACCOUNT_FILE=", exports)
            self.assertNotIn(values["APK_KEY_PASSWORD"], exports)
            if os.name != "nt":
                self.assertEqual((root / "blinky-upload-key.p12").stat().st_mode & 0o777, 0o600)

    def test_keytool_failure_removes_private_files(self):
        for outcome in [Mock(returncode=1), subprocess.TimeoutExpired("keytool", 30)]:
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / "app").mkdir()
                values = dict(os.environ, **environment(), RUNNER_TEMP=directory, GITHUB_WORKSPACE=directory,
                              GITHUB_ENV=str(root / "github-env"))
                with patch("prepare_android_release.subprocess.run") as run:
                    if isinstance(outcome, Exception):
                        run.side_effect = outcome
                        expected = subprocess.TimeoutExpired
                    else:
                        run.return_value = outcome
                        expected = ValueError
                    with self.assertRaises(expected):
                        prepare(values)
                self.assertFalse((root / "blinky-upload-key.p12").exists())
                self.assertFalse((root / "blinky-play-service-account.json").exists())
                self.assertFalse((root / "app" / "google-services.json").exists())

    def test_build_only_prepares_no_play_file_or_export(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "app").mkdir()
            values = dict(os.environ, **environment(), RUNNER_TEMP=directory, GITHUB_WORKSPACE=directory,
                          GITHUB_ENV=str(root / "github-env"))
            values.pop("GOOGLE_PLAY_INTERNAL_SERVICE_ACCOUNT_JSON")
            with patch("prepare_android_release.subprocess.run", return_value=Mock(returncode=0)):
                prepare(values, build_only=True)
            self.assertTrue((root / "blinky-upload-key.p12").is_file())
            self.assertTrue((root / "app" / "google-services.json").is_file())
            self.assertFalse((root / "blinky-play-service-account.json").exists())
            exports = (root / "github-env").read_text(encoding="utf-8")
            self.assertIn("APK_SIGNING_STORE_FILE=", exports)
            self.assertNotIn("PLAY_SERVICE_ACCOUNT_FILE", exports)

    def test_export_failure_removes_private_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "app").mkdir()
            values = dict(os.environ, **environment(), RUNNER_TEMP=directory, GITHUB_WORKSPACE=directory,
                          GITHUB_ENV=str(root / "missing" / "github-env"))
            with patch("prepare_android_release.subprocess.run", return_value=Mock(returncode=0)):
                with self.assertRaises(OSError):
                    prepare(values)
            self.assertFalse((root / "blinky-upload-key.p12").exists())
            self.assertFalse((root / "blinky-play-service-account.json").exists())
            self.assertFalse((root / "app" / "google-services.json").exists())


if __name__ == "__main__":
    unittest.main()

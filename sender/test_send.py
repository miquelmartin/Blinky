import contextlib
import io
import json
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import MagicMock, Mock, patch

from send import build_message, deliver, main, validate_project


class SenderTests(unittest.TestCase):
    def test_payload_is_data_only_high_priority(self):
        payload = build_message(" token\n", "Hello", "Phone")
        self.assertEqual(payload, {"message": {
            "token": "token", "data": {"title": "Hello", "body": "Phone"},
            "android": {"priority": "HIGH"},
        }})

    def test_blank_fields_and_invalid_token_fail(self):
        for values in [("", "a", "b"), ("a b", "a", "b"), ("a", " ", "b"), ("a", "b", "\n")]:
            with self.subTest(values=values), self.assertRaises(ValueError):
                build_message(*values)

    def test_exact_payload_size_boundary(self):
        framing = len(json.dumps({"title": "a", "body": ""}, ensure_ascii=False).encode("utf-8"))
        build_message("token", "a", "b" * (4096 - framing))
        with self.assertRaises(ValueError):
            build_message("token", "a", "b" * (4097 - framing))

    def test_size_uses_utf8_not_character_count(self):
        with self.assertRaises(ValueError):
            build_message("token", "Hello", "\U0001f600" * 1100)

    def test_project_id_validation(self):
        self.assertEqual(validate_project("blinky-demo-123"), "blinky-demo-123")
        for project in ["", "a", "../project", "My Project", "123456", "abcde-", "a" * 31]:
            with self.subTest(project=project), self.assertRaises(ValueError):
                validate_project(project)

    def test_accepted_response_and_endpoint(self):
        session = Mock()
        session.post.return_value.status_code = 200
        session.post.return_value.json.return_value = {"name": "projects/blinky-demo/messages/123"}
        payload = build_message("token", "Hello", "Phone")
        self.assertEqual(deliver(session, "blinky-demo", payload), "projects/blinky-demo/messages/123")
        session.post.assert_called_once_with(
            "https://fcm.googleapis.com/v1/projects/blinky-demo/messages:send",
            json=payload, timeout=30,
        )

    def test_api_rejection_is_not_success(self):
        session = Mock()
        session.post.return_value.status_code = 404
        session.post.return_value.json.return_value = {"error": {"status": "NOT_FOUND", "message": "Unregistered token"}}
        with self.assertRaisesRegex(ValueError, "NOT_FOUND.*Unregistered token"):
            deliver(session, "blinky-demo", {})

    def test_malformed_responses_are_not_success(self):
        for result in [{}, [], {"name": None}, {"name": ""}]:
            session = Mock()
            session.post.return_value.status_code = 200
            session.post.return_value.json.return_value = result
            with self.subTest(result=result), self.assertRaises(ValueError):
                deliver(session, "blinky-demo", {})
        session.post.return_value.json.side_effect = ValueError("not JSON")
        with self.assertRaisesRegex(ValueError, "non-JSON"):
            deliver(session, "blinky-demo", {})

    def test_missing_token_file_exits_with_error_before_auth(self):
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stderr(io.StringIO()) as errors:
            result = main(["--project", "blinky-demo", "--token-file", str(Path(directory) / "missing"),
                           "--title", "Hello", "--body", "Phone"])
        self.assertEqual(result, 1)
        self.assertIn("Error:", errors.getvalue())

    def test_cli_authentication_success_and_failures(self):
        auth_error = type("GoogleAuthError", (Exception,), {})
        request_error = type("RequestException", (Exception,), {})
        for scenario in ["accepted", "auth_failure", "network_failure", "api_failure"]:
            with self.subTest(scenario=scenario):
                auth = types.ModuleType("google.auth")
                auth.default = Mock(return_value=(object(), None))
                google = types.ModuleType("google")
                google.auth = auth
                exceptions = types.ModuleType("google.auth.exceptions")
                exceptions.GoogleAuthError = auth_error
                transport = types.ModuleType("google.auth.transport.requests")
                transport.AuthorizedSession = MagicMock()
                session = transport.AuthorizedSession.return_value.__enter__.return_value = Mock()
                transport.AuthorizedSession.return_value.__exit__ = Mock(return_value=False)
                request_exceptions = types.ModuleType("requests.exceptions")
                request_exceptions.RequestException = request_error
                modules = {
                    "google": google, "google.auth": auth,
                    "google.auth.exceptions": exceptions,
                    "google.auth.transport": types.ModuleType("google.auth.transport"),
                    "google.auth.transport.requests": transport,
                    "requests": types.ModuleType("requests"),
                    "requests.exceptions": request_exceptions,
                }
                session.post.return_value.status_code = 200
                session.post.return_value.json.return_value = {"name": "projects/blinky-demo/messages/123"}
                if scenario == "auth_failure":
                    auth.default.side_effect = auth_error("Credentials unavailable")
                elif scenario == "network_failure":
                    session.post.side_effect = request_error("Network timeout")
                elif scenario == "api_failure":
                    session.post.return_value.status_code = 403
                    session.post.return_value.json.return_value = {"error": {"status": "PERMISSION_DENIED"}}
                with tempfile.TemporaryDirectory() as directory:
                    token = Path(directory) / "token.txt"
                    token.write_text("private-routing-token", encoding="utf-8")
                    with patch.dict("sys.modules", modules), \
                            contextlib.redirect_stdout(io.StringIO()) as output, \
                            contextlib.redirect_stderr(io.StringIO()) as errors:
                        result = main(["--project", "blinky-demo", "--token-file", str(token),
                                       "--title", "Hello", "--body", "Phone"])
                auth.default.assert_called_once_with(scopes=["https://www.googleapis.com/auth/firebase.messaging"])
                self.assertNotIn("private-routing-token", output.getvalue() + errors.getvalue())
                if scenario == "accepted":
                    self.assertEqual(result, 0)
                    self.assertIn("not proof of phone delivery", output.getvalue())
                    self.assertEqual(errors.getvalue(), "")
                else:
                    self.assertEqual(result, 1)
                    self.assertIn("Error:", errors.getvalue())
                    self.assertNotIn("accepted", output.getvalue())


if __name__ == "__main__":
    unittest.main()

import unittest
from unittest.mock import Mock

from verify_internal_release import BASE, verify


def response(data=None, status=200):
    result = Mock(status_code=status)
    result.json.return_value = data
    return result


class VerifyInternalReleaseTests(unittest.TestCase):
    def session(self, track):
        session = Mock()
        session.request.side_effect = [response({"id": "123"}), response(track), response(status=204)]
        return session

    def test_fresh_completed_track_and_uncommitted_edit_cleanup(self):
        session = self.session({"track": "internal", "releases": [{"status": "completed", "versionCodes": ["3"]}]})
        verify(session, 3)
        self.assertEqual(["POST", "GET", "DELETE"], [call.args[0] for call in session.request.call_args_list])
        self.assertEqual(BASE + "/123/tracks/internal", session.request.call_args_list[1].args[1])

    def test_wrong_version_draft_or_other_track_do_not_confirm_availability(self):
        for track in [
            {"track": "internal", "releases": [{"status": "completed", "versionCodes": ["2"]}]},
            {"track": "internal", "releases": [{"status": "draft", "versionCodes": ["3"]}]},
            {"track": "production", "releases": [{"status": "completed", "versionCodes": ["3"]}]},
            {"track": "internal", "releases": []},
        ]:
            with self.subTest(track=track):
                session = self.session(track)
                with self.assertRaises(ValueError):
                    verify(session, 3)
                self.assertEqual("DELETE", session.request.call_args_list[-1].args[0])

    def test_failed_get_still_deletes_edit(self):
        session = Mock()
        session.request.side_effect = [response({"id": "123"}), response(status=403), response(status=204)]
        with self.assertRaises(ValueError):
            verify(session, 3)
        self.assertEqual("DELETE", session.request.call_args_list[-1].args[0])

    def test_malformed_release_shapes_never_confirm_availability(self):
        for releases in [
            None,
            ["completed"],
            [{"status": "completed", "versionCodes": "23"}],
            [{"status": "completed", "versionCodes": [3]}],
            [{"status": "completed", "versionCodes": None}],
        ]:
            with self.subTest(releases=releases):
                session = self.session({"track": "internal", "releases": releases})
                with self.assertRaises(ValueError):
                    verify(session, 3)
                self.assertEqual("DELETE", session.request.call_args_list[-1].args[0])

    def test_cleanup_failure_is_not_success(self):
        session = self.session({"track": "internal", "releases": [{"status": "completed", "versionCodes": ["3"]}]})
        session.request.side_effect = [response({"id": "123"}), response({"track": "internal", "releases": [{"status": "completed", "versionCodes": ["3"]}]}), response(status=500)]
        with self.assertRaises(ValueError):
            verify(session, 3)

    def test_invalid_version_does_not_request_an_edit(self):
        session = Mock()
        with self.assertRaises(ValueError):
            verify(session, 0)
        session.request.assert_not_called()

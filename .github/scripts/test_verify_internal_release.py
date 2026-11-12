import unittest
from unittest.mock import Mock

from verify_internal_release import BASE, preflight, verify


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


class PlayPreflightTests(unittest.TestCase):
    def session(self, tracks=None, bundles=None, apks=None):
        session = Mock()
        session.request.side_effect = [
            response({"id": "123"}),
            response({"tracks": []} if tracks is None else tracks),
            response({"bundles": []} if bundles is None else bundles),
            response({"apks": []} if apks is None else apks),
            response(status=204),
        ]
        return session

    def test_reads_all_tracks_and_artifacts_then_deletes_without_commit(self):
        session = self.session(
            {"tracks": [{"track": "internal", "releases": [{"status": "completed", "versionCodes": ["6"]}]}]},
            {"bundles": [{"versionCode": 5}]},
            {"apks": [{"versionCode": 4}]},
        )
        self.assertEqual(6, preflight(session, 7))
        calls = session.request.call_args_list
        self.assertEqual(["POST", "GET", "GET", "GET", "DELETE"], [call.args[0] for call in calls])
        self.assertEqual([BASE + "/123/" + name for name in ("tracks", "bundles", "apks")],
                         [call.args[1] for call in calls[1:4]])
        self.assertEqual(BASE + "/123", calls[-1].args[1])

    def test_any_track_draft_or_unreleased_artifact_blocks_used_or_older_code(self):
        for tracks, bundles, apks in [
            ({"tracks": [{"track": "production", "releases": [{"status": "draft", "versionCodes": ["7"]}]}]}, None, None),
            (None, {"bundles": [{"versionCode": 8}]}, None),
            (None, None, {"apks": [{"versionCode": 7}]}),
        ]:
            with self.subTest(source=(tracks, bundles, apks)):
                session = self.session(tracks, bundles, apks)
                with self.assertRaises(ValueError):
                    preflight(session, 7)
                self.assertEqual("DELETE", session.request.call_args_list[-1].args[0])

    def test_empty_snapshot_does_not_invent_observed_versions(self):
        self.assertEqual(0, preflight(self.session(), 7))

    def test_malformed_snapshot_fails_closed_and_deletes_edit(self):
        invalid_responses = [
            [response({"tracks": None})],
            [response({"tracks": ["internal"]})],
            [response({"tracks": [{"track": "internal", "releases": None}]})],
            [response({"tracks": [{"track": "internal", "releases": ["draft"]}]})],
            [response({"tracks": [{"track": "internal", "releases": [{"versionCodes": [7]}]}]})],
            [response({"tracks": [{"track": "internal", "releases": [{"versionCodes": ["invalid"]}]}]})],
            [response({}), response({"bundles": None})],
            [response({}), response({"bundles": [{"versionCode": True}]})],
            [response({}), response({}), response({"apks": [{"versionCode": "7"}]})],
        ]
        for replies in invalid_responses:
            with self.subTest(replies=replies):
                session = Mock()
                session.request.side_effect = [response({"id": "123"}), *replies, response(status=204)]
                with self.assertRaises(ValueError):
                    preflight(session, 7)
                self.assertEqual("DELETE", session.request.call_args_list[-1].args[0])

    def test_api_or_cleanup_failure_prevents_upload_permission(self):
        for replies in [
            [response({"id": "123"}), response(status=403), response(status=204)],
            [response({"id": "123"}), response({}), response({}), response({}), response(status=500)],
        ]:
            session = Mock()
            session.request.side_effect = replies
            with self.assertRaises(ValueError):
                preflight(session, 7)
            self.assertEqual("DELETE", session.request.call_args_list[-1].args[0])

    def test_invalid_candidate_does_not_create_edit(self):
        session = Mock()
        with self.assertRaises(ValueError):
            preflight(session, 0)
        session.request.assert_not_called()

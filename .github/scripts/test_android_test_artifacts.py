import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from android_test_artifacts import APKS, identity, produce, verify


class ApkArtifactTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.expected = {
            "run_id": "123", "attempt": "1", "merge_sha": "merge", "head_sha": "head", "base_sha": "base",
            "native_tests": [["example.Test", "method"]],
        }
        sources = {}
        for name in APKS:
            sources[name] = self.root / name
            sources[name].write_bytes(name.encode())
        patcher = patch.dict(APKS, sources)
        patcher.start()
        self.addCleanup(patcher.stop)
        patcher = patch("android_test_artifacts.identity", return_value=self.expected.copy())
        self.identity = patcher.start()
        self.addCleanup(patcher.stop)
        self.directory = self.root / "artifact"

    def test_exact_apk_pair_is_transferred_and_verified(self):
        produce(self.directory)
        self.assertEqual(set(APKS), set(verify(self.directory)))

    def test_identity_inventory_and_checksums_cannot_be_substituted(self):
        produce(self.directory)
        path = self.directory / "manifest.json"
        original = json.loads(path.read_text())
        for field in ("run_id", "attempt", "merge_sha", "head_sha", "base_sha", "native_tests", "apks"):
            manifest = original.copy()
            manifest[field] = "different"
            path.write_text(json.dumps(manifest))
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "mismatch"):
                verify(self.directory)

    def test_empty_missing_modified_and_extra_apks_fail(self):
        produce(self.directory)
        extra = self.directory / "unexpected.apk"
        extra.write_bytes(b"extra")
        with self.assertRaisesRegex(ValueError, "mismatch"):
            verify(self.directory)
        extra.unlink()
        path = self.directory / "app-debug.apk"
        for data in (b"", b"modified"):
            path.write_bytes(data)
            with self.assertRaises(ValueError):
                verify(self.directory)
        path.unlink()
        with self.assertRaises(ValueError):
            verify(self.directory)

    @patch("android_test_artifacts.expected_tests", return_value={("example.Test", "method")})
    @patch("android_test_artifacts.subprocess.check_output", return_value="merge\n")
    def test_real_identity_binds_checkout_and_workflow_attempt(self, checkout, tests):
        with patch.dict(os.environ, {
            "GITHUB_SHA": "merge", "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "1",
            "PR_HEAD_SHA": "head", "PR_BASE_SHA": "base",
        }):
            self.assertEqual(self.expected, identity())
            with patch.dict(os.environ, {"GITHUB_SHA": "changed"}):
                with self.assertRaisesRegex(ValueError, "merge candidate"):
                    identity()

import stat
import unittest
from types import SimpleNamespace

from remove_unused_dotnet import (
    DOTNET,
    MINIMUM_PRE_SETUP_BYTES,
    MINIMUM_POST_SETUP_BYTES,
    OBSERVED_ANDROID_SETUP_BYTES,
    SETUP_HEADROOM_BYTES,
    removal_command,
    validate_target_metadata,
)


class HostedDotnetCleanupPolicyTests(unittest.TestCase):
    def setUp(self):
        self.info = SimpleNamespace(st_mode=stat.S_IFDIR, st_uid=0, st_dev=17)

    def test_only_root_owned_directory_on_runner_filesystem_without_mounts_is_eligible(self):
        validate_target_metadata(DOTNET, self.info, 17, set())

    def test_cleanup_refuses_non_directory_or_non_root_owned_target(self):
        for info in (
            SimpleNamespace(st_mode=stat.S_IFLNK, st_uid=0, st_dev=17),
            SimpleNamespace(st_mode=stat.S_IFDIR, st_uid=1001, st_dev=17),
        ):
            with self.subTest(info=info), self.assertRaises(RuntimeError):
                validate_target_metadata(DOTNET, info, 17, set())

    def test_cleanup_refuses_different_filesystem_and_nested_mounts(self):
        with self.assertRaisesRegex(RuntimeError, "separate filesystem"):
            validate_target_metadata(DOTNET, self.info, 18, set())
        with self.assertRaisesRegex(RuntimeError, "mounted content"):
            validate_target_metadata(DOTNET, self.info, 17, {f"{DOTNET.as_posix()}/cache"})

    def test_pre_setup_budget_keeps_measured_package_footprint_and_headroom(self):
        self.assertEqual(
            MINIMUM_POST_SETUP_BYTES + OBSERVED_ANDROID_SETUP_BYTES + SETUP_HEADROOM_BYTES,
            MINIMUM_PRE_SETUP_BYTES,
        )

    def test_removal_command_is_limited_to_the_exact_sdk_directory(self):
        self.assertEqual(
            ["sudo", "-n", "rm", "-rf", "--one-file-system", "--", DOTNET],
            removal_command(),
        )


if __name__ == "__main__":
    unittest.main()

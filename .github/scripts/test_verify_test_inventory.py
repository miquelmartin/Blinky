from pathlib import Path
import tempfile
import unittest

from verify_test_inventory import expected_tests, verify


class TestInventoryTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "source"
        self.reports = self.root / "reports"
        self.source.mkdir()
        self.reports.mkdir()
        (self.source / "ExampleTest.kt").write_text(
            "package example\nclass ExampleTest {\n"
            "@Test fun first() {}\n"
            "@Test(expected = IllegalStateException::class)\n"
            "@SdkSuppress(minSdkVersion = 29)\nfun second() {}\n}\n",
            encoding="utf-8",
        )

    def report(self, cases, attributes=""):
        (self.reports / "TEST-example.xml").write_text(
            f'<testsuite tests="{len(cases)}" {attributes}>'
            + "".join(
                f'<testcase classname="example.ExampleTest" name="{name}">{body}</testcase>'
                for name, body in cases
            )
            + "</testsuite>",
            encoding="utf-8",
        )

    def test_complete_source_inventory_passes(self):
        self.assertEqual(
            {("example.ExampleTest", "first"), ("example.ExampleTest", "second")},
            expected_tests(self.source),
        )
        self.report([("first", ""), ("second", "")])
        verify(self.source, self.reports)

    def test_missing_empty_partial_duplicate_and_unexpected_reports_fail(self):
        with self.assertRaisesRegex(ValueError, "No XML"):
            verify(self.source, self.reports)
        for cases in [
            [], [("first", "")], [("first", ""), ("first", ""), ("second", "")],
            [("first", ""), ("second", ""), ("unexpected", "")],
        ]:
            with self.subTest(cases=cases):
                self.report(cases)
                with self.assertRaises(ValueError):
                    verify(self.source, self.reports)

    def test_failure_error_and_skip_fail_even_with_complete_inventory(self):
        for tag in ("failure", "error", "skipped"):
            with self.subTest(tag=tag):
                self.report([("first", f"<{tag}/>"), ("second", "")])
                with self.assertRaisesRegex(ValueError, "Unsuccessful"):
                    verify(self.source, self.reports)
        for attribute in ("failures", "errors", "skipped"):
            with self.subTest(attribute=attribute):
                self.report([("first", ""), ("second", "")], f'{attribute}="1"')
                with self.assertRaisesRegex(ValueError, "Nonzero"):
                    verify(self.source, self.reports)

    def test_malformed_and_inconsistent_xml_fail(self):
        import xml.etree.ElementTree as ET

        self.report([("first", ""), ("second", "")])
        path = self.reports / "TEST-example.xml"
        path.write_text('<testsuite tests="3"><testcase/></testsuite>', encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "inconsistent"):
            verify(self.source, self.reports)
        path.write_text("<testsuite", encoding="utf-8")
        with self.assertRaises(ET.ParseError):
            verify(self.source, self.reports)

    def test_empty_or_unsupported_source_cannot_lower_expected_inventory(self):
        path = self.source / "ExampleTest.kt"
        for text in ("package example\nclass ExampleTest {}", "@Test suspend fun unsupported() {}"):
            with self.subTest(text=text):
                path.write_text(text, encoding="utf-8")
                with self.assertRaises(ValueError):
                    expected_tests(self.source)
        (self.source / "OtherTest.java").write_text("class OtherTest {}", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Java test sources"):
            expected_tests(self.source)


if __name__ == "__main__":
    unittest.main()

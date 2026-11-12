from pathlib import Path
import tempfile
import unittest

from android_test_results import write_report


def status(method, number, code, total=2):
    return (
        "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner\n"
        "INSTRUMENTATION_STATUS: class=example.NativeTest\n"
        f"INSTRUMENTATION_STATUS: test={method}\n"
        f"INSTRUMENTATION_STATUS: current={number}\n"
        f"INSTRUMENTATION_STATUS: numtests={total}\n"
        "INSTRUMENTATION_STATUS: stream=.\n"
        f"INSTRUMENTATION_STATUS_CODE: {code}\n"
    )


def complete():
    return "".join(status(method, index, code) for index, method in enumerate(("first", "second"), 1)
                   for code in (1, 0)) + (
        "INSTRUMENTATION_RESULT: stream=\nTime: 0.1\n\nOK (2 tests)\n\nINSTRUMENTATION_CODE: -1\n"
    )


class InstrumentationResultsTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "source"
        self.source.mkdir()
        (self.source / "NativeTest.kt").write_text(
            "package example\nclass NativeTest {\n@Test fun first() {}\n@Test fun second() {}\n}\n",
            encoding="utf-8",
        )
        self.reports = self.root / "reports"

    def test_complete_started_and_finished_inventory_generates_independently_verified_xml(self):
        write_report(complete(), self.source, self.reports)
        self.assertIn('tests="2"', (self.reports / "TEST-native.xml").read_text())

    def test_failures_errors_ignored_and_assumptions_never_emit_success_xml(self):
        for code in (-1, -2, -3, -4):
            with self.subTest(code=code), self.assertRaises(ValueError):
                write_report(complete().replace("STATUS_CODE: 0", f"STATUS_CODE: {code}", 1),
                             self.source, self.reports)
        self.assertFalse(self.reports.exists())

    def test_absent_truncated_unexpected_duplicate_and_wrong_totals_fail(self):
        good = complete()
        cases = [
            "", "INSTRUMENTATION_CODE: -1\n", good.split("INSTRUMENTATION_RESULT:")[0],
            good.replace(status("second", 2, 0), ""),
            good.replace("test=second", "test=unexpected"),
            good.replace("numtests=2", "numtests=1"),
            status("first", 1, 1) + status("first", 1, 0) + good,
            good.replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"),
            good.replace("OK (2 tests)", "OK (1 test)"),
            good + "INSTRUMENTATION_CODE: -1\n",
            good.replace("id=AndroidJUnitRunner", "id=OtherRunner"),
            good.replace("current=2", "current=1"),
            good.replace("INSTRUMENTATION_RESULT: stream=", "INSTRUMENTATION_RESULT: shortMsg=Process crashed"),
            good.replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_FAILED: Process crashed"),
        ]
        for index, output in enumerate(cases):
            with self.subTest(index=index), self.assertRaises(ValueError):
                write_report(output, self.source, self.reports)
        self.assertFalse(self.reports.exists())

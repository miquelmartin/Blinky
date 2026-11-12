import re
import xml.etree.ElementTree as ET

from verify_test_inventory import expected_tests, verify


def write_report(output, source, reports):
    expected = expected_tests(source)
    active = None
    completed = set()
    bundle = {}
    final = False
    result_seen = False
    for line in output.splitlines():
        if final and line.strip():
            raise ValueError("Instrumentation output continues after its terminal result")
        if line.startswith(("INSTRUMENTATION_FAILED", "INSTRUMENTATION_ABORTED")):
            raise ValueError(f"Instrumentation did not complete: {line}")
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, separator, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
            if not separator or key in bundle or result_seen:
                raise ValueError(f"Malformed or duplicate instrumentation status: {line}")
            bundle[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.removeprefix("INSTRUMENTATION_STATUS_CODE: "))
            test = (bundle.get("class"), bundle.get("test"))
            if (test not in expected or int(bundle.get("numtests", "-1")) != len(expected)
                    or bundle.get("id") != "AndroidJUnitRunner" or "stack" in bundle
                    or int(bundle.get("current", "-1")) != len(completed) + 1):
                raise ValueError(f"Unexpected test or incomplete discovered inventory: {bundle}")
            if code == 1 and active is None and test not in completed:
                active = test
            elif code == 0 and active == test and test not in completed:
                completed.add(test)
                active = None
            else:
                raise ValueError(f"Failed, skipped, duplicate or unpaired test status {code}: {bundle}")
            bundle = {}
        elif line.startswith("INSTRUMENTATION_RESULT: "):
            if active is not None or bundle or completed != expected:
                raise ValueError("Instrumentation ended with incomplete tests")
            key, separator, value = line.removeprefix("INSTRUMENTATION_RESULT: ").partition("=")
            if not separator or key != "stream" or result_seen:
                raise ValueError(f"Unexpected instrumentation result: {line}")
            result_seen = True
        elif line.startswith("INSTRUMENTATION_CODE: "):
            if line != "INSTRUMENTATION_CODE: -1" or not result_seen or active is not None or completed != expected:
                raise ValueError(f"Unsuccessful or incomplete instrumentation terminal result: {line}")
            final = True
        elif line.startswith("INSTRUMENTATION_"):
            raise ValueError(f"Unsupported instrumentation protocol: {line}")
    if not final or bundle or active is not None or completed != expected:
        raise ValueError("Missing or truncated instrumentation completion evidence")
    if not re.search(rf"^OK \({len(expected)} tests?\)\s*$", output, re.MULTILINE):
        raise ValueError("Missing complete AndroidJUnitRunner success summary")
    reports.mkdir(parents=True)
    suite = ET.Element("testsuite", tests=str(len(completed)), failures="0", errors="0", skipped="0")
    for classname, method in sorted(completed):
        ET.SubElement(suite, "testcase", classname=classname, name=method)
    ET.ElementTree(suite).write(reports / "TEST-native.xml", encoding="utf-8", xml_declaration=True)
    verify(source, reports)

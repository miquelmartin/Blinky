import argparse
from collections import Counter
from pathlib import Path
import re
import xml.etree.ElementTree as ET


def expected_tests(source):
    if any(source.rglob("*.java")):
        raise ValueError(f"Java test sources require an explicit inventory-parser update: {source}")
    expected = set()
    files = sorted(source.rglob("*.kt"))
    for path in files:
        text = path.read_text(encoding="utf-8")
        # Fail closed if a new test syntax cannot be inventoried.
        annotations = re.findall(r"@Test\b", text)
        methods = re.findall(
            r"@Test(?:\([^)]*\))?(?:\s+@\w+(?:\([^)]*\))?)*\s+fun\s+(\w+)\s*\(",
            text,
        )
        if len(annotations) != len(methods):
            raise ValueError(f"Unsupported test declaration in {path}")
        if not methods:
            continue
        package = re.search(r"^package\s+([\w.]+)", text, re.MULTILINE)
        classes = re.findall(r"^class\s+(\w+)", text, re.MULTILINE)
        if package is None or len(classes) != 1:
            raise ValueError(f"Expected one test class and package in {path}")
        for method in methods:
            identity = (f"{package[1]}.{classes[0]}", method)
            if identity in expected:
                raise ValueError(f"Duplicate source test: {identity}")
            expected.add(identity)
    if not expected:
        raise ValueError(f"No source tests found in {source}")
    return expected


def verify(source, reports):
    expected = expected_tests(source)
    observed = Counter()
    paths = sorted(reports.rglob("*.xml"))
    if not paths:
        raise ValueError(f"No XML test reports found in {reports}")
    for path in paths:
        root = ET.parse(path).getroot()
        suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
        if not suites:
            raise ValueError(f"No test suites in {path}")
        for suite in suites:
            cases = suite.findall("testcase")
            if not cases or int(suite.attrib["tests"]) != len(cases):
                raise ValueError(f"Empty or inconsistent test suite in {path}")
            for name in ("failures", "errors", "skipped"):
                if int(suite.get(name, "0")) != 0:
                    raise ValueError(f"Nonzero {name} in {path}")
            for case in cases:
                if any(case.find(name) is not None for name in ("failure", "error", "skipped")):
                    raise ValueError(f"Unsuccessful test in {path}: {case.attrib}")
                observed[(case.get("classname"), case.get("name"))] += 1
    missing = expected - observed.keys()
    unexpected = observed.keys() - expected
    duplicates = [identity for identity, count in observed.items() if count != 1]
    if missing or unexpected or duplicates:
        raise ValueError(
            f"Incomplete test inventory: missing={sorted(missing)}, "
            f"unexpected={sorted(unexpected, key=str)}, duplicates={duplicates}"
        )
    print(f"Verified all {len(expected)} source tests exactly once in {len(paths)} XML reports.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Reject incomplete Android JUnit coverage.")
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--reports", type=Path, required=True)
    args = parser.parse_args()
    verify(args.source, args.reports)

#!/usr/bin/env python3
"""Turn a Gradle log into GitHub annotations.

Why this exists: the raw job log lives behind a log-service host that some environments cannot read,
and a red build whose error text nobody can retrieve is a build that cannot be fixed. This script
prints the interesting lines *and* re-emits them as `::error`/`::warning` annotations, which travel on
the checks API. It never invents success: it exits with the exit code of the Gradle invocation it was
given, so a failure stays a failure.

Usage:  ci_explain.py <gradle-log-file> <gradle-exit-code> [--test-results <dir>]
"""

from __future__ import annotations

import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

KOTLIN = re.compile(r"^(?P<level>[ew]): file://(?P<path>[^:]+):(?P<line>\d+):(?P<col>\d+)\s+(?P<msg>.*)$")
GRADLE_FAIL = re.compile(r"^\* (What went wrong|Where):")
TEST_FAILED = re.compile(r"^(?P<cls>[A-Za-z0-9_.]+) > (?P<name>[^=]+) .*FAILED")


def emit(level: str, title: str, path: str, line: str, message: str) -> None:
    parts = [f"title={title}"]
    if path:
        parts.append(f"file={path}")
    if line:
        parts.append(f"line={line}")
    body = message.replace("\r", " ").replace("\n", " ").replace("%", "%25").replace("\r", "%0D")
    print(f"::{level} {' '.join(parts)}::{body}")


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        print("usage: ci_explain.py <log-file> <exit-code> [--test-results <dir>]", file=sys.stderr)
        return 2
    log_path, code = argv[1], int(argv[2])
    test_dir = None
    if "--test-results" in argv:
        test_dir = argv[argv.index("--test-results") + 1]

    lines: list[str] = []
    if os.path.exists(log_path):
        with open(log_path, encoding="utf-8", errors="replace") as handle:
            lines = handle.read().splitlines()

    # 1. Kotlin / Java compiler diagnostics, deduplicated, order preserved.
    seen: set[str] = set()
    reported = 0
    for raw in lines:
        match = KOTLIN.match(raw)
        if match:
            key = f"{match['path']}:{match['line']}:{match['msg']}"
            if key in seen:
                continue
            seen.add(key)
            emit("error" if match["level"] == "e" else "warning", "Compile", match["path"], match["line"], match["msg"])
            reported += 1
            continue
        if TEST_FAILED.match(raw):
            emit("error", "Test failure", "", "", raw.strip())
            reported += 1

    # 2. The Gradle "What went wrong" block, which is where the actionable line usually is.
    for index, raw in enumerate(lines):
        if GRADLE_FAIL.match(raw):
            block = [raw.strip()]
            for follow in lines[index + 1 : index + 9]:
                stripped = follow.strip()
                if stripped.startswith("* Try:") or stripped.startswith("FAILURE:") or stripped.startswith("* What"):
                    if stripped.startswith("* Try:"):
                        block.append(stripped)
                    break
                if not stripped:
                    break
                block.append(stripped)
            emit("error", "Gradle", "", "", " | ".join(block)[:500])
            reported += 1
            break

    # 3. Test result XMLs: exact counts, and the message of each failed case.
    if test_dir and os.path.isdir(test_dir):
        tests = failures = errors = skipped = 0
        for path in sorted(glob.glob(os.path.join(test_dir, "*.xml"))):
            try:
                root = ET.parse(path).getroot()
            except ET.ParseError:
                continue
            tests += int(root.get("tests", 0))
            failures += int(root.get("failures", 0))
            errors += int(root.get("errors", 0))
            skipped += int(root.get("skipped", 0))
            for case in root.iter("testcase"):
                for kind in ("failure", "error"):
                    node = case.find(kind)
                    if node is not None:
                        text = (node.get("message") or node.text or "").strip().splitlines()
                        detail = text[0] if text else "no message"
                        emit(
                            "error",
                            "Failed test",
                            "",
                            "",
                            f"{case.get('classname')}.{case.get('name')}: {detail}"[:500],
                        )
                        reported += 1
        print(f"JVM unit tests: {tests} run, {failures} failed, {errors} errors, {skipped} skipped")
        summary = os.environ.get("GITHUB_STEP_SUMMARY")
        if summary:
            with open(summary, "a", encoding="utf-8") as handle:
                handle.write(
                    f"\nJVM unit tests: {tests} run, {failures} failed, {errors} errors, "
                    f"{skipped} skipped\n"
                )

    if reported == 0 and code != 0:
        # Never leave a red build with no explanation. Show the tail so the cause is at hand.
        tail = [line for line in lines[-60:] if line.strip()]
        emit("error", "Failed without a parseable diagnostic", "", "", ("tail: " + " ⏎ ".join(tail[-12:]))[:800])

    if code != 0:
        print(f"Gradle exited {code}; propagating failure.", file=sys.stderr)
    return code


if __name__ == "__main__":
    sys.exit(main(sys.argv))

#!/usr/bin/env python3
"""Export measured JaCoCo coverage without summing nested counters or duplicate classes."""

import argparse
from datetime import datetime, timezone
from decimal import Decimal, ROUND_HALF_UP
import html
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

REPORTS = {
    "census-core": "target/site/jacoco/jacoco.xml",
    "census-agent": "target/site/jacoco-runtime/jacoco.xml",
    "census-delivery": "target/site/jacoco/jacoco.xml",
    "census-cli": "target/site/jacoco/jacoco.xml",
    "census-maven-plugin": "target/site/jacoco/jacoco.xml",
}
METRICS = ("LINE", "BRANCH")
MAX_REPORT_BYTES = 32 * 1024 * 1024
SCOPE = (
    "Five production modules: core, agent, delivery, CLI and Maven plugin. "
    "Agent uses shaded runtime classes with child-JVM and compatible unit-test data; "
    "Maven plugin uses merged unit and Invoker data. Demo and conformance fixture "
    "modules, dependencies and documentation are excluded."
)


def counter(element, metric):
    matches = [c for c in element.findall("counter") if c.get("type") == metric]
    if len(matches) != 1:
        raise ValueError(f"Expected one {metric} counter")
    values = {}
    for key in ("covered", "missed"):
        raw = matches[0].get(key, "")
        if not re.fullmatch(r"[0-9]{1,12}", raw):
            raise ValueError(f"Invalid {metric} {key} counter")
        values[key] = int(raw)
    return values


def percentage(covered, missed):
    total = covered + missed
    if total == 0:
        raise ValueError("Empty coverage denominator")
    return float((Decimal(covered) * 100 / Decimal(total)).quantize(Decimal("0.1"), rounding=ROUND_HALF_UP))


def measure(root):
    modules = []
    seen_classes = set()
    for module, relative in REPORTS.items():
        path = root / module / relative
        # Bounded read, including when this is called outside the trusted CI build.
        with path.open("rb") as stream:
            data = stream.read(MAX_REPORT_BYTES + 1)
        if len(data) > MAX_REPORT_BYTES:
            raise ValueError(f"Report too large: {module}")
        # JaCoCo emits an external DOCTYPE. ElementTree never fetches it. Reject
        # internal subsets/entities rather than accepting entity expansion.
        data = data.decode("utf-8")
        if "<!ENTITY" in data.upper() or re.search(r"<!DOCTYPE[^>]*\[", data, re.I):
            raise ValueError(f"XML entities/internal subsets forbidden: {module}")
        report = ET.fromstring(data)
        if report.tag != "report":
            raise ValueError(f"Expected JaCoCo report: {module}")
        classes = [c.get("name") for c in report.findall("./package/class")]
        if not classes or any(not name for name in classes):
            raise ValueError(f"Missing class identities: {module}")
        if len(set(classes)) != len(classes) or seen_classes.intersection(classes):
            raise ValueError(f"Duplicate class coverage: {module}")
        seen_classes.update(classes)
        metrics = {}
        for metric in METRICS:
            counts = counter(report, metric)
            packages = [counter(package, metric) for package in report.findall("package")
                        if any(c.get("type") == metric for c in package.findall("counter"))]
            if any(sum(p[key] for p in packages) != counts[key] for key in counts):
                raise ValueError(f"Package/report counter mismatch: {module} {metric}")
            metrics[metric.lower()] = {**counts, "percent": percentage(**counts)}
        modules.append({"name": module, "report": f"{module}/{relative}", "classes": len(classes), **metrics})
    aggregate = {}
    for metric in ("line", "branch"):
        counts = {key: sum(module[metric][key] for module in modules) for key in ("covered", "missed")}
        aggregate[metric] = {**counts, "percent": percentage(**counts)}
    return modules, aggregate


def badge(label, value):
    message = f"{value:.1f}%"
    left = len(label) * 7 + 14
    right = len(message) * 7 + 14
    width = left + right
    title = html.escape(f"{label}: {message}")
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="20" role="img" aria-label="{title}">
<title>{title}</title>
<rect width="{width}" height="20" rx="3" fill="#555"/>
<path d="M{left} 0h{right - 3}q3 0 3 3v14q0 3-3 3H{left}z" fill="#176b43"/>
<g fill="#fff" text-anchor="middle" font-family="Verdana,DejaVu Sans,sans-serif" font-size="11">
<text x="{left / 2}" y="14">{html.escape(label)}</text>
<text x="{left + right / 2}" y="14">{message}</text>
</g></svg>
'''


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--sha", required=True, help="Full source commit SHA")
    parser.add_argument("--run-url", required=True, help="GitHub Actions run producing these reports")
    args = parser.parse_args(argv)
    if not re.fullmatch(r"[0-9a-f]{40}", args.sha):
        parser.error("--sha must be a full lowercase Git commit SHA")
    if not re.fullmatch(r"https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/actions/runs/[0-9]+", args.run_url):
        parser.error("--run-url must identify a GitHub Actions run")
    try:
        modules, aggregate = measure(args.root)
    except (OSError, ValueError, ET.ParseError) as error:
        parser.exit(1, f"Coverage export failed: {error}\n")
    result = {
        "schemaVersion": 1, "sourceSha": args.sha, "runUrl": args.run_url,
        "generatedAt": datetime.now(timezone.utc).isoformat(), "scope": SCOPE,
        "measurement": "Eclipse Temurin 25 on Ubuntu; mvnw verify with quality profile",
        "aggregation": "sum(covered) / sum(covered + missed); one report per module",
        "modules": modules, "aggregate": aggregate,
    }
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "coverage.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    for metric, filename, label in (("line", "coverage.svg", "line coverage"),
                                     ("branch", "coverage-branches.svg", "branch coverage")):
        (args.output / filename).write_text(badge(label, aggregate[metric]["percent"]), encoding="utf-8")
    print(json.dumps({"sourceSha": args.sha, "aggregate": aggregate}))


if __name__ == "__main__":
    sys.exit(main())

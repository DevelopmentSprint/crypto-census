#!/usr/bin/env python3
"""Assert the documented synthetic demo outcomes; never inspect private tracker state."""
import argparse
import json
from html.parser import HTMLParser
from pathlib import Path


class EmbeddedReport(HTMLParser):
    def __init__(self):
        super().__init__()
        self.active = False
        self.parts = []
        self.count = 0

    def handle_starttag(self, tag, attrs):
        if tag == "script" and dict(attrs).get("id") == "report-data":
            self.active = True
            self.count += 1

    def handle_endtag(self, tag):
        if tag == "script":
            self.active = False

    def handle_data(self, data):
        if self.active:
            self.parts.append(data)


def verify(directory):
    report = json.loads((directory / "assessment.json").read_text(encoding="utf-8"))
    parser = EmbeddedReport()
    parser.feed((directory / "assessment.html").read_text(encoding="utf-8"))
    assert parser.count == 1, "HTML must contain exactly one canonical report"
    assert json.loads("".join(parser.parts)) == report, "HTML/JSON assessment differs"
    flows = {flow["subject"]["flowId"]: flow for flow in report["flows"]}
    expected = {
        "records-archive": "URGENT_PLANNING",
        "daily-export": "PLANNED_MIGRATION",
        "payment-signing": "URGENT_PLANNING",
        "card-vault": "NO_PQC_ACTION_IDENTIFIED_IN_OBSERVED_SCOPE",
        "async-archive": "NEEDS_CONTEXT",
    }
    assert set(expected) <= flows.keys(), "A documented flow is absent"
    for flow_id in expected:
        matches = [flow for flow in report["flows"] if flow["subject"]["flowId"] == flow_id]
        assert len(matches) == 1, (flow_id, "Duplicate application/flow identity")
        assert matches[0]["subject"]["applicationId"] == "demo-payments", flow_id
    for flow_id, priority in expected.items():
        flow = flows[flow_id]
        assert flow["priority"] == priority, (flow_id, flow["priority"])
        assert flow["readiness"]["effectiveState"] == "UNASSESSED", flow_id
        if flow_id != "async-archive":
            assert flow["directCount"] == 1 and flow["inclusiveCount"] == 1, flow_id
    asynchronous = flows["async-archive"]
    assert not asynchronous["observed"] and asynchronous["inclusiveCount"] == 0
    assert "NO_OBSERVATION" in asynchronous["gaps"]
    assert asynchronous["coverageIncomplete"]
    shared = [finding for finding in report["findings"]
              if finding["kind"] == "PRIMITIVE"
              and finding["asset"]["algorithm"] == "RSA"
              and finding["asset"]["operation"] == "ENCRYPT"
              and finding["occurrence"]["location"].endswith(".ArchiveFlows")
              and finding["occurrence"]["symbol"] == "protect"]
    assert sum(finding["count"] for finding in shared) == 4, "Shared-helper totals changed"
    unassigned = [finding for finding in shared if finding["subject"] is None]
    assert sum(finding["count"] for finding in unassigned) == 2
    assert all(finding["association"] == "NO_SCOPE" for finding in unassigned)
    assert all(finding["subject"]["applicationId"] == "demo-payments"
               for finding in shared if finding["subject"] is not None)
    assert report["incomplete"], "Unobserved/unassigned work cannot become complete"
    print("Advisory demo: five priorities, shared-helper counts, async gap and paired HTML/JSON verified")


if __name__ == "__main__":
    arguments = argparse.ArgumentParser(description=__doc__)
    arguments.add_argument("directory", type=Path, help="Synthetic demo assessment output directory")
    verify(arguments.parse_args().directory)

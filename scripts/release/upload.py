#!/usr/bin/env python3
"""Upload an already inspected bundle as USER_MANAGED; never publish or retry an upload."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

from central import require


BASE = "https://central.sonatype.com/api/v1/publisher/"
DEPLOYMENT_ID = r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("Central redirect refused; credentials were not forwarded")


def request(endpoint, body, content_type, token):
    require(endpoint.startswith("upload?") or endpoint.startswith("status?"), "Unsupported Central operation")
    req = urllib.request.Request(BASE + endpoint, data=body, method="POST", headers={
        "Authorization": "Bearer " + token, "Content-Type": content_type})
    try:
        with urllib.request.build_opener(NoRedirect()).open(req, timeout=120) as response:
            data = response.read(1024 * 1024 + 1)
            require(len(data) <= 1024 * 1024, "Central response too large")
            return data
    except (urllib.error.URLError, OSError):
        # Never print response bodies, request headers, or exception messages.
        raise ValueError("Central request failed; check Portal before retrying. Response withheld.") from None


def stage(bundle, evidence, token, send=request, clock=time.monotonic, sleep=time.sleep):
    record = json.loads(evidence.read_text())
    require(record.get("state") == "INSPECTED" and record.get("published") is False,
            "Only a locally inspected, unsubmitted candidate may be uploaded")
    data = bundle.read_bytes()
    require(hashlib.sha256(data).hexdigest() == record.get("bundleSha256"), "Bundle changed after inspection")
    boundary = "census-" + secrets.token_hex(24)
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"bundle\"; filename=\"central-bundle.zip\"\r\n"
            "Content-Type: application/octet-stream\r\n\r\n").encode() + data + f"\r\n--{boundary}--\r\n".encode()
    query = urllib.parse.urlencode({"name": f"crypto-census-{record['version']}-{record['sourceSha']}",
                                    "publishingType": "USER_MANAGED"})
    # No automatic upload retry: a lost response may still have created a deployment.
    deployment = send("upload?" + query, body, "multipart/form-data; boundary=" + boundary, token).decode().strip()
    require(re.fullmatch(DEPLOYMENT_ID, deployment), "Unexpected upload response; inspect Portal before retrying")
    record.update({"deploymentId": deployment, "state": "UPLOADED"})
    evidence.write_text(json.dumps(record, indent=2) + "\n")
    print("Uploaded deployment " + deployment + "; waiting for validation", flush=True)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
            summary.write(f"### Maven Central candidate\n\nSource: `{record['sourceSha']}`\n\n"
                          f"Deployment: `{deployment}`\n\nUpload accepted; validation pending.\n")
    deadline = clock() + 1800
    while clock() < deadline:
        status = json.loads(send("status?" + urllib.parse.urlencode({"id": deployment}), b"", "application/json", token))
        require(status.get("deploymentId") == deployment, "Central returned a different deployment ID")
        state = status.get("deploymentState")
        require(state in ("PENDING", "VALIDATING", "VALIDATED", "FAILED", "PUBLISHING", "PUBLISHED"),
                "Unexpected deployment state; inspect Portal before taking further action")
        record["state"] = state
        record["published"] = state == "PUBLISHED"
        evidence.write_text(json.dumps(record, indent=2) + "\n")
        require(state not in ("PUBLISHING", "PUBLISHED"), "Deployment was published outside this workflow; inspect Portal")
        require(state != "FAILED", "Central validation failed; inspect errors in Portal. Do not publish.")
        if state == "VALIDATED":
            print("Deployment validated. Publication remains a separate manual Portal action.")
            if os.environ.get("GITHUB_STEP_SUMMARY"):
                with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
                    summary.write("\n**VALIDATED; not published.** Test this signed deployment using docs/RELEASING.md before publishing.\n")
            return
        sleep(10)
    raise ValueError("Validation timed out; inspect the recorded deployment in Portal before retrying")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    username = os.environ.get("CENSUS_CENTRAL_USERNAME", "")
    password = os.environ.get("CENSUS_CENTRAL_PASSWORD", "")
    require(username and password, "Configure CENTRAL_USERNAME and CENTRAL_PASSWORD in maven-central")
    token = base64.b64encode(f"{username}:{password}".encode()).decode()
    stage(args.bundle, args.evidence, token)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, UnicodeError):
        # Includes malformed remote JSON; never echo server-controlled content or credentials.
        print("Central staging failed. Inspect the deployment in Portal before retrying; no automatic retry was made.", file=sys.stderr)
        raise SystemExit(1) from None

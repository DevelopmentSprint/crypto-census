#!/usr/bin/env python3
"""Fail-closed release provenance and signed Central bundle inspection. No credentials in output."""

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile


REPOSITORY = "DevelopmentSprint/crypto-census"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
CI_JOBS = {
    *(f"JDK {jdk} temurin / ubuntu-latest" for jdk in (17, 21, 25, 26, 27)),
    *(f"JDK {jdk} temurin / windows-latest" for jdk in (17, 25, 27)),
    *(f"JDK {jdk} semeru / ubuntu-latest" for jdk in (17, 21, 25)),
    "Maven plugin dogfood / runtime CBOM",
    "App servers (Open Liberty on Semeru, WildFly) / ubuntu-latest",
    "Classify changed paths",
    "Required CI",
}
MODULES = ("census-parent", "census-core", "census-agent", "census-delivery",
           "census-cli", "census-maven-plugin")
PRIVATE_PARTS = {".beads", ".beadroad", ".dolt", "dolt", "embeddeddolt",
                 "proxieddb", ".beads-credential-key"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def command(args):
    result = subprocess.run(args, capture_output=True, text=True, check=False)
    require(result.returncode == 0, f"{args[0]} operation failed; command output withheld")
    return result.stdout


def api(path):
    return json.loads(command(["gh", "api", f"repos/{REPOSITORY}/{path}"]))


def validate_context(event, repository, ref, expected_sha, actual_sha):
    require(event == "workflow_dispatch", "Only a manual dispatch may stage a release")
    require(repository == REPOSITORY, "Only the original repository may stage a release")
    require(ref == "refs/heads/main" or re.fullmatch(r"refs/heads/release/[^/]+", ref),
            "Select main or a release/* branch (not develop, a feature branch or a tag)")
    require(re.fullmatch(r"[0-9a-f]{40}", expected_sha), "Expected SHA must be 40 lowercase hex characters")
    require(expected_sha == actual_sha, "Selected branch has moved or does not match the accepted SHA")


def validate_run(run, jobs, sha, branch, workflow, required_jobs):
    require(run.get("repository", {}).get("full_name") == REPOSITORY
            and run.get("head_repository", {}).get("full_name") == REPOSITORY,
            "Evidence must come from the original repository")
    require(run.get("path") == f".github/workflows/{workflow}", "Wrong evidence workflow")
    require(run.get("head_sha") == sha and run.get("head_branch") == branch,
            "Evidence must match the exact source commit and selected branch")
    require(run.get("event") in ("push", "workflow_dispatch"), "PR or scheduled evidence is not release evidence")
    require(run.get("status") == "completed" and run.get("conclusion") == "success",
            "Evidence run must have completed successfully")
    for name in sorted(required_jobs):
        matches = [job for job in jobs if job.get("name") == name]
        require(len(matches) == 1 and matches[0].get("conclusion") == "success"
                and matches[0].get("status") == "completed"
                and matches[0].get("run_attempt") == run["run_attempt"],
                f"Required job did not execute successfully in the latest attempt: {name}")


def load_run(run_id, sha, branch, workflow, jobs):
    require(re.fullmatch(r"[1-9][0-9]*", run_id), "Run ID must be a positive integer")
    run = api(f"actions/runs/{run_id}")
    attempt = run.get("run_attempt")
    require(isinstance(attempt, int) and attempt > 0, "Missing run attempt")
    all_jobs = []
    page = 1
    while True:
        batch = api(f"actions/runs/{run_id}/attempts/{attempt}/jobs?per_page=100&page={page}")
        all_jobs.extend(batch["jobs"])
        if len(all_jobs) >= batch["total_count"]:
            break
        require(batch["jobs"] and page < 100, "Incomplete job pagination")
        page += 1
    validate_run(run, all_jobs, sha, branch, workflow, jobs)
    # Fail if a new attempt began while jobs were being inspected.
    current = api(f"actions/runs/{run_id}")
    require(current.get("run_attempt") == attempt and current.get("conclusion") == "success"
            and current.get("status") == "completed", "Evidence changed during inspection")
    return {"runId": int(run_id), "runAttempt": attempt,
            "url": f"https://github.com/{REPOSITORY}/actions/runs/{run_id}/attempts/{attempt}",
            "requiredJobs": sorted(jobs)}


def release_version(pom):
    root = ET.parse(pom).getroot()
    version = root.findtext("m:version", namespaces=NS)
    require(version and re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version),
            "Only a stable, explicit release version is accepted")
    require(root.findtext("m:groupId", namespaces=NS) == "com.developmentsprint",
            "Unexpected release group")
    require(root.findtext("m:scm/m:tag", namespaces=NS) == f"v{version}", "SCM tag must match release version")
    plugins = root.findall("m:profiles/m:profile[m:id='release']/m:build/m:plugins/m:plugin", NS)
    central = [p for p in plugins if p.findtext("m:artifactId", namespaces=NS) == "central-publishing-maven-plugin"]
    require(len(central) == 1, "Missing Central release plugin")
    config = central[0].find("m:configuration", NS)
    require(config is not None and config.findtext("m:autoPublish", namespaces=NS) == "false"
            and config.findtext("m:waitUntil", namespaces=NS) == "validated"
            and config.findtext("m:publishingServerId", namespaces=NS) == "central",
            "Central must use manual publication and wait for validation")
    return version


def preflight(args):
    sha = command(["git", "rev-parse", "HEAD"]).strip()
    validate_context(os.environ.get("GITHUB_EVENT_NAME"), os.environ.get("GITHUB_REPOSITORY"),
                     os.environ.get("GITHUB_REF", ""), args.expected_sha, sha)
    require(sha == os.environ.get("GITHUB_SHA"), "Checkout does not match workflow source")
    require(not command(["git", "status", "--porcelain"]).strip(), "Release checkout must be clean")
    branch = os.environ["GITHUB_REF"].removeprefix("refs/heads/")
    record = {"sourceSha": sha, "sourceTree": command(["git", "rev-parse", "HEAD^{tree}"]).strip(),
              "sourceBranch": branch, "version": release_version("pom.xml"),
              "ci": load_run(args.ci_run, sha, branch, "ci.yml", CI_JOBS),
              "dependencies": load_run(args.dependency_run, sha, branch, "dependency-check.yml", {"dependency-check"})}
    Path(args.output).write_text(json.dumps(record, indent=2) + "\n")
    print(f"Release {record['version']} at {sha}: full CI and dependency scan verified")


def expected_artifacts(version):
    expected = set()
    for module in MODULES:
        stem = f"com/developmentsprint/{module}/{version}/{module}-{version}"
        expected.add(stem + ".pom")
        if module != "census-parent":
            expected.update(stem + suffix for suffix in (".jar", "-sources.jar", "-javadoc.jar"))
        if module == "census-delivery":
            expected.add(stem + "-tests.jar")
    return expected


def inspect_bundle(bundle, version, fingerprint, verify_signature):
    require(re.fullmatch(r"[0-9A-F]{40}", fingerprint), "A full uppercase signing fingerprint is required")
    expected = expected_artifacts(version)
    signatures = {name + ".asc" for name in expected}
    allowed = expected | signatures
    allowed |= {name + suffix for name in allowed for suffix in (".md5", ".sha1", ".sha256", ".sha512")}
    records = []
    with zipfile.ZipFile(bundle) as archive:
        entries = archive.infolist()
        require(len(entries) <= 500 and sum(i.file_size for i in entries) <= 512 * 1024 * 1024,
                "Oversized release bundle")
        names = [i.filename for i in entries if not i.is_dir()]
        require(len(names) == len(set(names)), "Duplicate bundle entries")
        require(set(names) <= allowed and expected | signatures <= set(names),
                "Bundle must contain exactly the six approved coordinates, all 22 artifacts and signatures")
        for info in entries:
            require(not ((info.external_attr >> 16) & 0o170000) == 0o120000, "Symlink in bundle")
            if info.is_dir():
                require(any(name.startswith(info.filename) for name in expected), "Unexpected bundle directory")
        for name in sorted(expected):
            data = archive.read(name)
            signature = archive.read(name + ".asc")
            if name.endswith(".jar"):
                with zipfile.ZipFile(io.BytesIO(data)) as jar:
                    require(not any(PRIVATE_PARTS.intersection(Path(n).parts) for n in jar.namelist()),
                            "Private tracker path in release JAR")
            verify_signature(data, signature, fingerprint)
            records.append({"path": name, "sha256": hashlib.sha256(data).hexdigest(),
                            "signatureSha256": hashlib.sha256(signature).hexdigest()})
        for name in names:
            original, _, algorithm = name.rpartition(".")
            if algorithm in ("md5", "sha1", "sha256", "sha512"):
                checksum = archive.read(name).decode("ascii").strip().lower()
                require(checksum == hashlib.new(algorithm, archive.read(original)).hexdigest(), "Bundle checksum mismatch")
    return records


def gpg_verify(data, signature, fingerprint):
    with tempfile.TemporaryDirectory(prefix="census-signature-") as temp:
        payload, detached = Path(temp) / "artifact", Path(temp) / "signature.asc"
        payload.write_bytes(data)
        detached.write_bytes(signature)
        status = command(["gpg", "--batch", "--status-fd", "1", "--verify", str(detached), str(payload)])
        require(any(line.startswith(f"[GNUPG:] VALIDSIG {fingerprint} ") for line in status.splitlines()),
                "Artifact signature does not match the configured signing key")


def evidence(args):
    record = json.loads(Path(args.preflight).read_text())
    records = inspect_bundle(args.bundle, record["version"], args.fingerprint, gpg_verify)
    record.update({"state": "INSPECTED", "published": False,
                   "signingFingerprint": args.fingerprint, "artifacts": records,
                   "bundleSha256": hashlib.sha256(Path(args.bundle).read_bytes()).hexdigest()})
    out = Path(args.output)
    out.mkdir(parents=True, exist_ok=False)
    (out / "release-evidence.json").write_text(json.dumps(record, indent=2) + "\n")
    (out / "SHA256SUMS").write_text("".join(f"{item['sha256']}  {item['path']}\n" for item in records))
    print("All 22 artifact signatures verified; inspected bundle is ready for staging")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    check = commands.add_parser("preflight")
    for name in ("expected-sha", "ci-run", "dependency-run", "output"):
        check.add_argument("--" + name, required=True)
    audit = commands.add_parser("evidence")
    for name in ("bundle", "preflight", "fingerprint", "output"):
        audit.add_argument("--" + name, required=True)
    args = parser.parse_args()
    try:
        (preflight if args.command == "preflight" else evidence)(args)
    except (ValueError, OSError, KeyError, ET.ParseError, zipfile.BadZipFile) as error:
        # Exception text from external tools or file parsers may contain sensitive contents.
        print(f"Release check failed: {error}" if isinstance(error, ValueError)
              else "Release check failed: missing, unreadable or malformed evidence", file=__import__("sys").stderr)
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Publish a narrowly allowlisted, provenance-bound example from the CI dogfood job.

The Maven manifest's merged artifact digest is verified. Individual JVM input hashes
are retained as provenance; the private/local raw inputs are not republished or reverified.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
from datetime import datetime
from uuid import UUID

REPOSITORY = "DevelopmentSprint/crypto-census"
MODULE = "com.developmentsprint:census-demo"
FILES = {"index.json", "evidence.json", "manifest.json"}
MAX_BYTES = 32 * 1024 * 1024


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def decode(data):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "Duplicate JSON key")
            result[key] = value
        return result
    return json.loads(data, object_pairs_hook=unique)


def regular_bytes(directory, name):
    file = directory / name
    require(not file.is_symlink() and file.is_file(), f"Missing or nonregular {name}")
    require(file.stat().st_size <= MAX_BYTES, f"Oversized {name}")
    with file.open("rb") as stream:
        data = stream.read(MAX_BYTES + 1)
    require(len(data) <= MAX_BYTES, f"Oversized {name}")
    return data


def directory_files(directory, expected):
    require(not directory.is_symlink() and directory.is_dir(), "Evidence directory must be a regular directory")
    require({file.name for file in directory.iterdir()} == expected, "Unexpected or missing evidence files")


def timestamp(value):
    require(isinstance(value, str) and value.endswith("Z"), "Invalid evidence timestamp")
    datetime.fromisoformat(value.replace("Z", "+00:00"))


def uuid(value):
    require(isinstance(value, str) and str(UUID(value)) == value, "Invalid Maven run identity")


def identity(env, prefix):
    if prefix == "GITHUB":
        values = {"repository": env.get("GITHUB_REPOSITORY"), "sourceSha": env.get("GITHUB_SHA"),
                  "runId": env.get("GITHUB_RUN_ID"), "runAttempt": env.get("GITHUB_RUN_ATTEMPT")}
        require(env.get("GITHUB_EVENT_NAME") == "push" and env.get("GITHUB_REF") == "refs/heads/develop",
                "Publication requires a trusted develop push")
        values["runUrl"] = f"https://github.com/{values['repository']}/actions/runs/{values['runId']}"
    else:
        values = {"repository": env.get("DOCS_REPOSITORY"), "sourceSha": env.get("DOCS_SOURCE_SHA"),
                  "runId": env.get("DOCS_RUN_ID"), "runAttempt": env.get("DOCS_RUN_ATTEMPT"),
                  "runUrl": env.get("DOCS_RUN_URL")}
    require(values["repository"] == REPOSITORY, "Unexpected source repository")
    require(re.fullmatch(r"[0-9a-f]{40}", values["sourceSha"] or ""), "Invalid source SHA")
    for field in ("runId", "runAttempt"):
        require(re.fullmatch(r"[1-9][0-9]*", values[field] or ""), f"Invalid {field}")
    require(values["runUrl"] == f"https://github.com/{REPOSITORY}/actions/runs/{values['runId']}", "Run URL mismatch")
    return values


def publication(evidence_bytes, manifest_bytes, source, expected_run=None):
    evidence, manifest = decode(evidence_bytes), decode(manifest_bytes)
    require(manifest.get("schema") == "census.maven-evidence/1" and manifest.get("revision") == "1",
            "Unsupported Maven evidence schema/revision")
    require(manifest.get("artifact") == "evidence.json", "Manifest artifact must be evidence.json")
    require(manifest.get("sha256") == digest(evidence_bytes), "Evidence digest mismatch")
    descriptor = manifest.get("descriptor", {})
    require(descriptor.get("schema") == "census.maven-run/1" and descriptor.get("module") == MODULE
            and descriptor.get("reports") == ".", "Unexpected Maven workload descriptor")
    require(isinstance(descriptor.get("version"), str) and descriptor["version"], "Missing module version")
    for key in ("runId", "moduleRunId"):
        uuid(descriptor.get(key))
    if expected_run is not None:
        require(descriptor["runId"] == expected_run, "Maven run identity mismatch")
    timestamp(descriptor.get("preparedAt"))
    timestamp(manifest.get("finalizedAt"))
    require(manifest.get("health") == "healthy" and manifest.get("testOutcome") == "passed"
            and manifest.get("policyOutcome") == "passed", "Example requires healthy collection and passing tests/policy")
    require(isinstance(manifest.get("policy"), dict), "Missing collection policy")
    inputs = manifest.get("inputs")
    require(isinstance(inputs, dict) and 0 < len(inputs) <= 10000, "Missing or invalid JVM input references")
    for name, value in inputs.items():
        require(re.fullmatch(r"cbom-[A-Za-z0-9._-]+\.json", name) and
                isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value), "Invalid JVM input reference")
    require(evidence.get("bomFormat") == "CycloneDX" and evidence.get("specVersion") == "1.6", "Expected CycloneDX 1.6 CBOM")
    require(isinstance(evidence.get("components"), list) and
            any(c.get("type") == "cryptographic-asset" for c in evidence["components"]), "Empty cryptographic inventory")
    metadata = evidence.get("metadata", {})
    require(metadata.get("component", {}).get("name") == "census-demo", "Unexpected CBOM application")
    projects = [p.get("value") for p in metadata.get("properties", []) if p.get("name") == "cryptocensus:project"]
    require(projects == [f"{MODULE}:{descriptor['version']}"], "CBOM project identity mismatch")
    return {"schemaVersion": 1, "status": "published", **source, "sourceBranch": "develop",
            "generatedAt": manifest["finalizedAt"],
            "workload": {"module": MODULE, "version": descriptor["version"], "name": "Payments demo workload",
                         "scope": "Runtime cryptographic operations exercised by the census-demo tests; not a complete repository SBOM."},
            "collection": {"runId": descriptor["runId"], "moduleRunId": descriptor["moduleRunId"],
                           "revision": "1", "testOutcome": manifest["testOutcome"], "health": manifest["health"],
                           "policyOutcome": manifest["policyOutcome"]},
            "downloads": {"evidence": {"path": "evidence.json", "sha256": digest(evidence_bytes)},
                          "manifest": {"path": "manifest.json", "sha256": digest(manifest_bytes)}}}


def write_output(output, data):
    # Never follow a generated-assets symlink or delete unknown local files.
    for ancestor in [output, *output.parents]:
        require(not ancestor.is_symlink(), "Output directory must not contain symlinks")
    output.mkdir(parents=True, exist_ok=True)
    require({p.name for p in output.iterdir()} <= FILES, "Unexpected files in generated CBOM directory")
    for file in output.iterdir():
        require(file.is_file() and not file.is_symlink(), "Nonregular generated CBOM file")
    for file in output.iterdir():
        file.unlink()
    for name, content in data.items():
        (output / name).write_bytes(content)


def encode(value):
    return (json.dumps(value, indent=2) + "\n").encode()


def package(input_dir, output, env):
    source = identity(env, "GITHUB")
    require(env.get("CENSUS_RUN_ID"), "Expected Maven run identity is required")
    directory_files(input_dir, {"evidence.json", "manifest.json"})
    evidence, manifest = [regular_bytes(input_dir, name) for name in ("evidence.json", "manifest.json")]
    index = publication(evidence, manifest, source, env["CENSUS_RUN_ID"])
    write_output(output, {"evidence.json": evidence, "manifest.json": manifest, "index.json": encode(index)})


def prepare(output, env):
    input_name = env.get("DOCS_CBOM_DIR")
    if not input_name:
        require(env.get("DOCS_REQUIRE_CBOM") != "true", "Trusted CI example CBOM artifact is required for deployment")
        write_output(output, {"index.json": encode({"schemaVersion": 1, "status": "unavailable",
                     "message": "Published CI evidence is unavailable in this local or pull-request preview."})})
        return
    source = identity(env, "DOCS")
    input_dir = Path(input_name)
    directory_files(input_dir, FILES)
    data = {name: regular_bytes(input_dir, name) for name in FILES}
    index = decode(data["index.json"])
    producer_attempt = index.get("runAttempt")
    require(isinstance(producer_attempt, str) and re.fullmatch(r"[1-9][0-9]*", producer_attempt),
            "Invalid producing CI run attempt")
    require(int(producer_attempt) <= int(source["runAttempt"]), "CBOM producer attempt is newer than the completed workflow")
    # Partial reruns retain a successful dogfood artifact from an earlier attempt
    # of this exact run and SHA. Keep its actual producing attempt in public provenance.
    source["runAttempt"] = producer_attempt
    expected = publication(data["evidence.json"], data["manifest.json"], source)
    require(index == expected, "CBOM publication provenance, digest or path mismatch")
    write_output(output, data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    pack = sub.add_parser("package")
    pack.add_argument("--input", type=Path, required=True)
    pack.add_argument("--output", type=Path, required=True)
    sub.add_parser("prepare")
    args = parser.parse_args()
    if args.command == "package":
        package(args.input, args.output, os.environ)
    else:
        prepare(Path("docs/public/example-cbom"), os.environ)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, TypeError, AttributeError, KeyError) as error:
        sys.exit(f"Example CBOM publication rejected: {error}")

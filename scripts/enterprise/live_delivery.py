#!/usr/bin/env python3
"""Opt-in packaged CLI/Maven delivery against an operator-provided real vendor profile.

No fixtures, tenant provisioning, secret collection, upload retries, or remote cleanup.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import sys
from datetime import datetime, timezone

ADAPTERS = {"artifactory", "anchore-enterprise", "sonatype-sbom-manager"}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_private(path: Path, value: dict) -> None:
    with path.open("x", encoding="utf-8") as stream:
        os.chmod(path, 0o600)
        json.dump(value, stream, indent=2, sort_keys=True)
        stream.write("\n")


def read_plan_digest(plan: Path) -> str:
    envelope = json.loads(plan.read_text())
    digest = envelope.get("planSha256")
    if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest) or not isinstance(envelope.get("plan"), dict):
        raise ValueError("Malformed sealed plan envelope")
    return digest


def profile_path(profile: Path, value: str) -> Path:
    return (profile.parent / value).resolve()


def compare_export(source: dict, exported: dict) -> dict:
    """Compare explicit CBOM structures without claiming an equivalent vendor inventory."""
    def projection(document: dict) -> dict:
        components = []
        parents = {}
        def visit(items, parent=None):
            if not isinstance(items, list) or any(not isinstance(c, dict) for c in items):
                raise ValueError("Invalid components in native export")
            for component in items:
                ref = component.get("bom-ref")
                if not isinstance(ref, str) or not ref or ref in parents:
                    raise ValueError("Native comparison requires unique, nonempty component bom-ref values")
                parents[ref] = parent
                components.append(component)
                visit(component.get("components", []), ref)
        visit(document.get("components", []))
        return {
            "componentIdentityAndType": {c["bom-ref"]: c.get("type") for c in components},
            "cryptoProperties": {c["bom-ref"]: c.get("cryptoProperties") for c in components},
            "componentProperties": {c["bom-ref"]: c.get("properties") for c in components},
            "occurrencesAndEvidence": {c["bom-ref"]: c.get("evidence") for c in components},
            # Full per-component comparison includes names, versions, hashes, purls and
            # extensions. Preserve parent links while ignoring only component ordering.
            "completeComponents": {c["bom-ref"]: {k: v for k, v in c.items() if k != "components"} for c in components},
            "componentHierarchy": parents,
            "metadata": document.get("metadata"),
            "signature": document.get("signature"),
            "dependencyGraph": document.get("dependencies"),
            "externalReferences": document.get("externalReferences"),
            "otherDocumentFields": {k: v for k, v in document.items() if k not in
                                    {"components", "metadata", "signature", "dependencies", "externalReferences"}},
        }
    original, remote = projection(source), projection(exported)
    result = {}
    for name, value in original.items():
        # None and absent sections cannot demonstrate preservation.
        present = value is not None and value != [] and value != {} and (not isinstance(value, dict) or any(v is not None for v in value.values()))
        if name == "componentHierarchy":
            present = bool(value)
        result[name] = "MATCH" if present and value == remote[name] else "DIFFERENT" if present else "NOT_EXERCISED"
    return {"scope": "OPERATOR_SUPPLIED_NATIVE_EXPORT_STRUCTURAL_COMPARISON", "fields": result,
            "interpretation": "MATCH means equal exported structure, not UI queryability, signature validity, or durable retention; DIFFERENT requires field-level review."}


def inspect_receipt(receipt: dict, adapter: str, artifact_digest: str, plan_digest: str) -> None:
    if not isinstance(receipt, dict):
        raise ValueError("Invalid receipt document")
    if (receipt.get("schema") != "census.delivery-receipt/2" or receipt.get("adapter") != adapter
            or receipt.get("artifactSha256") != artifact_digest or receipt.get("planSha256") != plan_digest
            or receipt.get("gatePassed") is not True or receipt.get("upload") != "ACCEPTED"
            or not isinstance(receipt.get("reasonCode"), str)
            or not re.fullmatch(r"[A-Z0-9_]{1,96}", receipt["reasonCode"])):
        raise ValueError("Delivery receipt failed identity or completion checks")
    if adapter == "artifactory" and receipt.get("ingestion") != "NOT_APPLICABLE":
        raise ValueError("Artifact storage cannot claim inventory ingestion")
    if adapter in {"artifactory", "sonatype-sbom-manager"}:
        proof = receipt.get("destinationReadback", {})
        if not isinstance(proof, dict) or proof.get("observation") != "RETRIEVED_EXACT_BYTES" or proof.get("artifactSha256") != artifact_digest:
            raise ValueError("Destination exact-byte readback missing or mismatched")
    if adapter != "artifactory":
        proof = receipt.get("originalRetention", {})
        if (not isinstance(proof, dict) or receipt.get("ingestion") != "COMPLETED" or proof.get("remote") != "RETRIEVED_EXACT_BYTES"
                or proof.get("artifactSha256") != artifact_digest):
            raise ValueError("Ingestion or independent original readback missing")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", required=True, type=Path)
    parser.add_argument("--entry-point", required=True, choices=("cli", "maven"))
    parser.add_argument("--product-version", required=True, help="Exact installed version, verified independently by operator")
    parser.add_argument("--output", required=True, type=Path, help="New private evidence directory; must not exist")
    parser.add_argument("--allow-publish", action="store_true", help="Submit one vendor mutation after successful preflight")
    parser.add_argument("--cli-jar", type=Path)
    parser.add_argument("--maven", type=Path)
    parser.add_argument("--maven-repository", type=Path)
    parser.add_argument("--plugin-version", default="0.1.0")
    args = parser.parse_args()
    os.umask(0o077)
    root = Path(__file__).resolve().parents[2]
    profile = args.profile.resolve(strict=True)
    config = json.loads(profile.read_text())
    adapter = config.get("adapter")
    if adapter not in ADAPTERS:
        parser.error("Profile must select one of the three enterprise vendor adapters")
    if config.get("preparation", {}).get("mode") != "IDENTITY" or len(config.get("inputs", [])) != 1:
        parser.error("Live smoke requires one IDENTITY input; validate transformed profiles separately")
    artifact = profile_path(profile, config["inputs"][0]["path"])
    digest = sha256(artifact)
    if digest != config["inputs"][0]["sha256"]:
        parser.error("Input digest does not match the trusted profile")
    plan = profile_path(profile, config["outputs"]["plan"])
    receipt = profile_path(profile, config["outputs"]["receipt"])
    if plan.exists() or receipt.exists():
        parser.error("Plan/receipt already exists; reconcile earlier operations before choosing fresh paths")
    output = args.output.resolve()
    output.mkdir(mode=0o700, parents=True, exist_ok=False)
    if args.entry_point == "cli":
        jar = (args.cli_jar or root / "census-cli/target/census-cli-0.1.0.jar").resolve(strict=True)
        java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
        prefix = [java, "-jar", str(jar), "delivery", "--profile", str(profile)]
        binary = {"cliJarSha256": sha256(jar)}
    else:
        pom = output / "pom.xml"
        pom.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>example.live</groupId><artifactId>vendor-validation</artifactId><version>1</version></project>\n')
        prefix = [str((args.maven or root / "mvnw").resolve(strict=True)), "-B", "-ntp", "-f", str(pom)]
        if args.maven_repository:
            prefix += ["-Dmaven.repo.local=" + str(args.maven_repository.resolve())]
        prefix += ["com.developmentsprint:census-maven-plugin:" + args.plugin_version + ":delivery", "-Dcensus.delivery.profile=" + str(profile)]
        binary = {"pluginCoordinates": "com.developmentsprint:census-maven-plugin:" + args.plugin_version}
        if args.maven_repository:
            plugin_jar = args.maven_repository / "com/developmentsprint/census-maven-plugin" / args.plugin_version / ("census-maven-plugin-" + args.plugin_version + ".jar")
            if plugin_jar.is_file():
                binary["locallyInstalledPluginJarSha256"] = sha256(plugin_jar)
    evidence = {"schema": "census.live-delivery-evidence/1", "adapter": adapter,
                "productVersion": args.product_version, "productVersionSource": "OPERATOR_VERIFIED",
                "entryPoint": args.entry_point, "binary": binary, "artifactSha256": digest,
                "profileSha256": sha256(profile), "startedAt": datetime.now(timezone.utc).isoformat(),
                "steps": [], "publishRequested": args.allow_publish, "result": "INCOMPLETE",
                "nativeSemantics": "UNVERIFIED", "retentionDuration": "UNVERIFIED"}
    try:
        plan_digest = None
        for action in ["plan", "preflight"] + (["publish"] if args.allow_publish else []):
            command = prefix + (["--action", action] if args.entry_point == "cli" else ["-Dcensus.delivery.action=" + action])
            if plan_digest:
                command += ["--plan-sha256", plan_digest] if args.entry_point == "cli" else ["-Dcensus.delivery.planSha256=" + plan_digest]
            with (output / (action + ".log")).open("xb") as log:
                result = subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT, timeout=180)
            evidence["steps"].append({"action": action, "exitCode": result.returncode})
            if result.returncode:
                raise ValueError("Delivery step failed; inspect private logs/receipt and reconcile remote state before retrying")
            if action == "plan":
                plan_digest = read_plan_digest(plan)
                evidence["planFileSha256"] = sha256(plan)
                evidence["planSha256"] = plan_digest
        if args.allow_publish:
            observed = json.loads(receipt.read_text())
            inspect_receipt(observed, adapter, digest, plan_digest)
            evidence["receiptSha256"] = sha256(receipt)
            evidence["destinationReadback"] = observed.get("destinationReadback", {"observation": "UNVERIFIED"})
            evidence["originalRetention"] = observed.get("originalRetention", {"remote": "UNVERIFIED"})
            evidence["upload"] = observed["upload"]
            evidence["ingestion"] = observed["ingestion"]
            evidence["reasonCode"] = observed["reasonCode"]
            evidence["result"] = "DELIVERY_CONTRACT_PASSED"
        else:
            evidence["result"] = "PREFLIGHT_ONLY_NO_MUTATION"
        if sha256(artifact) != digest:
            raise ValueError("Original source changed during validation")
    except (ValueError, OSError, subprocess.TimeoutExpired) as error:
        evidence["result"] = "FAILED_OR_UNCONFIRMED"
        # Never persist arbitrary exception strings, endpoints, command output, or credentials.
        evidence["failureType"] = type(error).__name__
        print("Live validation failed or remains unconfirmed; inspect the private output and receipt before any retry.", file=sys.stderr)
        return 1
    finally:
        evidence["finishedAt"] = datetime.now(timezone.utc).isoformat()
        write_private(output / "evidence.json", evidence)
    print("Live validation result: " + evidence["result"])
    print("Evidence: " + str(output / "evidence.json"))
    return 0


if __name__ == "__main__":
    sys.exit(main())

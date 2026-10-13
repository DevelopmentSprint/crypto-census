# Validate delivery against a real vendor

Contract fixtures exercise Census behavior against a documented API. They do not prove compatibility with an installed vendor version. Use the opt-in runner below against a disposable application/version or repository prefix before adopting an experimental adapter.

## Access requirements

| Destination | Available test path | Required access |
| --- | --- | --- |
| Artifactory | A self-managed OSS installation supports local Generic repositories; use a disposable instance or approved repository prefix. | A non-admin bearer token with Read and Deploy/Cache, without Delete/Overwrite or inherited administrative rights. |
| Anchore Enterprise | An existing licensed Enterprise 6.2 installation exposing API 2.19.0. | Account-scoped application-read and asset-create credentials, a dedicated single-writer application version, and independent HTTPS original retention. |
| Sonatype SBOM Manager | An existing licensed cloud tenant or self-hosted installation with PostgreSQL. | An application-scoped token/password, an unused version, a single writer, and independent HTTPS original retention. |

JFrog lists Generic support for its non-commercial editions in its [feature matrix](https://docs.jfrog.com/installation/docs/feature-comparison-matrix-for-self-mangaged-jpds) and publishes [official Docker installation instructions](https://docs.jfrog.com/installation/docs/docker). The OSS 7.161.15 trial reached a [Pro-only provisioning API gate](ARTIFACTORY.md) before upload. An already provisioned supported repository/writer is needed. Artifactory delivery tests artifact storage; it does not provide Xray or native crypto inventory ingestion.

Anchore's [deployment prerequisites](https://docs.anchore.com/current/docs/deployment/docker_compose/) require an Enterprise license and access to its images. Sonatype's [getting started guide](https://help.sonatype.com/en/sbom-getting-started.html) describes licensed tenant provisioning and the PostgreSQL requirement. Neither Syft/Grype nor Nexus Repository is a substitute for these enterprise APIs. A public container image alone does not establish product entitlement.

No licensed Anchore or Sonatype tenant was available for the 2026-10-13 validation work. Their live compatibility and native crypto fidelity remain unverified. These are access gates, not passing test results. Provide credentials through a local secret store or CI environment, never an issue, profile literal, or public test artifact.

## Run the packaged entry points

Build and install the current candidate using the [source-build instructions](INSTALLATION.md). The runner requires Python 3.10+, a supported Java installation, and a built CLI JAR or locally installed Maven plugin. It does not build, provision tenants, retry uploads, or delete remote evidence.

1. Generate a representative Census CBOM with the Maven scan. Keep its raw evidence and trusted digest. Include multiple algorithms, operation identities, occurrences and Census health metadata; record whether signatures and a software dependency graph are actually present.
2. Save a complete [delivery profile](DELIVERY-PROFILES.md) using `IDENTITY` preparation and one input. Follow the destination's [Artifactory](ARTIFACTORY.md), [Anchore](ANCHORE.md), or [Sonatype](SONATYPE-SBOM-MANAGER.md) guide for its exact mapping, credentials and accepted loss codes.
3. Use fresh output paths and a new remote identity for each entry-point run. For Sonatype, that means a new application version; for Anchore, a new asset name. Artifactory's correlation run ID contributes to its object path. Do not repeat an ambiguous submission under a different name without reconciliation.
4. Verify and record the actual server version from the product API/UI. `--product-version` records your assertion; the runner does not independently discover it. Anchore additionally enforces its configured version through the adapter.
5. Run preflight first. Without `--allow-publish`, the runner performs only planning and read checks:

```bash
python3 scripts/enterprise/live_delivery.py \
  --entry-point cli --profile /secure/validation/cli-profile.json \
  --product-version YOUR_VERIFIED_SERVER_VERSION \
  --output /secure/validation/cli-preflight
```

Planning creates immutable outputs. After reviewing a preflight-only run, prepare fresh profile output paths for the publication run; keep its remote identity unchanged if nothing was uploaded.

To authorize one upload following a successful plan and preflight:

```bash
python3 scripts/enterprise/live_delivery.py \
  --entry-point cli --profile /secure/validation/cli-publish-profile.json \
  --product-version YOUR_VERIFIED_SERVER_VERSION \
  --output /secure/validation/cli-publication --allow-publish

python3 scripts/enterprise/live_delivery.py \
  --entry-point maven --profile /secure/validation/maven-profile.json \
  --product-version YOUR_VERIFIED_SERVER_VERSION \
  --output /secure/validation/maven-publication --allow-publish
```

Use different remote identities for those two publications. Set `JAVA_HOME` when needed. Optional `--cli-jar`, `--maven`, `--maven-repository`, and `--plugin-version` arguments select the installed tools. Maven runs the delivery goal in an isolated minimal project, so these checks do not repeat the application scan or run a goal across every reactor module. Use the normal Maven integration tests separately to validate scan/finalization binding.

The runner preserves private logs and writes `evidence.json` with the source digest, profile and plan digests, observed outcome, receipt digest, entry point and supplied product version. It rejects mismatched receipts, incomplete ingestion, and missing required exact-byte proofs. A failed command stops the sequence. Even a timeout or failed receipt can follow a successful remote write: inspect the vendor and existing receipts before retrying. Retain the original profile, prepared bundle, plan and receipt as well as this summary.

Review every evidence file before publishing it. Files can contain internal paths, mapping identifiers or proprietary crypto observations. The runner omits subprocess output and exception text from its summary; detailed logs remain local and private. POSIX files/directories are owner-only; on Windows configure equivalent ACLs.

## Measure native fidelity separately

A source file retrieved from an original-download endpoint proves byte retention. It cannot demonstrate that the vendor's inventory model parsed those fields. After ingestion, capture the vendor-generated CycloneDX export or documented native inventory response for the exact application/version/asset. Record the endpoint/API version and remote identifier privately, along with UI observations where relevant.

For a CycloneDX JSON native export, run the offline structural comparison:

```bash
python3 scripts/enterprise/compare_export.py \
  --original /secure/validation/cbom.json \
  --native-export /secure/validation/vendor-generated.json \
  --output /secure/validation/native-comparison.json
```

Do not pass the original-download file as a native export. This utility trusts that the operator captured the correct export; it cannot authenticate its origin. It recursively compares complete component structures and parent links, including names, versions, package URLs, hashes and extension fields, along with crypto properties, evidence/occurrences, metadata, signatures, dependency graph, external references and other document fields. Component ordering is ignored; other representation changes are conservatively reported as `DIFFERENT` for manual review. Missing source fields are `NOT_EXERCISED`, never credited as preserved. Duplicate or absent component references stop the comparison.

For non-CycloneDX APIs, make a field-by-field inventory assessment against the original and record explicit mappings. Preserve exact hashes of all captured files. A `MATCH` means equal exported structure only: it does not prove searchable UI semantics, signature validity, inventory mutation behavior, or a retention duration.

Keep these conclusions separate:

| Observation | Required evidence |
| --- | --- |
| Transport compatibility | Exact installed version and successful packaged CLI/Maven runs. |
| Original-byte retention | Independent and/or destination readback SHA-256 matching the prepared publication. |
| Native semantic fidelity | Vendor-generated export/native API and field-level comparison for exercised fields. |
| Inventory isolation | Before/after inventory capture proving the intended scope and absence of replacement of the software inventory. |
| Durability | Independently reviewed retention, backup, deletion and access policies; one readback is insufficient. |

## Cleanup and regression checks

Stop and remove only task-owned containers, networks and volumes after preserving sanitized evidence. Delete disposable vendor objects only with the appropriate administrator's authorization. Revoke temporary validation tokens. Do not stop an existing shared Dependency-Track or other development stack.

The runner's local evidence checks have a small offline regression suite:

```bash
python3 -m unittest discover -s scripts/enterprise -p 'test_*.py' -v
```

These tests protect the interpretation of evidence. They do not count as live vendor validation.

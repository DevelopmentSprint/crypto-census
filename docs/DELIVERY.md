# Deliver an existing CBOM over HTTPS

For typed preparation, sealed plans and guarded vendor delivery, see [delivery profiles](DELIVERY-PROFILES.md). Additional destination guides cover [Sonatype SBOM Manager](SONATYPE-SBOM-MANAGER.md), [Artifactory evidence storage](ARTIFACTORY.md), and [Anchore Enterprise](ANCHORE.md). These adapters are experimental and tested against local HTTPS fixtures derived from vendor documentation; no live commercial tenant validation is claimed.

Use the Maven `publish` goal or standalone CLI to send an existing Crypto Census CBOM to a company-operated HTTPS receiver. Both use the same delivery engine and preserve the original file. Publication is explicit and never runs inside the observed application.

The `publish` command supports a generic HTTPS receiver. Use a [delivery profile](DELIVERY-PROFILES.md) for the guarded [Dependency-Track 5.2 integration](DEPENDENCY-TRACK.md). That adapter requires a dedicated crypto-only project and explicit acceptance of measured fidelity losses: Dependency-Track drops cryptographic fields and operation identities, and an upload replaces the target inventory. Preserve the original CBOM independently; this integration does not provide full-fidelity cryptographic ingestion or replace a general software inventory.

`HTTP_ACCEPTED` means the endpoint returned HTTP 2xx. It does not mean the receiver indexed, analyzed, or retained every cryptographic field. Configure a receiver whose behavior your security team controls.

## Build the publisher

From the source checkout, install the modules using Java 17 or later:

```sh
./mvnw -B -ntp -pl census-cli,census-maven-plugin -am install
java -jar census-cli/target/census-cli-0.1.0.jar --help
```

This feature is source-only until its release is published. The standalone JAR includes its runtime dependencies; it does not include or launch the agent.

## Maven

Generate the report using the [existing Maven setup](MAVEN.md). For an explicitly retained artifact, capture the expected SHA-256 when the producing step finishes and retain the source run identifier alongside it. This unbound mode compares the exact input bytes with that digest; it does not establish freshness or discover the newest report.

For current CI evidence, use [immutable run finalization](CI-FINALIZATION.md): pass the exact `census.manifest` and expected `census.runId` to `publish`. It verifies the run/module/version, selected raw input digests and sealed artifact, then obtains the artifact/digest/run mapping from the manifest. Conflicting explicit input, digest or run options fail. The distinct `delivery` goal supports the same manifest binding for every profile action, including Dependency-Track.

The following example publishes the demo report after it has been produced. On Linux use `sha256sum`; on macOS use `shasum -a 256`. Obtain `SOURCE_RUN_ID` from the producing CI job or your recorded local run. Supply `CENSUS_UPLOAD_TOKEN` using the CI secret store or your shell's protected environment, not a command argument.

```sh
CBOM_SHA256=$(shasum -a 256 census-demo/target/cryptocensus/cbom.json | cut -d ' ' -f 1)
./mvnw -B -ntp -pl census-demo com.developmentsprint:census-maven-plugin:0.1.0:publish \
  -Dcensus.delivery.sha256="$CBOM_SHA256" \
  -Dcensus.delivery.runId="$SOURCE_RUN_ID" \
  -Dcensus.delivery.application=payments \
  -Dcensus.delivery.destinationId=company-inventory \
  -Dcensus.delivery.endpoint=https://inventory.example/ingest \
  -Dcensus.delivery.tokenEnv=CENSUS_UPLOAD_TOKEN
```

The default input is each module's `target/cryptocensus/cbom.json`. Module identity defaults to `groupId:artifactId`; version defaults to the Maven project version. Receipts get distinct UUID filenames under `target/cryptocensus/delivery/`. Application and destination ID must be supplied. Expected digest and source run ID are required in unbound mode; manifest-bound publication derives them from the verified selected revision.

For encrypted Maven credentials, use `census.delivery.serverId` instead of `tokenEnv`. The selected `<server>`'s decrypted password is the bearer token; its username is unused. Missing servers, decryption problems, and empty credentials fail before delivery. Never place the token itself in a POM or `-D` parameter. To send anonymously, explicitly set `census.delivery.auth=none` and omit credential references.

`publish` has no default lifecycle phase and does not run tests, merge reports, or evaluate policy. A recorded failed delivery fails this goal by default. `-Dcensus.delivery.failOnError=false` records the unsuccessful outcome without failing this goal; invalid evidence/configuration and receipt-write failures still fail.

## Standalone CLI

Use the standalone command for a server report after graceful shutdown, a non-Maven CI pipeline, or intentional resubmission of retained evidence:

```sh
java -jar census-cli/target/census-cli-0.1.0.jar publish \
  --input cbom.json --sha256 "$CBOM_SHA256" --receipt delivery.json \
  --endpoint https://inventory.example/ingest --destination-id company-inventory \
  --application payments --module example:payments --version 1.0 \
  --run-id "$SOURCE_RUN_ID" --token-env CENSUS_UPLOAD_TOKEN
```

The CLI requires an explicit receipt filename and all identity fields. `--auth none` selects anonymous delivery. `--report-only` records a failed delivery without returning its failure exit code; configuration/evidence/receipt errors remain nonzero.

| Exit | Meaning |
|---|---|
| `0` | HTTP acknowledged, or reporting-only mode recorded the outcome. |
| `2` | Invalid configuration or evidence; read the safe diagnostic code. |
| `3` | HTTP rejection. |
| `4` | Delivery failed or remote acceptance is unknown. |
| `5` | Receipt persistence failed; output states whether HTTP acceptance was observed. |

## Receiver contract and transport

The client POSTs the unchanged CBOM as `application/vnd.cyclonedx+json`. `X-Census-Artifact-Sha256`, `X-Census-Delivery-Id`, `X-Census-Application`, `X-Census-Module`, `X-Census-Version`, and `X-Census-Run-Id` identify the submission. Mapping fields require 1–256 printable ASCII characters without whitespace. Destination IDs permit letters, digits, dot, underscore and hyphen, up to 128 characters.

HTTPS certificate and hostname validation always remain enabled. Credentials in URLs, query parameters, fragments and redirects are rejected. The legacy `publish` command makes at most one network attempt, with 10-second connect, 30-second request and 60-second overall limits. Response bodies are bounded to 64 KiB and are not printed or copied into receipts. Arbitrary non-JSON success bodies are allowed. A 5xx or lost response can mean the receiver accepted before failing: reconcile the receipt with the receiver before intentionally trying again. This command has no automatic retry or exactly-once guarantee. A generic delivery profile can explicitly enable [bounded retries](DELIVERY-OPERATIONS.md#receiver-retries) when the receiver operator asserts the required deduplication contract. Dependency-Track uploads always make one attempt.

Use a custom PEM CA bundle with `census.delivery.caBundle` or `--ca-bundle`. It augments JVM trust only for the delivery client. Explicit HTTP CONNECT proxies use `census.delivery.proxyHost` / `proxyPort`, or `--proxy-host` / `--proxy-port`; the default port is 8080. [Authenticated proxy configuration](DELIVERY-OPERATIONS.md#authenticated-connect-proxies) uses separate environment references or a Maven settings server for proxy credentials. Origin and proxy credentials remain separate. The client does not inherit ambient proxy settings or bypass a failed proxy. Client certificates remain unsupported. Vendor authentication belongs to the selected profile adapter.

Receipts contain artifact and endpoint hashes, declared identity, outcome, safe reason code and transport bounds. They exclude the endpoint URL, token and raw response. A pending receipt is written before networking so a crash does not leave a previous success looking current. This is local operational evidence, not an attestation. Receipts and CBOMs remain sensitive: use private directories, protected CI artifacts and explicit retention. The publisher rejects an output that aliases the input.

## Failing builds and provenance

The existing `check` writes its merged report before failing a crypto policy, so a separate always-run CI step can publish that usable report while the original step remains failed. Preserve the producing step's exit status. Compute and retain its digest only when that step actually produced the selected artifact; starting from a clean output directory and a unique run identifier helps avoid accidental stale selection.

Surefire failure can stop Maven before `check`, leaving individual JVM reports. With a fresh explicit `census.runId` supplied during preparation, an always-run `report` invocation can finalize that exact run into an immutable manifest and merged CBOM. Run delivery and `enforce` in separate always-run steps so an upload failure cannot suppress policy enforcement and a successful upload cannot erase the original build failure. Follow the [CI finalization guide](CI-FINALIZATION.md) for exact revision selection, manifest-bound `publish` and profile `delivery` commands, and retention of raw evidence. Never select an older merged report to make delivery pass. Workflow cancellation or a killed JVM can prevent output entirely.

Do not expose upload credentials to untrusted pull-request code. If a separate trusted delivery job consumes an artifact, verify its producing workflow/run and artifact identity before providing credentials. A caller-supplied SHA-256 detects changed bytes relative to that supplied digest; it does not prove the original producer or collection time.

## Maven parameter reference

| Parameter (`census.delivery.` prefix) | Default / purpose |
|---|---|
| `input` | `${project.build.directory}/cryptocensus/cbom.json` in unbound mode; with a manifest, any explicit path must identify the selected sealed artifact. |
| `sha256` | Required in unbound mode: expected digest, 64 hexadecimal characters. With a manifest, any explicit value must match the seal. |
| `endpoint` | Required operator-selected HTTPS URL. |
| `destinationId` | Required nonsecret receiver ID. |
| `application` | Required application mapping. |
| `module` | `${project.groupId}:${project.artifactId}`. |
| `version` | `${project.version}`. |
| `runId` | Required in unbound mode: caller-supplied source run identity. With a manifest, any explicit value must match `census.runId`. |
| `receipt` | Optional explicit receipt filename. |
| `receiptDirectory` | `${project.build.directory}/cryptocensus/delivery`; UUID filename if `receipt` absent. |
| `auth` | `bearer`; `none` explicitly disables authentication. |
| `tokenEnv` | Environment variable name containing bearer token; mutually exclusive with `serverId`. |
| `serverId` | Maven settings server; decrypted password is bearer token. |
| `caBundle` | Optional PEM roots added to JVM trust for this client. |
| `proxyHost` | Optional explicit CONNECT proxy host; required with proxy credentials. |
| `proxyPort` | `8080`. |
| `proxyUserEnv` / `proxyPasswordEnv` | Both environment variable names for Basic proxy credentials; mutually exclusive with `proxyServerId`. |
| `proxyServerId` | Maven settings server whose username and decrypted password authenticate to the proxy. |
| `failOnError` | `true`; false changes only recorded unsuccessful delivery gating. |

Manifest controls use the **`census.` prefix**, not `census.delivery.`: `census.manifest` names the exact `run-.../revisions/N/manifest.json`, and `census.runId` supplies the expected producing UUID. They apply to Maven `publish` and profile `delivery`; see [CI finalization](CI-FINALIZATION.md). Explicit artifact/digest CLI publication retains caller-supplied provenance.

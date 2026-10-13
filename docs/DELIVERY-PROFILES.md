# Deliver evidence with a profile

Delivery profiles use the same preparation, validation and transport engine from the standalone CLI and Maven. The existing [`publish` command and goal](DELIVERY.md) remain compatible.

Census produces cryptographic evidence. Your existing software SBOM generator remains responsible for software components, dependency graphs and licenses. Profiles can retain these documents separately, link them, or explicitly prepare a derived combined CycloneDX document. Read [SBOM preparation](SBOM-PREPARATION.md) for supported inputs and transformation limits.

## Start with exact-byte HTTPS delivery

Save this JSON as `delivery.json` beside a Census CBOM named `cbom.json`. Replace the endpoint, correlation values and SHA-256 placeholder with values from your build. The expected digest must come from the producer or a trusted CI artifact record.

```json
{
  "schema": "census.delivery-profile/1",
  "adapter": "generic-https",
  "destinationId": "company-evidence",
  "endpoint": "https://evidence.example.com/census",
  "preparation": {
    "mode": "IDENTITY",
    "outputDirectory": "prepared-build-123"
  },
  "inputs": [{
    "path": "cbom.json",
    "sha256": "REPLACE_WITH_64_LOWERCASE_HEX_CHARACTERS",
    "kind": "CENSUS_RAW_CBOM",
    "correlation": {
      "application": "payments",
      "module": "com.example:payments",
      "version": "1.0.0",
      "runId": "build-123"
    }
  }],
  "options": {},
  "authentication": {"type": "ENV", "name": "EVIDENCE_TOKEN"},
  "policy": {
    "capabilityRevision": "generic-https/1",
    "acceptedLossCodes": [],
    "completion": "UPLOAD_ACCEPTED"
  },
  "outputs": {"plan": "plan-build-123.json", "receipt": "receipt-build-123.json"}
}
```

Relative paths resolve against the profile file. Input mapping values are explicit bounded ASCII identifiers; every source in a linked or combined preparation must use the same application, module, version and run ID. Use a separate profile per module unless you deliberately prepared an aggregate. Correlation labels are caller assertions, not authenticated build provenance or proof that a software component owns a crypto observation.

Run the offline planning step first:

```bash
java -jar census-cli/target/census-cli-0.1.0.jar delivery \
  --action plan --profile delivery.json
```

Planning validates input kinds, digests, preparation, destination capabilities and loss policy. It creates immutable prepared artifacts, a transformation manifest and a sanitized plan. It does not resolve secrets or contact any destination. Preserve its printed `Delivery plan SHA-256` in the CI job's trusted state.

Set `EVIDENCE_TOKEN` through your CI secret store, then use that exact plan digest:

```bash
java -jar census-cli/target/census-cli-0.1.0.jar delivery \
  --action preflight --profile delivery.json --plan-sha256 "$PLAN_SHA256"

java -jar census-cli/target/census-cli-0.1.0.jar delivery \
  --action publish --profile delivery.json --plan-sha256 "$PLAN_SHA256"
```

`preflight` runs configured evidence readback and vendor read-only checks, stopping before upload. For the generic adapter without readback, it makes no HTTP request. It cannot prove that a later upload will succeed or reserve a project against concurrent writers. `publish` rechecks the sealed profile, sources, prepared artifacts and capabilities before execution. Configured custom CA bytes are also digest-bound, and execution constructs trust from those exact verified bytes. Changing any of those requires a new plan. Use new preparation and plan output paths for every run, including replanning after a preparation or policy error; Census does not delete an existing preparation directory.

The generic adapter sends the publication artifact as one POST with content type `application/vnd.cyclonedx+json`. A complete bounded 2xx response proves HTTP acceptance only. It does not prove inventory ingestion, semantic preservation or durable storage. The receiver contract must accept an artifact at the configured endpoint.

## Maven uses the same profile

After installing or resolving the plugin version, use:

```bash
./mvnw com.developmentsprint:census-maven-plugin:0.1.0:delivery \
  -Dcensus.delivery.action=plan -Dcensus.delivery.profile=delivery.json

./mvnw com.developmentsprint:census-maven-plugin:0.1.0:delivery \
  -Dcensus.delivery.action=preflight -Dcensus.delivery.profile=delivery.json \
  -Dcensus.delivery.planSha256="$PLAN_SHA256"

./mvnw com.developmentsprint:census-maven-plugin:0.1.0:delivery \
  -Dcensus.delivery.action=publish -Dcensus.delivery.profile=delivery.json \
  -Dcensus.delivery.planSha256="$PLAN_SHA256"
```

Maven also accepts `{"type":"MAVEN_SERVER","name":"company-evidence"}` as the authentication reference. For bearer/API-key adapters, the plugin decrypts that server's password using Maven settings; the username is unused. CLI execution accepts environment references or `{"type":"NONE"}`. Both tools can plan without resolving credentials.

Adapters that require HTTP Basic authentication accept paired secret references:

```json
"authentication": {
  "type": "ENV_BASIC",
  "usernameName": "SBOM_USERNAME",
  "name": "SBOM_PASSWORD"
}
```

Both names refer to environment variables, never literal credential values. Maven can instead use `{"type":"MAVEN_SERVER_BASIC","name":"sbom-manager"}` to resolve the selected server's decrypted username and password. The adapter selects the authentication scheme; the profile cannot send a Basic pair to a bearer endpoint accidentally. Pairs are resolved only during execution, encoded by the caller, and sent only through the shared HTTPS transport. Usernames must be nonempty printable ASCII without spaces or colons; passwords must be nonempty printable ASCII (spaces and colons are allowed). Use service-token credentials that meet these bounds. A retention endpoint can use either bearer or paired Basic references independently of upload credentials.
 Profiles never contain literal tokens.

A recorded delivery failure fails the command by default. CLI `--report-only` and Maven `-Dcensus.delivery.failOnError=false` allow the job to retain that recorded failure without failing the delivery step. Invalid inputs, missing credentials and receipt persistence failures still fail. CLI exit codes are 0 for the selected outcome policy, 2 for invalid input/configuration, 4 for an unsuccessful recorded outcome, and 5 when a receipt could not be persisted. Archive the original evidence, preparation directory, plan and receipt as CI artifacts even when a delivery step fails.

The separate `delivery` goal does not collect evidence or change the scan policy result. Use [CI finalization](CI-FINALIZATION.md) to seal available current-run evidence after a failed test run and keep test, policy, and delivery outcomes separate.

## Select preparation and a destination

| Choice | Behavior |
|---|---|
| `IDENTITY` | Validate one original document and publish its exact bytes. |
| `LINK` | Retain a software SBOM and Census CBOM, write their correlation manifest, publish the unchanged CBOM. It does not create a target UI join. |
| `COMBINE` | Produce a separately identified combined BOM with a transformation manifest and explicit losses. Original signed documents stay unchanged; their signatures do not sign the derivative. |
| `generic-https` | Forward any supported prepared kind to an artifact receiver; ingestion and receiver semantic fidelity stay unverified. |
| `dependency-track` | Guarded, explicitly lossy runtime view for exactly the tested Dependency-Track 5.2.0 contract. See [Dependency-Track setup](DEPENDENCY-TRACK.md). |
| `artifactory` | Store the publication in a scoped repository path and verify its exact bytes by download. This does not establish Xray ingestion. See [Artifactory](ARTIFACTORY.md). |
| `sonatype-sbom-manager` | Import into an explicitly mapped application/version, observe asynchronous completion, and verify the original download. Experimental; see [Sonatype SBOM Manager](SONATYPE-SBOM-MANAGER.md). |
| `anchore-enterprise` | Submit an SBOM asset to an explicitly mapped application/version and observe the import job. Independent original retention is required. Experimental; see [Anchore Enterprise](ANCHORE.md). |

Inputs distinguish `CENSUS_RAW_CBOM`, `CENSUS_MERGED_CBOM` and `EXTERNAL_SOFTWARE_SBOM`. A combined document is produced as `DERIVED_COMBINED_BOM`; it is not accepted as a user-declared original. External CycloneDX 1.6 JSON uses its own pinned offline schema/reference validator. The Census-only validator still rejects software and mixed documents. No network schema resolution or implicit conversion is performed.

Loss acceptance must list the exact preparation and adapter loss-code set; a wildcard is unavailable. `requiredFields` can additionally name semantic fields that must be declared `PRESERVED`; accepting a loss cannot override that requirement. Unverified fields do not pass it. Capabilities, losses, input hashes, explicit project mapping and completion requirements are sealed into the plan.

Dependency-Track imports replace a project's inventory. Never point the runtime profile at a software-inventory project. The adapter requires a preprovisioned owned runtime project, verifies its ownership marker and rejects software or service inventory before mutation. The measured crypto field/identity losses require explicit named acceptance and independently retrievable original CBOM bytes. Async `COMPLETED` is an ingestion observation, not a fidelity claim. A concurrent writer can still change inventory after the preflight reads; restrict project write credentials to the dedicated Census workflow. The adapter does not publish combined documents or claim full CBOM fidelity.

## Original evidence, transport and receipts

An optional `retention` block requires exact original readback before upload:

```json
"retention": {
  "endpoint": "https://evidence.example.com/immutable/build-123/cbom.json",
  "authentication": {"type": "ENV", "name": "EVIDENCE_READ_TOKEN"}
}
```

Set `policy.requireRemoteRetention` to `true` to make this a policy requirement. The Dependency-Track, Sonatype SBOM Manager, and Anchore Enterprise adapters require it regardless of that flag. An external evidence-store workflow must first save the original CBOM. Census performs an authenticated HTTPS GET and verifies the exact publication digest; this is an observation at that moment, not a guarantee of future storage durability. With `LINK`, it proves retention of the published CBOM only, not the linked software SBOM. Combined-document remote retention is rejected until separate original-source retention can be expressed. Upload and readback credentials are separate.

Optional `transport` fields are `caBundle` (PEM certificates added to default trust), `proxyHost` and `proxyPort` (default 8080). TLS hostname verification is mandatory. The explicit CONNECT proxy is used for both upload and readback; ambient proxy configuration is ignored. Credential-bearing URLs, query strings, fragments and redirects are rejected. The generic adapter permits only nonsecret `X-Correlation-Id` and `X-Tenant-Id` entries under `options.headers`; endpoint templates, arbitrary header overrides and executable hooks are unsupported.

Limits are 64 KiB per profile/plan and protocol response body, 16 MiB per source/readback, two inputs, 64 exchanges, one mutation, 10-second connection, 30-second full-request and 60-second overall execution deadlines. Polling shares the overall budget. Vendor uploads are single-attempt. Generic HTTPS can opt into bounded retries only with an explicit receiver idempotency contract; see [delivery operations](DELIVERY-OPERATIONS.md). There are no exactly-once claims. A timeout after submission remains unknown; inspect the destination before a manual repeat.

Receipts use `census.delivery-receipt/2` and are atomically replaced as execution progresses. They independently record upload, ingestion, original readback, declared semantic loss, inventory mutation intent, completion gate, digests, caller mapping and timings. Semantic and inventory readback remain `UNVERIFIED` unless explicitly observed; the current adapters do not perform those comparisons. An adapter can require a destination download in addition to the selected completion gate. `destinationReadback` records centrally verified exact publication bytes separately from pre-upload `originalRetention`; a mismatch, failed download, or missing proof fails delivery even after the server accepted the upload. Both readback paths are limited to 16 MiB and the same overall deadline. A readback observation is not a retention SLA or proof of searchable cryptographic inventory.

Original collection health remains in the immutable artifact. Plans/receipts omit credentials, endpoint text and remote response bodies. They still contain operational identifiers and local manifest paths, so retain them under your organization's access policy. Protect the receipt directory on Windows using the runner's ACLs; POSIX files are created with owner-only permissions.

## Adapter authors

The versioned contract is `census.delivery-spi/1` in `com.developmentsprint.cryptocensus.delivery.profile`. `DestinationAdapter` decodes strict options into an `AdapterOptions` type, declares evidence-backed capabilities, performs local validation, and creates an `AdapterSession`. A session emits immutable `AdapterRequest` values and interprets bounded `ProtocolResponse` values into separate upload/ingestion observations. The central executor owns credentials, same-origin path checks, TLS, limits, intent receipts and readback proofs. An adapter receives no secret or HTTP client.

Adapters are explicitly registered Java code; runtime plugin loading and arbitrary code hooks are unsupported. Preparation stays separate under `delivery.preparation`. New adapters must demonstrate schema/field fidelity, replacement semantics, async status interpretation and original retention independently. A declaration alone never establishes runtime readback or idempotency.

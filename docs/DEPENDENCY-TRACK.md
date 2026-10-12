# Dependency-Track runtime view

The `dependency-track` adapter publishes an explicitly limited runtime view to Dependency-Track **5.2.0**. It accepts validated Census CycloneDX 1.6 raw or merged CBOMs. Census supplies runtime cryptographic evidence; a separate SBOM tool remains responsible for software inventory.

The default fidelity policy rejects publication. Dependency-Track 5.2.0 drops standard `cryptoProperties`, can collapse distinct cryptographic operations, and does not retain retrievable original upload bytes. Accepting the named losses below enables only this limited view. It does not make a full-fidelity CBOM round trip possible. The [measured fidelity report](design/DEPENDENCY-TRACK-FIDELITY.md) records the pinned server, real demo results, and synthetic protocol experiment.

## Project ownership and replacement

Dependency-Track imports replace a project's inventory. Never select the UUID of the application's software SBOM project. Provision a separate active `APPLICATION` project at the exact application version, restrict its upload access to the designated Census writer, and configure that UUID explicitly. Auto-creation and name/version lookup are not supported.

An administrator must provision exactly one project property:

```json
{
  "groupName": "cryptocensus",
  "propertyName": "runtime.owner",
  "propertyType": "STRING",
  "propertyValue": "payments-runtime-writer"
}
```

The profile's `ownerId` must match that value. The publishing API key needs `BOM_UPLOAD`, `VIEW_PORTFOLIO`, and `PORTFOLIO_MANAGEMENT_READ`; the last permission allows checking project properties. The adapter does not create the project, grant permissions, or set its own ownership marker.

Immediately before upload, authenticated checks verify the server version, project UUID/version/classifier/activity, ownership property, every component page, and the absence of services. Any non-cryptographic component, service, malformed response, repeated page, or exceeded bound prevents upload. At most 40 pages of 25 components are inspected; inventories requiring more pages are refused.

These checks do not lock the remote project or prove its access-control configuration. The operator must enforce exclusive writer access for this dedicated runtime project. Concurrent imports are unsupported. A marker alone does not grant permission to overwrite software. Combined SBOM/CBOM replacement remains disabled because writer ownership and concurrency safety have not been validated for that use case.

## Retain originals before publishing

Store the exact original CBOM in a separately managed evidence store first. Configure an HTTPS retrieval endpoint and a separate secret reference. Before native preflight or upload, the common executor retrieves that object, bounds its size, and verifies its SHA-256 against the prepared publication. A local bundle or claimed retention receipt is insufficient.

Successful readback proves that exact bytes were retrievable at the recorded time. It does not prove indefinite retention, object immutability, or the evidence store's durability policy; those remain operator responsibilities. Dependency-Track's temporary upload storage is not an evidence store. This adapter does not upload to the evidence store automatically.

## Profile

Use the common `census.delivery-profile/1` format. This excerpt shows the adapter-specific fields; provide the input path/digest/correlation, preparation and output paths as described by the common delivery profile documentation.

```json
{
  "adapter": "dependency-track",
  "endpoint": "https://dependency-track.example/",
  "options": {
    "projectUuid": "11111111-2222-3333-4444-555555555555",
    "ownerId": "payments-runtime-writer",
    "replacementPolicy": "OWNED_RUNTIME_SNAPSHOT"
  },
  "authentication": {"type": "ENV", "name": "DT_API_KEY"},
  "policy": {
    "capabilityRevision": "dependency-track-5.2.0/2026-10-10",
    "acceptedLossCodes": [
      "DT_CRYPTO_PROPERTIES_DROPPED",
      "DT_OPERATION_IDENTITIES_COLLAPSED",
      "DT_OCCURRENCE_CONTEXT_DROPPED",
      "DT_METADATA_PROPERTIES_DROPPED",
      "DT_DOCUMENT_IDENTIFIERS_REWRITTEN",
      "DT_TOOL_PROVENANCE_REPLACED",
      "DT_ORIGINAL_BYTES_NOT_RETAINED"
    ],
    "completion": "INGESTION_COMPLETED",
    "requireRemoteRetention": true
  },
  "retention": {
    "endpoint": "https://evidence.example/objects/cbom.json",
    "authentication": {"type": "ENV", "name": "EVIDENCE_READ_TOKEN"}
  }
}
```

All seven losses and the exact capability revision must be accepted; a blanket waiver is unsupported. Required semantic fields are independently checked: requesting preserved `cryptoProperties`, for example, still prevents publication even when named losses are accepted. BOM-level Census metadata loss is distinct from surviving component-level custom Census properties.

The common transport injects `X-Api-Key` for Dependency-Track and a separate bearer credential for evidence retrieval. Profiles contain references, never literal secrets. HTTPS certificate and hostname verification remain enabled; use the shared CA bundle option for a private CA. HTTP destinations, redirect following, arbitrary adapter headers and TLS bypasses are unsupported.

Create and review a local plan, then use its exact digest:

```sh
java -jar census-cli.jar delivery --action plan --profile delivery.json
java -jar census-cli.jar delivery --action preflight --profile delivery.json --plan-sha256 PLAN_DIGEST
java -jar census-cli.jar delivery --action publish --profile delivery.json --plan-sha256 PLAN_DIGEST
```

The Maven equivalent is goal `delivery` with `census.delivery.action`, `census.delivery.profile`, and `census.delivery.planSha256`. Planning is local. Remote preflight performs authenticated read-only checks and original retrieval, stopping before the upload.

## Completion and receipt limits

The adapter sends the unchanged prepared CBOM as a native multipart upload with an explicit project UUID. A valid upload acknowledgment records `ACCEPTED` and its operation token. Polling must subsequently report `processing: false` **and** `status: COMPLETED` to record completed ingestion. An unknown token returning only `processing: false` remains unverified. A failed workflow, malformed response, timeout or inaccessible status never becomes success.

There is no verified native idempotency guarantee and no automatic upload retry. After a transport failure or ambiguous acknowledgment, inspect the receipt and destination before deciding to retry. Import completion does not establish completion of all background analysis. Capability declarations describe the pinned experiment; this implementation does not compare a fresh destination export with the input after every upload.

## Verification

`DependencyTrackAdapterTest` exercises protocol guards without network access. The opt-in `DependencyTrackLiveTest` creates and removes its own projects and temporary API team on an explicitly selected loopback server. It uses a private-CA HTTPS proxy with the production executor, verifies exact original retrieval and completed import, and proves an existing software component prevents upload. It never alters pre-existing sample projects.

```sh
CENSUS_DT_LIVE_URL=http://127.0.0.1:PORT \
CENSUS_DT_LOGIN_FILE=/private/path/login.json \
CENSUS_DT_CBOM_PATH=/path/to/public-demo-cbom.json \
mvn -pl census-delivery -am test \
  -Dtest=DependencyTrackAdapterTest,DependencyTrackLiveTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

The private login JSON contains `baseUrl`, `username`, and `password`; keep it mode `0600`. The HTTP URL is solely the opt-in test proxy's local backend. Production delivery still uses verified HTTPS.

On 2026-10-10, both the packaged CLI and installed Maven `delivery` goal completed separate-process `plan`, `preflight`, and `publish` runs against the pinned local 5.2.0 server through a private-CA HTTPS proxy. Each performed one native upload, received explicit `COMPLETED`, and produced five assets from the seven-component public demo. Each preflight stopped before upload. The experiment removed its own temporary projects/team and TLS keys; the separate inspection UI remained running. Sanitized receipts, command logs, the reproduction script and cleanup record were retained in `/tmp/census-6dt5-entrypoint` on the experiment host.

# Dependency-Track 5.2.0 runtime CBOM fidelity

Empirical spike for `census-6dt.5`, 2026-10-10. This records a local experiment, not a shipped adapter or approval to accept information loss. Census supplies runtime cryptographic evidence; existing SBOM tools remain responsible for software dependency inventories.

## Result

Dependency-Track 5.2.0 accepts Census's CycloneDX 1.6 CBOM and reports a completed import, but **does not provide a full-fidelity round trip**. The real demo's seven cryptographic assets become five, all standard `cryptoProperties` disappear, and the exported document loses the original health/policy/provenance envelope. Custom Census component properties survive only for the components that remain.

**A second BOM upload replaces the same project's component inventory.** Uploading the crypto-only CBOM after a software SBOM removed every seeded software library. Uploading the software SBOM again removed every cryptographic asset. Separate uploads are not additive enrichment.

## Environment and evidence

- Baseline Census revision: `e909943d34baba3ef0c520b32704e04e1f978401`.
- Actual `/api/version` response: Dependency-Track `5.2.0`.
- Official API image index: `dependencytrack/apiserver@sha256:7411c390182e6e42156ccf2ab033aa9120a1f37b7ce59e8c2da0d77ec23c0a5f`; ARM64 child: `sha256:8c1892c7cb3f59346516e2924898832a8a5233c4d51bfbb98fa54c28f3aa4b0f`.
- PostgreSQL 17 Alpine: `postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73`. The test required no migrations or changes to any existing database.
- Dedicated Compose project `census-6dt5-fidelity`, internal Docker network, private disposable database/API volumes. Telemetry disabled and outbound destination policy restricted. No external uploads. Existing Supabase resources were untouched.
- A dynamically allocated host loopback mapping was configured, but this Docker runtime suppressed published ports on the internal network. A small Java HTTP client therefore ran inside the API container against `127.0.0.1:8080`; credentials and bodies arrived on standard input. The default administrator password was rotated, and a separate Automation API key exercised upload/readback.
- Real input: the public `census-demo` build's `target/cryptocensus/cbom.json`. Its occurrence locations were checked to contain only the public demo package. Its bytes were copied unchanged: SHA-256 `5f2f36765f1c3a7982707e876081197fff082a5fd0eb530be71f464cce94ffff`.
- TLS and software-inventory checks used explicitly synthetic fixtures, separately from the real demo result. The demo itself did not exercise TLS.

The local evidence directory is `/tmp/census-6dt5-fidelity`. It contains `input-demo.json`, `export-demo-1.6.json`, `components-demo.json`, `project-demo.json`, `project-properties-demo.json`, `upload-receipt.json`, `processing.json`, `unknown-token.json`, `summary.json`, protocol fixtures/exports, and replacement fixtures/exports. It also contains the HTTP harness and scenario scripts. A 56,700-byte safe bundle is `/tmp/census-6dt5-fidelity-evidence.tar.gz`, with `REPRODUCE.md` and a SHA-256 manifest. Credentials are excluded from the bundle and were removed at teardown. The ignored `target/dependency-track-spike` directory contains pinned source downloads and the Compose definition. These temporary artifacts are machine-local, not a permanent CI evidence store.

Teardown completed: both owned containers, the owned network and both owned volumes were removed. Exact Compose-label queries returned no remaining resources, and the preexisting Supabase containers remained running. `cleanup.json` records this check. No production code, remote branch, or external service was changed.

## Field comparison

All final comparisons explicitly request `GET /api/v1/bom/cyclonedx/project/{uuid}?format=json&version=1.6`. The export parameter is **`version`**, not `specVersion`; an initial request with the latter returned the default 1.5 export and was superseded for the fidelity comparison.

| Evidence | Input | Observed 1.6 export / API result |
|---|---|---|
| Component count / identity | Seven distinct Census `bom-ref` values | Five components. AES encrypt/decrypt collapse; RSA sign/verify collapse. The first matching identity's properties and occurrence survive. |
| Component type | `cryptographic-asset` | Retained for all five; inventory API classifier is `CRYPTOGRAPHIC_ASSET`. |
| Standard algorithm properties | Seven `cryptoProperties` objects, including primitive, parameter set, functions, mode, padding and quantum-security level | Zero `cryptoProperties` objects. |
| Standard protocol properties | Synthetic TLS 1.3 fixture with protocol type/version/cipher suite | Entire `cryptoProperties` object absent. |
| Custom component properties | Census risk/reason/CNSA2/count/primitive/algorithm/provider/key size/mode/padding/operation | Complete property sets match the five surviving input components. The two collapsed operations' distinct values do not survive. Synthetic TLS custom primitive/version/algorithm properties survive. These strings are not proof of native cryptographic interpretation. |
| Occurrences | Location, line, symbol, `additionalContext=count=1` | Location, line and symbol survive for the five remaining assets. `additionalContext` is absent. Occurrences belonging to the collapsed operations are lost. Default 1.5 export additionally omits line/symbol. |
| Policy / collection health / input provenance | Ten `metadata.properties`, including policy version, health counters/targets and source-input SHA-256 | No metadata properties in export; project property API returns `[]`. |
| Tool provenance | `crypto-census` version `0.1.0` | Retained in project metadata API, but export identifies Dependency-Track `5.2.0` as its generating tool. |
| Original identifiers / timestamp | Census serial number, seven asset references, collection timestamp | Export generates a new serial number, uses DT component UUIDs, and writes an export timestamp. No original asset reference survives as the exported reference. |
| Exact bytes | Original input SHA-256 above | Export SHA-256 `039e72fa894573ed629754f2f75a20cb052c3fb66ae8d9c82ec87240c5f10a22`; not the original artifact. |

Pinned source explains the observations: [ModelConverter](https://github.com/DependencyTrack/dependency-track/blob/5.2.0/apiserver/src/main/java/org/dependencytrack/parser/cyclonedx/util/ModelConverter.java) maps ordinary properties and selected occurrence fields, but has no `cryptoProperties` mapping. [ImportBomActivity](https://github.com/DependencyTrack/dependency-track/blob/5.2.0/apiserver/src/main/java/org/dependencytrack/tasks/ImportBomActivity.java) deduplicates components by [ComponentIdentity](https://github.com/DependencyTrack/dependency-track/blob/5.2.0/apiserver/src/main/java/org/dependencytrack/model/ComponentIdentity.java), rather than treating every Census reference as a unique operation. These source observations supplement the deployed results.

## Replacement experiment

One separate project UUID/version received three sequential uploads; each import token reported `COMPLETED` before export:

| Upload | Exported inventory |
|---|---|
| Synthetic conventional SBOM with two library components | Two libraries, zero cryptographic assets |
| Unchanged real Census CBOM | Zero libraries, five cryptographic assets |
| Original synthetic software SBOM again | Two libraries, zero cryptographic assets |

Evidence files are `replacement-before-export.json`, `replacement-after-cbom-export.json`, and `replacement-after-sbom-export.json`, with matching receipts/status records. The importer source also deletes existing components absent from the new processed BOM.

## Native authentication, mapping and acknowledgment

The seeded Automation team has `BOM_UPLOAD` and `VIEW_PORTFOLIO`. An ephemeral team API key sent through `X-Api-Key` successfully uploaded and exported evidence. Missing and invalid credentials returned 401. Pre-provisioning used the isolated administrator; this is not a requirement for a deployed uploader.

Both JSON/base64 `PUT /api/v1/bom` and multipart `POST /api/v1/bom` returned HTTP 200 with an upload token. The response schema also contains `projectUuid`. Exact project-name/version mapping resolved to the previously provisioned UUID. Unknown UUID returned 404, automatic creation without the extra permission returned 401, and an invalid BOM returned 400. Explicit UUID mapping avoids accidental creation and should be the first adapter's default. Name/version mapping must verify the returned UUID. The live tests did not cover organization-specific project ACLs, SSO, or service-account/workload-identity deployment.

`GET /api/v1/event/token/{token}` returned `{"processing":false,"status":"COMPLETED"}` for successful imports. A random unknown token returned only `{"processing":false}`. Therefore `processing=false` alone must never become successful ingestion. In 5.2, require the token's explicit terminal status; retain `FAILED` separately, and treat missing status, an expired token, timeout, or an unfamiliar state as unverified. HTTP 200 is upload acceptance, not completion or fidelity. A completed import also does not establish that every separately scheduled analysis workflow finished. See the pinned [EventResource](https://github.com/DependencyTrack/dependency-track/blob/5.2.0/apiserver/src/main/java/org/dependencytrack/resources/v1/EventResource.java) and importer workflow creation.

Repeated submissions produced new upload tokens. This spike establishes no provider idempotency or exactly-once guarantee. Multipart avoids JSON/base64's documented 20,000,000-character field cap; the destination's configured upload-size limit still applies. Production transport must retain TLS verification and bounded request/polling deadlines. This isolated HTTP experiment does not validate production TLS/proxies.

## Original artifact retention

The pinned [BomResource](https://github.com/DependencyTrack/dependency-track/blob/5.2.0/apiserver/src/main/java/org/dependencytrack/resources/v1/BomResource.java) decodes the upload, strips a byte-order mark if present, and stores temporary bytes under `bom-upload/{token}`. The [ImportBomWorkflow](https://github.com/DependencyTrack/dependency-track/blob/5.2.0/apiserver/src/main/java/org/dependencytrack/tasks/ImportBomWorkflow.java) deletes that temporary file after success and also attempts deletion after failure. The local storage directory was empty after the completed test imports.

The deployed public BOM API exposes generated CycloneDX exports, not a retained-original download endpoint. Its `download=true` option returns a generated export, not the uploaded file. No durable exact-original retrieval was demonstrated; source and observed cleanup contradict using temporary storage as an evidence archive. Temporary on-disk byte equality was not measured. Original evidence must remain in Census/CI artifact storage or a separately verified exact-byte receiver.

## Proposed adapter boundary

No production adapter was added. Full-fidelity acceptance for `census-6dt.5` remains unmet, and the user's vendor selection does not approve a lossy integration.

If an explicitly approved limited DT integration proceeds, its contract must:

1. Keep the original CBOM and digest in durable evidence storage; label the DT inventory view as lossy. A completed upload must not claim the evidence is fully preserved or collection/policy passed.
2. Use a separately owned runtime-evidence project/version with explicit linkage to the existing software project, or an explicitly designed upstream composition workflow that takes the independently generated software SBOM and runtime evidence together. Do not overwrite a software project's inventory with a crypto-only upload. Combined-BOM composition would still encounter the DT fidelity losses above and requires separate validation.
3. Avoid fake software components or silent algorithm renaming to evade deduplication. Any future representation change requires a documented transformation and receipt.
4. Record source artifact digest/run identity, explicit destination/project UUID, server version, HTTP acceptance, upload token, bounded polling result and a tested capability/fidelity version separately. A token is not a digest attestation.
5. Send credentials from execution-scoped secret sources, preserve TLS verification, and avoid automatic retries after ambiguous acceptance until a real idempotency contract is established.

The viable immediate boundary is exact runtime evidence plus explicit linkage. Native, fully preserved cryptographic inventory in Dependency-Track 5.2.0 is not established by this experiment and fails the measured round-trip requirements.

# Anchore Enterprise delivery

The `anchore-enterprise` adapter submits the original Census CBOM as an SBOM asset in an existing Anchore application version. Use it from either the CLI delivery command or the Maven `delivery` goal with the same profile. The adapter is **experimental and contract-tested** against the official Anchore Enterprise 6.2 API 2.19.0 schema. No commercial tenant has been used to verify runtime compatibility or cryptographic fidelity.

An import adds runtime evidence to Anchore's analysis. Census does not claim to produce your complete software inventory, and successful ingestion does not establish that Anchore retains or displays the cryptographic fields. Keep the complete software SBOM in its established workflow. Do not relabel crypto assets as software packages or fabricate package URLs to make an import succeed.

## Prepare the destination

1. Provision an Anchore application and a **dedicated runtime-evidence version**. Copy their UUIDs. The version's name must equal the input correlation `version`, and its status must be `in_progress`.
2. Restrict writes to that version to one designated CI writer. Other software assets and other writers belong in separate versions. The profile's `DEDICATED_VERSION_SINGLE_WRITER` value is your explicit attestation of this arrangement; Census cannot establish access exclusivity by reading the API.
3. Choose a new `assetName` for each sealed evidence run. Use 1–128 ASCII letters, digits, underscores or hyphens, such as `runtime-<run-uuid>-revision-1`. The name is fixed in the plan. Reusing a previously delivered name fails the collision preflight.
4. Store the original CBOM in an independent HTTPS evidence store, and configure its exact readback endpoint and read-only credentials. Census retrieves and hashes these bytes before contacting Anchore. A local file or generated Anchore export does not satisfy this requirement.
5. Provide an Anchore account-scoped user with application-read and asset-create permissions. The adapter authenticates with HTTP Basic over verified TLS. For CI, you can use a scoped API key as the password with the reserved username `_api_key`, as documented in [Anchore authentication](https://docs.anchore.com/6.2/docs/api/). It does not send `x-anchore-account`; use credentials belonging to the intended account and a distinct `destinationId` for each account.

The Anchore job contract can create **or update** assets. The adapter rejects any existing exact asset name, submits once and never calls update or delete. That read-before-write check is **not atomic**: another writer could create the name after preflight. The dedicated version and single writer are therefore mandatory. There is no native idempotency claim or automatic upload retry. Do not retry an ambiguous upload with a new name until you have inspected the remote job and asset state.

## Configure a profile

Start with the identity-preparation profile in [Delivery profiles](DELIVERY-PROFILES.md). Set the adapter and destination options as follows. `endpoint` is the service root before `/v2`, including any reverse-proxy path prefix. Set `serverVersion` to the exact installed 6.2 patch version; the adapter verifies it against `GET /v2/version` and also requires API version `2.19.0`.

```json
{
  "adapter": "anchore-enterprise",
  "endpoint": "https://anchore.example.com/",
  "destinationId": "anchore-runtime-account-a",
  "options": {
    "applicationUuid": "00000000-0000-4000-8000-000000000001",
    "versionUuid": "00000000-0000-4000-8000-000000000002",
    "assetName": "runtime-87a6592b-8e21-49c8-84f1-0c0a74e26804-revision-1",
    "serverVersion": "6.2.0",
    "mutationPolicy": "DEDICATED_VERSION_SINGLE_WRITER"
  },
  "authentication": {
    "type": "ENV_BASIC",
    "usernameName": "ANCHORE_USERNAME",
    "name": "ANCHORE_PASSWORD"
  },
  "policy": {
    "capabilityRevision": "anchore-enterprise-api-2.19.0/2026-10-12",
    "acceptedLossCodes": [
      "ANCHORE_CRYPTO_SEMANTICS_UNVERIFIED",
      "ANCHORE_ORIGINAL_RETENTION_UNVERIFIED",
      "ANCHORE_INVENTORY_MUTATION_UNVERIFIED",
      "ANCHORE_LIVE_CONTRACT_UNVERIFIED"
    ],
    "completion": "INGESTION_COMPLETED"
  },
  "retention": {
    "endpoint": "https://evidence.example.com/immutable/runtime-run-1/cbom.json",
    "authentication": { "type": "ENV", "name": "EVIDENCE_READ_TOKEN" }
  }
}
```

This is a profile fragment; keep the schema, inputs, preparation and immutable output paths from the base profile. Profiles contain secret references, never password values. Maven can instead use `{"type":"MAVEN_SERVER_BASIC","name":"anchore"}` to resolve the username and decrypted password from the named Maven server. The CLI requires environment references.

Only original Census raw and merged CycloneDX 1.6 CBOMs are accepted in this first adapter. Combined software snapshots, application/version creation, existing asset replacement, cross-account override headers and arbitrary additional annotations are unsupported. Unknown profile options fail before network access. `INGESTION_COMPLETED` is required; upload-only acceptance is unsupported because a later job can reject the CBOM.

Run `plan`, then `preflight`, then `publish` with the expected plan digest as described in [Delivery profiles](DELIVERY-PROFILES.md). Local planning performs no network requests. Remote preflight retrieves the independently retained evidence, checks the API/application/version mapping and confirms the asset name is absent. It stops before submission. Both preflight and publish use the shared TLS, proxy, bounded response and overall deadline controls.

## Interpret results

The upload sends one multipart request to `POST /v2/apps/{applicationUuid}/jobs/add-sbom-asset`, with `asset_name`, `asset_type=application`, `app_version_id`, and the unmodified `sbom` file bytes. `application` describes the outer evidence asset; it does not rewrite component types inside the CBOM.

A `201` response must identify an `add-sbom-asset` job with the exact requested version and asset name/type. Census polls the typed job endpoint. `pending` and `processing` remain pending; `failed` and `cancelled` fail delivery. Missing, unknown or contradictory status information remains unverified. A `complete` job must identify its completed asset, whose UUID/name/type Census reads and verifies before marking ingestion completed. Upload acknowledgment alone never means ingestion succeeded.

Timeouts or connection failures during upload produce `UNKNOWN`, with no second submission. Polling failures retain upload acceptance but leave ingestion unverified. Receipts include the nonsecret job UUID, observed service version and the named unverified capability codes. They omit credentials and remote response bodies. Independent evidence readback proves exact byte retrieval at that time; it does not prove indefinite retention or Anchore semantic preservation.

All semantic fields, inventory mutation, linkage and Anchore's own original-byte retention remain `UNVERIFIED`. Requests requiring preservation of any semantic field fail closed. The four acceptance codes acknowledge separate uncertainties; they are not evidence that the destination drops those fields. Before using this experimentally with a production tenant, verify a representative CBOM, compare crypto assets and properties after ingestion, confirm inventory effects, and record the exact server version. Report any schema divergence without including credentials or private response content.

## Contract sources and validation

The adapter uses the [Anchore Enterprise 6.2 OpenAPI document](https://docs.anchore.com/6.2/docs/api/specs/anchore_api_swagger.yaml), schema `2.19.0`, fetched on 2026-10-12 with SHA-256 `523f984f56eec406109ac60c5a19e0f4f17db9424db8f9903a4326a5f63d9e9c`. See also [SBOM asset uploads](https://docs.anchore.com/6.2/docs/sbom_management/apps/assets/sboms/), [asset management](https://docs.anchore.com/6.2/docs/sbom_management/apps/assets/) and [the app/job lifecycle](https://docs.anchore.com/6.2/docs/sbom_management/how_it_works/).

Automated tests cover native request framing, exact input bytes, explicit mapping and collision checks, completion verification, unrecognized/malformed responses, asynchronous failure, upload ambiguity, TLS authentication and independent retention. These tests exercise a local HTTPS contract fixture, not an Anchore installation. They establish implementation behavior against the documented contract, not commercial service compatibility or full-fidelity support.

## Repeatable live validation

Use the [enterprise live-validation runner](ENTERPRISE-LIVE-VALIDATION.md) with the packaged CLI and Maven plugin against separate disposable identities. It records sealed-plan and artifact digests, validates required completion/readback proofs, and provides a separate native-export comparison. Licensed tenant access was unavailable during the 2026-10-13 validation work; this adapter remains experimental, with no claim of commercial runtime compatibility or native crypto fidelity.

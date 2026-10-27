# Sonatype SBOM Manager delivery

The `sonatype-sbom-manager` delivery adapter imports a validated CycloneDX 1.6 JSON document into an existing application. Use the same profile with Maven `delivery` or the CLI `delivery` command with `--action plan`, `--action preflight`, and `--action publish`; see [Delivery profiles](DELIVERY-PROFILES.md) for the complete invocation and immutable evidence workflow.

**Experimental, contract-tested integration.** This revision has been exercised against isolated HTTPS fixtures, not a licensed Sonatype deployment. Every cryptographic semantic field, native linkage, native retention duration and inventory mutation behavior remains `UNVERIFIED`. An HTTP acknowledgment is insufficient: a successful delivery requires a matching terminal import result and byte-for-byte retrieval of the imported original. That comparison does not prove those fields appear correctly in Sonatype's component inventory or UI.

## Configure the application and evidence

Provision the application before delivery and give a dedicated token read/write access to that application. Sonatype recommends a user token and token password. Supply the server root as `endpoint`; Sonatype Cloud uses a `/platform/` base path. The native import uses multipart fields `file`, `applicationId`, and `applicationVersion`. Census always sends `ignoreValidationError=false` and does not request binary analysis. [Sonatype SBOM Manager API](https://help.sonatype.com/en/sbom-manager-api.html)

Find the internal application ID using its public ID. The adapter reads the application lookup response and requires exactly one matching internal/public ID pair before uploading. [Application REST API](https://help.sonatype.com/en/application-rest-api.html)

Upload the exact publication file to your independently managed evidence store first. The profile's `retention` URL must return those same bytes over HTTPS, using a separate credential if needed. Census verifies that retrieval before any vendor operation. Operators own that store's durability and retention policy.

Use these destination-specific fields in a complete profile:

```json
{
  "adapter": "sonatype-sbom-manager",
  "endpoint": "https://sonatype.example/platform/",
  "destinationId": "sonatype-payments-runtime",
  "authentication": {
    "type": "ENV_BASIC",
    "usernameName": "SONATYPE_USER_TOKEN",
    "name": "SONATYPE_TOKEN_PASSWORD"
  },
  "options": {
    "applicationId": "c9920b0b240f405a8fc11c897864e2e5",
    "publicId": "payments-runtime",
    "importPolicy": "NEW_VERSION_ONLY"
  },
  "policy": {
    "capabilityRevision": "sonatype-sbom-manager/docs-2026-10-12-experimental-1",
    "acceptedLossCodes": [
      "SONATYPE_MUTATION_UNVERIFIED",
      "SONATYPE_SEMANTICS_UNVERIFIED"
    ],
    "completion": "INGESTION_COMPLETED"
  },
  "retention": {
    "endpoint": "https://evidence.example/build-123/cbom.json",
    "authentication": {"type": "ENV", "name": "EVIDENCE_TOKEN"}
  }
}
```

This is a destination fragment, not a complete executable profile: add the schema, preparation, inputs and output paths described in [Delivery profiles](DELIVERY-PROFILES.md). `applicationVersion` comes exclusively from the verified input's `correlation.version`. Set that correlation consistently across your build evidence. Application public IDs and versions are restricted to 1–128 ASCII letters, digits, `_`, `-`, and `.`, with an initial letter, digit, `_` or `-`; consecutive dots are forbidden. The internal ID must be 32 lowercase hexadecimal characters. Unsupported characters fail locally rather than being interpolated into URLs.

For Maven settings credentials, use `{"type":"MAVEN_SERVER_BASIC","name":"sonatype-sbom"}` and supply the user token as that server's username and token password as its password. Credentials resolve only during preflight/publication. Keep them out of committed JSON and untrusted pull-request jobs.

## Import and failure behavior

The adapter reads the documented version listing, checking up to 1,000 versions across pages of 25. It rejects an existing target version, inconsistent totals, duplicate entries, incomplete pages and oversized inventories. This check is not an atomic reservation: another writer may race the upload. Use one writer per application/version, a separate runtime application where appropriate, and unique build versions. `NEW_VERSION_ONLY` expresses the operator's intent and performs that guard; it does not claim a server-side conditional-create guarantee.

There is one mutating request and no automatic upload retry. After acceptance, the adapter accepts only the documented application-scoped status URL. A terminal response must match the configured application and input version. Its original-download URL must match that exact scope; arbitrary, absolute, redirected, encoded or traversing URLs are rejected. The original bytes are fetched only through the shared TLS/credential/deadline transport.

The documented import result includes `isError`, application/version and an original download URL. Sonatype's public guide does not specify every intermediate status representation. This revision conservatively rechecks the already validated status URL after HTTP 202/204, at 500 ms intervals for at most 16 incomplete responses, within the shared operation deadline and exchange limit. This is tested HTTP fallback behavior, not a claim that any particular tenant emits those statuses. Unknown HTTP 200 JSON or other unverified responses stop without asserting completion. [Import API contract](https://help.sonatype.com/en/sbom-manager-api.html)

A timeout, malformed acknowledgment, 5xx or lost response may follow a server-side import. Inspect the receipt and destination operation before deciding whether to submit again. Do not use a new version to conceal an ambiguous result. Import failure remains separate from upload acceptance. Server response text never enters diagnostics or receipts.

## Scope and validation

Supported inputs are raw Census CBOM, merged Census CBOM and externally generated software SBOM. Census does not generate a software inventory. Combined documents remain unsupported here because the shared engine cannot independently verify retention of both original inputs for that mode. `LINK` may carry correlation evidence but does not establish a native Sonatype UI relationship.

The capability revision explicitly requires both named unverified-loss acceptances. Requiring preservation of any semantic field fails planning. Keep local CBOMs, raw evidence, manifests and receipts even when delivery succeeds. Planning performs no network or credential resolution; preflight retrieves evidence and inspects application/version mapping without importing anything.

Tests cover actual HTTPS multipart bytes and BASIC auth, read-only preflight, both original comparisons, bounded asynchronous fallback, rejected and ambiguous responses, malicious returned URLs, application/version mismatches, pagination, and safe receipts. A future certified capability revision needs a real licensed tenant with retained request/result evidence and field-level comparison of crypto types/properties, operation identity, occurrences, Census metadata, signatures and software dependency graph.

## Repeatable live validation

Use the [enterprise live-validation runner](ENTERPRISE-LIVE-VALIDATION.md) with the packaged CLI and Maven plugin against separate disposable identities. It records sealed-plan and artifact digests, validates required completion/readback proofs, and provides a separate native-export comparison. Licensed tenant access was unavailable during the 2026-10-13 validation work; this adapter remains experimental, with no claim of commercial runtime compatibility or native crypto fidelity.

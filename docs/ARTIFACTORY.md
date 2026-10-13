# Store CBOM evidence in JFrog Artifactory

The `artifactory` delivery adapter stores the exact prepared CycloneDX JSON bytes in an existing local Generic repository and downloads them to verify SHA-256. Use it to retain cryptographic evidence alongside build artifacts. It does not submit a BOM to Xray, update software inventory, or claim that JFrog indexes cryptographic fields.

This adapter is **experimental**. Its evidence revision, `artifactory/1-contract`, covers official REST contracts and local HTTPS integration tests. It has not been certified against a licensed JFrog deployment. Test it against your deployment before relying on its retention behavior; a successful receipt proves retrieval at that moment, not future availability or a retention SLA.

## Prepare the repository and credentials

An Artifactory administrator must create a **local Generic repository** and a dedicated service principal scoped to the intended repository and path prefix. Grant Read and Deploy/Cache, and remove Delete/Overwrite and administrative permissions from every applicable user, group, and project role. The profile's `READ_DEPLOY_NO_OVERWRITE` value is your explicit assertion that this effective permission configuration is in place; the adapter cannot independently inspect it with a restricted token.

Artifactory documents overwrite control through its [repository permissions](https://docs.jfrog.com/administration/docs/permissions). A read-before-write check alone cannot prevent a concurrent writer. The adapter relies on those server permissions to protect the gap between its absence check and upload. Do not use an administrator token, a Maven snapshot repository, or a virtual deployment repository. Configure retention, backups, and any privileged cleanup separately.

Provide the principal's bearer access token through a protected environment variable or Maven settings reference. See [delivery operations](DELIVERY-OPERATIONS.md) for private CA trust and authenticated CONNECT proxies. Use a different `destinationId` for every distinct destination or security context.

## Configure a delivery profile

Use the normal [delivery profile](DELIVERY-PROFILES.md) preparation, inputs, correlation, authentication, and output fields. Replace the destination-specific fields with this fragment; the endpoint is the Artifactory base URL, including its `/artifactory` context when configured:

```json
{
  "adapter": "artifactory",
  "destinationId": "company-artifactory-crypto",
  "endpoint": "https://artifacts.example.com/artifactory",
  "authentication": {"type": "ENV", "name": "ARTIFACTORY_TOKEN"},
  "options": {
    "repository": "cbom-local",
    "pathPrefix": "crypto/evidence",
    "deploymentPermission": "READ_DEPLOY_NO_OVERWRITE"
  },
  "policy": {
    "capabilityRevision": "artifactory/1-contract",
    "acceptedLossCodes": [],
    "completion": "UPLOAD_ACCEPTED"
  }
}
```

This is a fragment, not a complete profile. Keep the preparation's exact loss acceptance if you deliberately select a transformation with declared losses. `IDENTITY` preparation preserves the source bytes. The adapter accepts all currently supported input kinds, including a CBOM or a separately prepared combined BOM, without modifying the publication bytes.

Repository names allow letters, digits, underscores, and hyphens, with an alphanumeric first character (128 characters maximum). The required prefix allows those same characters plus single `/` separators (256 characters maximum). Leading/trailing slashes, dot segments, percent encoding, and query or property syntax fail before network access. The reserved repository name `api` is rejected.

`INGESTION_COMPLETED`, required remote semantic fields, and a certified durable-retention capability are unsupported: this integration provides observed artifact storage, with semantic queryability and deployment retention policy unverified.

Run the CLI `delivery` command with `--action plan`, `--action preflight`, and `--action publish`, using the sealed plan digest for preflight and publication. The Maven `delivery` goal supports the same profile and current-run manifest binding, so no application server is required. See the [profile commands](DELIVERY-PROFILES.md) and [CI finalization](CI-FINALIZATION.md) examples for exact entry-point syntax.

## Object identity and publication

The adapter derives a deterministic object path:

```text
<repository>/<pathPrefix>/<correlation-sha256>/<publication-sha256>.json
```

`correlation-sha256` hashes the canonical JSON array `['census.artifactory-path/1', application, module, version, runId]` encoded by Crypto Census's JSON codec. The publication digest hashes the exact prepared bytes. This distinguishes runs and modules without placing raw correlation strings in URL paths. The sealed plan and local receipt retain the explicit mapping; protect and retain them with the evidence.

The adapter performs three bounded, same-origin HTTPS exchanges:

1. Read the [storage file information](https://jfrog.com/help/r/artifactory-how-to-retrieve-information-about-artifacts) endpoint at `api/storage/<object-path>`. Only HTTP 404 allows publication. Any existing path, including an identical artifact, stops without mutation.
2. [Deploy the artifact](https://docs.jfrog.com/artifactory/reference/deployartifact) with one raw PUT to `<object-path>`. Only HTTP 201 is a deployment acknowledgment. It does not use checksum deployment or assume support for conditional PUT.
3. [Retrieve the artifact](https://docs.jfrog.com/artifactory/reference/retrieveArtifact) from the same path and compare its bytes' SHA-256 with the planned publication digest. The download is capped at 16 MiB; other responses remain capped at 64 KiB. Redirects are never followed, including signed cloud-storage redirects.

Offline planning makes no network request and resolves no secrets. Remote preflight only checks that the intended path is absent; it does not prove deploy permission or reserve the path. Publication checks again before PUT. For installations that redirect downloads to object storage, configure direct same-origin retrieval or use another delivery mechanism.

A successful receipt records `ARTIFACTORY_STORED_EXACT_BYTES`, upload `ACCEPTED`, and ingestion `NOT_APPLICABLE`, with separate destination readback evidence. All CBOM fields remain present in the retrieved bytes. That result does not establish Xray analysis, searchable cryptographic fields, signatures' validity, or indefinite retention. Static capability declarations remain unverified where no commercial live measurement exists.

## Failures and reconciliation

A collision fails closed, even when the existing artifact appears identical. A server-side conflict or permission rejection never triggers an overwrite or automatic retry. A 5xx, disconnected upload, or unexpected success code leaves upload acceptance `UNKNOWN`; inspect the destination and reconcile the exact digest before another intentional attempt.

If upload returned 201 but retrieval fails or differs, the receipt preserves upload `ACCEPTED` and delivery remains unsuccessful. Do not infer that an unsuccessful delivery left no remote object. Retain the receipt and investigate repository policies or concurrent privileged changes. The adapter never deletes objects, creates repositories, follows server-provided download URLs, or automatically retries a PUT.

For this adapter, `remoteOperationId` is the planned artifact’s SHA-256 filename identifier, not a server-issued job ticket; its presence alone does not prove that the remote object exists.

## Live validation access check — 2026-10-13

A disposable official **Artifactory OSS 7.161.15**, revision **86115900**, started successfully and returned its version/license through `GET /artifactory/api/system/version`. Its image digest was `sha256:3f6d67f38805a25468a63fd257452168c894f53613b162f5b69b5e6859a9c627`. Requests used verified loopback HTTPS through an isolated TLS proxy; no shared development stack was changed.

Provisioning stopped before any CBOM upload. The repository creation endpoint `PUT /artifactory/api/repositories/cbom-local` and the legacy user lookup endpoint returned HTTP 400 with a Pro-license requirement. Access v2 user management returned HTTP 403 with an admin-scoped token. We did not bypass those gates or treat startup as successful delivery validation.

JFrog's non-commercial edition matrix includes Generic repositories, but that does not make every provisioning API available. A licensed installation or an OSS repository and restricted writer provisioned through a supported mechanism is still needed for packaged CLI/Maven upload, overwrite-denial and exact-byte readback measurements. Both packaged entry points successfully created digest-bound offline plans, then stopped at the missing-credential gate; neither submitted a CBOM. The disposable containers, networks, data volume and temporary credentials were removed. The adapter therefore remains experimental and contract-tested. Use the [live validation procedure](ENTERPRISE-LIVE-VALIDATION.md) to collect those measurements and distinguish artifact retention from native semantic ingestion.

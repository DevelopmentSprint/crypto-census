# Link software inventory to runtime cryptographic evidence

Census contributes observed cryptographic usage. Use an existing software SBOM generator, such as the CycloneDX Maven plugin, to inventory packages, versions, licenses and dependencies. Preparation joins these independently produced documents locally before a delivery adapter sees them.

The Java API lives in `com.developmentsprint.cryptocensus.delivery.preparation`; CLI and Maven profile integration are described separately in the delivery documentation.

## Choose the preparation mode

| Mode | Inputs | Publication | Retained evidence |
| --- | --- | --- | --- |
| `IDENTITY` | One Census raw/merged CBOM or external software SBOM | Original bytes | Original and manifest |
| `LINK` | One Census CBOM and one external software SBOM | Original CBOM bytes | Both originals and correlation manifest |
| `COMBINE` | One Census CBOM and one external software SBOM | New CycloneDX document | Both originals, derived document and transformation manifest |

`LINK` does not claim the destination resolves BOM-Link or displays related projects. The correlation manifest identifies separate source documents by digest; it does not dereference URLs. `COMBINE` does not authorize replacing a destination inventory. In particular, Dependency-Track combined-snapshot publication remains blocked until ownership, concurrency and fidelity requirements are separately established.

## Declare the build and hashes

Every `ArtifactInput` requires its path, lowercase expected SHA-256, declared `InputKind` and `Correlation(application, module, version, runId)`. For two inputs, all four correlation fields must match exactly. A supplied run ID is an operator assertion, not authenticated provenance or proof of freshness. Choose the documents belonging to the same build; do not discover the latest file by modification time.

```java
var build = new Correlation("payments", "gateway", "1.7.0", "ci-run-1842");
var software = new ArtifactInput(
    softwareBomPath, softwareSha256, InputKind.EXTERNAL_SOFTWARE_SBOM, build);
var crypto = new ArtifactInput(
    censusBomPath, censusSha256, InputKind.CENSUS_MERGED_CBOM, build);
var bundle = new ArtifactPreparer().prepare(new PreparationRequest(
    PreparationRequest.Mode.LINK, List.of(software, crypto), newOutputDirectory));
```

The parent directory must exist. The output directory must not exist: preparation creates it exclusively and never replaces a previous evidence set. The sources are read once with a 16 MiB limit each, hashed, parsed and validated from those bytes, then copied exactly. On POSIX filesystems the new directory is owner-only and files use `0600` permissions. Hash verification detects later changes; the filesystem is not a write-once storage service.

Before execution, `PreparedArtifact.readVerified()` reads bounded bytes once and checks the bound digest. `ArtifactPreparer.load(manifestPath, expectedManifestSha256)` also replays preparation deterministically in a private temporary sibling directory and compares the resulting manifest. It verifies every source and publication, rejects altered transformations or escaping filenames, then removes its scratch files. The expected manifest hash must come from the sealed delivery plan. Hashes alone do not authenticate who created the plan.

## Validation and preservation

External software input accepts CycloneDX **1.6 JSON only**. The official 1.6.1 schemas are pinned in the delivery module at upstream commit `8a27bfd1be5be0dcb2c208a34d2f4fa0b6d75bd7`. SPDX and other CycloneDX versions require explicit future conversion support. The validator loads only bundled schemas; document URLs, external references and BOM links are never fetched. Unknown document schema declarations fail closed.

Census input additionally passes the existing strict cryptographic component validator and the declared raw or merged health-envelope validator. Labeling Census properties as external software evidence is rejected. An already derived combined BOM must be reloaded through its preparation manifest; supplying it as an ordinary source does not bypass validation.

Limits are 16 MiB per input or output, at most two inputs (32 MiB aggregate input), depth 64, 250,000 JSON tokens, one MiB per string, 128 characters per number, an estimated 64 MiB parsed-tree budget, and 100,000 component objects. The token budget can bind before the component cap. Reference traversal has its own bounded work and depth counters. Duplicate JSON keys, invalid UTF-8, unpaired Unicode surrogates, duplicate declarations and dangling internal references are errors.

Composition preserves the software document's fields, nested components, services, licenses, dependencies, vulnerability references, and other schema-declared relationships. References are discovered from the pinned schema and rewritten to deterministic source-digest namespaces, including a separate mapping for identically named source references. Distinct runtime crypto operations remain distinct. External BOM links still reference original documents; no retrieval or receiver support is implied.

The output has a new deterministic document UUID and revision `1`. Original producer metadata remains attributable to its source; the composer is recorded separately. The software document's source timestamp is retained in the manifest rather than reused as a creation timestamp for the derived document. Original Census metadata, health and policy stay in the exact CBOM and the manifest's `sourceMetadata`, associated with the source digest. They are not presented as metadata describing the completeness of the combined software inventory.

Source signatures remain in the exact originals. Signatures are **not cryptographically verified** by preparation. Signature fields are removed from modified derived content, with `SOURCE_SIGNATURE_NOT_TRANSFERRED` recorded explicitly. An adapter or policy must not describe the derived document as signed by an original generator.

Census does not assign observed call sites or providers to a software dependency by name. The manifest reports unresolved crypto-asset attribution and emits no guessed package edges. An unobserved component is not thereby quantum safe.

## Supported composition boundary

The external software document may use the full pinned schema; its reference-bearing fields are traversed using that schema. Census root inventory arrays can be added to their corresponding arrays in the derived document. Unsupported extra Census root sections fail with `COMPOSITION_SURFACE_UNSUPPORTED`, as do Census metadata containing reference declarations that cannot safely be relocated. `LINK` can retain these valid originals without needing to rewrite them.

Preparation does not merge independently authored declaration or definition objects, convert SPDX, retrieve external BOMs, verify signatures, infer package attribution, or provide durable remote retention. It also does not prove that a server preserved the uploaded fields. Those remain explicit concerns for delivery profiles and destination capabilities.

## Test fixtures and dependencies

`census-delivery/src/test/resources/preparation/cyclonedx-maven-software.json` is real output from CycloneDX Maven plugin `2.9.1`, schema `1.6`, generated from the adjacent `software-pom.xml`. Tests also exercise raw and merged Census documents, signatures, reference collisions, nested graph rewrites, source and manifest tampering, malformed schemas, resource limits and symlinks.

The delivery module uses `com.networknt:json-schema-validator:2.0.8`, the current Jackson 2 release line at implementation time. It targets Java 8+ and works on the project's Java 17 baseline. Its runtime dependencies include Jackson 2.22.3, pinned by its BOM to supersede vulnerable transitive 2.22.1 defaults (including its YAML module and SnakeYAML 2.5), SLF4J API 2.0.17 and ITU 1.14.0. The validator and bundled CycloneDX schemas are Apache 2.0 licensed. These dependencies are delivery-side only; the in-process Census agent remains independent of Jackson and network delivery.

Jackson 2.22.3 addresses the upstream [parser denial-of-service advisory](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-7hhh-6rmp-j9qf) and [forward-reference denial-of-service advisory](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-cxp5-3px4-pw24), among the advisories affecting the validator's default Jackson release. Dependency management applies the patched family consistently to Maven, CLI and test graphs.

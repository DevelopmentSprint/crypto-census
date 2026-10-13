# Enterprise delivery validation

Delivery support has several independent meanings: a destination can accept an upload, finish an import, return the original bytes, or preserve cryptographic fields in its inventory. Verify the capability your workflow needs. A successful upload does not establish the other three.

## Read the evidence by capability

| Destination | Validation available | Original evidence | Native cryptographic inventory |
|---|---|---|---|
| Generic HTTPS | Executable HTTPS, retry, proxy, CA and error-path tests. | The source stays unchanged; optional authenticated readback checks its exact digest. | Receiver-specific and unverified. |
| Dependency-Track 5.2.0 | Real imports, exports, replacement experiments, guarded adapter execution, and packaged CLI/Maven runs. | A separate store is required and retrieved before import. Dependency-Track does not return the original upload. | Measured losses: seven demo assets become five, with standard cryptographic properties and other evidence omitted. |
| Artifactory | See the [current adapter validation](../ARTIFACTORY.md). | The adapter uploads unchanged bytes and compares the download digest. A real deployment must establish its permission and readback behavior. | Storage is not Xray analysis or searchable cryptographic inventory. |
| Sonatype SBOM Manager | Vendor-contract HTTPS fixtures; a licensed deployment is needed for live validation. | Separate original retention is required; the adapter also checks destination original-download bytes. | Unverified; acceptance of the import format is insufficient. |
| Anchore Enterprise | Vendor-contract HTTPS fixtures; a licensed deployment is needed for live validation. | Separate original retention is required. | Unverified; completed asset processing does not prove field preservation. |

A digest comparison establishes byte equality at the observation time. It does not prove a retention period, backup policy, availability guarantee, or native interpretation of those bytes. Configure those obligations with the destination operator.

## Dependency-Track's supported contract

The approved profile design permits an explicitly limited runtime view. It requires a separately owned crypto-only project, acceptance of the exact measured loss codes, verified retrieval of the original evidence elsewhere, and an explicit completed import status. It rejects replacing a software inventory with a crypto-only report. Default fidelity requirements still reject unsupported preservation claims.

The original full-fidelity investigation remains a negative result. Its [field-by-field experiment](../design/DEPENDENCY-TRACK-FIDELITY.md) has not been superseded by the guarded adapter. These are different conclusions:

- The limited adapter can satisfy its declared upload, completion and original-retention gates.
- Dependency-Track 5.2.0 cannot satisfy a requirement for complete native cryptographic-field preservation.

Keep native full-fidelity destination qualification separate from acceptance of this limited integration. Selecting another destination or upgrading a server version requires new evidence; an Artifactory byte-for-byte round trip does not establish native inventory fidelity either.

## How the implementation is verified

| Contract | Executable evidence | Scope of the conclusion |
|---|---|---|
| Maven and CLI use the same profile engine | `ProfileCliTest`, `DeliveryMojoTest`, and separate-process live Dependency-Track runs. | Both entry points validate and execute the shared plan. |
| Failed tests retain usable current-run evidence | Maven Invoker cases `finalize-failed-surefire`, `finalize-failed-failsafe`, and `finalize-parallel-modules`; `CurrentRunTest`. | Finalization, evidence integrity, policy evaluation and delivery remain distinct. Missing and stale reports fail safely. |
| Policy failure does not hide the artifact | `finalize-failed-surefire` (publication followed by failed enforcement and uploaded-byte comparison), `fail-quantum-vulnerable`, and the repository CI's always-run evidence steps. | Preserve evidence before enforcing the outcome; workflow cancellation can still prevent finalization. |
| Strict and report-only modes preserve failure outcomes | `ProfileFlowTest`, `ProfileCliTest`, `DeliveryMojoTest`. | Strict mode enforces delivery gates; report-only mode records failed gates without turning them into successful delivery. |
| Unsafe profiles, inputs and capability requests fail safely | `ProfileValidationTest`, `ProfileFlowTest`, `ProfileCliTest`, `DeliveryMojoTest`. | Invalid configuration, changed input and unsupported requirements are rejected before publication. |
| Planning is local and preflight is read-only | `ProfileFlowTest`, `ProfileCliTest`, `DeliveryMojoTest`. | Planning performs no network or secret resolution; authenticated preflight performs destination reads without publishing evidence. Secrets stay out of plans and receipts. |
| Preparation preserves source evidence | `ArtifactPreparerTest`, `ProfileValidationTest`, `ProfileFlowTest`. | Identity, link and combine validation, source digests, reference rewrites, unresolved attribution and mutation rejection. |
| HTTP acceptance differs from completed ingestion | `DependencyTrackAdapterTest`, `DependencyTrackLiveTest`, `SonatypeHttpsTest`, `AnchoreHttpsTest`. | Unknown tokens, malformed responses and incomplete operations never become completed imports. Commercial adapter tests use fixtures. |
| Retries require a receiver contract | `RetryPolicyTest`, `ProfileFlowTest`. | Generic HTTPS retries require an explicit deduplication declaration, preserve identity and bytes, and honor bounded delay. Vendor mutation remains one attempt. |
| TLS and proxy credentials remain scoped | `AuthenticatedProxyTest`, `EnterpriseTransportTest`, `TransportLoggingTest`. | Private-CA trust, hostname rejection, authenticated CONNECT, no origin/proxy credential crossover, bounded operations and redacted diagnostics. |
| Concurrent work has separate identity | `finalize-parallel-modules`, `DeliveryIdentityTest`, adapter mapping tests. | Distinct runs/modules receive separate evidence and mapping. A preflight read is not a server-side lock. |
| The observed server produces its own report | `AppServerIT` on Open Liberty and WildFly. | The agent runs inside the server, a servlet exercises cryptography, and graceful shutdown produces a healthy report with application attribution. |

The test sources live in [delivery](../../census-delivery/src/test/java/com/developmentsprint/cryptocensus/delivery), [CLI](../../census-cli/src/test/java/com/developmentsprint/cryptocensus/cli), [Maven](../../census-maven-plugin/src/test/java/com/developmentsprint/cryptocensus/maven), [Invoker scenarios](../../census-maven-plugin/src/it), and [app-server conformance](../../census-app-servers/src/test/java/com/developmentsprint/cryptocensus/appservers/AppServerIT.java). The [conformance matrix](../CONFORMANCE.md) identifies required hosted jobs and optional local tests; a skipped live test is not validation of a destination.

## Recorded integration evidence

The enterprise lifecycle and transport hardening landed through [PR #17](https://github.com/DevelopmentSprint/crypto-census/pull/17). Its [required hosted CI](https://github.com/DevelopmentSprint/crypto-census/actions/runs/38317740821) and [dependency scan](https://github.com/DevelopmentSprint/crypto-census/actions/runs/38317769241) passed. Those results establish the tested source revision, not a Maven Central publication or commercial vendor certification.

The October 12 local Dependency-Track validation used separate disposable projects and private-CA HTTPS. Packaged CLI and manifest-bound Maven delivery each reached `DT_IMPORT_COMPLETED_WITH_LOSS`, with original-byte retrieval and a completed native import. The Maven input came from a failed Surefire run; successful delivery did not convert the failed workload into passing test evidence. Temporary projects and credentials were removed after the experiment. The earlier fidelity experiment remains the source for the seven named field-loss codes.

On October 13, both required app-server tests ran again at source `42913958043be658bb41ff4703752d8597d63929`, with Docker required and no app-server skips. Their actual shutdown reports were passed to the packaged CLI in separate processes and uploaded to a task-owned authenticated HTTPS receiver with private-CA validation. Open Liberty produced four assets and WildFly seven; each invocation exited successfully, sent exactly one upload, preserved its input, and delivered bytes with the same SHA-256. The receiver and temporary TLS material were removed afterward. This verifies server-to-CLI handoff and generic acceptance, not commercial-vendor ingestion or future retention.

Receipts record the outcome of one attempt. Profile receipt metadata is marked `CALLER_SUPPLIED`; a Maven manifest-bound invocation performs additional input/run checks before execution. Preserve the manifest, plan, invocation evidence and receipt together instead of treating that receipt label as independent attestation of CI origin.

## Qualify a new deployment

Use a disposable, explicitly owned project or repository path and representative non-sensitive input. Record the product edition and exact version, adapter capability revision, source and retrieved digests, and the intended mutation. Run planning, read-only preflight and publication through both supported entry points.

Compare the exported inventory field by field: crypto asset types and operations, algorithm/protocol properties, occurrences, policy and collection-health metadata, document identifiers, and software dependency relationships where applicable. Test replacement and concurrency behavior separately. Preserve negative findings and unknowns alongside successful results.

Record upload acceptance, asynchronous completion, original readback and semantic comparison independently. Update capability declarations only for claims the real deployment establishes. Keep experimental status when access is unavailable or the evidence is incomplete.

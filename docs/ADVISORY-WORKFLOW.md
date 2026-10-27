# Observe, add context, and reassess

Crypto Census can produce an advisory business-context report alongside its existing cryptographic
inventory. Collection and reporting are optional; existing `check` severity and collection-health
rules remain unchanged. The report separates current weakness, PQC planning priority, readiness
assertions and coverage gaps.

Build/install the unpublished candidate as described in [installation](INSTALLATION.md). For an
application already using the Census Maven plugin, enable collection and reporting for a real test
workload:

```sh
mvn verify -Dcensus.enrichment=true -Dcensus.assessment=true
```

Open `target/cryptocensus/assessment/assessment.html`. The companion `assessment.json` contains the
same canonical assessment. Keep the original paired `cbom-*.json` and `cbom-*.evidence.json` files
from the unique run directory under `target/cryptocensus/`; the merged `cbom.json` alone cannot
reconstruct per-JVM flow or handshake associations. Record the selected run directory before a
later build creates another one. Labels describe workloads; they do not establish deployment identity.

## Start without annotations

1. Open **Unlisted areas / lost attribution**, inspect a project-owned call site, and choose
   **Name a business flow or add application defaults**. Supply a stable flow ID and optionally select an exact project binding, which selects its
   resolved application ID. For an unbound declaration, supply the application ID explicitly. An unresolved application identity or ambiguous
   binding remains unassigned after reassessment; a package name is not ownership proof.
2. Enter classification, protection lifetime and accountable owner. Choose **Not yet known**
   for unanswered classification; custom identifiers are a separate choice. Leave other
   unknown answers blank.
   For broad application defaults, leave the flow ID empty. Defaults remain visibly broad until
   explicitly confirmed for a particular flow.
3. Add answers to the draft and review the proposed edits. Existing findings become stale; the
   browser does not recalculate risk. Export `context-changes.json`.
4. Choose a durable private destination explicitly, then apply and reassess. These goals do not
   rerun application tests:

```sh
mvn census:context-apply \
  -Dcensus.contextFile=/private/inventory/payments-context.json \
  -Dcensus.changesFile=/private/downloads/context-changes.json
mvn census:assess \
  -Dcensus.inputDir=/private/inventory/saved-run \
  -Dcensus.contextFile=/private/inventory/payments-context.json
```

Substitute real paths. Do not put the authoritative context under `target/`, which `clean` removes.
A project-local context path is an explicit opt-in: ignore it and review access permissions.
Imported file contents never authorize a destination write. Managed apply locks the destination,
checks base/current/proposed records, and reports all conflicts without partial writes. A conflicting
annotation and user answer requires a reviewed resolution against the exact source references in
a regenerated report. Reviewer names and dates are assertions, not authenticated audit identities.

Context import proposes additions/updates, preserving records omitted from the import. Explicit
removal, retirement and an unobserved flow are separate actions. Exported changes remain unapplied
until the Java goal succeeds. Open the regenerated HTML to see authoritative findings.

A binding describes an exact artifact/class/method/descriptor and main/test origin. Binding a
shared helper may group several unscoped callers. This is declared call-site association, not proof
of a particular transaction or protected record. Library and unresolved frames are not promoted to
application ownership. See [source catalogs](SOURCE-CATALOG.md) for explicit shaded, server and
named-loader mappings. Generated Maven artifact labels do not independently establish runtime
deployment ownership; competing loaders require an explicit qualified deployment mapping.

## Add natural Java declarations

Add the passive annotations artifact to the application:

```xml
<dependency>
  <groupId>com.developmentsprint</groupId>
  <artifactId>census-annotations</artifactId>
  <version>0.1.0</version>
</dependency>
```

```java
@CryptoFlow(value = "records-archive", purpose = CryptoPurpose.KEY_PROTECTION)
@DataProtection(classification = "sensitive", confidentialityYears = 20)
public byte[] archiveKey(byte[] syntheticKey) throws GeneralSecurityException {
    return protect(syntheticKey);
}
```

These are declared minimum requirements from data creation/signing, not expiry dates. IDs survive
method renaming and are scoped by application identity. `@CryptoFlow("card-vault")` is a useful
identity-only declaration; the report can ask for missing context later. Signature flows use
`authenticityYears`; they are not automatically less urgent than confidentiality flows. Annotations
have no operational effect without the agent and do not validate values by themselves.

Only concrete annotated method execution on the current thread supplies synchronous scope.
An annotation on executor submission does not propagate to its task. Same-thread nested work and
outer requirements remain visible; recursion does not multiply event counts. Loaded declarations
can be unobserved, while never-loaded classes are outside inventory. See the
[annotation contract](../census-annotations/README.md), [structural diagnostics](ANNOTATION-DIAGNOSTICS.md)
and [conformance evidence](CONTEXT-CONFORMANCE.md).

## Executable repository demo

After building/installing this checkout, run the demo with opt-in enrichment:

```sh
./mvnw -B -ntp -pl census-demo clean verify \
  -Dcensus.enrichment=true -Dcensus.assessment=true \
  -Dcensus.applicationId=demo-payments -Dgitbuildhook.gitconfig.skip=true
```

The demo exercises these outcomes:

| Area | Evidence and interpretation |
|---|---|
| `records-archive` | RSA key protection with a sensitive 20-year confidentiality requirement: urgent planning. |
| `daily-export` | The same helper under public, one-year requirements: planned migration; separate flow counts. |
| `payment-signing` | RSA signature with 20-year authenticity requirement: urgent planning independently of confidentiality. |
| `card-vault` | Identity-only AES-256-GCM use: no PQC action identified for that primitive; not a readiness claim. |
| `async-archive` | Declared submission method, but work executes off thread: known flow with no assigned crypto observation. |
| Unannotated calls | `protectWithoutContext`, signature verification and legacy statement checksum remain available for guided review. Existing SHA-1 weakness stays visible regardless of context. |

The CI advisory-demo job runs these workloads and checks the generated JSON/HTML with
`scripts/check-advisory-demo.py`, including shared-helper count conservation and the async gap.
It also opens the actual report in a browser, exports a helper-flow declaration, applies it with
`census:context-apply`, and runs `census:assess` against copied saved inputs. The input and test-report
hashes must remain unchanged. Browser instructions are in the
[workflow test guide](../census-assessment/src/test/browser/README.md).

All values and records in this demo are synthetic. It is not a storage or payment implementation.
The same tests still run with `-Dcensus.skip=true`, demonstrating passive annotations without Census
instrumentation. Ordinary inventory without business enrichment remains the default.

## TLS and report boundaries

Opt in separately with `-Dcensus.tlsCorrelation=true` alongside enrichment. Only supported,
capability-qualified completed handshakes can establish negotiated protection facts. The report
keeps confidentiality and local/peer authentication separate. Missing PSK lineage, failed attempts
and unsupported providers remain gaps. An initiating annotation does not prove later pooled or
multiplexed requests used that connection. See [TLS correlation](TLS-CORRELATION.md).

The HTML makes no network requests and does not fetch imported evidence links. Hashes establish
pairing and revision references, not authenticity against a hostile JVM. No browser or algorithm
observation establishes verified migration readiness. Store enriched artifacts privately and avoid
broad upload globs; [operations guidance](OPERATIONS.md) describes retention and output controls.

See the [exploratory measurements](ENRICHMENT-MEASUREMENTS.md) before interpreting runtime cost.
They show substantial synthetic enrichment overhead and a full-capacity sidecar publication
limit; these results support further testing and optimization, not production-suitability claims.

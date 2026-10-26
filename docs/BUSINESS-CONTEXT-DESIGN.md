# Business context, annotations, and migration assessment

Status: reviewed proposal; three completed Claude Opus 5.5 high-effort review rounds,
with final documentation consistency edits applied. Ready for user acceptance; not implemented.
Date: 2026-10-26. This document describes future behavior, not released capabilities.

## Product outcome and boundaries

Crypto Census helps a team identify which observed uses of cryptography need migration,
explain their priority, and verify changes against representative workloads. A useful report
combines runtime evidence with business requirements and shows the provenance of each.

Users can start without changing application code or authoring a configuration file. They
run Census, open a local report, enrich discovered application areas, and reuse those answers.
Java developers can optionally declare stable business flows and protection requirements
using a new `census-annotations` artifact. Both paths feed the same assessment model.

The first release remains a local tool for tests and staging. It does not inspect payloads,
infer sensitivity from plaintext, change cryptographic behavior, certify compliance, predict
the arrival of a cryptographically relevant quantum computer, or assert enterprise coverage.
Application-wide metadata does not establish which records a particular operation protects.

The existing algorithm policy and Maven failure defaults remain authoritative for technical
findings. Business priority is an additional assessment, never an override of a broken or
unknown algorithm finding. Existing `allow` exceptions do not turn observations into migration
readiness evidence. Additional migration enforcement is opt-in and follows report-only adoption.

## Existing architecture and constraints

- `census-core` owns `CryptoAsset`, `Occurrence`, bounded `Inventory`, `Policy`, and CBOM IO.
  Asset identity currently includes cryptographic parameters, not business context.
- `census-agent` instruments JCA/JSSE through `Installer`, `Advices`, and `Recorder`.
  Advice suppresses observer failures and preserves application behavior.
- `census-maven-plugin` merges per-JVM reports and applies existing gates in `check`.
- `docs/SAFETY.md` requires bounded collection and honest health reporting. Unexecuted code,
  arbitrary third-party crypto APIs, and unsupported instrumentation remain coverage gaps.
- Current TLS session construction is not completed-handshake evidence. Aggregate observations
  cannot establish that two algorithms participated in the same exchange.

Requirements come from the requested observe-first experience and first-class Java annotations.
Module boundaries and safety contracts come from `CONTRIBUTING.md`, `POLICY.md`, and `SAFETY.md`.
No separate approved architecture specification exists in the inspected checkout.

## User experience

### Observe, review, reuse

1. Run the existing instrumented Maven tests. Migration reporting is initially opt-in.
2. Open `target/cryptocensus/migration-report.html`. The report works from a local file.
3. Review discovered application areas and annotated flows. Unannotated areas use a nearest
   project-owned frame when available; otherwise they are explicitly library/unresolved groups.
   Grouping is a navigation suggestion, not an inferred business process.
4. Answer only missing questions: owner, sensitivity, confidentiality lifetime, authenticity
   lifetime when applicable, exposure, and business impact. Unknown is a valid answer.
5. Export `census-context-changes.json` and apply it through a Maven context-apply goal to a
   configured project or private context path. A subsequent assessment reuses the declarations.
   The initial context is empty with a well-defined content hash; no hand-authored file is needed.

The static report cannot silently write to a repository. It provides import/export, an unsaved
changes indicator, a best-effort before-unload warning, and a preview of proposed declarations.
It uses no backend, telemetry,
remote fonts, CDN scripts, or implicit local-storage persistence. Imported data is validated
before applying it; conflicts appear together. Users choose whether to version the context
file because business classifications and owners may be sensitive.

The report groups findings by flow/application, with owner and explicit reasons. Selecting a
finding reveals technical evidence, declaration provenance, missing evidence, and next action.
Do not use a single readiness percentage. Separate priority, readiness, and evidence quality.

Illustrative display:

```text
Records export                           Owner: Records Platform
Priority: urgent planning                Readiness: unassessed

Declared: restricted data; confidentiality at least 20 years
Observed: X25519 key-agreement initialization within records-export
Association: annotated execution scope; protected data not independently traced
Reason: classical key agreement in a declared long-lived confidential flow
Next action: identify the protocol path and validate a supported migration option
Gap: successful TLS negotiation and production-path coverage not established
```

Filtering by owner, evidence gap, technical verdict, or priority is local. The default page
shows collection gaps prominently. A flow with no observed crypto reads “not observed,” not
“safe.” Imported known flows remain visible even when absent from the latest workload.

### Optional Java declarations

Publish `com.developmentsprint:census-annotations` with Java 17 bytecode, no runtime dependencies,
public Javadoc, and the same release version as Census. Applications use the normal compile
dependency so annotation types and their defaults are available at runtime. Annotation usages
remain in class bytes regardless of dependency scope; unresolved types get an explicit diagnostic.
No annotation processor is required.

```java
@CryptoFlow(value = "records-export", purpose = CryptoPurpose.TRANSPORT)
@DataProtection(classification = "restricted", confidentialityYears = 20)
public void exportRecords() {
    archiveClient.upload(records);
}
```

Proposed package and automatic JPMS module name: `com.developmentsprint.cryptocensus.annotations`.
The artifact contains only that public API package; an automatic module exports its packages
implicitly. Add `DataProtection.UNDECLARED = -1` for API consumers.

| API | Contract |
| --- | --- |
| `@CryptoFlow` | Runtime-retained, method-targeted, documented; required `String value`; optional `CryptoPurpose purpose` default `UNSPECIFIED`. |
| `CryptoPurpose` | `UNSPECIFIED`, `TRANSPORT`, `STORAGE`, `KEY_PROTECTION`, `SIGNING`, `VERIFICATION`. Purpose is declared, never inferred proof. |
| `@CensusAnnotationApi` | Runtime-retained, annotation-type-targeted infrastructure marker; required `int value`; version 1 on both public usage annotation types, never required at application use sites. |
| `@DataProtection` | Runtime-retained, method-targeted, documented; optional `String classification` default empty, `int confidentialityYears` and `int authenticityYears` default `-1`. Requires `@CryptoFlow` on the same method in v1. |

Year values: `-1` means undeclared; positive integers specify a minimum required protection
lifetime from data creation/signing. Zero and other negative values are invalid, not “no risk.”
The assessment does not subtract elapsed time without a separately established data date.
Classification values are organization-defined identifiers. Unmapped values remain unknown
for priority purposes. Strings and years have schema-defined bounds; invalid declarations
produce diagnostics and do not disappear silently. Flow IDs use lowercase ASCII
`[a-z0-9][a-z0-9._-]{0,63}` and are case-sensitive; invalid IDs are rejected, not normalized.
Classification identifiers use the same syntax. Positive lifetimes are bounded to 1–1000 years.

Only concrete executable methods are supported in v1. No constructor, abstract/interface
declaration inheritance, class-level default, or automatic override inheritance. An annotation
on an interface method does not annotate an implementation. Proxy/generated class behavior
is documented and tested where supported; unsupported methods produce explicit diagnostics
when discovered. Existing synthetic-class exclusions must not be silently changed globally.

Maven supplies a source-artifact map covering directory outputs, packaged main/test artifacts,
and explicitly identified reactor artifacts, including resolved packaged copies. Declaring-code
identity is separate from the executing test module and deployment. An explicit product identity
mapping takes precedence; otherwise the declaring artifact’s stable `groupId:artifactId` is the
proposed application identity, persisted when accepted. Thus a shared annotated library has the
same default identity in its own tests and a consuming module’s tests. Multiple modules intended
to share a product identity use one explicit mapping. Runtime deployment remains separate evidence
and cannot be merged merely because declaration identities match. Known shaded/uber artifacts require an explicit original-source/product mapping for stable
original declaring identity; container ownership alone is only a navigation hint. Source
provenance cannot recover original module identity from arbitrary relocation. Unknown code-source ownership
produces an unresolved subject rather than adopting the current test module’s identity.
Standalone applications use the configured application identity. Missing
identity allows ordinary inventory but prevents durable cross-run flow reconciliation. A later
coordinate rename requires an explicit identity mapping; display-name changes do not change IDs.
Per-deployment/loader overrides are supported through bounded configuration and have source
provenance. Multi-deployment JVMs without an explicit deployment mapping cannot assert distinct
application identity: retain deployment-local unresolved subjects and diagnose ambiguous IDs
instead of merging declarations. Maven forks carry source-artifact mappings independently of PID.

Flow identity is `(applicationId, flowId)`, independent of method name, line, display name,
or cryptographic algorithm. Multiple methods may deliberately use the same ID when they
represent the same flow. Conflicting declarations for that ID remain unresolved. Method
descriptors distinguish overloaded methods in declaration evidence.

With no agent, annotations have no operational behavior. The agent matches annotation names
and reads metadata through Byte Buddy descriptions rather than linking application annotation
classes into bootstrap code. Shading must not rename the application's annotation package.
Use an explicit version marker on the annotation type (`@CensusAnnotationApi(1)`), with a
versioned agent definition of defaults. Descriptor discovery does not require resolving the
annotation type, but authoritative declaration parsing requires a supported marker and defaults;
unresolvable or unknown versions produce capability gaps. The marker is supplied by the artifact,
not repeated by users. Build annotation match names to survive agent relocation and test the
shaded JAR, including library consumers using provided/optional annotation dependencies.

### Execution scope semantics

Method entry pushes a bounded scope frame; every normal or exceptional exit restores the prior
frame. The innermost flow receives direct attribution; each aggregate also retains its bounded
active flow path. Inclusive counts use those path-specific counts, not an unweighted parent graph.
A repeated flow ID within a recursive path contributes once to that flow’s inclusive count.
Assess each active flow against its own requirements and preserve every applicable result; the
path priority is the highest established priority. A weaker inner declaration cannot lower the
outer flow’s priority. Incomparable/custom classifications are not silently ordered. Reentrant
calls and recursion restore correctly.

Scope labels mean “observed while this flow executed,” not taint tracking or proof that the
annotated data was protected. A crypto operation in a nested helper inherits execution scope,
but a previously initialized reusable cipher cannot be attributed to later uses that Census
does not observe. Reports retain the existing entry-point-count semantics.

V1 supports synchronous scope on the executing thread, including a virtual thread when the
runtime supports it. It does not use inheritable thread locals or automatically propagate
through executors, reactive frameworks, futures, or thread pools. Every scoped finding carries
the scope capability `synchronous-only`; the report states that off-thread work may be missing.
Arbitrary lost propagation is not claimed to be detectable. Scope is dynamic execution context:
unrelated callbacks or queued work executed inline on the same thread can be included. Record
this limitation alongside missing off-thread work; it is not causal attribution. Test fork/join
helping, synchronous future callbacks, caller-runs rejection, and reactive subscription boundaries.
Balanced supported execution must restore context before later work; catastrophic JVM failure
is outside a recoverability guarantee.

An optional explicit context API and selected async adapters are later work, with capture,
restore, cancellation, and executor-reuse tests. They are not part of the annotations artifact.

### Instrumentation isolation, visibility, and restoration

Install annotation enrichment with a separate transformer, listener, bounded registry, and
health counters. Leave the fixed JCA target tracker, selected/instrumented sets, core transformer
rollback, and existing check health inputs unchanged. Enrichment installation/transform failure
must leave ordinary CBOM health and check outcomes equivalent to enrichment disabled.
The same isolation applies after recording: complete the core inventory update first, then
invoke attribution in a separate failure guard that updates only enrichment counters. No
sidecar exception, registry cap, or attribution saturation increments core recording/drop health.

Inlined scope advice calls a minimal bootstrap bridge with primitive-only arguments/return
tokens; no agent-defined reference types appear in method frames. Verified parent-delegating
loaders are supported first. For non-delegating app-server loaders, use documented explicit
bridge-package delegation configuration, verified before transformation. If bridge visibility
cannot be established safely, skip enrichment and diagnose scope-unavailable; do not discover
visibility failure by throwing on every application invocation. Liberty/WildFly conformance must
exercise annotated deployments. JPMS read edges alone do not solve classloader visibility.

Use cheap loader/package/descriptor prefilters before expensive method metadata parsing; do
not scan bootstrap/platform classes for application annotations. The bounded binding registry
contains strings/primitives only, keyed by application/deployment identity, binary class name,
method descriptor and normalized declaration hash. Retransformation is idempotent; redeploys
do not retain classloaders. Registry exhaustion uses an explicit unknown binding and health gap.

Entry returns a primitive long balancing token; zero is reserved for no successful state change.
A bounded per-thread frame array stores prior state and generation. Perform fallible allocation
and validation before committing a push; no fallible diagnostics follow a committed push before
the token returns. Overflow still produces a balancing token and marks the scope unknown.
Exit validates the token and restores state before any reporting/allocation. A zero token does
not pop the parent. A mismatched generation invalidates the scope (unknown attribution), never
blindly applies stale state. Clear the thread-local at outermost exit. Fault injection must
show that supported entry/exit failures do not corrupt parents or leak context. If entry cannot
establish an inner scope, report lost scope where possible; never claim complete attribution.

Use a constant-stack restore path with primitive operations. Recoverable stack exhaustion or
allocation failure may prevent even suppressed cleanup/diagnostics from running; these are not
automatically catastrophic JVM failures. Where caught, invalidate scope and increment
`scope-integrity`/`scope-restore-failure` before accepting further attribution if possible. A
later mismatch marks the entire run’s scope attribution unverified, including earlier records,
since the start of a misattribution window may be unknown. If neither detection nor recording
can execute, Census cannot guarantee a counter or correct scoped attribution. Report this
residual limitation for all scoped evidence; absence of a counter is not proof against VM-resource
failures. Add caught StackOverflowError and injected cleanup-failure fixtures, including the
window before the next outer exit, and require detected failures to prevent validated scope claims.

## Evidence and persistence model

Keep `CryptoAsset.ref()` and existing inventory totals unchanged. Add bounded aggregates rather
than keeping a full event stream. Every entry-point event belongs to one direct attribution
bucket, including an explicit unassigned/overflow bucket. Parents are relationships, not extra
event counts. `Inventory.add` exposes its effective keep/fold/drop outcome to attribution; it
does not independently decide whether an event was retained. Keep distinct `no-scope`,
`scope-lost`, and dropped-event counters. Dropped events never create dangling asset references.
Apply write-time occurrence folding to both representations from a common drained snapshot.
Compare saturated sums per retained asset: require exact conservation for unsaturated, fully
drained snapshots; otherwise report unreconciled/unknown conservation, not a fabricated delta.
The existing drop, overflow and shutdown-timeout evidence remains visible.

| Record | Identity and purpose |
| --- | --- |
| `FlowDefinition` | Application + flow ID, label, declaration source, revision, and method bindings. |
| `ProtectionDeclaration` | Subject ID, field/value, source kind, source revision/hash, review metadata, scope of applicability. |
| `FlowObservation` | Run ID + application + active flow path + asset ref + effective occurrence + association kind; saturating count. |
| `HandshakeEvidence` | Run ID + opaque connection ID + attempt sequence; outcome, negotiation, resumption, provenance and related assets. |
| `AssessmentFinding` | Subject, technical finding references, rule ID/version, priority reasons, readiness, evidence gaps and next action. |

Unannotated occurrences initially have diagnostic identities derived from class, method
descriptor where available, and application. They are not stable business identifiers. Reviewers
may create a stable flow and bind selected observations; a changed/unmatched binding requires
review and never follows fuzzy name matching automatically. Application defaults remain labeled
as broad triage context, not specific protection claims.

For useful observe-first navigation, Maven passes the bounded canonical source-artifact map
(main/test directories, packaged artifacts and identified reactor dependencies).
During the existing stack walk, capture the nearest project-owned frame and main/test origin
alongside the unchanged first application frame. Resolve ownership through verified code-source
paths with artifact coordinates and main/test role; unresolved sources stay library/unknown.
Standalone users may supply source mappings optionally. Paths alone are not trusted identity
across builds; record the resolved artifact mapping and provenance.
Do not apply a library-package binding as if it uniquely identifies a business flow. Include an
explicit unlisted-areas/overflow row. Test dependency frameworks and test-fixture crypto.

Use a versioned per-run `evidence.json` sidecar for flow and handshake relationships. Link it
to the exact CBOM bytes by hash and run ID. Generate a random run nonce for each collector run
and embed it as a schema-valid CBOM property before serialization, only when enrichment is
enabled. Enrichment-disabled output keeps existing metadata behavior. Hash the exact buffer written. Preserve the ordinary CycloneDX 1.6 CBOM for current
consumers; do not invent non-schema fields. A future standardized relationship export is separate.

Core shutdown draining and CBOM serialization retain their existing budget and priority.
Freeze attribution with a bounded nonblocking handoff of the corresponding epoch; if a consistent
view cannot be captured immediately, mark enrichment incomplete rather than waiting. Derive
sidecar folding from the final CBOM folding decisions after the core output is published.
Sidecar construction/serialization uses a separate bounded time/memory/output budget and cannot
consume the core shutdown deadline. If the process ends first, the sidecar is missing/incomplete.

Publish each CBOM first and its evidence sidecar last using the existing safe atomic-write
pattern. A sidecar is trusted for association only if its schema, run identity, and CBOM hash
match. Missing/mismatched sidecars leave the ordinary CBOM usable but make enriched evidence
incomplete. Do not claim a multi-file atomic transaction. No previous-run sidecar may attach to
a new CBOM. Merging namespaces run-local IDs, deduplicates by (run nonce, CBOM hash), rejects identity/hash
conflicts, and never correlates connections across JVMs from timestamps or names.

The merged assessment records all input hashes, Census version, context revision/hash, full policy-profile hash (including mappings/thresholds), rule
version, workload/environment labels, and collection capabilities. Old CBOM-only input is
supported with “no flow/correlation evidence”; old evidence formats require explicit migration
or rejection. Reads use bounded bytes, depth, strings, collections, and retained-memory budgets.

Declarations discovered in loaded classes may appear without executed operations. Classes
never loaded are not inventoried by the agent. Catalog imports and saved declarations can
provide known-but-unobserved flows; discovering all source annotations is outside v1 scope.

## Declaration reconciliation

Preserve all sources: annotation, user report entry, imported catalog, and application default.
No source silently overwrites another. Effective values are resolved field by field:

1. An explicit reviewed resolution references the exact source revisions it reconciles.
2. Otherwise identical flow-specific values agree; differing values create a conflict.
3. An application default fills an undeclared field only as an application-level assumption.
4. Missing values stay unknown. Unknown is not public, zero lifetime, or low priority.

A new conflicting source revision invalidates an older resolution. Source revisions hash normalized
class/method/element declarations, not unrelated class bytecode. Context revisions are content
hashes, not counters. Maven embeds canonical base-context and per-field hashes/values in report data. The browser
copies these opaque base values verbatim and emits proposed edits; it does not implement Java’s
canonicalization or recompute authoritative hashes. Apply recomputes and validates hashes in
Java using the common schema fixtures. The page exports a change set containing its base-context hash, per-field
base values/hashes and proposed edits; it does not overwrite the authoritative context. A Maven
context-apply goal locks a target-specific lock file, reloads current context, validates schema,
merges disjoint edits by comparing base/current/proposed fields, reports all conflicts together,
and atomically writes only a conflict-free result. Deletion requires matching base values.
Concurrent writers use the same lock; lock failure leaves the file untouched. Imports are size
bounded and source/base values are untrusted declarations, not authorization. Manual overwrites
outside Census cannot be prevented; the guarantee applies only to Census-managed apply.
On first apply without an explicitly chosen destination, return an actionable error offering
a durable private path or an explicitly opted-in project path with ignore guidance. Never default
to `target/` or silently create a source-controlled context file. The guided report explains this
one-time location choice and supplies the invocation template; no JSON authoring is required.
The regenerated report can show the chosen path as a convenience, but a path inside imported
data never authorizes a write: the destination must come from explicit CLI/plugin configuration.

Reviewer/date are declarations, not authenticated
audit identity. Rename preserves IDs; deletion, retirement, and unobserved-in-this-run are distinct.

## Assessment policy

Introduce `census-assessment` for context validation/reconciliation, prioritization, and report
data. Keep this business policy separate from `census-core.Policy` algorithm classification.
Maven orchestrates assessment after collection; no business scoring runs in the application JVM.
Extend core’s canonical technical policy result with orthogonal, versioned quantum-exposure
facts (`vulnerable`, `resistant`, `not-established`) and observed primitive role. Derive those
facts from the same core algorithm/parameter rules, never a duplicate assessment catalog.
Existing Risk verdicts and defaults remain unchanged; RSA-1024 may be both broken and quantum
vulnerable. An acceptable TLS suite does not establish the exchange’s quantum exposure.
Assessment consumes these core facts and any capability-verified relationship assessments;
include the core-facts version in provenance and the assessment profile digest.

Use versioned explainable decision rules, with explicit organization-configurable thresholds.
Initial priority categories: `technical-remediation-first`, `urgent-planning`, `planned-migration`,
`needs-context`, and
`no-pqc-action-identified-in-observed-scope`. None of them implies overall safety.

Initial rule behavior:

- Existing broken/weak crypto remains visible as current technical remediation, separately from
  PQC priority. Business enrichment cannot suppress it.
- Classical key establishment or key protection in a specifically declared sensitive,
  long-confidentiality flow yields urgent migration planning when policy prerequisites are met.
  Missing connection correlation changes wording and confidence, not the underlying evidence.
- Signature uses consider declared authenticity lifetime, impact, and replacement dependencies.
  They are not universally lower priority than confidentiality findings.
- An unmapped classification, conflicting declarations, missing applicable lifetime, or missing
  purpose that cannot be established from the observed primitive produces `needs-context` for the affected business assessment. Preserve independently
  established technical findings and risk reasons rather than replacing them with unknown.
- A hybrid connection assessment requires directly associated negotiated-group evidence and
  completed-handshake evidence. Mere co-occurrence of ML-KEM and X25519 is insufficient.
- Absence of observations, lost attribution, or unsupported correlation must never yield a clean bill of
  health. Show coverage and assessment completeness separately from priority.

Readiness starts `unassessed`. Later user/import evidence can establish `blocked`, `candidate`,
or `validated-in-tested-scope`, with explicit prerequisites and provenance. Finding a PQC
algorithm installed or executed does not establish a migration replacement or interoperability.

The initial report is advisory. A later opt-in migration gate selects explicit rule IDs and
required evidence capabilities. It must distinguish failed policy from incomplete assessment.
It cannot weaken existing `check` health or algorithm gates.

### Deterministic policy contract

Policy profile v1 includes schemaVersion, sensitivity mapping, confidentiality/authenticity
thresholds, business-impact mapping, and rule-set version. The initial documented profile maps
`public=0`, `internal=1`, `sensitive=2`, `restricted=3`; custom names require explicit mapping.
It uses 5 years as a configurable planning threshold for either lifetime, sensitivity >=2 for
confidentiality, and declared high business impact or >=5-year lifetime for signature urgency.
These are product triage defaults, not standards or forecasts. No maximum-implied risk reduction
is inferred from shorter lifetimes. Purpose UNSPECIFIED does not block an unambiguous observed
primitive role: key agreement/KEM/key transport concern confidentiality; sign/verify concern
authenticity. Key generation alone and generic digest/MAC usage do not establish a protected-data
role. A conflicting declared purpose adds a context conflict rather than changing observed facts.

Evaluate each observed asset/path/active-flow combination using this ordered table. Technical
priority is an independent output: every broken/weak verdict establishes technical-remediation-first
regardless of row order, missing association, or declaration conflicts. The first applicable
row determines the business/PQC result; technical priority always remains visible and wins
the display rank when stronger. Row 3 below restates that independent technical rule.

| First applicable condition | Business result and reason |
| --- | --- |
| No observation, lost association, ambiguous application identity, or unknown technical verdict | needs-context: evidence missing/unknown; preserve any independent findings. |
| Conflicting/unmapped applicable declarations or contradictory purpose | needs-context: declaration conflict/unknown; preserve prior positive findings as unresolved, never mark resolved. |
| Broken/weak technical verdict | technical-remediation-first, above urgent-planning; attach the existing technical finding and independently evaluate applicable quantum-exposure reasons using core facts. Never request already-supplied context. |
| Quantum-vulnerable confidentiality primitive, flow-specific mapped sensitivity >=2, lifetime >= threshold | urgent-planning: long-lived sensitive confidentiality. |
| Quantum-vulnerable signature primitive, flow-specific high impact or authenticity lifetime >= threshold | urgent-planning: long-lived/high-impact authenticity. |
| Quantum-vulnerable use with complete applicable context below the urgency thresholds | planned-migration: classical public-key dependency. |
| Quantum-vulnerable use with missing applicable context, application-only assumption, or unclear role | needs-context: potential classical dependency; name the missing facts and retain technical verdict. |
| Quantum-safe or acceptable verdict, resistant core exposure fact, observed role and nonconflicting context | no-pqc-action-identified-in-observed-scope for this primitive only; key establishment, key protection and protocol dependencies remain separately unassessed. |
| Core exposure not-established, or any other combination | needs-context: quantum exposure not established or unsupported assessment combination. |

Quantum-vulnerable in the table means the orthogonal core quantum-exposure fact, not a
comparison against the final combined Risk enum. Technical remediation remains established
even when missing context blocks the business/PQC assessment; it overrides any lower display
priority while retaining incomplete flags. An acceptable symmetric primitive has no implied
public-key safety, and an acceptable TLS suite must use the separate composite rule, not row 8.

For confidentiality, complete context means mapped sensitivity and declared lifetime; for
signatures it means declared authenticity lifetime and impact. Positive urgency can be
established from the sufficient conditions above without declaring all lower-priority fields.
Missing dependencies prevent overall flow readiness even when a primitive requires no action.
TLS composite findings use a separate capability-gated rule: incomplete hybrid evidence yields
needs-context, and individual classical/PQC observations remain in the report. Verified hybrid
confidentiality never removes a classical authentication finding.

Aggregate positive flow priority as technical-remediation-first, then urgent-planning,
then planned-migration, then needs-context,
then no-action. Alongside it retain a separate incomplete/conflict flag and all reason codes;
known urgent findings cannot be erased by unknown peers. A no-action aggregate requires every
observed finding to be no-action and still says nothing about unobserved paths. Readiness is
independent: user-asserted states are labeled asserted; validated-in-tested-scope requires linked
validation evidence and cannot be established by a declaration alone.

Canonical JSON sorts object keys and semantically unordered arrays by stable IDs/source hashes;
flow paths remain ordered. Sort findings by explicit priority rank, subject ID, asset ref,
rule ID, canonical ordered flow path, effective occurrence, and a canonical-content hash
tiebreaker. Exact identical findings are deduplicated under a defined aggregation rule. Input permutations with identical evidence/context/profile produce byte-identical
canonical assessment data. Presentation generation timestamps are outside its digest.

## TLS correlation workstream

Create a provider/JDK-specific capability adapter behind a common bounded handshake collector.
Discover exact hooks in a feasibility spike; do not commit to an unverified private JDK method.
Start with the supported SunJSSE socket and engine paths; test other vendors before claiming
support. Unsupported providers continue ordinary inventory with correlation unavailable.

Track object identity plus handshake attempt, not only thread ID, timestamps, or session IDs.
Capture attempted/completed/failed/unknown outcome, selected protocol/suite/group, authentication
evidence, and resumption mode where observable. Separate offered algorithms from selected ones.
Session ticket creation is not a new completed handshake. Abandoned attempts expire as unknown.

Resumed sessions record provenance for the PSK when available and whether fresh key exchange
occurred; missing PSK lineage prevents an overall PQC confidentiality claim. Authentication and
confidentiality receive separate assessments. Certificate key type alone does not establish
the actual handshake signature, nor full trust-chain validation semantics.

A pooled connection can serve multiple flows. Annotated handshake execution identifies the
initiating execution context only. Claiming that a later request/flow used that connection needs
a separately verified request-to-connection adapter. HTTP/2 multiplexing and proxies require
their own evidence; do not use a hostname or enclosing scope as a substitute.

The engine feasibility gate explicitly exercises `getDelegatedTask()` execution on unrelated
executor threads. Group/algorithm association must come from engine/handshake-owned state or
verified object links; if that is unavailable, hybrid correlation is unavailable on that path.

Keep connection identities opaque and local. Do not export raw session IDs, tickets, secrets,
certificate identities, full URLs, or request data by default. Collect only reviewed structural
metadata. TLS correlation is independently releasable and not a prerequisite for useful
annotation-based assessment.

## Safety, performance, and report integrity

- Bound flow definitions, scope depth, attribution buckets, live connections, attempts, and
  output size. Use non-retaining object associations and explicit timeout/close cleanup.
- On scope overflow, attribute affected events to unknown/overflow, never a misleading parent.
  Exit restoration must remain correct even after failed entry instrumentation.
- Distinguish existing collection health from enrichment/correlation health. Optional enrichment
  failure does not change application outcomes or silently invalidate ordinary inventory.
- Correlation failure remains visible even if algorithm inventory succeeded. Requested migration
  gates fail as incomplete when their required evidence is missing.
- Measure allocation, throughput, startup, stack-walking, hot-method/JIT bytecode growth, and memory effects against agent-off
  and current-agent baselines. This does not remove the current tests/staging positioning.
- Render all imported/runtime strings as text. Escape embedded JSON against script termination;
  use a restrictive meta CSP (`default-src 'none'`, `connect-src 'none'`, generated script/style
  hashes, no unsafe-inline/eval) and no dynamic evaluation. Test hostile names and downloaded files.
- Enriched reports get the existing safe-write permissions and retention guidance. They are not
  uploaded by default; public issue templates must not solicit raw enriched artifacts. Support a
  private configurable output directory and warn in operations guidance that broad CI artifact
  globs such as `target/**` can include business metadata.
- Digests establish pairing and provenance references, not authenticity against a hostile JVM.
  Existing self-reported-evidence limitations remain explicit.

## Proposed module and file responsibilities

| Module | Change |
| --- | --- |
| `census-annotations` (new) | Small public annotation API and enums; no agent/bootstrap dependency. |
| `census-core` | Evidence DTOs, bounded aggregates/IO, run/asset references and canonical quantum-exposure/role facts; existing Risk verdicts and defaults unchanged. |
| `census-agent` | Annotated method discovery/scopes, evidence collection, capability health, later TLS adapters. |
| `census-assessment` (new) | Declaration reconciliation, versioned assessment rules, report model and offline HTML assets. |
| `census-maven-plugin` | Optional report configuration, per-run sidecar pairing/merge, assessment-only regeneration goal, future opt-in gates. |
| `census-demo` | Annotated and unannotated examples demonstrating missing/ambiguous context as well as useful findings. |

An assessment-only Maven goal regenerates reports from existing evidence and edited context,
without running application tests again. Exact goal/property names are proposals until the
implementation establishes plugin descriptors and executable documentation fixtures.

## Acceptance scenarios

1. An unmodified application produces a report; a reviewer supplies an application default,
   exports/imports context, and sees it reused with its broad scope clearly labeled.
2. An annotated synchronous flow attributes nested JCA calls, restores state after exceptions,
   handles recursion and virtual threads, and never contaminates a reused worker thread.
3. Two flows sharing one crypto helper retain separate attributed counts without duplicating
   the underlying inventory. Counts and lost-attribution diagnostics reconcile.
4. Overloads, duplicate IDs with agreeing/conflicting metadata, unsupported versions, custom
   classifications, invalid years, absent agent, and application classloaders are covered.
5. A renamed method with the same flow ID retains metadata; an inferred binding that no longer
   matches becomes stale. A missing workload does not imply a removed business flow.
6. Old CBOM input remains usable. Mismatched/stale/missing sidecars, multi-JVM merge conflicts,
   bounded parsing and overflow never create invented relationships or apparent completeness.
7. The offline UI survives hostile strings and imports; import/export preserves declarations
   and revisions, and unsaved edits are visible. Keyboard navigation and basic accessibility work.
8. Rules distinguish confidentiality from authenticity, preserve technical severity, expose
   conflicts and unknowns, and never equate frequency with protected-data volume or impact.
9. TLS tests cover overlapping successful/failed handshakes, hybrid and classical exchanges,
   socket/engine paths, resumption/tickets, timeouts, provider gaps, and bounded cleanup.
10. Existing CBOM schema, Maven behavior, supported runtime matrix, and quality gates remain
    intact. No new compliance, production-overhead, or complete-data-flow claim is introduced.

## Review and delivery

Three independent Claude Code review rounds used API-confirmed Opus 5.5 with high effort;
the second and third used the requested `claude-ds` function. The final review found no High
or Medium issues; its Low consistency edits are applied. See [review findings and dispositions](BUSINESS-CONTEXT-REVIEW.md).
Design artifacts do not authorize implementation, commits, publication, merging, or releases.

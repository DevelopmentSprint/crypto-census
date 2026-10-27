# Business-context implementation review

Status: implemented, corrected and verified on the local pre-develop integration branch; ready for human review. No release acceptance implied.

Claude Code reviewed commit `78ddf1e` using `claude-opus-5-5` with high effort.
The review received the accepted design and implementation through read-only tools.
It did not execute tests. The first-review findings and subsequent checkpoints are retained below as historical evidence.
The final delivery disposition appears at the end of this document.

| Finding | Disposition and implementation boundary |
| --- | --- |
| F1: Global application ID overrides declaring-artifact ownership | Accepted. Catalog ownership must survive an application override; unmapped code must not acquire the caller's identity. Standalone configuration retains its fallback only without a supplied catalog. Corrected; declaring-module Maven expressions and execution configuration resolve consistently across producer/consumer tests, with explicit source mappings last. |
| F2: Unrelated context conflicts suppress urgency | Accepted. Only declarations applicable to the observed protection role block prioritization. Unrelated conflicts and unmapped values remain visible and mark the result incomplete. Corrected with regressions for owner/opposite-lifetime conflicts and signature classification mapping. |
| F3: Assessment failure hides the existing check gate | Accepted. Evaluate core gate problems first; retain them as the primary build failure and attach assessment failure as secondary information. Corrected with a real-CBOM regression covering simultaneous gate/report failure and report-only failure. |
| F4: HTML size failure occurs after JSON publication | Accepted. Render and validate both output budgets before publishing either artifact. Independent filesystem failures still cannot provide a two-file transaction. Corrected; a valid context whose escaped HTML exceeds the budget preserves both prior outputs. |
| F5: Missing qualification for unmapped multiple deployments | Accepted with a narrower trigger. Explicitly mapped deployments may share a business subject and retain different declarations. Detect missing deployment ownership; do not combine declarations across deployment instances. Pending. |
| F6: Local scope overflow invalidates unrelated scopes | Accepted. Depth overflow now contributes a local attribution-loss diagnostic. A regression preserves earlier and subsequent independent scopes while retaining the lost observation; actual integrity/restoration failures retain global invalidation. |
| F7: Disappearing sources incorrectly make resolutions stale | Accepted with qualifications. Unchanged subsets of reviewed revisions must not appear changed. New or changed declaration hashes must still invalidate resolutions. Saved-only assertions remain in durable context; without any current sources the effective result is unknown. Corrected with subset, absent-source and changed-revision regressions. |
| F8: Invalid annotations lack actionable bounded diagnostics | Accepted. Bounded structural warnings identify class, method descriptor and reason through the JVM logger, without annotation values or exception text. Existing health counters retain loss; sink failures cannot escape. See [annotation diagnostics](ANNOTATION-DIAGNOSTICS.md). |
| F9: Missing destination silently loses unedited base answers | Accepted. Reject an absent destination when the change set has a nonempty base; instruct the user to select or restore the original context. Regression verifies no output and successful apply after restoration. |
| F10: Missing-sidecar and guided identity usability | Accepted and corrected in the guided workflow: expected missing sidecars have a distinct gap, only usable evidence offers exact owning-application bindings, and unknown answers are separate from explicit custom IDs. |

The independent drift review confirmed the F5 and F7 design boundaries above.
The Maven plugin reactor `verify` passed after the F2, F3, F4, F6 and F9
corrections, including core, assessment, annotations, agent runtime tests, plugin
integration fixtures and existing quality gates. Other acceptance evidence and the
follow-up Claude review remain outstanding.

The subsequent F1/F7 plugin reactor `verify` passed, including the XML-only
identity configuration exercised in producer, consumer and resolved-copy tests.
The source review found no blocking regression. F5 and the follow-up Claude
review remain open. F8 core/agent verification includes real invalid and missing-API
annotation diagnostics, payload exclusion and unchanged failure boundaries.

## Final candidate verification — 2026-10-27

Candidate `0e6a9014cdc12ae9d277cd439ada30eaa361e80b` incorporates all ten
accepted corrections and the declaring-module `pluginManagement` identity follow-up.
F5 now distinguishes generated build ownership from explicit deployment mappings:
unqualified ambiguous instances retain evidence and an actionable gap, while explicitly
mapped peers retain their own findings. Java and browser regressions exercise both cases.
The earlier pending statements above describe the first correction checkpoints.

The integration checks completed on this candidate:

- Clean seven-module Maven `install` with enrichment and assessment enabled on JDK 25,
  including the normal verification and coverage gates.
- All 18 guided-report browser tests and the real demo browser-to-Maven round trip,
  with no skips. Exported changes apply to durable context and reassess saved evidence
  without rerunning application tests.
- The demo assertion script verifies five flow priorities, shared-helper attribution,
  the async attribution gap, and paired JSON/HTML output. The passive Maven demo test
  reactor also passes with `census.skip=true`.
- Full agent reactor verification with the hybrid-capable JDK and
  `X25519MLKEM768`; a prior TLS-only invocation passed its selected tests but failed
  aggregate coverage, so the complete suite was rerun successfully without lowering gates.
- Four real application-server integration tests, with no skips, covering Liberty/Semeru 25
  and WildFly/Temurin 21. Component acceptance also includes JDK 17/21/25 and the
  unsupported BCJSSE provider boundary.
- Private-tracker regression checks: 41 checks across 24 disposable repositories.
  Inspection of the five production JARs found no tracker paths or generated assessment
  artifacts, and no Bouncy Castle classes in the agent JAR.

These checks support local advisory use in tests and staging. They do not establish
production readiness. [Enrichment measurements](ENRICHMENT-MEASUREMENTS.md) document
substantial overhead in scoped workloads and a stress run whose sidecar was not published;
the cause was not established. Scopes do not propagate across asynchronous work or prove
request-to-connection ownership. JSON and HTML publication uses separate atomic writes,
not a transaction spanning both files.

## Second implementation review

Claude Code completed a read-only review of `0e6a901` using `claude-opus-5-5`
with high effort. The response metadata confirms the requested model. It did not execute
tests; the verification results above were obtained separately. This review found additional
issues, so the candidate is not yet ready for final acceptance.

| Finding | Disposition |
| --- | --- |
| R1: Standalone bystander loaders create false deployment ambiguity | Accepted; correction and a real two-loader assessment regression in progress. |
| R2: Compatible purpose conflicts suppress urgency | Accepted. Role-consistent purposes remain visible as incomplete context without suppressing urgency; contradictory candidate purposes still require review. |
| R3: Applicable conflict loses independently established urgency | Accepted for sufficient nonconflicting fields. Retain the positive reason as unresolved while business priority remains needs-context. Inferring a new agreed value from conflicting lifetime alternatives is not required by the accepted rule table. |
| R4: Annotation diagnostics invalidate independent TLS evidence | Accepted; separate annotation diagnostics from TLS transform health. |
| R5: Guided resolution cannot review multiple deployment revisions together | Accepted; retain instance-specific evidence while reviewing the relevant source union. |
| R6: Ignored defaults invalidate specific resolutions | Accepted; only participating declarations determine whether a resolution is stale. |
| R7: Invalid timestamps bypass check failure precedence | Accepted; normalize validation failures and preserve the core gate result. |
| R8: A paired run plus its unpaired copy rejects assessment | Accepted subject to contract verification; deduplication must preserve validated evidence and reject genuinely conflicting sidecars. |
| R9: Operator catalog example omits explicit qualification | Accepted documentation correction. |
| R10: Assessment input budget is undocumented | Accepted documentation correction. |
| R11: Failed generation leaves previous report files | Retaining prior files on preflight failure is intentional. Deleting or marking them before validation can destroy prior valid output or an aliased input. Separate atomic writes are not a multi-file transaction. Document that failed generation does not make the previous report current; do not introduce destructive cleanup. |

A third broad review was not run: after raising review cost, the user authorized targeted
verification and handoff of the two final correction branches. The selected-group
HelloRetryRequest test-coverage concern and Maven session-clone concern are hypotheses,
not demonstrated defects; their validation status must remain explicit.

### Hypothesis checks

Installed JDK 17.0.18, 21.0.10 and 25.0.2 sources store the completed server share in
`SH_KEY_SHARE.serverShare`; retry requests use `HRR_KEY_SHARE`. The adapter reads the
former's `namedGroupId`, not the client's offered groups. No selected-versus-offered defect
was found in that path. A dedicated forced-HelloRetryRequest end-to-end scenario remains
untested; ordinary client/server and hybrid fixtures do not establish that coverage.

The installed Maven 3.9.5, 3.9.6, 3.9.10, 3.9.12 and 3.9.16 bytecode creates a separate
current-project `ThreadLocal` during session cloning. In the older supported minimum,
[Maven 3.6.3 source](https://raw.githubusercontent.com/apache/maven/maven-3.6.3/maven-core/src/main/java/org/apache/maven/execution/MavenSession.java)
uses a plain instance field and `super.clone()`, so assigning the clone's current project does
not replace the original session's field. This checks the proposed mutation mechanism;
it is not a claim of running the complete plugin suite on every Maven release.

## Final correction disposition

All corrections are merged into local candidate `adbd41a` on
`integration/census-dlz_business-context`. This is a pre-develop review branch, not a release.
The pending entries above describe earlier checkpoints; they are superseded as follows:

- F1–F10 have implemented corrections and regressions, including deployment qualification (F5).
- R1 and R4: commit `655d352` restricts deployment ambiguity to declaring-flow and project-frame
  identities, and separates optional annotation diagnostics from TLS transform health. Real
  shaded-agent tests exercise one versus two declaring loaders through assessment, and an
  invalid annotation beside two completed, qualified TLS confidentiality findings. These new
  runtime cases passed on JDK 17, 21 and 25; the complete JDK 25 reactor and normal commit
  gates passed. Existing sidecars without the optional counter remain readable.
- R2 and R3: commit `dcb84a8` keeps compatible-purpose conflicts visible without suppressing
  urgency and retains independently sufficient positive reasons as unresolved. The business
  result still requires review when an applicable declaration conflicts. Core and assessment
  verification and the normal commit gates passed.
- R5–R10: commit `4297e65` adds multi-instance source review, deciding-source freshness,
  controlled timestamp validation, order-independent paired/unpaired deduplication, and
  catalog/input-budget documentation. The full plugin reactor, all 24 consumer builds,
  19 browser tests and normal commit gates passed.
- R11: prior-output preservation remains intentional; the operator workflow now explains
  that a failed generation leaves the last successful report, not a newly successful assessment.

The drift guard reviewed the correction slices with no remaining source-review blockers.
Claude completed two read-only implementation reviews; **Claude has not re-reviewed these
last corrections**. The final result therefore claims verified corrections and documented
review dispositions, not a clean third Claude review. The R3/R11 interpretations and the
coverage limitations above remain visible for human review.

### Combined delivery verification

On `adbd41a`, the final clean seven-module Maven `install` passed with enrichment and
assessment enabled on JDK 25, including static analysis, coverage and consumer integration
fixtures. The 19 real Chrome guided-report tests and the actual demo browser-export →
Maven apply → reassess workflow passed with no skips. The demo assertion script confirmed
its five priorities, shared-helper counts, async gap and paired outputs. Two existing
agent integration tests remain environment-dependent skips; they are not claimed as passed.

The final private-tracker index check and whitespace check passed. Inspection of all five
production JARs found no known tracker paths or generated assessment artifacts; the agent
contains no Bouncy Castle implementation classes. No thresholds or hooks were bypassed.
Documentation-only closeout follows this tested product snapshot. Nothing was pushed,
merged into develop, or released. The retained integration checkout supplies the reviewable
code, workflow documentation and generated demo report.

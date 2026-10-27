# Business-context implementation review

Status: corrections in progress; not final acceptance.

Claude Code reviewed commit `78ddf1e` using `claude-opus-5-5` with high effort.
The review received the accepted design and implementation through read-only tools.
It did not execute tests. Findings below require regressions and a follow-up review
before integration is considered complete.

| Finding | Disposition and implementation boundary |
| --- | --- |
| F1: Global application ID overrides declaring-artifact ownership | Accepted. Catalog ownership must survive an application override; unmapped code must not acquire the caller's identity. Standalone configuration retains its fallback only without a supplied catalog. Corrected; declaring-module Maven expressions and execution configuration resolve consistently across producer/consumer tests, with explicit source mappings last. |
| F2: Unrelated context conflicts suppress urgency | Accepted. Only declarations applicable to the observed protection role block prioritization. Unrelated conflicts and unmapped values remain visible and mark the result incomplete. Corrected with regressions for owner/opposite-lifetime conflicts and signature classification mapping. |
| F3: Assessment failure hides the existing check gate | Accepted. Evaluate core gate problems first; retain them as the primary build failure and attach assessment failure as secondary information. Corrected with a real-CBOM regression covering simultaneous gate/report failure and report-only failure. |
| F4: HTML size failure occurs after JSON publication | Accepted. Render and validate both output budgets before publishing either artifact. Independent filesystem failures still cannot provide a two-file transaction. Corrected; a valid context whose escaped HTML exceeds the budget preserves both prior outputs. |
| F5: Missing qualification for unmapped multiple deployments | Accepted with a narrower trigger. Explicitly mapped deployments may share a business subject and retain different declarations. Detect missing deployment ownership; do not combine declarations across deployment instances. Pending. |
| F6: Local scope overflow invalidates unrelated scopes | Accepted. Depth overflow now contributes a local attribution-loss diagnostic. A regression preserves earlier and subsequent independent scopes while retaining the lost observation; actual integrity/restoration failures retain global invalidation. |
| F7: Disappearing sources incorrectly make resolutions stale | Accepted with qualifications. Unchanged subsets of reviewed revisions must not appear changed. New or changed declaration hashes must still invalidate resolutions. Saved-only assertions remain in durable context; without any current sources the effective result is unknown. Corrected with subset, absent-source and changed-revision regressions. |
| F8: Invalid annotations lack actionable bounded diagnostics | Requires implementation triage. Preserve limits and avoid exporting annotation payloads or sensitive runtime data. Pending. |
| F9: Missing destination silently loses unedited base answers | Accepted. Reject an absent destination when the change set has a nonempty base; instruct the user to select or restore the original context. Regression verifies no output and successful apply after restoration. |
| F10: Missing-sidecar and guided identity usability | Requires implementation triage. Distinguish requested-but-missing evidence, validate guided bindings against application ownership, and clarify unknown classification entry. Pending. |

The independent drift review confirmed the F5 and F7 design boundaries above.
The Maven plugin reactor `verify` passed after the F2, F3, F4, F6 and F9
corrections, including core, assessment, annotations, agent runtime tests, plugin
integration fixtures and existing quality gates. Other acceptance evidence and the
follow-up Claude review remain outstanding.

The subsequent F1/F7 plugin reactor `verify` passed, including the XML-only
identity configuration exercised in producer, consumer and resolved-copy tests.
The source review found no blocking regression. F5, F8, F10 and the follow-up
Claude review remain open.

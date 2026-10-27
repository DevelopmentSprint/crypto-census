# Offline business assessment

`AssessmentEngine.assess(inputs, context, profile)` validates saved run evidence and returns one
canonical `AssessmentReport`. `report.write(path)` writes JSON; `GuidedReport.write(report, path)`
writes a self-contained HTML review page. The instrumented application runs no business policy.

The report keeps current cryptographic weakness, PQC planning priority, declared readiness and
collection gaps separate. Business totals deduplicate event buckets; deployment views preserve
their run and source identity. Observed declarations apply only to the corresponding deployment.
Saved business context applies by stable application/flow ID. A discovered flow with no crypto
observations retains its requirements and an explicit missing-observation state.

The HTML opens locally, without a server or network access. Reviewers can filter by owner,
priority, technical verdict and evidence gap; inspect preserved sources; enter flow requirements
or broad application defaults; select exact project call-site bindings; and propose reviewed
resolutions against Java-provided source references. Counts represent observed entry points,
not protected bytes, transactions, or business impact. Very large counts are labeled rather
than presented as exact JavaScript numbers.

Edits are drafts. They mark the displayed findings stale and must be exported as a change set,
applied through the Maven context-apply goal to an explicitly chosen durable context path, and
reassessed from the same saved inputs. The browser copies base values/hashes and requests Java
revisions for new USER proposals; it does not compute risk or authoritative hashes. It never
writes the authoritative context directly. Imported context adds/updates proposed records;
missing records do not imply deletion. Explicit saved-record removal is separate from retirement.
Conflicting local draft imports leave the draft unchanged.

Readiness is a user/import assertion. A claimed validated state remains effectively unassessed
when supporting evidence has not been verified. Neither algorithm presence nor the absence of
observed weaknesses establishes migration readiness or complete application coverage.

Report strings render as text, with HTML-sensitive JSON characters escaped and bundled script
and stylesheet hashes enforced by a restrictive CSP. Imports are bounded, then validated again
by Java on apply. The local report contains business metadata: store it privately, choose any
project context location explicitly and ignore it, and avoid broad CI artifact uploads.

[Browser checks](src/test/browser/README.md) verify editing, downloads, hostile content and
Java reassessment in an actual browser. Maven unit tests cover rendering and canonical policy.

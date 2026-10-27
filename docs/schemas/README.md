# Business-context wire contracts

The Java codecs in `census-core` own serialization and validation. These v1 schemas describe
JSON structure for tooling. The codecs additionally enforce referential integrity, field-specific
values, aggregate memory budgets, stable identity, and evidence/CBOM pairing. Schema validation
alone does not establish valid evidence or readiness.

Canonical output sorts object keys and unordered declaration, observation, identity, capability,
and handshake arrays by their canonical JSON representation. Scope paths retain order and recursion.
Only integral JSON numbers are accepted; no strings-to-numbers coercion occurs. Files are limited
to 8 MiB and nesting to 32 levels. Unknown fields or versions are rejected.

Every evidence document carries all health counters, including zero counters. The run UUID must
match `cryptocensus:runId` in the paired CBOM, and `cbomHash` is SHA-256 of its exact bytes. Hashes
provide pairing and revision checks, not authentication. Healthy evidence conserves each effective
CBOM occurrence count. Saturation requires a diagnostic; drain or conservation uncertainty is
explicit and must appear in downstream reports.

Source catalogs contain local build paths and remain local configuration. Evidence exports stable
artifact identifiers and opaque run-local loader/deployment identities instead of those paths.
A directory mapping requires an explicit trailing slash; other mappings are exact file matches.
Unmapped sources remain unresolved. Catalog entries assert ownership; they do not authenticate it.

`census-core/src/test/resources/context/unobserved-evidence-v1.json` is a synthetic declaration-only
fixture. Its hash is illustrative and deliberately does not claim to pair with an existing CBOM.
The executable contract tests cover valid pairing, swapped occurrence counts, saturation, stale
nonce/hash, missing counters, malformed UTF-8, dangling references, cyclic PSK lineage, immutable
snapshots, bounded input, and atomic file IO.

Durable context and change-set schemas live in `census-assessment`. A change set carries its
complete base context, canonical context hash, and base values/hashes for individual edit keys.
Java recomputes these values before applying any edit. Declarations are addressed separately by
subject, protection field, source kind and source ID; source revision and value changes preserve
that address. Flow metadata, reviewed resolutions and readiness assertions use atomic record
edits, so concurrent edits to the same record require review rather than losing either change.

Reviewed resolutions preserve both the asserted source revision and a Java-computed hash of each
complete declaration. A replacement that reuses an asserted revision still invalidates the old
resolution. The browser copies these hashes from report data instead of computing authoritative
revisions. An asserted validated-readiness state does not establish effective readiness; linked
validation evidence must be checked independently by the assessment engine.

Managed apply requires a destination explicitly selected by the caller. It uses a persistent
`.census.lock` beside the destination, reloads under that lock, collects all conflicting edits,
and writes only a conflict-free result by atomic replacement. Symlink targets are rejected.
Manual writers outside this locking protocol are not covered by its concurrency guarantee.

Assessment input loading preserves ordinary CBOMs when sidecars are missing or invalid and marks
the associated evidence gap. Reused run identities with different CBOM or sidecar hashes are
rejected. Duplicate exact pairs merge workload/environment labels without duplicating counts.

Attribution rows retain the run, applicable source identity and an event-bucket hash. Equivalent
observation buckets are summed before path expansion. Recursive references to the same source
identity and subject produce one inclusive row; a distinct deployment identity remains a separate
row. Business-flow totals must deduplicate by event bucket and subject, while deployment views
retain the source identity. Only the active scope reference receives direct attribution. Scope
integrity or restoration failures invalidate all scoped associations, including earlier snapshots.
Unreconciled counts produce an unknown-count diagnostic bucket, never an invented missing delta.

Guided change proposals may request Java-derived creation identities with an empty edit `id`,
only when `base` is null and `proposed` is present. USER declaration and readiness proposals may
use an empty source revision. Java validates and canonicalizes the typed proposal before computing
its revision; other source kinds and all saved/base documents still require complete revisions.
The browser copies existing edit identities and base hashes rather than recomputing them.

Report reconciliation applies observed annotations only to their exact run and source identity.
Saved business declarations remain keyed by stable application and flow. Business summaries show
`savedContextFields`; each deployment instance shows its own reconciled `fields`. Priority aggregates
existing findings without borrowing a different deployment's requirements. Readiness gaps contribute
to `readinessIncomplete` and overall incompleteness independently of cryptographic priority.

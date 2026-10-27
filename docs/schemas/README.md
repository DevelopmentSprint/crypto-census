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

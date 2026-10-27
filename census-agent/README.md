# Optional business-context evidence

The ordinary CBOM collector works without business-context enrichment. To also collect
annotated execution scopes and an evidence sidecar, use:

```sh
java -javaagent:census-agent.jar=enrichment=true,applicationId=example:billing,output=cbom.json -jar app.jar
```

Keep `census-annotations` on the application's runtime classpath. Missing annotation types,
unsupported versions/defaults, invalid declarations and unsupported annotated methods produce
enrichment diagnostics. They do not disable ordinary inventory collection. The agent reads
annotation descriptors from the actual JVM class bytes before resolving metadata, so a missing
annotation dependency cannot silently erase discovery.

| Option | Meaning |
| --- | --- |
| `enrichment` | Opt in to context evidence; defaults to `false`. |
| `applicationId` | Explicit stable product identity. The existing `app` display name never supplies it. |
| `sourcesFile` | A schema-v1 source catalog mapping declaring code sources to artifact, application, deployment and main/test/library origin. |
| `evidenceOutput` | Sidecar path. By default, `cbom.json` produces `cbom.evidence.json`. |
| `maxScopeDepth` | Retained synchronous path depth, from 1 through 32; defaults to 32. |
| `tlsCorrelation` | Opt in to object-owned TLS attempt evidence; requires `enrichment=true`. |

Option values use the same percent escaping as existing agent options. A source catalog is
local configuration, not a network lookup. With a source catalog, unmapped sources remain
unresolved. Without one, an explicit application identity supplies standalone product identity;
it does not invent a declaring artifact, project origin, or deployment name. Separate classloaders
retain distinct run-local identities. Neither source paths nor classloader objects are exported.

Source mappings distinguish `EXPLICIT_DEPLOYMENT`, `GENERATED_BUILD` and `UNSPECIFIED` ownership.
Old serialized catalogs without qualification are conservative (`UNSPECIFIED`). Multiple loaders
sharing a product identity need explicit deployment mappings before their business context can
be trusted. Generated Maven artifact labels alone do not establish separate runtime deployments.

## What a scope establishes

An annotation associates operations with the synchronous execution of its method. Nested
methods preserve the complete bounded parent path, including outer confidentiality and
authenticity requirements. Each retained cryptographic event still has exactly one direct
attribution bucket. Parent relationships do not multiply event counts.

Scopes do not propagate to executors, futures or other threads. Inline callbacks can execute
within the current scope even when they concern different data. A scope therefore describes
execution context; it is not evidence that a particular record was protected. Main/test project
frames come from configured source ownership independently of a flow's declaring library.
Unannotated observations retain a resolved application identity when their source mapping
establishes one, allowing observe-first binding workflows.

The application loader must resolve the installed bootstrap bridge, not a private class with the
same name. A loader that hides that bridge is diagnosed and skipped before calls are emitted.
Server loaders need explicit delegation for `com.developmentsprint.cryptocensus.agent.FlowBridge`
when they do not normally delegate to bootstrap. Named-module readability is handled separately.

Overflow makes attribution unknown until the outermost tracked scope exits. A failed entry
does not pop its parent. Detected restoration or generation failures invalidate the run's scope
claims, including prior observations. Resource exhaustion can prevent cleanup and diagnostics
themselves from executing; absence of a diagnostic does not prove intact scope attribution.

## Canonical capabilities

The sidecar reports these capability names, separately from ordinary CBOM health:

| Feature | Meaning |
| --- | --- |
| `annotation-scopes` | Whether the synchronous instrumentation path is supported. `SUPPORTED` never means complete application or unloaded-code coverage. |
| `scope-propagation` | Always qualified as synchronous-only; off-thread work may be missing and inline work is noncausal. |
| `scope-resource-failure` | The residual cleanup/diagnostic limitation under JVM resource exhaustion. |
| `project-origin` | Whether configured source ownership establishes the observed main/test project frames. Unknown origins remain explicit. |
| `tls-correlation` | Aggregate TLS adapter availability, qualified by the protocol and lineage subcapabilities. |

See [TLS correlation](../docs/TLS-CORRELATION.md) for protocol-specific evidence boundaries.

## Publication and count integrity

The canonical inventory decides whether an event is retained, folded or dropped. Evidence uses
that exact outcome, then applies the CBOM writer's occurrence folding to the same drained
snapshot. Dropped core events never create dangling evidence references. A bounded attribution
bucket loss marks conservation unverified; it does not alter core counts or core health.

The core CBOM is written first. A daemon evidence worker then gets a separate 500 ms waiting
budget. It checks cancellation and its deadline before atomic publication. Filesystem I/O and
atomic rename cannot be forcibly bounded; a delayed daemon may finish while other shutdown
hooks are still running. Every sidecar, including a late one, must match both the exact CBOM
hash and its run nonce. A timeout or failure leaves independent diagnostics and may leave no
sidecar. It never reverses a completed CBOM write. Canonical target checks reject evidence
paths that alias the CBOM through parent-directory symlinks.

The primitive balancing values use a 48-bit process-wide generation and a 16-bit nesting depth.
Only zero means no entry; negative opaque values remain valid. Generation exhaustion after
281,474,976,710,655 enabled entry attempts, or nesting beyond 65,535, fails closed with an integrity
diagnostic. The retained path is still capped at 32 frames.

The metadata registries, observation buckets, paths and health counts are bounded. Health counts
saturate rather than wrap. Observer-owned declaration hashing is narrowly suppressed from the
ordinary recorder, and its prior state is restored even after failure; application cryptography
is not suppressed while annotation classes or application callbacks run.

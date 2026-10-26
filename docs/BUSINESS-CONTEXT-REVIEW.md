# Independent design review

Date: 2026-10-26.
Scope: [design](BUSINESS-CONTEXT-DESIGN.md) and [implementation plan](BUSINESS-CONTEXT-IMPLEMENTATION-PLAN.md).
Reviewer: Claude Code, API-reported model `claude-opus-5-5`, requested effort `high`.
Method: independent supplied-file review with tools disabled, no author self-assessment or
test-success claims. The first invocation covered the design and selected existing source;
the second covers the revised design and implementation plan. No implementation was reviewed.

## Round 1

The reviewer returned seven High findings, seven Medium findings, and seven Low observations.
All are incorporated or given explicit implementation acceptance gates below. These dispositions
are the author's response; round 2 confirmed closure or identified specific residuals below.

| ID | Reviewer concern | Design response and implementation consequence |
| --- | --- | --- |
| H1 | Optional annotation instrumentation can poison existing health or roll back the core transformer. | Separate transformer, listener, registry and health namespace. Add install/transform failure-isolation fixtures. |
| H2 | Application classloaders may not see bootstrap bridge types; suppression alone is insufficient. | Primitive-only advice boundary; verified loader visibility or explicit delegation configuration; skip unsupported loaders with diagnostics; annotated Liberty/WildFly fixtures. |
| H3 | Suppressed entry and failed exit can corrupt or leak scope. | Zero/no-op balancing token, bounded state/generation, fallible work before commit, restoration before diagnostics, invalidation on mismatch and fault-injection tests. |
| H4 | Independent counters diverge after asset/site drops, writer folding, saturation or shutdown timeout. | Derive attribution from inventory outcomes, apply shared folding/snapshot rules, distinguish lost/no-scope, and report uncertainty instead of asserting conservation. |
| H5 | An inner helper can hide outer requirements; unweighted parent links cannot produce inclusive counts. | Store bounded path-specific aggregates; assess each active flow separately and preserve highest established priority; inclusive counts derive from paths without recursive duplication. |
| H6 | Static-page whole-file export cannot prevent concurrent lost updates. | Export hash-based change sets; locked, validated three-way apply through Maven; explicitly limit guarantees to Census-managed writes. |
| H7 | First application frames often identify libraries rather than business code. | Capture nearest project frame with main/test origin from configured output roots; retain original technical attribution and unresolved/overflow groups. |
| M1 | Stable application/deployment identity is unspecified. | Maven coordinates proposed and persisted; deployment overrides and provenance; ambiguous multi-deployment identity cannot be merged silently. |
| M2 | Shading, missing annotation types, defaults/version discovery and scanning cost are unresolved. | Descriptor prefilter; relocation-safe names; explicit annotation-type API marker; diagnosed unresolved types; shaded-JAR and startup gates. |
| M3 | Per-method binding registries can grow during redeploy/retransform. | Bounded, normalized, idempotent primitive/string registry without classloader retention; explicit overflow behavior. |
| M4 | Synchronous scope may include unrelated inline callbacks/helped tasks. | Label dynamic execution context rather than causality; document/test inline work and off-thread gaps; adapters remain later work. |
| M5 | Identical consecutive CBOM bytes permit stale pairing or accidental deduplication. | Fresh run nonce inside schema-valid CBOM properties; hash exact written bytes; deduplicate by nonce plus hash. |
| M6 | Priority rules, prerequisites, profiles and aggregation are not deterministic. | Ordered total rule table, declared configurable thresholds, role inference only from observed primitive, full profile hash, explicit aggregation/completeness and canonical ordering. |
| M7 | SSLEngine delegated tasks can break thread-based correlation. | Engine-owned evidence or verified object links are a feasibility gate; otherwise correlation is unavailable. |
| L1–L3 | API ergonomics, ID canonicalization and revision stability. | Add undeclared constant/module name, bounded lowercase ASCII IDs and normalized declaration hashes. |
| L4–L7 | Offline hardening, readiness provenance, folded sites and bytecode overhead. | Hashed CSP and explicit change sets; configurable private output; assertion/evidence distinction; unlisted-area rows; annotated hot-method/JIT measurements. |

For H5, the design preserves each active flow's assessment instead of merging custom
classifications into one synthetic value. This retains provenance and avoids inventing an
ordering for organization-defined labels while preventing a weaker inner declaration from
reducing an established outer-flow priority.

## Round 2

Executed through the user’s `claude-ds` shell function with API-reported model
`claude-opus-5-5` and requested effort `high`. The reviewer reported **no remaining High issues**
and eight Medium issues. The following corrections were incorporated:

| ID | Remaining issue | Correction |
| --- | --- | --- |
| M-A | Shared recording/output failures can still affect core collection. | Guard attribution separately after core recording; preserve core shutdown deadline; sidecar has its own budget; nonce emitted only with enrichment enabled. |
| M-B | Broken/weak crypto can be demoted to needs-context. | Add technical-remediation-first above urgent-planning; retain independent PQC reasons and existing technical findings. |
| M-C | Business assessment could duplicate algorithm classification. | Extend canonical core facts with orthogonal versioned quantum exposure and observed role; assessment consumes them. |
| M-D | Directory-only ownership and executing-module identity break packaged/reactor cases. | Include packaged/reactor artifact maps; default identity derives from declaring artifact or explicit product mapping; keep deployment evidence separate. |
| M-E | No safe default destination for saved business context. | Guided explicit choice of a durable private or opted-in project path; never default under target; imported paths do not authorize writes. |
| M-F | Finding ordering does not distinguish different paths/occurrences. | Add canonical flow path, effective occurrence and content-hash tiebreaker; permutation fixtures. |
| M-G | Browser hashing could diverge from Java canonicalization. | Embed base hashes/values in report data, copy verbatim in browser, recompute and validate only in Java. |
| M-H | Recoverable resource errors can prevent exit cleanup. | Constant-stack restore, detected-error invalidation, whole-run scope-unverified status on later mismatch, stack-exhaustion fixtures, explicit undetectable-failure limitation. |

The reviewer’s M-C example suggested RSA-2048 might have an acceptable final verdict. Existing
`POLICY.md` classifies it as quantum-vulnerable. The adopted correction still matters: a broken
RSA use also has quantum exposure, while an acceptable TLS suite says nothing by itself about
the exchange. The plan uses those actual cases rather than changing existing risk semantics.

The Low API observation was addressed by adding the infrastructure marker to the API table
and clarifying that automatic modules implicitly export their packages.

## Round 3

Executed through `claude-ds`, API-confirmed `claude-opus-5-5`, with requested effort `high`.
The reviewer concluded: **“No High or Medium issues remain.”** All M-A through M-H were
addressed in substance. Three Low consistency edits were requested and applied:

- Make technical-remediation-first independent of decision-table ordering and missing context.
- Update the module table to name the added canonical core exposure/role facts while preserving
  existing Risk verdicts and defaults.
- Route not-established quantum exposure to needs-context rather than a no-action result.

The review history is now reflected in the design and plan. These Low edits were checked
locally; no fourth independent review was run. The interrupted plain-`claude` second invocation
produced no review and is not counted as a completed round.

Residual implementation experiments include restoration under stack exhaustion, shutdown epoch
handoff, hot-path overhead, shaded-artifact identity, annotation relocation, app-server bridge
visibility, offline CSP behavior across browsers, cross-platform context locking, and TLS hooks.
The design explicitly requires mappings for original identity in known shaded/uber artifacts.
These experiments are implementation gates, not claims of verified runtime behavior.

## Verification limits

These are design reviews, not runtime or security certifications. Implementation must prove
classloader visibility, advice restoration, snapshot conservation, bounded performance, browser
behavior and TLS hooks through the gates in the plan. Existing collection coverage remains
limited to exercised paths. Business requirements and reviewer identity remain declarations.

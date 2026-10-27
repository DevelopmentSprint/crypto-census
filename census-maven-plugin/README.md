# Business context in Maven

Existing `prepare-agent` and `check` behavior remains the default. Enable context evidence with
`-Dcensus.enrichment=true`; each fork writes a paired `.evidence.json` beside its CBOM in the
current run directory. `check` continues merging only CBOMs. Add
`-Dcensus.tlsCorrelation=true` to request the supported TLS correlation adapter as well.

The plugin generates a bounded `sources.json` for the current reactor: main classes and packaged
artifacts retain their declaring module's `groupId:artifactId`, test classes have `TEST` origin,
and external dependencies have `LIBRARY` origin. A shared reactor library keeps its own identity
when called by consumer tests. The artifact's version is not part of its stable identity.
The generated `maven:groupId:artifactId` deployment label describes the build, not a production
deployment. Unmapped locations remain unknown.

An explicit `-Dcensus.applicationId=billing` asserts a stable product identity. It does not turn
the Maven display name into an identity or change declaring-artifact ownership. For deployment,
loader or packaged-artifact overrides, supply `-Dcensus.sourcesFile=/private/sources.json` using
the shared source-catalog schema. Explicit entries replace the same generated location/selector.
Generated entries carry `GENERATED_BUILD` qualification; supplied overrides carry
`EXPLICIT_DEPLOYMENT`. Generated `maven:` deployment labels alone do not establish runtime
deployment ownership.
The plugin does not infer original module ownership from classes inside a shaded JAR. When a
module configures the shade or assembly plugin, its packaged outputs require an explicit physical
artifact mapping; generated main/test directory mappings still describe ordinary class output.

## Apply an exported context proposal

The report exports a change set. Applying it requires an explicit durable destination:

```sh
./mvnw census:context-apply \
  -Dcensus.changesFile=/private/review-changes.json \
  -Dcensus.contextFile=/private/billing-context.json
```

Choose a private file outside `target/`, or intentionally opt a project file into your team's
review process. There is no destination default, and a path inside an imported proposal never
authorizes a write. Context files may contain business information; they are separate from build
artifacts.

Java validates the full proposal and its base revisions before applying it under the shared
context-store lock. Concurrent edits to the same fields fail without partially writing any
changes. Reload the current context in the report, review the conflict, and export a revised
proposal. Reapplying the same accepted proposal is a no-op. A context edit requires a fresh
assessment before findings reflect the change.

## Assess saved evidence

Use `-Dcensus.assessment=true` with the existing `check` execution to generate
`target/cryptocensus/assessment/assessment.json` and `assessment.html` for that build's exact run.
The self-contained HTML embeds the same canonical result, works offline without external
resources, and exports review proposals for `context-apply`; draft edits do not recompute risk. This is independent
of enabling enrichment: old CBOM-only runs still yield technical findings with explicit context
and collection gaps.

After reviewing context, regenerate findings directly from a saved run directory:

```sh
./mvnw census:assess \
  -Dcensus.inputDir=/saved/census-run \
  -Dcensus.contextFile=/private/billing-context.json
```

This goal has no lifecycle binding and invokes no application tests. It does not guess a previous
run or recursively search `target/`. The selected directory contains original `cbom-*.json` files
and optional sibling `*.evidence.json` files, rather than only the merged `cbom.json`. Configure
`<inputDirectories><inputDirectory>...</inputDirectory></inputDirectories>` for several saved
module or workload directories. For manually named standalone or older CBOMs, use
`-Dcensus.inputFile=/saved/manual-output.json` or configure `<inputFiles>` explicitly. These files
use the same size limits and canonical validation. Their default sibling sidecar replaces a final
`.json` with `.evidence.json`, or appends `.evidence.json` when there is no `.json` suffix. Selecting
a merged CBOM never reconstructs individual run identities or missing relationships. Duplicate run evidence is counted once by the canonical loader;
inconsistent reuse of a run identity is rejected. Invalid sidecar relationships never establish
flow or handshake evidence. An unreadable optional sidecar retains the valid CBOM and reports
`SIDECAR_UNREADABLE`, with no fabricated sidecar hash.

Optional parameters are `census.policyFile`, `census.workload`, `census.environment`, and
`census.assessmentOutputDirectory`. Workload and environment labels describe evidence, not
stable product identities. Policy files use the versioned Java policy-profile schema; omitting
a profile selects the documented product triage defaults. Omitting context produces honest
missing-context findings; explicitly naming a missing or malformed context file fails instead
of silently ignoring it. Both output targets are checked before publication and cannot replace an existing context,
policy, or explicitly selected evidence input, including aliases. Each output is atomically
replaced; a publication error fails the goal and the two files are not a cross-file transaction.

### Assessment input budget

Each assessment accepts at most **64 selected CBOM file paths**, with up to **64 MiB total
CBOM plus sidecar bytes**. Sidecars do not count as separate CBOM inputs. Repeated selection
of the same physical path is collapsed, but copied paths count against the file budget before
logical run deduplication. The same limits apply to `check -Dcensus.assessment=true` and
`assess`; a module with more than 64 reporting forks (for example `reuseForks=false`) can
exceed the limit. An assessment failure fails the requested goal even when cryptographic gates
pass; if gates also fail, their failure remains primary.

Use bounded, explicitly selected workload groups for separate reports and retain their coverage
labels. Do not silently omit forks or present a partial selection as complete. This limit does
not change ordinary CBOM collection/check behavior when assessment is disabled.

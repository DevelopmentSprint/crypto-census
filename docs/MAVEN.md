# Maven reference

Configure `com.developmentsprint:census-maven-plugin` under `<build><plugins>`. The [README quickstart](../README.md#quick-start) uses defaults so command-line properties work. An explicit XML value such as `<failOn>broken</failOn>` takes precedence over `-Dcensus.failOn=...`; use defaults or a POM property when you need command-line overrides.

The lifecycle goals are thread-safe and operate per Maven project. `prepare-agent` defaults to `initialize`; `check` defaults to `verify`. Run the lifecycle through `verify` so preparation, forked tests and checking share the same Maven session. Directly invoking `check` in a later Maven process cannot authenticate the previous run directory.

## prepare-agent

| XML parameter | User property | Default | Behavior |
|---|---|---|---|
| `destDir` | `census.destDir` | `${project.build.directory}/cryptocensus` | Parent of unique per-build run directories. |
| `propertyName` | `census.propertyName` | `argLine` | Project property to append the agent option to. Use late binding when composing a custom Surefire/Failsafe `argLine`. |
| `tls` | `census.tls` | `true` | Observe JSSE sessions. |
| `maxAssets` | `census.maxAssets` | `500` | Distinct assets per JVM, integer 1–2000. |
| `maxSites` | `census.maxSites` | `50` | Call sites retained per asset, integer 1–100. |
| `debug` | `census.debug` | `false` | Log instrumentation errors from the agent. |
| `skip` | `census.skip` | `false` | Skip preparation and, with the shared property, checking. |

Collection maxima are count ceilings, not a promise that arbitrarily long asset names and metadata fit the output budget; an unrepresentable report fails explicitly even after attribution is folded. Invalid collection limits fail configuration. The plugin percent-encodes agent options, sets `app` to the project's artifact ID, and disables per-JVM summaries because `check` prints the merged findings. `cryptocensus.runDir` is informational; changing it cannot substitute an evidence directory. `experimental` is a standalone agent option, not a Maven plugin parameter.

## check

| XML parameter | User property | Default | Behavior |
|---|---|---|---|
| `output` | `census.output` | `${project.build.directory}/cryptocensus/cbom.json` | Merged CBOM destination. |
| `failOn` | `census.failOn` | `broken` | Lowest failing severity: `broken`, `weak`, `quantum-vulnerable`, or `none`. |
| `failOnUnknown` | `census.failOnUnknown` | `true` | Fail on assets whose safety cannot be established. Independent of `failOn`. |
| `failOnMissingData` | `census.failOnMissingData` | `true` | Fail when no evidence exists and the build cannot establish that no tests ran. |
| `failOnIncompleteCollection` | `census.failOnIncompleteCollection` | `true` | Fail on instrumentation/recording errors, dropped observations or calls in flight at shutdown. |
| `failOnMissingTargets` | `census.failOnMissingTargets` | `false` | Turn instrumentation-gap warnings into failures. |
| `allow` | None; XML list | Empty | Case-insensitive exact matches against canonical algorithm or display name; accept matching assets regardless of risk. |
| `ignoreJdkInternal` | `census.ignoreJdkInternal` | `false` | Ignore only assets whose every retained occurrence is demonstrably JDK-only. Overflow attribution does not qualify. |
| `testReportDirectories` | None; XML list | `${project.build.directory}/surefire-reports`, `${project.build.directory}/failsafe-reports` | Report directories used to determine whether tests ran in this build. Set this when customizing test report destinations. |
| `skip` | `census.skip` | `false` | Skip checking. |

`failOn=none` disables severity failures, but unknowns and evidence failures remain enabled. For an explicit risk-report-only run, use `-Dcensus.failOn=none -Dcensus.failOnUnknown=false`; collection validation still protects the integrity of the report. `allow` and `ignoreJdkInternal` affect policy enforcement, not whether collection health is acceptable.

The check reads at most 10,000 input files and 512 MiB total; each file also passes the [parser budgets](OPERATIONS.md#output-budgets). Merging retains at most 5,000 assets and 200 sites per asset, with dropped assets reported as incomplete and lost attribution reported as a warning.

## Reviewed exceptions

Place an approved exception in the Census plugin configuration, with an owner, rationale, and review date in your own project. This example permits the canonical SHA-1 digest name and does not constitute a general recommendation to permit SHA-1:

```xml
<configuration>
  <allow>
    <algorithm>SHA-1</algorithm>
  </allow>
</configuration>
```

An exception can also match a raw signature's canonical name, such as `NONEwithECDSA`, after you verify the external hashing. Exceptions are not scoped to individual call sites and do not expire automatically. Prefer a narrow display-name match when appropriate and remove exceptions as you migrate.

## Custom report directories

If you move Surefire or Failsafe reports, list both destinations in `check` configuration:

```xml
<configuration>
  <testReportDirectories>
    <testReportDirectory>${project.build.directory}/unit-reports</testReportDirectory>
    <testReportDirectory>${project.build.directory}/integration-reports</testReportDirectory>
  </testReportDirectories>
</configuration>
```

## What check guarantees

`check` fails the build when any of these is true:

1. an observed asset is at or above `failOn`, or is `unknown` (with the default `failOnUnknown=true`);
2. an input in this build's run directory is not valid agent evidence: wrong schema, a missing,
   non-numeric or inconsistent health counter, a component without Crypto Census properties, or
   standard CycloneDX fields that contradict them;
3. the agent reported instrumentation errors, recording failures, dropped assets, or calls still
   running at shutdown (`failOnIncompleteCollection`);
4. there is no evidence at all and it cannot be *established* that no tests ran
   (`failOnMissingData`). Established means: no compiled tests, or an explicit `skipTests` /
   `maven.test.skip` with no test report written during this build. `skipITs` alone does not count
   (it skips Failsafe, not Surefire), and silence does not count either, so `forkCount=0` with
   reports disabled still fails.

The run directory is bound to the current build (Maven project context), so neither a POM property
nor evidence from an earlier build can satisfy `check`. Running `prepare-agent` again before Failsafe
keeps the unit-test results.

`check` also reports what became of the JDK classes the agent always instruments (`Cipher`,
`Signature`, ..., `sun.security.ssl.SSLSessionImpl`), merged over all test JVMs:

- **never loaded** by any test JVM (for example no test used TLS): logged as information;
- **instrumentation gap**: the class does not exist in this JDK (renamed or removed), was loaded but
  never transformed, or was transformed but none of the expected methods exist. Uses of it were not
  observed. A test JVM whose CBOM carries no target report at all (an older agent, or one that could
  not determine its targets) is a gap too: its coverage is unknown. This is a **warning** by default, because an unfamiliar JDK build can legitimately lack a
  JDK-internal class such as `SSLSessionImpl`; set `failOnMissingTargets=true` to fail instead. (A
  target that *failed* to transform also counts as an instrumentation error, which fails the build
  through `failOnIncompleteCollection`.)

Not detected (by design or not yet):

- one test JVM with the agent cannot vouch for a *different* execution that never had it (for
  example a Failsafe execution with its own hard-coded `argLine`) when the other produced evidence;
- tests run with reporting disabled *and* an explicit skip property set;
- code paths your tests never exercise;
- hostile code inside the observed JVM (see [safety and limitations](SAFETY.md)).

## Optional assessment and managed context

`prepare-agent` accepts `census.enrichment`, `census.tlsCorrelation`, `census.applicationId` and
`census.sourcesFile`. `check` optionally generates advisory output with `census.assessment=true`.
`assess` reads explicitly selected saved runs/files without invoking a test lifecycle;
`context-apply` applies exported proposals to an explicitly selected durable context destination.
Neither goal weakens existing `check` policy or collection-health failures.

The [plugin parameter reference](../census-maven-plugin/README.md#assess-saved-evidence)
contains the executable configuration, multiple-input options and source-catalog rules. The
[guided workflow](ADVISORY-WORKFLOW.md) explains the reviewer steps. Migration enforcement gates
are future work; the current report is advisory.

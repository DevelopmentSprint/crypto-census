# Operating Crypto Census

Exercise representative test or staging workloads, shut down gracefully, then collect the CBOM. The agent does not periodically flush and cannot produce a report after `SIGKILL`, a container kill, or a JVM crash. Startup and recording failures leave the host application running; read stderr and validate collection health before using the evidence.

## Agent options

Pass comma-separated options after `-javaagent:/path/census-agent.jar=`. Percent-encode reserved characters in values: comma `%2C`, equals `%3D`, percent `%25`, space `%20`. Quote the whole JVM argument if the agent JAR path contains spaces.

| Option | Default | Meaning |
|---|---|---|
| `output` | `cryptocensus-cbom.json` | Output file at graceful JVM exit. |
| `dir` | Unset | Directory for unique `cbom-<pid>-<random>.json` files; takes precedence over `output`. Prefer this for multiple JVMs. |
| `app` | Unset | Application name in metadata. |
| `summary` | `true` | Print findings to stderr on exit. Disable if logs should not contain inventory details. |
| `tls` | `true` | Observe JSSE session establishment. |
| `maxAssets` | `500` | Distinct assets retained; integer 1–2000. Further observations of new assets are dropped and counted. |
| `maxSites` | `50` | Sites retained per asset; integer 1–100. Excess attribution folds into `<overflow>`. |
| `debug` | `false` | Log instrumentation errors. |
| `experimental` | `false` | Best-effort Byte Buddy parsing for newer class files; also enabled by `-Dnet.bytebuddy.experimental=true`. This does not make an unlisted JDK supported. |

Unknown keys, duplicate keys and invalid values generate warnings. Duplicates keep the first value; invalid values fall back to defaults. Read startup warnings, especially for a misspelled output path or limit. Maven validates its supported collection parameters before launching tests.

## Output budgets

Agent metadata records `cryptocensus:maxAssets` and `cryptocensus:maxSites`. Collection retains asset counts and verdicts while excess call-site observations fold into `<overflow>`. `cryptocensus:overflowedSites` counts **observations whose attribution was lost**, not the number of distinct sites; it includes loss during collection and serialization.

The writer allows at most 8 MiB of UTF-8 JSON and a 32 MiB estimated parser-retained-memory budget. To fit, it folds the largest call-site groups into `<overflow>` while preserving every asset, risk verdict, and observation count. Console summaries use that same bounded snapshot. Lost attribution causes a warning rather than incomplete-collection failure, and prevents `ignoreJdkInternal` from assuming an affected asset is JDK-only.

If assets and metadata alone cannot fit, serialization fails explicitly, leaves any previous destination untouched, and reports an error. A previous report is not current evidence; Maven's current-run checks reject a run without new evidence. Lower collection limits can introduce dropped assets, so partition a large workload into separately observed JVMs or modules rather than accepting an incomplete result. Merged output has the same serialization budgets. The maximum collection settings with very long asset names or metadata can therefore fail output even when the configured count limits are respected.

The reader independently rejects input beyond 16 MiB, estimated retained memory beyond 64 MiB, more than 1.5 million JSON values, or depth beyond 64. It requires strict UTF-8 and JSON encoding. These defensive bounds are not user-configurable; increasing collection limits does not disable them.

## Troubleshooting

| Symptom | Check and remedy |
|---|---|
| No report yet | Wait for graceful JVM exit. Inspect startup stderr and output-directory ownership. A killed JVM cannot flush. |
| Maven says no agent data | Confirm tests fork (`forkCount` greater than zero), inspect Surefire/Failsafe configuration, and remove command-line `-DargLine`. Use [late binding](RECIPES.md#jacoco-and-custom-jvm-options). Do not disable missing-data checks to repair attachment. |
| One test execution is absent | Inspect each execution's `argLine`. An instrumented execution does not prove another execution attached; see [coverage limitations](SAFETY.md#maven-evidence-coverage). |
| Key size not observed | Provider/HSM keys are never exported. Public/HMAC keys become unknown; AES remains acceptable because all AES sizes meet the classical floor. Review the provider and parameter metadata externally. |
| Unknown algorithm/verdict | Check the canonical algorithm and operation, raw external hashing, PSS parameter timing, key observability, and [policy](POLICY.md). Use a narrow reviewed exception only with independent evidence. |
| Dropped observations | Increase `maxAssets` within its supported range or partition the workload; rerun and require zero dropped assets. |
| Overflow attribution | Increase `maxSites` within its range if useful. Output budgets can still trim sites. Asset/verdict/count evidence remains, but source attribution is incomplete. |
| Instrumentation errors or gaps | Match the [supported runtime matrix](COMPATIBILITY.md), enable `debug`, inspect stderr and target metadata. `not-loaded` is benign; absent/not-transformed/no-matching-methods indicates a gap. |
| New JDK class files unsupported | Use a supported JDK, or deliberately try `experimental=true` in staging and validate all target/health results. |
| CBOM rejected as malformed or too large | Preserve the original for private diagnosis. Check version, strict UTF-8, input limits, and whether another process edited it; do not repair JSON and call it original evidence. |
| CBOM write fails | Check directory create/rename permissions, free space, report budgets and stderr. Use a private writable destination; stale output does not establish success. |

## Sensitivity and retention

CBOMs contain application and runtime identity, algorithm choices, key sizes, code locations, counts, and collection-health data. They do not contain private/secret key bytes, plaintext payloads, or session secrets, but they can reveal internal architecture and exploitable cryptographic weaknesses. Review stderr summaries and debug logs with the same care.

Store reports in access-controlled build artifacts or a private inventory store. POSIX output files are mode `0600`; Windows files inherit the output directory ACL. The output directory is trusted, so protect it from other writers. Do not publish a CBOM in a public issue without reviewing and sanitizing it.

Choose a retention period with your security/data owners. Keep the merged report, original inputs, source revision, policy/agent versions, and relevant verification logs together when audit evidence is needed; merged reports record input SHA-256 values, not an external signature. A trusted pipeline can sign evidence outside the observed JVM.

Maven creates a separate run directory for each build and does not delete earlier evidence. Archive required records before `mvn clean`, which deletes the default `target` directory. Configure CI artifact expiration and storage cleanup explicitly; for custom/shared destinations, delete only completed runs after checking that no JVM or build still uses them.

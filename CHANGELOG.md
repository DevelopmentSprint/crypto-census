# Changelog

All notable changes to Crypto Census. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Removing a row from the
[conformance matrix](docs/CONFORMANCE.md) requires a "Deprecated" entry here first.

## [0.1.0] — Unreleased candidate

### Added

- Project license/notice resources in each published main JAR, with project and Byte Buddy notices merged in the shaded agent. Release-profile checks reject snapshot project/dependency versions, and candidate SCM metadata identifies the intended `v0.1.0` tag.

- Validated `prepare-agent` parameters `maxAssets` (1–2000), `maxSites` (1–100), and `debug`, with collection limits recorded in agent CBOM metadata.
- Executable README quickstart regression coverage, standalone acquisition instructions, Maven/agent parameter references, integration recipes, operator guidance, and a staged release runbook.
- A security policy defining support and response targets; confirmation of the private reporting channel remains a public-release prerequisite.

- Conformance matrix, enforced in CI on every build: Eclipse Temurin 17, 21, 25, 26 and 27 on Linux;
  Temurin 17, 25 and 27 on Windows; IBM Semeru 17, 21 and 25 on Linux; the next JDK's early-access
  build as an advisory job. See [docs/CONFORMANCE.md](docs/CONFORMANCE.md).
- Conformance tests: the OpenTelemetry Java agent 2.31.1 in both `-javaagent` orders; embedded
  Tomcat 11 serving HTTPS; Open Liberty 26.0.0.8 on IBM Semeru 25 and WildFly 41.0.1 in containers
  (`-P app-servers`).
- Agent option `experimental=true`, and support for `-Dnet.bytebuddy.experimental=true`, to run on a
  JDK newer than the bundled Byte Buddy supports. Recorded as `cryptocensus:byteBuddyExperimental`.
- CBOM metadata `cryptocensus:targets` and `cryptocensus:missingTargets`: whether each JDK class the
  agent always instruments was instrumented, not loaded, absent, not transformed, or had no matching
  methods. The agent prints gaps on stderr; `census:check` merges them over all test JVMs and warns,
  or fails with the new `failOnMissingTargets=true`.

### Changed

- Policy `cryptocensus-default/6` treats raw `NONEwith*` signatures as unknown when the external hash is not observable. Definite broken key or hash findings still win when the other signature component is unknown; RSA key findings also survive unknown modes or padding.
- Generated CBOMs enforce an 8 MiB UTF-8 budget and a 32 MiB estimated parser-memory budget. When needed, whole call-site groups fold into `<overflow>` while preserving assets, verdicts, and observation counts; impossible asset/metadata payloads fail without replacing existing output. `overflowedSites` continues to count observations that lost attribution, including serialization trimming.
- Standalone collection limits now have supported upper bounds: 2000 assets and 100 sites per asset. Invalid options warn and fall back; Maven rejects invalid limits before test launch.

- An RSASSA-PSS signature whose hash could not be observed is now `unknown` instead of being judged
  on its RSA key alone (SHA-1 is a valid PSS hash). When the PSS parameters are set before `init`,
  the hash is recorded (`SHA256withRSASSA-PSS`) and judged like `SHA256withRSA`, so SHA-1 PSS
  signing is `broken`.
- `census:check` treats a test JVM whose CBOM has no `cryptocensus:targets` (an older agent, or one
  that could not determine its targets) as an instrumentation gap: a warning, or a failure with
  `failOnMissingTargets=true`.
- `census:check` no longer accepts `-DskipITs` alone as proof that no tests ran; it skips only
  Failsafe.
- CI: the app-server conformance job fails when Docker is unavailable or either app-server test did
  not run, instead of passing on a skip. Testcontainers' Ryuk image is pinned by digest.

### Fixed

- Unknown signature hashes no longer hide independently broken signing keys when unknown verdicts are permitted. Raw signatures no longer bypass the default unknown-hash safeguard.
- The quickstart no longer hardcodes a policy threshold or silently permits SHA-1; documented command-line overrides now change enforcement.
- Oversized default-limit inventories remain readable by compacting attribution, with consistent console/CBOM reporting and conservative JDK-only filtering.
- CBOM parsing rejects malformed UTF-8 and non-ASCII hexadecimal digits in JSON Unicode escapes.
- The pre-commit hook selects Java consistently with Maven Wrapper using `JAVA_HOME` before PATH.

- Recording a key generation disabled the JDK's provider failover: the agent called
  `KeyGenerator.getProvider()` / `KeyPairGenerator.getProvider()`, which turn it off, so a provider
  failing after the first key made the application's next `generateKey()` throw instead of falling
  back. The provider is now read from the field.
- Call sites on IBM Semeru: OpenJCEPlus provider frames (defined by the application class loader)
  were reported as the application's call site. Provider frames of IBM (IBMJCE, IBMJCEFIPS,
  IBMJCEPlus, OpenJCEPlus), SunMSCAPI, Apple, ACCP, AWS CloudHSM, Thales Luna, Entrust nShield,
  wolfCrypt and Conscrypt are now skipped, and Bouncy Castle's TLS stack (BCJSSE) is attributed like
  JSSE.
- `-Dnet.bytebuddy.experimental=true` had no effect, because shading relocated the property name.
- The Maven plugin's own Invoker tests failed on JDK 27 (Groovy 4.0.31 could not read Java 27 class
  files); they now use Groovy 4.0.33.

### Deprecated

- Nothing.

### Deferred

- Production overhead benchmarks (startup, allocation, throughput, latency, concurrency, virtual threads, shutdown); periodic/on-demand CBOM output and live collection health.
- Artifact SBOMs, source-linked provenance, reproducible-build configuration, and release automation.
- Evidence from one instrumented test execution still cannot establish attachment in another execution; see [Maven evidence coverage](docs/SAFETY.md#maven-evidence-coverage).

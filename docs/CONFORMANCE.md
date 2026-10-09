# Conformance matrix

Crypto Census instruments JDK-internal classes from inside the JVM it observes. Whether it works is a
property of the exact runtime: JDK release, vendor and VM, the app server around the application,
and the other agents in the same JVM. This document lists the runtimes Crypto Census is **verified**
on, how each one is verified, and the rules for changing the list.

The source of truth is the `build` job's matrix in
[`.github/workflows/ci.yml`](../.github/workflows/ci.yml). The tables below and the
[Compatibility](../README.md#compatibility) section of the README mirror it. If they ever disagree,
the workflow is right and the documents are a bug.

## The matrix

### JDKs

Every row runs the complete build on every push and pull request (CI job name in the last column).

| JDK | Vendor / VM                | OS      | Tier     | CI job                                       |
|-----|----------------------------|---------|----------|----------------------------------------------|
| 17  | Eclipse Temurin / HotSpot  | Linux   | gate     | `JDK 17 temurin / ubuntu-latest`             |
| 21  | Eclipse Temurin / HotSpot  | Linux   | gate     | `JDK 21 temurin / ubuntu-latest`             |
| 25  | Eclipse Temurin / HotSpot  | Linux   | gate     | `JDK 25 temurin / ubuntu-latest`             |
| 26  | Eclipse Temurin / HotSpot  | Linux   | gate     | `JDK 26 temurin / ubuntu-latest`             |
| 27  | Eclipse Temurin / HotSpot  | Linux   | gate     | `JDK 27 temurin / ubuntu-latest`             |
| 17  | Eclipse Temurin / HotSpot  | Windows | gate     | `JDK 17 temurin / windows-latest`            |
| 25  | Eclipse Temurin / HotSpot  | Windows | gate     | `JDK 25 temurin / windows-latest`            |
| 27  | Eclipse Temurin / HotSpot  | Windows | gate     | `JDK 27 temurin / windows-latest`            |
| 17  | IBM Semeru / OpenJ9        | Linux   | gate     | `JDK 17 semeru / ubuntu-latest`              |
| 21  | IBM Semeru / OpenJ9        | Linux   | gate     | `JDK 21 semeru / ubuntu-latest`              |
| 25  | IBM Semeru / OpenJ9        | Linux   | gate     | `JDK 25 semeru / ubuntu-latest`              |
| 28-ea | Eclipse Temurin / HotSpot | Linux  | advisory | `JDK 28-ea temurin / ubuntu-latest (advisory)` |

The JDK version is the feature release; CI uses the newest build `actions/setup-java` resolves for
it on the day of the run.

### App servers and other agents

| Environment                     | Version                              | JVM                  | Tier | Verified by                               |
|---------------------------------|--------------------------------------|----------------------|------|-------------------------------------------|
| Embedded Tomcat, HTTPS connector | `tomcat-embed-core` 11.0.26         | every JDK row        | gate | `TomcatIT` (in every JDK job)             |
| OpenTelemetry Java agent        | 2.31.1, both `-javaagent` orders     | every JDK row        | gate | `ApmCoexistenceIT`, `TomcatIT`            |
| Open Liberty (container)        | 26.0.0.8, `full-java25-openj9-ubi-minimal` | IBM Semeru 25  | gate | `AppServerIT` (job `App servers ...`)     |
| WildFly (container)             | 41.0.1.Final, `jdk21`                | Eclipse Temurin 21   | gate | `AppServerIT` (job `App servers ...`)     |

Versions are pinned in the root `pom.xml` (`tomcat.version`, `opentelemetry-javaagent.version`,
`testcontainers.version`) and, for the container images, by digest in
`census-app-servers/src/test/java/.../AppServerIT.java`.

### Tiers

- **gate**: a failure fails the CI run and is a bug to fix. Make these jobs required status checks
  in the repository's branch protection for `develop` and `main` (job names are listed above; they
  change when a row is added, so update the required checks with the matrix).
- **advisory**: an early warning. The job runs with `continue-on-error: true`. It exists to show
  *before* a JDK is released what will break, typically the class file version outrunning the
  bundled Byte Buddy. It never blocks a merge.

## How a row is verified

Every JDK job runs `./mvnw -B -ntp verify` with that JDK as the build JDK, so every test below runs
*on* that JDK and launches its child JVMs from the same `java.home`.

| Test | What it proves |
|------|----------------|
| Unit tests (`census-core`, `census-agent`, `census-maven-plugin`) | policy, CBOM schema (validated against the official CycloneDX 1.6 schema), call-site attribution rules including vendor provider packages, target bookkeeping |
| `AgentIT` | the shaded agent in a child JVM: every instrumented entry point, verdicts, call-site attribution, a loopback TLS 1.3 handshake, post-quantum algorithms (JDK 24+), health counters at zero, **every fixed target instrumented with no gaps**, `-Dnet.bytebuddy.experimental` reaching the shaded Byte Buddy. On Semeru: OpenJCEPlus provider frames are not mistaken for the caller. On a JDK newer than Byte Buddy supports: the agent reports errors and gaps by default and works with `experimental=true` |
| `ApmCoexistenceIT` | the same workload with the OpenTelemetry Java agent loaded before and after Crypto Census: the application runs, the CBOM is complete and healthy, expected assets are present and attributed to the application |
| `TomcatIT` | embedded Tomcat serving HTTPS with crypto in a servlet, with and without the OpenTelemetry agent: servlet crypto attributed to `doGet`, server-side TLS sessions attributed to Tomcat's connector via JSSE |
| Maven Invoker ITs (`census-maven-plugin/src/it`) | `prepare-agent`/`check` in real Maven builds on that JDK, including the merged `cryptocensus:targets` |
| `census-demo` | the plugin on itself |
| Static analysis (JDK 21+ rows) | Spotless, Checkstyle, Error Prone, `javac -Werror`, PMD, CPD, SpotBugs/FindSecBugs, JaCoCo floors, Enforcer |

The `App servers` job runs `./mvnw -B -ntp -P app-servers -pl census-app-servers -am verify`.
`AppServerIT` deploys a WAR with a crypto servlet into each server container, enables the agent the
way an operator would (`jvm.options` for Liberty, `JAVA_OPTS` for WildFly), calls the servlet, stops
the container gracefully so shutdown hooks run, and asserts on the CBOM copied out of the stopped
container: zero health counters, no instrumentation gaps, servlet call-site attribution. Without
Docker the test class is skipped, not failed (`@Testcontainers(disabledWithoutDocker = true)`), and
without `-P app-servers` the module is not built at all, so local builds never need Docker.

The early-access job runs `./mvnw -B -ntp verify -P '!quality'`: the static analyzers lag behind
new JDKs and say nothing about whether the agent works.

### Instrumentation targets in every CBOM

The agent always means to instrument a fixed set of JDK classes. Every CBOM records the outcome per
class in `cryptocensus:targets`, and the ones that were not instrumented in
`cryptocensus:missingTargets`:

| Status | Meaning |
|--------|---------|
| `instrumented` | transformed, with at least one advised method |
| `not-loaded` | exists in this JDK, never loaded in this run (nothing of that kind ran); benign |
| `absent` | no such class in this JDK (renamed or removed): a gap |
| `not-transformed` | loaded, but never transformed: a gap |
| `no-matching-methods` | transformed, but none of the expected methods exist: a gap |

`census:check` merges these over all test JVMs, logs never-loaded targets, and warns about gaps
(`failOnMissingTargets=true` makes gaps fail the build). The conformance tests require zero gaps.

## Running it locally

```
JAVA_HOME=/path/to/jdk ./mvnw -B clean verify                     # one JDK row
./mvnw -B -P app-servers -pl census-app-servers -am verify         # app servers (needs Docker)
JAVA_HOME=/path/to/ea-jdk ./mvnw -B clean verify -P '!quality'    # early access
```

Download JDKs from their vendors (for example the [Adoptium API](https://api.adoptium.net) and
[IBM Semeru releases](https://github.com/ibmruntimes)) and verify the published SHA-256 checksums
before running them.

## Adding and removing rows

### The ratchet rule

A combination in the matrix is tested on every build, **and the matrix only grows**. A row leaves
only through an explicit deprecation:

1. A pull request adds an entry under "Deprecated" in [CHANGELOG.md](../CHANGELOG.md) naming the
   row, the reason (for example: the vendor no longer publishes builds of that JDK), and the release
   after which it will be removed. The row stays in the matrix and keeps passing meanwhile.
2. Only after a release that carries that notice may a second pull request remove the row from
   `ci.yml`, this document and the README, recording the removal under "Removed" in the changelog.

A failing row is fixed, not removed, skipped or downgraded to advisory. Moving a row from *gate* to
*advisory* counts as a removal.

### Adding a JDK or vendor

1. Add the row to the `build` matrix in `.github/workflows/ci.yml` (`os`, `distribution` as
   understood by `actions/setup-java`, `java`).
2. Run `./mvnw -B clean verify` on that runtime locally if you can, and fix what fails: in the
   agent if it is the agent's fault, never by weakening an assertion.
3. Add the row to the tables here and in the README, and an "Added" line to the changelog.

When a JDK that was early access is released: add it as a gate row (step 1) and move the
`early-access` job to the next feature release's EA build.

### Adding an app server or another agent

App servers go into `census-app-servers` (`AppServerIT`): pin the image by digest, enable the agent
the way the server's operators would, exercise an endpoint, and reuse the common CBOM assertions.
Other `-javaagent`s go next to `ApmCoexistenceIT` in `census-agent`, fetched as Maven artifacts by
`maven-dependency-plugin` (never downloaded ad hoc, never put on a test class path).

### Moving a pinned version forward

Patch and minor updates of a pinned JDK image, server or agent do not change the matrix; they are
ordinary dependency updates. New versions of dependencies, plugins, images and GitHub Actions must
have been released at least two weeks earlier. GitHub Actions are pinned to full commit SHAs and
container images to digests.

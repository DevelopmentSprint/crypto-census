# Annotation context conformance

The conformance fixtures exercise the packaged agent against real JVM execution
and pinned application-server images. They validate the CBOM and evidence
sidecar as a pair, including occurrence-count conservation and retained asset
references. Passing a unit test or compiling a fixture does not satisfy these
runtime gates.

## JVM boundary cases

`ScopeBoundaryIT` exercises these behaviors without propagation wrappers:

| Boundary | Required attribution |
| --- | --- |
| Annotated call on a reused executor worker | That worker's active flow |
| Later unannotated work on the same worker | No scope |
| Work submitted from an active flow to another thread | No inherited scope |
| Callback on an already completed `CompletableFuture` | Current synchronous flow |
| Saturated executor using `CallerRunsPolicy` | Current synchronous flow, with exact thread assertion |
| A single ForkJoin worker joining its own queued task | Current synchronous flow, with exact thread assertion |
| JDK `SubmissionPublisher` using a direct executor | Current synchronous flow |
| JDK `SubmissionPublisher` using a separate worker | No inherited scope |
| New virtual thread called from an annotated platform thread | No inherited scope |
| Annotated method executing on a virtual thread, then returning | Its own flow, followed by no scope |

The virtual-thread cases execute on JDK 21 and later. JDK 17 executes the other
cases. These tests describe dynamic execution scope, not causal request
propagation. Inline unrelated callbacks can inherit an active synchronous
scope. JDK `SubmissionPublisher` coverage does not establish support for every
reactive framework or scheduler.

`LoaderBoundaryIT` loads identical annotated bytecode from one file URL into
two named class loaders. Explicit catalog selectors assign different
application/deployment identities. The fixture closes both loaders and checks
collection through weak references with bounded explicit GC attempts. A second
case denies bootstrap-bridge visibility: application crypto must still run,
core collection health must remain clean, and enrichment must report a gap
without claiming a synchronous scope.

## Application-server cases

`AnnotatedAppServerIT` deploys two WARs with identical annotated servlet
bytecode and separate annotation API copies into each pinned server. Catalogs
explicitly map the observed file or vendor CodeSource and, for WildFly, exact
deployment-loader names. Assertions require distinct declaring identities,
application/deployment mappings, nested archive/transport scopes, and no scope
after the annotated method returns.

WildFly's fixture explicitly includes
`com.developmentsprint.cryptocensus.agent` in `jboss.modules.system.pkgs` alongside
the existing `org.jboss.byteman` setting, so deployment classes can resolve the
bootstrap advice bridge. This supported configuration is part of the test;
there is no universal class-loader visibility promise.

Images reuse the digest pins in `AppServerIT`: Open Liberty 26.0.0.8 on IBM
Semeru 25 and WildFly 41.0.1.Final on Temurin 21. Containers have task labels and
ephemeral mapped ports. Tests stop only their own containers and collect paired
output after graceful JVM shutdown. The CI job requires both existing server
cases and both annotated server cases to run with zero skips, errors and
failures.

The [source catalog documentation](SOURCE-CATALOG.md) records the actual
CodeSource forms observed by the initial two-deployment probe. Those source
probes establish mapping inputs; the annotated runtime assertions establish
scope behavior separately.

## Required execution

Run the standard JVM reactor verification to execute the boundary cases:

```sh
./mvnw -B -ntp -pl census-agent -am verify
```

Run the required Docker server gate:

```sh
./mvnw -B -ntp -P app-servers -pl census-app-servers -am verify -Dcensus.requireDocker=true
```

Main-agent TLS initiation, completed/failed handshake capture, retained-asset
joins and exact paired counts are covered separately by `TlsFlowEnrichmentIT`.

## Local verification record

On 2026-10-26, a clean verification against production commit `9403543` plus
these fixtures passed on OpenJDK 25.0.2. The run included 307 core tests,
146 agent unit tests, 46 agent integration cases (two existing runtime-specific
skips), and all four required server cases with zero skips. Agent runtime
coverage remained above the unchanged floors: 1,787/2,135 lines (83.70%) and
755/1,066 branches (70.83%).

The three new JVM cases also passed against the same packaged agent on
OpenJDK 17.0.18 and 21.0.10, without skips. JDK 17 omits virtual-thread actions
inside the boundary workload because that API is unavailable there. The
server cases used the pinned Liberty/Semeru 25 and WildFly/Temurin 21 images;
each case verified both deployments. These are local results, not evidence of
a hosted CI run or support for untested server versions and loader policies.

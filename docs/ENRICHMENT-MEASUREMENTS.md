# Exploratory enrichment measurements

Measured 2026-10-27 against producer revision `ded46d216122057f137e912b75bec96eeef0466d`.
The results identify substantial allocation and per-operation overhead in this synthetic
workload. They do **not** establish acceptable production overhead. The agent remains
intended for tests and staging; production profiling and optimization require separate work.

The [captured results](measurements/enrichment-2026-10-27.json) contain every timing sample,
startup measurement, retained-state snapshot, qualification result, and summary. They are
synthetic measurements, not customer or tracker data. The measured shaded agent SHA-256 was
`7c66853509ae4b317ae8bf50f9d8db7db6607a8efad59e3834ddfdc99837eb74`.

## Reproduce

Use JDK 25 and Python 3.9 or newer. From the checkout root:

```sh
export JAVA_HOME=/path/to/jdk-25
./mvnw -B -ntp -pl census-agent -am package -DskipTests -Dgitbuildhook.gitconfig.skip=true
python3 scripts/measure-enrichment.py --java-home "$JAVA_HOME" \
  --output target/enrichment-measurements --forks 3 --rounds 5
python3 scripts/summarize-enrichment-diagnostics.py target/enrichment-measurements
```

The output must be a new directory inside this checkout's ignored `target` directory.
The harness uses ephemeral loopback ports and a generated synthetic PKCS12 keystore.
It does not start containers, contact external servers, or modify global JVM settings.
Each child has a 180-second watchdog. Unset `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` and
`_JAVA_OPTIONS`; the harness rejects them to avoid hidden agents or JVM options.
`--skip-tls` and `--skip-stress` support focused runs, whose omissions must be disclosed.
Do not run other benchmarks or verification builds concurrently when seeking quieter samples.

## Method and limits

The measured host was an Apple M3 Max, 16 logical CPUs, 48 GiB memory, macOS/arm64,
with Homebrew OpenJDK 25.0.2 and fixed `-Xms256m -Xmx256m`. This was a shared development
machine: background load, CPU frequency and thermal state were not controlled. No JaCoCo
agent was attached. JIT logging and bytecode dumping used separate diagnostic JVMs.

Each workload/mode has three fresh JVM forks with rotated mode order. One warmup round
is excluded, then five rounds are recorded. Tables show the median of the 15 pooled
rounds; ranges in the JSON are observed extrema, **not confidence intervals**. The rounds
within a fork are correlated. This is a small exploratory harness, not a stabilized JMH
benchmark. Very short hot-method timings include loop/dispatch costs and may reflect
compiler optimization. No subtraction purports to isolate a single instruction's cost.

Allocation uses the supported [ThreadMXBean allocation extension](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.management/com/sun/management/ThreadMXBean.html).
It measures bytes allocated by the current main thread, not retained memory or all-thread
allocation. TLS workers and delegated-task threads are excluded. Unsupported allocation
measurement is represented as unavailable, never zero. Startup is parent spawn to a
flushed `READY` line in main; it includes process, class loading and agent installation,
but excludes workload initialization and shutdown. Whole-process durations are also saved.

Healthy timing runs require a CBOM, exact-byte-paired sidecar for enrichment, supported
annotation capability, zero health counters, and expected declarations. Scoped digest
counts must equal warmup plus measured invocations. TLS correlation additionally requires
all expected completed attempts, both socket and engine transports, resumption and scoped
initiation. Descriptor/matcher checks require exactly zero or 32 declarations per iteration.
The hot-method diagnostic requires actual transformed bytecode and JIT decisions. The
stress run is qualified separately and must not be described as a complete evidence run.

## Operation timings and allocation

Each digest operation creates a SHA-256 instance and hashes 32 zero bytes. Hot operations
perform the same integer arithmetic; the scoped variant has `@CryptoFlow`. Counts are
10,000 digest operations or 2,000,000 hot operations per round. Results feed a volatile sink
after each round. These results do not estimate overhead for applications reusing digest
instances or with substantially different call stacks.

| Workload | Agent off | Core only | Enrichment | Main-thread bytes/op, off / core / enrichment |
| --- | ---: | ---: | ---: | ---: |
| Plain digest | 0.102 µs | 3.101 µs | 26.726 µs | 320 / 2,800 / 22,344 |
| Scoped digest | 0.105 µs | 3.184 µs | 32.917 µs | 320 / 2,968 / 27,096 |
| Plain arithmetic | 1.44 ns | 1.68 ns | 1.46 ns | 0 / 0 / 0 |
| Scoped arithmetic | 1.53 ns | 1.54 ns | 19.91 ns | 0 / 0 / 192 |

For this scoped-digest fixture, the medians correspond to approximately 9.54 million,
314 thousand and 30.4 thousand operations/second respectively. This result makes
allocation and attribution overhead a concrete optimization target. It is not evidence
that the annotation syntax alone accounts for the enrichment cost: the unscoped digest
also pays enrichment attribution costs.

For the scoped-digest processes, median spawn-to-main was 27.0 ms off, 330.8 ms core and
432.9 ms enrichment. Startup varies across workload processes; the JSON retains every
measurement instead of treating those three values as a universal startup budget.

## Stack attribution, descriptor prefilter and loading

These probes call the actual bootstrap-loaded shaded implementation through reflection.
They include reflection and result handling. The stack-attribution probe includes its
particular reflective call stack and result hash; it is not a full decomposition of the
digest path. The descriptor probe scans original class bytes. The name matcher examines
all declared methods of an already loaded class with 32 fixture methods, including its
constructor; it excludes building a complete type pool. These are different units of work,
so their numbers do not prove that removing the prefilter would speed up transformation.

| Probe | Core only | Enrichment | Bytes/operation, core / enrichment |
| --- | ---: | ---: | ---: |
| Stack attribution | 2.027 µs | 1.736 µs | 1,352 / 1,352 |
| Descriptor scan, plain class | 2.529 µs | 2.266 µs | 5,080 / 5,080 |
| Descriptor scan, annotated class | 6.059 µs | 5.806 µs | 16,656 / 16,656 |
| Name matching, plain class | 0.449 µs | 0.445 µs | 792 / 792 |
| Name matching, annotated class | 0.512 µs | 0.757 µs | 792 / 1,560 |

Each class-loading batch creates and closes a new named loader and loads eight classes,
32 methods each, without invoking the methods. One warmup plus five batches stays below
flow/binding capacity. Plain-class batches took median 1.456 / 1.356 / 1.937 ms for
off/core/enrichment; annotated batches took 1.530 / 1.696 / 43.471 ms. Annotated enrichment
allocated 28,922,096 main-thread bytes per batch versus 543,720 core and 118,888 off.
The annotated enrichment range was 31.376–113.624 ms, illustrating substantial run variance.
The full pipeline includes class loading, scanning, metadata registration and advice generation.

## Hot-method bytecode and JIT

Separate `LogCompilation` runs record actual HotSpot decisions. The shaded Byte Buddy dump
property is `com.developmentsprint.cryptocensus.agent.shaded.bytebuddy.dump`, verified against
the packaged implementation. The helper parses class-file `Code` lengths and JIT XML.
The scoped hot method grew from 13 to 57 bytecode bytes; its plain counterpart stayed at
13. Off/core logs recorded successful inlining. Enrichment recorded both successful hot
inlining and `callee is too large` failures at different compilation sites/stages. Thus
there is an observed compilation effect, but no claim that enrichment universally prevents
inlining. Diagnostic timings are excluded from all timing tables.

## TLS batch

One operation initializes the context from the generated keystore, runs two successful
socket connections (including resumption), then two concurrent engine pairs using delegated
tasks. It includes setup, reflection and teardown; **it is not per-handshake latency**.
One warmup and five measured batches yielded 48 completed correlated attempts per fork,
with socket/engine, resumed and scoped-initiation evidence. This runtime selected classical
X25519; hybrid TLS correctness was established in the separate TLS matrix, not measured here.

| Mode | Median batch | Observed range | Main-thread bytes/batch | Median startup |
| --- | ---: | ---: | ---: | ---: |
| Off | 29.178 ms | 25.081–45.565 ms | 1,430,536 | 30.2 ms |
| Core | 37.413 ms | 32.350–62.346 ms | 2,058,272 | 349.6 ms |
| Enrichment | 47.664 ms | 35.948–78.420 ms | 4,434,224 | 465.8 ms |
| Enrichment + TLS correlation | 50.237 ms | 40.126–98.286 ms | 4,512,840 | 542.0 ms |

These overlapping ranges do not establish statistical significance for the incremental
TLS collector cost. Failure-path latency is excluded. An earlier unreported timing attempt
aborted when the rejected-certificate fixture produced server-side `SocketException: Broken
pipe` instead of `SSLException`; the successful-path batch avoids that unrelated race.

## Cardinality and memory stress

A separate JVM loads and invokes three batches of 150 classes × 32 distinct flow methods
(14,400 total), each with five digest factories. Named loaders close after each batch.
An annotated recursion of depth 40 also exceeds configured scope depth 8. `maxSites=8`
exercises site folding. After requesting GC and waiting 100 ms, the fixture records whole-JVM
heap/nonheap usage and quiescent live registry sizes through reflection. GC completion is
not guaranteed, and these are neither RSS nor per-collector retained-size measurements.

| Completed batch | Retained flows | Retained observations | Heap bytes | Nonheap bytes | Dropped flows | Lost attribution |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 4,096 | 16,384 | 10,605,504 | 27,219,240 | 708 | 11,158 |
| 2 | 4,096 | 16,384 | 10,627,712 | 25,497,984 | 5,508 | 59,160 |
| 3 | 4,096 | 16,384 | 10,627,856 | 24,163,112 | 10,308 | 107,162 |

`CONSERVATION_UNVERIFIED` rose from 7,617 to 55,619. The final live counts had no transform,
recording or restoration failures. This demonstrates these two collection caps and visible
loss under this workload, not a universal leak-freedom proof or exhaustion of every bound.
Bindings, identity mappings, TLS attempts and tickets have separate bounds and tests.

**The stress process wrote the core CBOM but did not publish its sidecar.** It logged
`Enrichment evidence publication incomplete`. The live snapshots precede publication,
so their zero `OUTPUT_FAILURES` value does not describe the later shutdown result. The
warning does not distinguish deadline exhaustion from encoding/validation failure; this
measurement does not infer the cause. The process completed in 6.53 seconds. Bounded
collection therefore does not establish successful full-capacity publication. That limitation
must remain visible in acceptance and follow-up work.

## Validation and interpretation

The final run contains 96 qualified timing JVMs, three separate diagnostic JVMs and one
stress JVM. Earlier smoke/failed/capacity-degraded samples are excluded from the committed
results. In particular, the first 32-class loading batch design exceeded capacity across
repeated loaders; it was corrected to eight and the entire final matrix rerun.
No production code, performance threshold, coverage floor, or default configuration changes
are part of this harness. Compare future runs against the exact producer revision and
agent hash, preserve units and qualification checks, and repeat on representative services
before drawing deployment conclusions.

The focused JDK 25 `clean verify` passed: annotations 4 unit + 3 integration tests,
core 307 tests, agent 146 unit + 46 integration tests (two pre-existing runtime-specific
skips), with static and coverage gates unchanged. JaCoCo still warned that `FlowAdvice`
execution data did not match its analyzed class, including after clean verification;
the configured runtime coverage gate passed. The harness does not alter that class or
coverage configuration. Treat the warning separately from these uninstrumented timing runs.

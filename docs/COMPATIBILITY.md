# Compatibility

The agent is compiled for Java 17 bytecode. It is **verified on every row below in every configured CI push and
pull-request run**; the matrix in [`.github/workflows/ci.yml`](../.github/workflows/ci.yml) is the source of
truth, and [Conformance](CONFORMANCE.md) lists which test proves what.

| JDK         | Eclipse Temurin (HotSpot) | IBM Semeru (OpenJ9 VM, OpenJCEPlus) |
|-------------|---------------------------|-------------------------------------|
| 17 LTS      | Linux, Windows            | Linux                               |
| 21 LTS      | Linux                     | Linux                               |
| 25 LTS      | Linux, Windows            | Linux                               |
| 26          | Linux                     |                                     |
| 27          | Linux, Windows            |                                     |
| 28 (early access) | Linux, *advisory*   |                                     |

| Runs with                       | Verified version                    | On                                   |
|---------------------------------|-------------------------------------|--------------------------------------|
| Embedded Tomcat (HTTPS)         | 11.0.26                             | every JDK row above                  |
| Open Liberty                    | 26.0.0.8 (container image)          | IBM Semeru 25                        |
| WildFly                         | 41.0.1.Final (container image)      | Temurin 21                           |
| OpenTelemetry Java agent        | 2.31.1, either `-javaagent` order   | every JDK row above, also with Tomcat |

**What a row means.** "Verified" rows are gates: the full build (unit tests, the agent in child JVMs
with TLS, the APM-agent and Tomcat conformance tests, the Maven plugin's Invoker builds) must pass, and
the conformance tests' CBOMs must show zero instrumentation errors and no instrumentation gaps. The
early-access row is an early warning only: it runs on the next JDK's EA build and may fail without
blocking anything. Each verified row tests a specific version; newer patch releases of the same JDK,
server or agent are expected to work but are proven only once CI runs on them.

**The ratchet.** Once a combination is in the matrix it is tested on every build and stays there.
Removing one requires an explicit deprecation, announced in [CHANGELOG.md](../CHANGELOG.md) at least one
release before the row goes. See [Conformance](CONFORMANCE.md#adding-and-removing-rows).

**Not supported:**

- **Java 8 and 11.** The agent's class files target Java 17; an older JVM cannot load the agent class
  and, as with any `-javaagent` whose premain class fails to load, refuses to start. (This also rules
  out WebSphere traditional on IBM Java 8.)
- **GraalVM native images.** A native image has no bytecode to instrument and no `java.lang.instrument`;
  Java agents cannot run there at all. GraalVM's JVM mode is a HotSpot JDK, but it is not in the matrix.
- **Dynamic attach on JDK 21+** works (the jar has an `Agent-Class`) but is not in the matrix, and the
  JVM prints a warning when an agent is loaded into a running JVM
  ([JEP 451](https://openjdk.org/jeps/451)); a future JDK will refuse it unless the JVM is started with
  `-XX:+EnableDynamicAgentLoading`. Use `-javaagent`.
- **JDKs newer than the matrix.** The bundled Byte Buddy (1.18.14) reads class files up to Java 27. On
  a newer JDK the agent instruments nothing; the CBOM says so (`cryptocensus:transformErrors`,
  `cryptocensus:missingTargets`) and `check` fails the build as incomplete. Opt in to Byte Buddy's
  best-effort parsing with the agent option `experimental=true` or `-Dnet.bytebuddy.experimental=true`.

Other OpenJDK builds (Oracle, Azul, Corretto, Red Hat, Microsoft), FIPS-mode JVMs and other app
servers or APM agents are not in the matrix. Provider frames from IBM's providers (IBMJCE, IBMJCEFIPS,
OpenJCEPlus), SunPKCS11 (including NSS-based FIPS mode), SunMSCAPI, Bouncy Castle (including BC-FIPS
and BCJSSE), ACCP, Conscrypt, wolfCrypt and the Luna, nShield and AWS CloudHSM providers are
recognized for call-site attribution.

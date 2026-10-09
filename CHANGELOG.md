# Changelog

All notable changes to Crypto Census. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Removing a row from the
[conformance matrix](docs/CONFORMANCE.md) requires a "Deprecated" entry here first.

## [Unreleased]

### Added

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

### Fixed

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

# Crypto Census

<!-- project-badges:start -->
[![CI on develop](https://github.com/DevelopmentSprint/crypto-census/actions/workflows/ci.yml/badge.svg?branch=develop)](https://github.com/DevelopmentSprint/crypto-census/actions/workflows/ci.yml?query=branch%3Adevelop)
[![Dependency check on develop](https://github.com/DevelopmentSprint/crypto-census/actions/workflows/dependency-check.yml/badge.svg?branch=develop)](https://github.com/DevelopmentSprint/crypto-census/actions/workflows/dependency-check.yml?query=branch%3Adevelop)
[![Documentation](https://github.com/DevelopmentSprint/crypto-census/actions/workflows/docs.yml/badge.svg?branch=develop)](https://github.com/DevelopmentSprint/crypto-census/actions/workflows/docs.yml)
[![Measured line coverage](https://cryptocensus.developmentsprint.com/badges/coverage.svg)](https://cryptocensus.developmentsprint.com/reference/quality.html)
[![Measured branch coverage](https://cryptocensus.developmentsprint.com/badges/coverage-branches.svg)](https://cryptocensus.developmentsprint.com/reference/quality.html)
[![License: Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)
[![Java: 17+](https://img.shields.io/badge/Java-17%2B-blue)](docs/COMPATIBILITY.md)
[![CycloneDX: 1.6](https://img.shields.io/badge/CycloneDX-1.6-blue)](docs/SBOM-PREPARATION.md)
<!-- project-badges:end -->

[**Documentation website**](https://cryptocensus.developmentsprint.com/) · [What the badges measure](docs/reference/quality.md)

**See which cryptography your Java application actually uses.**

Crypto Census observes JCA/JSSE calls at runtime and writes a CycloneDX 1.6 Cryptography Bill of Materials (CBOM): algorithms, key sizes when observable, risk verdicts, and application call sites. Use it in tests and staging to find broken cryptography and plan post-quantum migration. No server or paid tier is required.

## Quick start

**Prerelease:** `0.1.0` is not published to Maven Central yet. First [build and install from source](docs/INSTALLATION.md#from-source), using JDK 21+ for the full quality gates. The agent itself requires Java 17+. Add this plugin under `<build><plugins>` in the Maven application whose tests you want to observe:

<!-- quickstart-plugin:start -->
```xml
<plugin>
  <groupId>com.developmentsprint</groupId>
  <artifactId>census-maven-plugin</artifactId>
  <version>0.1.0</version>
  <executions>
    <execution>
      <goals>
        <goal>prepare-agent</goal>
        <goal>check</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```
<!-- quickstart-plugin:end -->

Run your application tests with Maven:

```sh
mvn verify
```

Census attaches to forked Surefire/Failsafe JVMs, prints findings, and writes `target/cryptocensus/cbom.json`. For example, a test that initializes AES-GCM with a 256-bit key produces a finding like:

```text
  [quantum-safe      ] cipher               AES-256-GCM (encrypt)                   AES-GCM with a 256-bit key (CNSA 2.0)
                         at com.acme.CardVault.seal:31                            x1
```

By default, broken algorithms, unknown verdicts, missing evidence, and incomplete collection fail the build. Set a stricter threshold to also reject classical public-key cryptography:

```sh
mvn verify -Dcensus.failOn=quantum-vulnerable
```

Tests must fork a JVM. If you already configure `argLine`, JaCoCo, or Failsafe, use the [Maven recipes](docs/RECIPES.md); command-line `-DargLine` can prevent attachment. Review exceptions explicitly using the [Maven reference](docs/MAVEN.md#reviewed-exceptions).

For a standalone JVM, [acquire the agent](docs/INSTALLATION.md#standalone-agent) and run:

```sh
java -javaagent:census-agent.jar=output=cbom.json,app=payments-api -jar payments-api.jar
```

The agent writes at graceful JVM shutdown. Exercise representative workloads before stopping it.

## Compatibility

Java 17+ is required. CI gates cover Temurin and Semeru/OpenJ9, Windows and Linux, embedded Tomcat, Open Liberty, WildFly, and OpenTelemetry coexistence. Exact versions and exclusions are in [Compatibility](docs/COMPATIBILITY.md); the [conformance matrix](docs/CONFORMANCE.md) defines the tests and deprecation ratchet. An unlisted runtime is unverified.

## Understand the evidence

Census observes executed JCA/JSSE entry points; it complements source scanners. Unexecuted paths and APIs that bypass JCA are invisible. The policy distinguishes known weakness from missing information; `unknown` fails by default, and `quantum-safe` does not imply CNSA 2.0 compliance. See [observation and risk policy](docs/POLICY.md).

The agent observes without changing application cryptographic operations, bounds collection and output, and records collection health. It runs inside the application: hostile code can forge or suppress evidence. Overhead is not benchmarked; current positioning is tests and staging. Read the complete [safety guarantees and limitations](docs/SAFETY.md) before deployment.

## Guides

- [Installation and artifact verification](docs/INSTALLATION.md)
- [Maven parameters and check guarantees](docs/MAVEN.md)
- [Finalize and deliver evidence after failed CI tests](docs/CI-FINALIZATION.md)
- [JaCoCo, Failsafe, multimodule, container, and app-server recipes](docs/RECIPES.md)
- [Agent options, troubleshooting, sensitivity, and retention](docs/OPERATIONS.md)
- [Contributing and quality gates](docs/CONTRIBUTING.md)
- [Release runbook](docs/RELEASING.md)
- [Security reporting and supported versions](SECURITY.md)

## Roadmap

Production overhead benchmarks and sampling; periodic/on-demand flushing; additional PSS/OAEP/GCM/PBE parameters and named curves; TLS session/group correlation; a Gradle plugin; keystore/truststore inventory; and additional FIPS, APM, and macOS conformance rows. Artifact SBOMs, provenance and reproducible-build configuration remain release-assurance work.

## License

Copyright 2026 Development Sprint, LLC. [Apache License 2.0](LICENSE).

For delivery to a company HTTPS receiver, see [CBOM delivery](docs/DELIVERY.md). [Delivery profiles](docs/DELIVERY-PROFILES.md) also support guarded Dependency-Track delivery and experimental Sonatype SBOM Manager, JFrog Artifactory, and Anchore Enterprise adapters. Each guide distinguishes stored evidence from imported inventory and documents verification limits.

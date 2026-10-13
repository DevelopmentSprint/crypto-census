# Dependency compatibility

This records the compatibility review of the dependency updates proposed on October 13, 2026, against development commit `4291395`. It explains the build constraints behind the selected versions. It is not a release or publication record.

## Reviewed upgrades

The Maven runtime target remains Java 17. Static analysis still runs on JDK 21 and later; contributor verification uses JDK 25. The wrapper now selects Maven 3.10.0, while the plugin's declared Maven minimum remains unchanged. All Actions references remain pinned to full commit IDs.

| Proposal | Selected version | Compatibility evidence |
| --- | --- | --- |
| [#1](https://github.com/DevelopmentSprint/crypto-census/pull/1) ([efa7613](https://github.com/DevelopmentSprint/crypto-census/commit/efa7613f9fa5c54db51ee3c2d7b1900c859dd91d)), checkout | 7.0.1 | Hosted runners support Node 24. Credential persistence remains disabled. The Pages workflow checks out only a successful same-repository `develop` push; it does not opt into unsafe fork checkout. |
| [#3](https://github.com/DevelopmentSprint/crypto-census/pull/3) ([0ea2fe8](https://github.com/DevelopmentSprint/crypto-census/commit/0ea2fe80573399e5b67d18b1d6031389baf26cd7)), cache | 6.1.0 | Existing cache inputs are supported; database cache keys and scope remain unchanged. |
| [#4](https://github.com/DevelopmentSprint/crypto-census/pull/4) ([cfa706e](https://github.com/DevelopmentSprint/crypto-census/commit/cfa706e0bcc07668d3dae0fe4c620a7372749629)), static analysis | Spotless 3.10.4, Checkstyle 14.3.0, PMD 7.28.0 | Existing rules, failure behavior and coverage floors remain enabled. No suppressions were added for the upgrades. |
| [#5](https://github.com/DevelopmentSprint/crypto-census/pull/5) ([ac701a6](https://github.com/DevelopmentSprint/crypto-census/commit/ac701a6f2c4937c91e31247e9e606dc3e6271841)), JAR plugin | 3.5.1 | Packaging retains Java 17 bytecode and manifest settings. |
| [#6](https://github.com/DevelopmentSprint/crypto-census/pull/6) ([6c005f7](https://github.com/DevelopmentSprint/crypto-census/commit/6c005f7b1e5cba3fe48732c20d91c3f5613b56da)), setup-java | 6.0.1 | Temurin and Semeru remain supported. Existing inputs need no deprecated aliases. |
| [#7](https://github.com/DevelopmentSprint/crypto-census/pull/7) ([76c58e9](https://github.com/DevelopmentSprint/crypto-census/commit/76c58e912ca6a7552736091bc1cb8a518d8d2a31)), Shade plugin | 3.6.2 | Agent and CLI packaging retain existing relocations and filters. |
| [#8](https://github.com/DevelopmentSprint/crypto-census/pull/8) ([84ead5a](https://github.com/DevelopmentSprint/crypto-census/commit/84ead5afc194f1421a04cf91871f30a27df1f680)), Maven Plugin Tools | 3.16.0 | Descriptor generation and the real Maven Invoker builds exercise the plugin. |
| [#9](https://github.com/DevelopmentSprint/crypto-census/pull/9) ([f37bbbb](https://github.com/DevelopmentSprint/crypto-census/commit/f37bbbb1730520f9adaca745a7671550e3729a9e)), Invoker Groovy | 6.0.0, with test-hook changes | Five JSON-reading hooks now use explicit `Map.get('properties')`; every original assertion remains. See the migration below. |
| [#10](https://github.com/DevelopmentSprint/crypto-census/pull/10) ([d2438d8](https://github.com/DevelopmentSprint/crypto-census/commit/d2438d8d80182e3e5bddf19dea335fb8013d4b4c)), Failsafe | 3.6.0 | Forked runtime integration tests and their coverage checks remain enabled. Static-only and bootstrap commands explicitly skip ITs; full verification does not. |
| [#12](https://github.com/DevelopmentSprint/crypto-census/pull/12) ([6ebe393](https://github.com/DevelopmentSprint/crypto-census/commit/6ebe3935d499ca76aea9367bd8688592fede2968)), Deploy plugin | 3.2.0 | Plugin resolution and disposable local-file deployment validate the upgrade without publishing a release. |
| [#13](https://github.com/DevelopmentSprint/crypto-census/pull/13) ([0082190](https://github.com/DevelopmentSprint/crypto-census/commit/00821901c7d18c443533cd691c4b99ba508ab28b)), Install plugin | 3.2.0 | Local installation and the Invoker repository exercise installation. |
| [#20](https://github.com/DevelopmentSprint/crypto-census/pull/20) ([7c3e5da](https://github.com/DevelopmentSprint/crypto-census/commit/7c3e5daa675ba6b910f9d61ae84724c06eacc46c)), Maven wrapper distribution | 3.10.0 | Fresh wrapper bootstrap verifies SHA-256; an independent download also matches upstream SHA-512. The proposal's unrelated Windows line-ending conversion is omitted. |
| [#21](https://github.com/DevelopmentSprint/crypto-census/pull/21) ([25c537b](https://github.com/DevelopmentSprint/crypto-census/commit/25c537bee1d408e492c387fe30ee98983a001313)), Site plugin | 3.22.0 | Plugin goal resolution is checked. The public website continues to use VitePress, not Maven Site. |
| [#22](https://github.com/DevelopmentSprint/crypto-census/pull/22) ([caf2234](https://github.com/DevelopmentSprint/crypto-census/commit/caf22342299ffdd6c30963185c7828bd3f93ef7c)), deploy-pages | 5.0.1 | Artifact name, environment, OIDC permissions and exact-source deployment checks remain unchanged. A production deployment is a separate verification step. |
| [#23](https://github.com/DevelopmentSprint/crypto-census/pull/23) ([159bbaf](https://github.com/DevelopmentSprint/crypto-census/commit/159bbaf8fba8eac7b822e2ea284b765647a1e01c)), TypeScript | Retain 5.9.3; defer 7.0.2 | TypeScript 7 removes the compiler API required by the current Vue type checker. Type checking is not disabled. |
| [#24](https://github.com/DevelopmentSprint/crypto-census/pull/24) ([1ac48b1](https://github.com/DevelopmentSprint/crypto-census/commit/1ac48b10fe9166bd62be71b5cdd83208de2a9820)), upload-artifact | 7.0.2 | Named ZIP artifacts, retention and missing-file behavior remain unchanged. Coverage is still downloaded from the exact producing run. |

Each proposal links to the exact head reviewed. Their existing hosted CI is supporting evidence, not a substitute for testing the combined change. Most proposals passed the supported matrix individually. The existing JDK 28 early-access advisory failure is separate from the supported release gates.

## Maven 3.10 migration

[Maven 3.10.0](https://maven.apache.org/docs/3.10.0/release-notes.html) changes dependency traversal order, tightens model validation, and scopes repository credentials more strictly. This project keeps the new validation and credential protections enabled. Do not add compatibility flags that disable them merely to make a build green.

The distribution SHA-256 is `1f6d9909266510f039f59aa0e13dcd2c66da85f043e56276e41f2918f8bddaff`. Updating the wrapper URL and checksum together is required. A warm wrapper cache alone does not prove a new checksum works; verify a fresh bootstrap too.

[Maven's plugin documentation](https://maven.apache.org/plugins/) and the individual plugin release notes establish their supported Java and Maven baselines. The selected plugin updates do not require raising this project's Java 17 runtime baseline. In particular, [Install 3.2.0](https://github.com/apache/maven-install-plugin/releases/tag/maven-install-plugin-3.2.0) and [Deploy 3.2.0](https://github.com/apache/maven-deploy-plugin/releases/tag/maven-deploy-plugin-3.2.0) include trust-boundary hardening.

## Failsafe 3.6 migration

[Failsafe 3.6](https://github.com/apache/maven-surefire/releases/tag/surefire-3.6.0) stops treating `skipTests` as an integration-test skip. Commands that intentionally run static analysis or install the dogfood plugin without tests now pass both `-DskipTests` and `-DskipITs`. The full `clean verify`, pre-push and supported CI matrix still run integration tests and enforce coverage. A fresh `clean verify -DskipTests -DskipITs` was also checked and skips both test phases successfully. The shaded-agent runtime coverage gate now follows actual integration-test execution: `skipTests` alone no longer disables that gate. Explicit `skipITs`, `maven.test.skip` and `jacoco.skip` remain supported.

An IT-only check (`clean verify -DskipTests`) ran all 33 agent integration tests (31 passed, two existing opt-in skips), then failed the unchanged unit-coverage floor because unit tests were absent. Invoking the runtime gate against that fresh child-JVM data also failed its unchanged line floor (79% versus 80%). This confirms that skipping unit tests does not silently waive coverage requirements. Use the complete verification command for a passing quality gate.

## Groovy JSON hooks

The unmodified Groovy 6 proposal failed five Invoker scenarios with `MissingPropertyException: No such property: name for class: java.util.LinkedHashMap$Entry`. Reading `component.properties` did not yield the JSON array expected by those hooks. Explicit `component.get('properties')` reads the JSON key without invoking Groovy's object-property shorthand.

This changes test access syntax, not Census findings or the assertions against them. Core, JSON and XML Groovy artifacts stay on the same version. The [Groovy 6 release notes](https://groovy-lang.org/releasenotes/groovy-6.0.html) also describe security and parsing changes; application code does not acquire a Groovy runtime dependency.

## Why TypeScript 7 is deferred

The proposed TypeScript 7.0.2 update fails `npm run docs:typecheck` before checking any theme code:

```text
ERR_PACKAGE_PATH_NOT_EXPORTED:
Package subpath './lib/tsc' is not defined by "exports"
```

The current `vue-tsc` 3.3.13 expects the JavaScript compiler API. Microsoft's [TypeScript 7 announcement](https://devblogs.microsoft.com/typescript/announcing-typescript-7-0/) confirms that 7.0 does not expose that API and specifically identifies Vue/Volar as affected. Retain the working pin until the Vue toolchain supports the new compiler API. Revisit this with a coordinated tooling upgrade and passing type, build, link and browser checks; do not replace Vue template checking with plain `tsc`.

## Actions compatibility

The selected [checkout](https://github.com/actions/checkout/tree/v7.0.1), [cache](https://github.com/actions/cache/tree/v6.1.0) and [setup-java](https://github.com/actions/setup-java/tree/v6.0.1) releases use Node 24 and require an appropriately updated runner. This repository uses GitHub-hosted runners. Organizations copying the workflows to self-hosted runners should check each action's documented runner minimum before upgrading.

[Upload artifact](https://github.com/actions/upload-artifact/tree/v7.0.2) and [deploy-pages](https://github.com/actions/deploy-pages/tree/v5.0.1) preserve the configured artifact workflow. The newer checkout's fork protections supplement the Pages job's existing trust checks. Never enable unsafe checkout to work around a failed provenance check.

## Verification scope

The combined upgrades passed `clean verify` on macOS with JDK 25 and a fresh Maven 3.10.0 wrapper distribution: 774 tests discovered, 770 passed, four existing opt-in tests skipped, and all 21 Maven Invoker scenarios passed. Static analysis and coverage thresholds remained enabled. Documentation type checking, dependency audit (zero findings), coverage-provenance regression tests and production build/link checks also passed.

Install and Deploy were exercised with the parent POM and a disposable local file repository; deployed bytes matched the source POM. Site's help goal resolved successfully. These checks do not establish Maven Central publication, a production Pages deployment, or the combined cross-platform CI matrix. Those remain integration/release checks.

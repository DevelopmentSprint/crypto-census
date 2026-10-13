# Contributing

```sh
./mvnw clean verify
```

Modules:

- `census-core`: model, canonical names, risk policy, CBOM read/write. Zero runtime dependencies;
  output is validated against the official CycloneDX 1.6 schema in tests.
- `census-agent`: the `-javaagent` (shaded jar), with integration tests that run real JVMs, including
  the conformance tests with the OpenTelemetry Java agent and embedded Tomcat.
- `census-delivery`: evidence validation, SBOM preparation, delivery profiles and adapters, shared
  HTTPS transport, and private delivery receipts. It depends on core and stays outside the agent.
  Test fixtures generate temporary TLS identities.
- `census-cli`: standalone executable delivery JAR using the shared engine.
- `census-maven-plugin`: `prepare-agent` and `check` lifecycle integration; explicit `report` and
  `enforce` goals for sealed CI evidence; explicit `publish` and profile-based `delivery` goals.
  Maven Invoker tests exercise argument composition, odd paths, missing data, and failure modes.
- `census-demo`: a toy payments service that runs the plugin on itself.
- `census-app-servers`: Open Liberty and WildFly in containers; only with `-P app-servers` (needs
  Docker, skipped without it), never published.

Read the [architecture overview](architecture/overview.md) for module and process boundaries,
the [evidence pipeline](architecture/evidence-pipeline.md) for current-run identity and finalization,
and [enterprise delivery](architecture/enterprise-delivery.md) for adapter and transport responsibilities.

## Code quality

Every build on JDK 21+ runs a strict static analysis suite; every tool fails the build on any finding.
(The advisory early-access CI job turns it off with `-P '!quality'`: the analyzers lag behind new JDKs.)

| Stage | Tool | What it enforces |
|---|---|---|
| validate | [Spotless](https://github.com/diffplug/spotless) + [google-java-format](https://github.com/google/google-java-format) | formatting (`./mvnw spotless:apply` fixes it) |
| validate | [Checkstyle](https://checkstyle.org) with Google's `google_checks.xml` | Google Java Style |
| compile | [Error Prone](https://errorprone.info) + `javac -Xlint:all -Werror` | bug patterns at compile time |
| verify | [PMD](https://pmd.github.io) + CPD | error-prone, security and multithreading rules; copy-paste |
| verify | [SpotBugs](https://spotbugs.github.io) + [FindSecBugs](https://find-sec-bugs.github.io) | bytecode bug and security patterns, effort max, threshold low |
| verify | [JaCoCo](https://www.jacoco.org) | per-module coverage floors (the plugin's include the Invoker builds; the agent's also gate the shaded agent as it runs in the integration tests' child JVMs) |
| always | Maven Enforcer | pinned plugin versions, dependency convergence, no duplicate classes, Java 17 bytecode |
| CI | [OWASP Dependency-Check](https://owasp.org/www-project-dependency-check/) | known CVEs, fails on CVSS >= 7 (weekly and on `main`/`develop`) |
| GitHub | Dependabot | weekly Maven and GitHub Actions updates |

PMD and Checkstyle exclusions live in [`config/`](../config); SpotBugs findings are suppressed only on
the reviewed method or field, with `@SuppressFBWarnings` and a justification. Each one states why.
The Maven Wrapper pins Maven 3.9.12 and verifies its SHA-256.

**Git hooks** are installed by the first build (`core.hooksPath .githooks`):

- `pre-commit` runs the whole static analysis suite (no tests) for staged Java, POM, `config/`, or
  `.mvn/` changes and blocks the commit on any finding; documentation-only changes skip this suite;
- `pre-push` runs the full build with tests and coverage.

Use JDK 21+ for contributor checks. The pre-commit hook and Maven Wrapper select `$JAVA_HOME/bin/java` when `JAVA_HOME` is set, otherwise `java` from PATH. The pre-push hook invokes the wrapper directly. `git commit --no-verify` exists for emergencies; CI runs the same checks regardless.
To let Error Prone fix what it can: `./mvnw -P errorprone-patch test-compile && ./mvnw spotless:apply`.

## Contributing

The project follows [gitflow](https://nvie.com/posts/a-successful-git-branching-model/): `main` only
ever contains released versions, `develop` is the integration branch, and changes arrive through
`feature/*` branches merged into `develop`. Releases are cut from `release/*` branches, fixes to a
released version go through `hotfix/*`. Open pull requests against `develop`.

## Toolchain and local configuration

The agent and ordinary build require JDK 17 or newer; full static analysis requires JDK 21 or newer. Use JDK 25 for contribution work and `./mvnw --version` to confirm the selected Java. Maven 3.6.3 is the enforced minimum; the wrapper pins and checksums Maven 3.9.12. On Windows use `mvnw.cmd`.

The first lifecycle build through `initialize` sets this repository's `core.hooksPath` to `.githooks`. This replaces a previous local hooks-path choice; check `git config --show-origin --get core.hooksPath` before building if you maintain custom hooks. Linked worktrees share repository configuration unless you explicitly use Git's worktree configuration support.

For a single operation, `git -c core.hooksPath=/dev/null commit` or `git -c core.hooksPath=/dev/null push` opts out on POSIX; `--no-verify` also skips the corresponding hooks. This is an exceptional local bypass, not a way around required CI. To restore your prior persistent hooks path, set it with `git config --local core.hooksPath /your/hooks` after the build; a later lifecycle build reinstalls this project's path unless you pass `-Dgitbuildhook.gitconfig.skip=true`. Use that property on every build when retaining your own hooks or operating in a linked worktree.

Documentation-only commits do not run pre-commit static analysis; Java, POM, `config/`, and `.mvn/` changes do. The pre-push hook runs the full build. Format Java with `./mvnw spotless:apply` and run `./mvnw -B -ntp clean verify` before opening a pull request. The Docker conformance gate is documented in [CONFORMANCE.md](CONFORMANCE.md); a local Docker skip does not satisfy release verification.

## Changing contracts

Update the policy version whenever verdicts change and document the behavior in [CHANGELOG.md](../CHANGELOG.md). Keep Maven parameter documentation aligned with the plugin descriptor, and keep the marked README quickstart aligned with its executable Invoker fixture. Add focused regression coverage for behavior changes without weakening existing coverage floors or [conformance gates](CONFORMANCE.md#the-ratchet-rule).

Report ordinary bugs through repository issues with a minimal reproduction, JDK/vendor, Census version, command and sanitized error output. Use [SECURITY.md](../SECURITY.md) for vulnerabilities. Do not attach an unreviewed CBOM or credentials to public issues.

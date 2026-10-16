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
| CI | [OWASP Dependency-Check](https://owasp.org/www-project-dependency-check/) | known CVEs, fails on CVSS >= 7 (weekly, on demand, and on non-documentation `main`/`develop` pushes) |
| GitHub | Dependabot | weekly Maven and GitHub Actions updates |

PMD and Checkstyle exclusions live in [`config/`](../config); SpotBugs findings are suppressed only on
the reviewed method or field, with `@SuppressFBWarnings` and a justification. Each one states why.
The Maven Wrapper pins Maven 3.10.0 and verifies its SHA-256. See [dependency compatibility](reference/dependency-compatibility.md) for reviewed toolchain upgrades and migration constraints.

**Git hooks** are installed by the first build (`core.hooksPath .githooks`):

- `pre-commit` runs the whole static analysis suite (no tests) for staged Java, POM, `config/`, or
  `.mvn/` changes and blocks the commit on any finding; documentation-only changes skip this suite;
- `pre-push` always checks private tracker state and outgoing Git history. A verified documentation-only push runs documentation checks; all other pushes run the full build with tests and coverage.

Use JDK 21+ for contributor checks. The pre-commit hook and Maven Wrapper select `$JAVA_HOME/bin/java` when `JAVA_HOME` is set, otherwise `java` from PATH. The pre-push hook invokes the wrapper directly. `git commit --no-verify` exists for emergencies; CI runs the same checks regardless.
To let Error Prone fix what it can: `./mvnw -P errorprone-patch test-compile && ./mvnw spotless:apply`.

## Contributing

The project follows [gitflow](https://nvie.com/posts/a-successful-git-branching-model/): `main` only
ever contains released versions, `develop` is the integration branch, and changes arrive through
`feature/*` branches merged into `develop`. Releases are cut from `release/*` branches, fixes to a
released version go through `hotfix/*`. Open pull requests against `develop`.

## Toolchain and local configuration

The agent and ordinary build require JDK 17 or newer; full static analysis requires JDK 21 or newer. Use JDK 25 for contribution work and `./mvnw --version` to confirm the selected Java. Maven 3.6.3 is the enforced minimum; the wrapper pins and checksums Maven 3.10.0. On Windows use `mvnw.cmd`.

The first lifecycle build through `initialize` sets this repository's `core.hooksPath` to `.githooks`. This replaces a previous local hooks-path choice; check `git config --show-origin --get core.hooksPath` before building if you maintain custom hooks. Linked worktrees share repository configuration unless you explicitly use Git's worktree configuration support.

For a single operation, `git -c core.hooksPath=/dev/null commit` or `git -c core.hooksPath=/dev/null push` opts out on POSIX; `--no-verify` also skips the corresponding hooks. This is an exceptional local bypass, not a way around required CI. To restore your prior persistent hooks path, set it with `git config --local core.hooksPath /your/hooks` after the build; a later lifecycle build reinstalls this project's path unless you pass `-Dgitbuildhook.gitconfig.skip=true`. Use that property on every build when retaining your own hooks or operating in a linked worktree.

Documentation-only commits do not run pre-commit static analysis; Java, POM, `config/`, and `.mvn/` changes do. The pre-push hook uses the conservative documentation-only boundary described below. For Java or other full-build changes, format Java with `./mvnw spotless:apply` and run `./mvnw -B -ntp clean verify` before opening a pull request. Use the documentation checks below for docs-only changes. The Docker conformance gate is documented in [CONFORMANCE.md](CONFORMANCE.md); a local Docker skip does not satisfy release verification.

## Documentation-only checks

The shared classifier in `scripts/ci_changes.py` permits the faster path only for changes under `docs/`, under `scripts/docs/`, or to the exact root files `package.json` and `package-lock.json`. **`README.md` requires the full build** because the Maven plugin's `ReadmeQuickstartTest` executes its quickstart. Java, Maven, workflows, hooks, the classifier, analysis configuration, unknown paths, symlink/submodule changes and mixed changes require the full build. Missing or ambiguous Git history also requires the full build.

A local documentation-only push runs locked npm installation, dependency audit, Python/Node provenance tests, Vue type checking, and the production site build with internal link checks. The hosted **Build and check documentation** check still runs the complete browser suite; run `npm run docs:test` locally when changing navigation, layouts or interactions. Node.js and Python are required for the fast path.

The hook reads the full outgoing history, including intermediate changes that were later reverted, and always runs the private tracker guard before choosing checks. First pushes of feature/integration branches work when the named destination remote has been fetched. The fast path requires a clean tracked checkout, a single matching branch update of the checked-out commit, and an ancestral destination commit when that branch already exists. Tags, multiple-ref pushes, unusual destinations, release/hotfix branches, force updates and unverified history use the full path. Fetch the named remote before pushing; do not bypass hooks to get a faster result.

CI starts for every configured event. Its **Required CI** aggregate accepts either successful Java matrix, dogfood and application-server jobs for a full build, or explicitly skipped Java jobs for a successfully classified documentation-only change. A failed classifier, cancellation or unexpected job result cannot pass. The advisory JDK job follows the same scope decision. Scheduled and manually requested dependency scans remain full; only verified documentation-only push scans skip Java dependency analysis. Documentation dependency audit still runs.

A manual CI run always performs the full matrix and produces fresh evidence. If documentation publication cannot find unexpired evidence for unchanged runtime code, refresh it explicitly:

```sh
gh workflow run ci.yml --ref develop
```

This command requests a full CI run on the current `develop` source. Successful original-repository `develop` runs publish the site after its normal documentation checks. See [publication provenance](reference/documentation.md#publish-through-ci) for the evidence reuse rules.

## Changing contracts

Update the policy version whenever verdicts change and document the behavior in [CHANGELOG.md](../CHANGELOG.md). Keep Maven parameter documentation aligned with the plugin descriptor, and keep the marked README quickstart aligned with its executable Invoker fixture. Add focused regression coverage for behavior changes without weakening existing coverage floors or [conformance gates](CONFORMANCE.md#the-ratchet-rule).

Report ordinary bugs through repository issues with a minimal reproduction, JDK/vendor, Census version, command and sanitized error output. Use [SECURITY.md](../SECURITY.md) for vulnerabilities. Do not attach an unreviewed CBOM or credentials to public issues.

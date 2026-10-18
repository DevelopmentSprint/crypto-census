# Install from source

A local source build installs the plugin and its libraries into your Maven repository and creates the standalone agent and CLI JARs.

## Check prerequisites

You need Git, a JDK, and network access for the first Maven dependency download. Use JDK 21+ for the full repository build and quality gates. Maven Wrapper supplies the pinned Maven version; an existing system Maven installation is unnecessary for building this repository.

The runtime agent requires Java 17+. Supported JVM vendors, operating systems, app servers, and other-agent combinations are listed in [compatibility](../COMPATIBILITY.md). A newer or unlisted runtime is unverified rather than automatically supported.

## Build and install

Run these commands from a directory where you keep source checkouts:

```sh
git clone https://github.com/DevelopmentSprint/crypto-census.git
cd crypto-census
./mvnw --version
./mvnw -B -ntp clean install
```

Confirm that the wrapper reports the intended Java installation before building. On Windows, use `mvnw.cmd` in place of `./mvnw`. The build also configures the repository's [local quality hooks](../CONTRIBUTING.md#toolchain-and-local-configuration).

The `install` lifecycle includes verification and installs the parent, agent, core, delivery, CLI, and Maven plugin artifacts locally. A successful install in your account does not install them for a different user, container, or hosted CI runner.

## Choose an artifact

| Artifact | Source-build location | Use |
|---|---|---|
| Shaded agent | `census-agent/target/census-agent-0.1.0.jar` | `-javaagent` in an application JVM. |
| Shaded CLI | `census-cli/target/census-cli-0.1.0.jar` | Explicit delivery of existing evidence. |
| Maven plugin | Installed as `com.developmentsprint:census-maven-plugin:0.1.0` | Preparation, checking, finalization, and delivery in a Maven project. |

Use the main shaded JAR rather than `original-*.jar`, sources, or Javadoc JARs. To copy the agent into an existing application directory, run this from the Census checkout and replace the destination:

```sh
cp census-agent/target/census-agent-0.1.0.jar /path/to/application/census-agent.jar
```

Then follow [Maven setup](../examples/maven-project.md) or [runtime deployment](../guides/runtime-deployment.md). The CLI needs a valid input report and a delivery configuration; it does not collect runtime evidence on its own.

## Use an isolated Maven repository

A separate local repository keeps source-built artifacts isolated and automation reproducible. Pass the same repository setting when building Census and when running a consuming application:

```sh
./mvnw -B -ntp clean install -Dmaven.repo.local=/path/to/census-maven-repository
```

In the consumer project, use its wrapper or `mvn` with the identical `-Dmaven.repo.local` path. A fresh CI runner must build and install Census before resolving the plugin. The [repository's CI workflow](https://github.com/DevelopmentSprint/crypto-census/blob/develop/.github/workflows/ci.yml) demonstrates that order.

## Transition to a published release

Do not assume locally installed `0.1.0` bytes match an eventual Central artifact with the same coordinates. Verify the release through an empty local repository or follow the cache replacement and signature-verification steps in the [artifact installation reference](../INSTALLATION.md#after-010-is-published).

The [release runbook](../RELEASING.md) defines publication and verification steps.

For testing development artifacts in an external application, follow the [public snapshot procedure](../SNAPSHOTS.md).

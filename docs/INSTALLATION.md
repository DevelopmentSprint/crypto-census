# Installation

Build and install from source using the steps below. For development artifacts, see [public snapshots](SNAPSHOTS.md). For stable published artifacts, see [release installation](#after-010-is-published).

## From source

Use Git and JDK 21+ for the full build; Maven Wrapper downloads its pinned Maven distribution and verifies the distribution checksum. The agent runs on Java 17+; see [compatibility](COMPATIBILITY.md). The first lifecycle build installs [local Git hooks](CONTRIBUTING.md#toolchain-and-local-configuration).

```sh
git clone https://github.com/DevelopmentSprint/crypto-census.git
cd crypto-census
./mvnw --version
./mvnw -B -ntp clean install
```

`install` includes the complete verification lifecycle and puts the parent POM, core, agent, delivery library, CLI and Maven plugin in your local Maven repository. Use version `0.1.0` in the [quickstart](../README.md#quick-start) in your application; installing in one user account does not install into another account or CI runner's repository. On Windows, use `mvnw.cmd`.

## Standalone agent

After the source build, copy the shaded agent to the application directory (run from the Census checkout, substituting your application directory):

```sh
cp census-agent/target/census-agent-0.1.0.jar /path/to/application/census-agent.jar
```

The shaded main JAR contains its dependencies; do not use `original-census-agent-*.jar`, a sources JAR, or a Javadoc JAR. Run from the application directory:

```sh
java -javaagent:census-agent.jar=output=cbom.json,app=payments-api -jar payments-api.jar
```

The destination directory must allow the JVM user to create and rename files. The agent writes the report at graceful shutdown. See [operations](OPERATIONS.md) for options and output security.

<a id="after-010-is-published"></a>

## After 0.1.0 is published

Before switching from a source-installed candidate to the public release, remove the `0.1.0` directory for **each** of `census-parent`, `census-core`, `census-agent`, `census-delivery`, `census-cli`, and `census-maven-plugin` under your configured local repository (normally `~/.m2/repository/com/developmentsprint/`). Maven otherwise keeps using locally installed candidate bytes under those release coordinates; `-U` is not a substitute for clearing them. For an isolated comparison, pass `-Dmaven.repo.local=/path/to/a/new-empty-directory` on every verification command instead of changing your existing cache.

Use `<version>0.1.0</version>` in the plugin snippet. To acquire the standalone artifact through Maven from an empty working directory:

```sh
mvn org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy \
  -Dartifact=com.developmentsprint:census-agent:0.1.0 \
  -DoutputDirectory=.
mv census-agent-0.1.0.jar census-agent.jar
```

For manual download and verification, download the JAR and detached signature from the same release. Obtain the maintainer's signing-key fingerprint through an independently trusted channel and confirm the imported key matches it before trusting a signature:

```sh
curl --fail --location --remote-name https://repo.maven.apache.org/maven2/com/developmentsprint/census-agent/0.1.0/census-agent-0.1.0.jar
curl --fail --location --remote-name https://repo.maven.apache.org/maven2/com/developmentsprint/census-agent/0.1.0/census-agent-0.1.0.jar.asc
gpg --verify census-agent-0.1.0.jar.asc census-agent-0.1.0.jar
```

A matching checksum detects corruption; a signature proves identity only when you trust the signing key. The release record must provide artifact SHA-256 values and the signing-key fingerprint before these instructions are advertised as a verified public installation.

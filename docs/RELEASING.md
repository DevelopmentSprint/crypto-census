# Release runbook

The `0.1.0` candidate is unpublished. Preparing and testing artifacts does not publish them. The maintainer owns signing, Central publication, release tagging, remote pushes, and the final public-visibility decision.

## Immediate prerequisites

- Revoke and replace any credential exposed outside its intended secret store immediately. Record any incident-specific rotation work in private release evidence; rotation must not wait for repository publication. Update the repository secret and rerun dependency scanning without logging the key.
- Keep the mailbox in [SECURITY.md](../SECURITY.md) monitored. Verify delivery whenever the address or mail configuration changes.
- Scan every reachable commit/ref with a dedicated secret detector, then review author emails, commit messages, session links and other metadata for privacy. Record tool/version, scope, findings and limits. Rotate confirmed secrets even if history is later rewritten; deletion is not revocation. Do not erase history without an explicit decision.
- Confirm ownership of the `com.developmentsprint` Central namespace and a trusted signing key. Store the Central Portal token as Maven server ID `central` in private `~/.m2/settings.xml`. Keep credentials and passphrases out of the repository and command logs.

## Set up the Central namespace and credentials

The namespace `com.developmentsprint` requires control of `developmentsprint.com`. Sign in to [Central Portal](https://central.sonatype.com), open your account menu, select **View Namespaces**, and add `com.developmentsprint`. Copy its verification key into a new DNS TXT record at `developmentsprint.com` (usually host `@`). Preserve existing TXT records, including mail and other verification records.

Check the record through public DNS before selecting **Verify Namespace**:

```sh
dig +short TXT developmentsprint.com
```

The TXT value must match the portal's verification key. Sonatype checks the exact domain, not a `maven-central` subdomain. Wait until the portal reports **Verified**. A GitHub login's automatically verified `io.github.<username>` namespace does not grant this domain namespace. See [Sonatype namespace registration](https://central.sonatype.org/register/namespace/) for verification and existing-namespace recovery.

If the portal says the namespace is already registered to an inaccessible older account, keep `com.developmentsprint` and request publishing-access recovery from [Central Support](mailto:central-support@sonatype.com). Provide the current Portal account, namespace, public project URL, any known former account identity, and your ability to prove control of `developmentsprint.com`; follow the DNS challenge supplied by support. Previously verified namespaces require [human review to add users or transfer ownership](https://central.sonatype.org/faq/verify-ownership/). A new token alone does not restore namespace access. Continue unsigned verification while recovery is pending; do not switch coordinates or follow retired OSSRH/Jira registration instructions.

Generate a publishing token at [Central Portal user tokens](https://central.sonatype.com/usertoken). Save both generated fields in a secret manager: the token username and token password are distinct from your login credentials. The portal shows the token only once. Configure Maven server ID `central` in a private settings file; the following template contains environment references only:

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <servers>
    <server>
      <id>central</id>
      <username>${env.CENSUS_CENTRAL_USERNAME}</username>
      <password>${env.CENSUS_CENTRAL_PASSWORD}</password>
    </server>
  </servers>
</settings>
```

Merge the server entry into existing `~/.m2/settings.xml`, or save a separate file and pass `-s /private/path/central-settings.xml` to Maven. On POSIX systems, set its permissions to `0600`. Inject both variables from your secret manager in the publishing process; do not paste credentials into chat, source files, command arguments or shell history. A token stored only as a GitHub Actions secret is available only to an authorized workflow, not a local Maven invocation. See [token generation](https://central.sonatype.org/publish/generate-portal-token/) and [Maven publishing configuration](https://central.sonatype.org/publish/publish-portal-maven/#credentials).

Use an existing organization-approved signing key, or create one interactively with `gpg --full-generate-key`. Choose a public signing identity and protect the private key with a passphrase. Record the full public fingerprint, retain the revocation certificate securely, and publish only the public key to a Central-supported keyserver:

```sh
gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_PUBLIC_KEY_FINGERPRINT
```

Verify that the public key can be retrieved independently before signing. Select the intended key explicitly with `-Dgpg.keyname=YOUR_PUBLIC_KEY_FINGERPRINT` on signing commands. Prefer a locally unlocked GPG agent; unattended builds can obtain `MAVEN_GPG_PASSPHRASE` from a secret manager. Enable `-Dgpg.bestPractices=true` to reject unsafe passphrase configuration. Never export a private signing key into the repository or supply the passphrase as a Maven argument. See [Central signing requirements](https://central.sonatype.org/publish/requirements/gpg/) and [Maven GPG usage](https://maven.apache.org/plugins/maven-gpg-plugin/usage.html).

Namespace verification, token access, and signing-key access are separate prerequisites. Confirm all three before upload; none is established by a successful unsigned build.

## Freeze the release source

After accepting the implementation, cut `release/0.1.0` from the accepted integration state using the repository's gitflow process. The prepared candidate already uses `0.1.0`; confirm every reactor parent/project still has that version and the parent SCM tag is `v0.1.0`. Record the real release date in the changelog only when publishing is scheduled. The release profile rejects snapshot project or dependency versions.

Freeze a clean, committed candidate before final verification. Record its commit and tree IDs, toolchain versions, verification logs and artifact SHA-256 values in a private release evidence directory:

```sh
git status --short
git rev-parse HEAD
git rev-parse 'HEAD^{tree}'
./mvnw --version
```

The signed build must use this exact revision. Any source change invalidates its previous acceptance and requires a new candidate and applicable verification. Do not tag an unrelated merge commit after testing a different tree. At release completion, `v0.1.0` must identify the tested release source; retain the build commit identity even if gitflow creates a merge commit with the same tree.

## Required verification

Use a supported contributor JDK (21+; JDK 25 is the normal choice). Run complete lifecycle gates before asking the maintainer to sign:

```sh
./mvnw -B -ntp clean verify
./mvnw -B -ntp -P app-servers -pl census-app-servers -am verify -Dcensus.requireDocker=true
./mvnw -B -ntp -P release clean verify -Dgpg.skip=true
```

The app-server Failsafe report must contain both Liberty and WildFly cases with zero failures, errors and skips. Capture the report before the next clean build. The release-profile command checks release packaging without accessing a private signing key. `package` alone is insufficient because it stops before integration-test verification and quality gates.

Require supported-platform CI and the dependency scan for the exact candidate commit. The early-access row remains advisory; do not downgrade a required row to obtain a release. Record CI run links, conclusion, source SHA, and any applicable conditional skips; see [conformance gates](CONFORMANCE.md).

## Inspect the artifact set

| Artifact | Required files |
|---|---|
| `census-parent` | POM only; no main, source or Javadoc JAR. |
| `census-core` | Main JAR, source JAR, Javadoc JAR, POM. |
| `census-agent` | Shaded main JAR, source JAR, Javadoc JAR, POM. |
| `census-delivery` | Main JAR, source JAR, Javadoc JAR, test-fixture JAR (`tests` classifier), POM. |
| `census-cli` | Executable shaded main JAR, source JAR, Javadoc JAR, POM. |
| `census-maven-plugin` | Main JAR with plugin descriptor, source JAR, Javadoc JAR, POM. |

All use group `com.developmentsprint` and version `0.1.0`. The demo and app-server test modules are excluded from deployment. Do not publish an `original-*` agent or CLI JAR. The delivery test-fixture JAR contains only shared test support, with no static private keys or runtime dependency on the agent. Verify the CLI main class and that neither Byte Buddy nor agent instrumentation is included in the CLI. Inspect the agent manifest and relocated classes, and verify the content of `META-INF/LICENSE` (Apache 2.0), `META-INF/NOTICE` (Development Sprint copyright plus the retained Byte Buddy/Rafael Winterhalter notice), and `META-INF/licenses/ASM` (ASM copyright and BSD terms). Each published main JAR must carry the project license and notice; checking only that a file with the right name exists is insufficient. Confirm license/SCM/developer metadata in POMs, usable Javadocs and correct source contents. Signing adds detached signatures for published artifacts/POMs; record checksums for the exact signed bundle contents.

## Test a locally staged candidate

Central cannot supply an unpublished first release. Stage the candidate in a dedicated local repository, then point a separate consumer at it. From the candidate checkout, choose fresh paths outside the checkout:

```sh
CENSUS_STAGE_REPO=$(mktemp -d /tmp/census-stage.XXXXXX)
./mvnw -B -ntp -P release -pl census-core,census-agent,census-delivery,census-cli,census-maven-plugin -am install \
  -Dgpg.skip=true -Dmaven.repo.local="$CENSUS_STAGE_REPO"
export CENSUS_CANDIDATE_REPOSITORY="file://$CENSUS_STAGE_REPO"
CENSUS_CONSUMER_REPO=$(mktemp -d /tmp/census-consumer-cache.XXXXXX)
```

Create a fresh external Maven application with at least one real cryptographic unit test, then paste the marked README quickstart verbatim. Configure a temporary Maven settings profile with **both** dependency and plugin repositories; plugin artifacts are not resolved solely through `<repositories>`:

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <profiles>
    <profile>
      <id>candidate</id>
      <repositories>
        <repository>
          <id>census-candidate</id>
          <url>${env.CENSUS_CANDIDATE_REPOSITORY}</url>
        </repository>
      </repositories>
      <pluginRepositories>
        <pluginRepository>
          <id>census-candidate</id>
          <url>${env.CENSUS_CANDIDATE_REPOSITORY}</url>
        </pluginRepository>
      </pluginRepositories>
    </profile>
  </profiles>
</settings>
```

Save as `candidate-settings.xml`, then run inside that external application:

```sh
mvn -s candidate-settings.xml -P candidate -Dmaven.repo.local="$CENSUS_CONSUMER_REPO" verify
mvn -s candidate-settings.xml -P candidate -Dmaven.repo.local="$CENSUS_CONSUMER_REPO" \
  verify -Dcensus.failOn=quantum-vulnerable
```

Use a SHA-256/RSA-2048 workload: the first run must pass and the stricter run must fail for the expected policy reason. Inspect a new current-run CBOM, policy version, counts and health in each run. Also exercise [JaCoCo/Failsafe and multimodule recipes](RECIPES.md). Verify the packaged CLI and actual Maven `publish` goal against a local HTTPS receiver using the same report and expected digest; require byte equality, equivalent delivery identities, private receipts, and no credential leakage. Follow [delivery limitations](DELIVERY.md) when testing policy-failed builds; the generic receiver does not establish vendor ingestion or current-run provenance.

With another fresh consumer cache and external directory, run the [agent acquisition command](INSTALLATION.md#after-010-is-published) using the same candidate settings. Run a standalone cryptographic workload, stop it gracefully and validate its CBOM. Check the acquired agent's SHA-256 against the staged artifact. Archive the exact consumer POM/source and logs as smoke-test evidence.

## Sign and upload without publishing

The maintainer returns to the frozen candidate checkout, confirms a clean worktree and the recorded commit/tree, and runs:

```sh
./mvnw -B -ntp -P release deploy -Dgpg.bestPractices=true \
  -Dgpg.keyname=YOUR_PUBLIC_KEY_FINGERPRINT
```

The profile has `autoPublish=false` and waits until validation. Confirm the deployment reaches **VALIDATED**, record its deployment ID, and inspect the uploaded artifact list. This step rebuilds/signs, so compare its artifact contents with the accepted source and record the uploaded checksums; unsigned-package testing alone does not prove the signed bundle's identity. Do not use `-Dgpg.skip` for this step.

## Test the signed, unpublished deployment

Use Sonatype's [validated-deployment download API](https://central.sonatype.org/publish/publish-portal-api/#manually-testing-a-deployment-bundle). Select the deployment-specific repository URL, not the endpoint that mixes all validated deployments:

```text
https://central.sonatype.com/api/v1/publisher/deployment/DEPLOYMENT_ID/download
```

Set `CENSUS_CANDIDATE_REPOSITORY` to that URL. Add a private `<servers>` entry to the temporary settings for ID `census-candidate`, with `configuration/httpHeaders/property` containing name `Authorization` and value `Bearer ${env.CENSUS_PORTAL_BEARER}`. Follow Sonatype's token encoding instructions; never print or commit the token.

Repeat the external Maven and standalone smoke tests with new empty caches. Download detached signatures from the same deployment, verify every published POM/JAR against the trusted signing-key fingerprint, and compare artifact hashes to the upload record. A failed check blocks publication; drop or replace the candidate only after diagnosing the failure. A successful test of the local staging repository does not replace this signed-deployment check.

## Publish and verify public resolution

The maintainer explicitly publishes the accepted deployment in Central Portal. Wait for status **PUBLISHED**, then remove the candidate settings/repository and all authentication from the consumer test. Use new empty local caches and ordinary Central resolution to repeat the quickstart and standalone download/signature verification. Confirm parent/core/agent/delivery/CLI/plugin coordinates, classifiers and POMs resolve from `repo.maven.apache.org`.

Complete the accepted gitflow release: merge the release into `main`, create the release tag identifying the verified source, merge back to `develop`, and advance development to `0.1.1-SNAPSHOT` with development SCM metadata reset to `HEAD`. Review and authorize remote pushes and the GitHub release separately. Publish the signing-key fingerprint and artifact SHA-256 values with the release record, then update installation text to reflect verified public availability. Never reuse an already published version for different bytes.

## Public repository settings

The repository is public, and GitHub private vulnerability reporting is enabled. Before announcing an artifact release, verify that a separate signed-in account can open the [private reporting form](https://github.com/DevelopmentSprint/crypto-census/security/advisories/new), without submitting a test vulnerability. Keep the email fallback in [SECURITY.md](../SECURITY.md).

Protections on `develop` and `main` require pull requests, the 13 mandatory jobs named in [CONFORMANCE.md](CONFORMANCE.md), and an up-to-date branch; they apply to administrators and block force pushes and deletion. Recheck these settings through the API/UI before release. The configured approving-review count is zero, so an independent review remains a release-process gate rather than a GitHub-enforced approval. Record that review and verify all required jobs for the exact candidate; the advisory early-access failure is not a required-job failure.

Keep the release checklist, security-channel check, dependency-scan record, history-scan scope, CI results and artifact evidence together. Source changes remain reviewable even when operator credentials or hosting settings are still pending; record those gates as pending rather than claiming publication readiness.

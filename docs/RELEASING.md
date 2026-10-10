# Release runbook

The `0.1.0` candidate is unpublished. Preparing and testing artifacts does not publish them. The maintainer owns signing, Central publication, release tagging, remote pushes, and the final public-visibility decision.

## Immediate prerequisites

- Revoke and replace any credential exposed outside its intended secret store immediately. Record any incident-specific rotation work in private release evidence; rotation must not wait for repository publication. Update the repository secret and rerun dependency scanning without logging the key.
- Confirm the mailbox in [SECURITY.md](../SECURITY.md) exists and receives mail. Remove its release-blocker marker only after verification.
- Scan every reachable commit/ref with a dedicated secret detector, then review author emails, commit messages, session links and other metadata for privacy. Record tool/version, scope, findings and limits. Rotate confirmed secrets even if history is later rewritten; deletion is not revocation. Do not erase history without an explicit decision.
- Confirm ownership of the `com.developmentsprint` Central namespace and a trusted signing key. Store the Central Portal token as Maven server ID `central` in private `~/.m2/settings.xml`. Keep credentials and passphrases out of the repository and command logs.

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
| `census-maven-plugin` | Main JAR with plugin descriptor, source JAR, Javadoc JAR, POM. |

All use group `com.developmentsprint` and version `0.1.0`. The demo and app-server test modules are excluded from deployment. Do not publish an `original-*` agent JAR. Inspect the agent manifest and relocated classes, and verify the content of `META-INF/LICENSE` (Apache 2.0), `META-INF/NOTICE` (Development Sprint copyright plus the retained Byte Buddy/Rafael Winterhalter notice), and `META-INF/licenses/ASM` (ASM copyright and BSD terms). Each published main JAR must carry the project license and notice; checking only that a file with the right name exists is insufficient. Confirm license/SCM/developer metadata in POMs, usable Javadocs and correct source contents. Signing adds detached signatures for published artifacts/POMs; record checksums for the exact signed bundle contents.

## Test a locally staged candidate

Central cannot supply an unpublished first release. Stage the candidate in a dedicated local repository, then point a separate consumer at it. From the candidate checkout, choose fresh paths outside the checkout:

```sh
CENSUS_STAGE_REPO=$(mktemp -d /tmp/census-stage.XXXXXX)
./mvnw -B -ntp -P release -pl census-core,census-agent,census-maven-plugin -am install \
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

Use a SHA-256/RSA-2048 workload: the first run must pass and the stricter run must fail for the expected policy reason. Inspect a new current-run CBOM, policy version, counts and health in each run. Also exercise [JaCoCo/Failsafe and multimodule recipes](RECIPES.md).

With another fresh consumer cache and external directory, run the [agent acquisition command](INSTALLATION.md#after-010-is-published) using the same candidate settings. Run a standalone cryptographic workload, stop it gracefully and validate its CBOM. Check the acquired agent's SHA-256 against the staged artifact. Archive the exact consumer POM/source and logs as smoke-test evidence.

## Sign and upload without publishing

The maintainer returns to the frozen candidate checkout, confirms a clean worktree and the recorded commit/tree, and runs:

```sh
./mvnw -B -ntp -P release deploy
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

The maintainer explicitly publishes the accepted deployment in Central Portal. Wait for status **PUBLISHED**, then remove the candidate settings/repository and all authentication from the consumer test. Use new empty local caches and ordinary Central resolution to repeat the quickstart and standalone download/signature verification. Confirm parent/core/agent/plugin coordinates, classifiers and POMs resolve from `repo.maven.apache.org`.

Complete the accepted gitflow release: merge the release into `main`, create the release tag identifying the verified source, merge back to `develop`, and advance development to `0.1.1-SNAPSHOT` with development SCM metadata reset to `HEAD`. Review and authorize remote pushes and the GitHub release separately. Publish the signing-key fingerprint and artifact SHA-256 values with the release record, then update installation text to reflect verified public availability. Never reuse an already published version for different bytes.

## Public repository settings

Before announcing availability, enable GitHub private vulnerability reporting and verify the private reporting path from a separate account. Replace SECURITY.md's pending-channel wording only after it is usable. Apply branch protections/rulesets to `develop` and `main`, requiring the exact gate job names in [CONFORMANCE.md](CONFORMANCE.md); verify the settings through the API/UI and check that an unreviewed or failing change cannot merge. If the hosting plan prevents protection while private, perform and verify this step as part of the visibility change before accepting outside contributions.

Keep the release checklist, security-channel check, dependency-scan record, history-scan scope, CI results and artifact evidence together. Source changes remain reviewable even when operator credentials or hosting settings are still pending; record those gates as pending rather than claiming publication readiness.

# Development snapshots

Snapshots let external applications exercise Crypto Census before a stable release. They are public, mutable development builds at `https://central.sonatype.com/repository/maven-snapshots/`, currently retained for 90 days. Sonatype performs no stable-release validation for snapshots. Passing these checks does not establish signatures, immutable release identity, Central validation, or readiness to publish `0.1.0`. See [Sonatype's snapshot documentation](https://central.sonatype.org/publish/publish-portal-snapshots/) and the separate [stable release procedure](RELEASING.md).

## Operator setup

Enable snapshot publishing for the verified `com.developmentsprint` namespace in Central Portal before running the workflow. Namespace permission and token access remain prerequisites. Create a separate GitHub environment named `maven-snapshots`, restricted to `develop`, with a required maintainer reviewer. Store only `CENTRAL_USERNAME` and `CENTRAL_PASSWORD` as environment secrets. These are Central Portal token fields, not account login credentials. Snapshots do not require the stable release signing key. Keep `maven-central` restricted to `main` and `release/*`.

The **Publish development snapshots** workflow is manual and accepts only `develop` in the original repository. Once its implementation is on the default branch and the accepted source is on `develop`:

1. Run full **CI** and **Dependency check** on the exact accepted `develop` commit. A manual CI dispatch forces every required job to run even for documentation changes.
2. Dispatch `central-snapshots.yml` on `develop`, supplying `expected_sha` (the full lowercase commit SHA), `ci_run`, and `dependency_run`. The guards reject different branches, forks, moved SHAs, skipped checks, PR evidence, and stale attempts.
3. Approve the `maven-snapshots` job after checking that evidence. Approval permits immediate public deployment; there is no Portal staging or separate Publish action for snapshots.
4. Check both deployment and the independent public-consumer job. A failed consumer job leaves an already published snapshot in place. Diagnose before retrying; repeated deployments overwrite mutable snapshot metadata.

The source remains `0.1.0`. Only the disposable workflow checkout changes to `0.1.0-SNAPSHOT`: the root project version, all seven module parent versions (including the nonpublished demo/app-server fixtures), and root SCM tag `HEAD`. Dependencies and stable README defaults stay intact. Both the source and every transformed reactor version are checked before upload credentials are injected. The job summary records source SHA/tree, source and generated versions, exact CI evidence, and transformed POM SHA-256 values. The source CI evidence applies before transformation; the transformed reactor also runs `clean verify` without credentials.

The `snapshot` profile enforces snapshot project/parent versions and uses Maven Deploy Plugin with `deployAtEnd=true` and only a `snapshotRepository`. It attaches source and Javadoc JARs and publishes the same 22 POM/JAR/classifier coordinates as the stable artifact set, without signing. Demo and app-server modules remain nondeployable. The `release` profile and stable workflow continue rejecting snapshots. Activating both profiles fails their contradictory version rules. No workflow creates tags, releases, or branch promotions.

## External consumer verification

Use a contributor JDK (25 recommended) and Maven; consumers require no credentials. Both dependencies and build plugins need the snapshot repository. This settings profile enables snapshots only at that endpoint while ordinary release dependencies continue resolving from Central:

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <profiles><profile><id>census-snapshots</id>
    <repositories><repository>
      <id>central-snapshots</id>
      <url>https://central.sonatype.com/repository/maven-snapshots/</url>
      <releases><enabled>false</enabled></releases>
      <snapshots><enabled>true</enabled><updatePolicy>always</updatePolicy></snapshots>
    </repository></repositories>
    <pluginRepositories><pluginRepository>
      <id>central-snapshots</id>
      <url>https://central.sonatype.com/repository/maven-snapshots/</url>
      <releases><enabled>false</enabled></releases>
      <snapshots><enabled>true</enabled><updatePolicy>always</updatePolicy></snapshots>
    </pluginRepository></pluginRepositories>
  </profile></profiles>
  <activeProfiles><activeProfile>census-snapshots</activeProfile></activeProfiles>
</settings>
```

Use `0.1.0-SNAPSHOT` in the [README plugin quickstart](../README.md), leaving other plugin settings intact. To run the automated fresh-consumer check from a source checkout:

```sh
python3 scripts/release/snapshot_consumer.py --version 0.1.0-SNAPSHOT --maven "$PWD/mvnw"
```

The script creates external temporary applications and two empty Maven caches with explicit user/global settings. It uses the README plugin snippet with only its version changed, runs real SHA-256/RSA-2048 JUnit tests, checks collected CBOM assets, and requires a second clean run to fail for the expected `quantum-vulnerable` policy reason. It independently acquires/runs the standalone agent, checks its collected CBOM, resolves all 22 expected artifacts (including source/Javadoc and delivery test fixtures), and starts the executable CLI with `--help`. The workflow runs this in a separate job with no publishing environment or inherited publishing cache. Temporary consumer files are deleted after the check.

For local transport testing, `--repository file:///absolute/path/to/staged-repository` uses the same consumer. A local pass tests packaging and consumption; only a successful workflow consumer job verifies unauthenticated public snapshot resolution. Snapshot checks do not replace signed unpublished-deployment tests or final Central-only tests in [RELEASING.md](RELEASING.md).

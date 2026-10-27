# Integration recipes

These examples use the locally installed, unpublished `0.1.0` candidate. Complete [installation](INSTALLATION.md) first. Keep your application's existing dependency and compiler configuration; add the plugins under `<build><plugins>`. Run `mvn verify` and inspect `target/cryptocensus/cbom.json`.

## JaCoCo and custom JVM options

Give Census its own property and use Surefire's `@{...}` late binding so both agents survive initialization. Define empty properties to keep profiles that disable either preparer usable. The same final `argLine` must reach every forked test execution.

```xml
<properties>
  <argLine></argLine>
  <censusArgLine></censusArgLine>
</properties>
<build>
  <plugins>
    <plugin>
      <groupId>org.jacoco</groupId>
      <artifactId>jacoco-maven-plugin</artifactId>
      <version>0.8.15</version>
      <executions>
        <execution><goals><goal>prepare-agent</goal></goals></execution>
        <execution>
          <id>coverage-report</id><phase>verify</phase>
          <goals><goal>report</goal></goals>
        </execution>
      </executions>
    </plugin>
    <plugin>
      <groupId>com.developmentsprint</groupId>
      <artifactId>census-maven-plugin</artifactId>
      <version>0.1.0</version>
      <configuration><propertyName>censusArgLine</propertyName></configuration>
      <executions>
        <execution><goals><goal>prepare-agent</goal><goal>check</goal></goals></execution>
      </executions>
    </plugin>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-surefire-plugin</artifactId>
      <version>3.5.4</version>
      <configuration>
        <argLine>@{argLine} @{censusArgLine} -Xmx1g</argLine>
      </configuration>
    </plugin>
  </plugins>
</build>
```

Census and JaCoCo evidence should both appear: `target/cryptocensus/cbom.json` and `target/site/jacoco/index.html`. `-DargLine=...` overrides the project property JaCoCo modifies; put custom JVM flags in the POM. Surefire documents the reason for [late property evaluation](https://maven.apache.org/surefire/maven-surefire-plugin/faq.html#How_do_I_use_properties_set_by_other_plugins_in_argLine.3F).

## Failsafe

Bind Failsafe before Census in the plugin list so its verification runs first. Census preparation in `initialize` covers both Surefire and Failsafe; its single `check` at `verify` merges their per-JVM reports. With the JaCoCo recipe, give Failsafe the same composed `argLine`.

```xml
<properties><censusArgLine></censusArgLine></properties>
<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-failsafe-plugin</artifactId>
      <version>3.5.4</version>
      <configuration><argLine>@{censusArgLine}</argLine></configuration>
      <executions>
        <execution><goals><goal>integration-test</goal><goal>verify</goal></goals></execution>
      </executions>
    </plugin>
    <plugin>
      <groupId>com.developmentsprint</groupId>
      <artifactId>census-maven-plugin</artifactId>
      <version>0.1.0</version>
      <configuration><propertyName>censusArgLine</propertyName></configuration>
      <executions>
        <execution><goals><goal>prepare-agent</goal><goal>check</goal></goals></execution>
      </executions>
    </plugin>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-surefire-plugin</artifactId>
      <version>3.5.4</version>
      <configuration><argLine>@{censusArgLine}</argLine></configuration>
    </plugin>
  </plugins>
</build>
```

Name integration tests `*IT.java` and unit tests `*Test.java`, then run `mvn verify`, not `mvn integration-test`. Re-running `prepare-agent` before Failsafe in the same Maven build reuses the run directory. One execution's evidence cannot prove another execution attached; verify each custom execution's configuration.

## Multiple Maven modules

Put the README plugin under the parent POM's `<build><plugins>` to inherit both executions into every module. A parent with packaging `pom` and no tests skips missing-data failure when no compiled tests exist. Each child writes its own `target/cryptocensus/cbom.json`; run the parent with:

```sh
mvn -T 2 verify
```

Keep the default per-module destinations. `check` merges JVM reports for one project; it is not a reactor-wide aggregate goal. Publish all child reports as separate CI artifacts. A plugin declaration only inside `<pluginManagement>` supplies defaults but does not activate the plugin; add it under each participating child's `<plugins>` if opting in that way. Shared output filenames can overwrite each other's merged reports even though unique input run directories remain separate.

## CI scanning and downloadable CBOMs

This repository's [CI workflow](../.github/workflows/ci.yml) includes a **Maven plugin dogfood / runtime CBOM** job. It installs the plugin and agent from the current checkout, then uses the plugin to scan the demo's payments tests in a separate Maven invocation. The tests exercise signing, encryption and checksums; the normal conformance matrix independently runs the full test suite.

Open the GitHub Actions run summary and download **crypto-census-cbom** from **Artifacts**. Its `cbom.json` is the merged CycloneDX runtime cryptographic inventory. **crypto-census-cbom-jvm-reports** contains the individual JVM reports for diagnosis. Both artifacts have a 14-day retention period.

Reproduce the job from the repository root with JDK 17:

```sh
./mvnw -B -ntp -pl census-maven-plugin -am clean install -DskipTests -Dinvoker.skip=true
./mvnw -B -ntp -pl census-demo clean verify
```

Only the bootstrap skips tests; the second command runs the demo tests with Census attached and writes `census-demo/target/cryptocensus/cbom.json`. Cleaning the demo before scanning prevents an old report from appearing as current evidence. CI rejects a missing report or one without cryptographic assets.

Artifact upload runs even when a risk policy fails, and the job keeps Maven's failure status. For example, add `-Dcensus.failOn=quantum-vulnerable` to the second command: the demo fails the stricter policy after writing its merged CBOM. A test failure before `check` can prevent merging; any available individual JVM reports remain downloadable, but their presence does not satisfy the merged-report requirement. Abrupt JVM termination can leave no reports.

This inventory covers cryptography observed in the demo workload. It is not a dependency SBOM or a scan of every path in the tool's own implementation. Uploading a workflow artifact also does not deliver it to an enterprise security platform.

## Standalone container

Copy your acquired shaded agent to `census-agent.jar`, place your runnable application at `app.jar`, and create this `Dockerfile`. Choose and pin an approved JRE image for your deployment; this example's base tag is illustrative and does not add a conformance row.

```dockerfile
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY app.jar census-agent.jar ./
ENTRYPOINT ["java", "-javaagent:/app/census-agent.jar=dir=/reports,app=example", "-jar", "/app/app.jar"]
```

On a POSIX host, use the current user's UID/GID and a private writable report mount. The exec-form entry point makes Java receive the termination signal. Substitute an unused container name if needed.

```sh
mkdir -m 700 reports
docker build -t census-example .
docker run -d --name census-example \
  --user "$(id -u):$(id -g)" \
  --mount "type=bind,src=$(pwd)/reports,dst=/reports" \
  census-example
# Exercise the application's endpoints or workload before stopping it.
docker stop --timeout 60 census-example
docker logs census-example
ls -l reports
docker rm census-example
```

Do not use `--rm` if evidence is kept only inside a container. A bind mount survives removal; otherwise copy the report out of the stopped container before removing it. A timeout that forces a kill can lose the report.

## Open Liberty

For the [verified Liberty image](CONFORMANCE.md#app-servers-and-other-agents), copy the agent to `/opt/census/census-agent.jar`. Append this line to `/opt/ol/wlp/usr/servers/defaultServer/jvm.options` (preserve existing options):

```text
-javaagent:/opt/census/census-agent.jar=output=/tmp/cryptocensus-cbom.json,app=open-liberty
```

Deploy your WAR into the server's `dropins` directory and exercise it. The repository provides a fully executable, pinned container example in `AppServerIT`; run it with the [required-Docker command](CONFORMANCE.md#running-it-locally). For an operator-owned running container named `census-liberty`, stop and collect before removal:

```sh
docker stop --timeout 60 census-liberty
docker logs census-liberty
docker cp census-liberty:/tmp/cryptocensus-cbom.json ./liberty-cbom.json
docker rm census-liberty
```

The built-in `/tmp` destination is private to the container and writable by the server user. For persistent shared storage, use `dir=/reports` with a mount owned by the image's runtime UID/GID; verify write and rename access as that user. Never broaden permissions to world-writable to fix a mount mismatch.

## WildFly

For the [verified WildFly image](CONFORMANCE.md#app-servers-and-other-agents), copy the agent to `/opt/census/census-agent.jar`, then append to the server's existing `JAVA_OPTS` in its environment or `standalone.conf`. If supplying `JAVA_OPTS` replaces the image's defaults, retain those defaults; the conformance test uses:

```text
-Djava.net.preferIPv4Stack=true -Djboss.modules.system.pkgs=org.jboss.byteman -Djava.awt.headless=true -javaagent:/opt/census/census-agent.jar=output=/tmp/cryptocensus-cbom.json,app=wildfly
```

Deploy the WAR into `/opt/jboss/wildfly/standalone/deployments`, exercise it, and use the same stop/copy/remove sequence with your WildFly container name. The [pinned executable test](../census-app-servers/src/test/java/com/developmentsprint/cryptocensus/appservers/AppServerIT.java) demonstrates both deployments, graceful shutdown, and health assertions. Preserve other agents and server-specific JVM flags when composing options.

## Business context without changing existing gates

Enable `-Dcensus.enrichment=true -Dcensus.assessment=true` for optional business evidence and
advisory reporting. Existing risk/collection gates retain their defaults. The
[guided workflow](ADVISORY-WORKFLOW.md) covers Java annotations, unmodified call-site binding,
private context apply and reassessment without test reruns. Keep sidecars and HTML/JSON reports
out of broad CI uploads; an ordinary `cbom-*.json` glob also matches evidence sidecars.

Generated catalogs distinguish main/test output and known reactor artifacts from libraries.
Shared annotated dependencies keep declaring-artifact identity rather than adopting the test
runner's identity. Version/type/classifier mismatches and known shaded packages do not acquire
ownership by guesswork; use explicit mappings as described in [source catalogs](SOURCE-CATALOG.md).

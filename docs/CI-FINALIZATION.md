# Finalize evidence after a failed CI build

Use `prepare-agent`, `report`, delivery, and `enforce` as separate steps. A failed
Surefire test, Failsafe verification, or crypto policy can stop Maven before a
later goal executes. CI must run finalization and enforcement independently and
retain the original failing producer step.

`check` remains compatible: it merges the current Maven invocation's reports,
writes the configured `census.output`, and enforces policy. It does not discover
reports from a previous invocation. Existing explicit artifact/digest delivery
continues to accept caller-supplied provenance.

## Prepare and finalize an exact run

Generate a fresh lowercase UUID for every CI attempt, including reruns. Pass it
to the normal build as `-Dcensus.runId=...`; keep the plugin's `prepare-agent`
execution in `initialize`. Do not run `clean` between collection and finalization.

```sh
run_id="$(python3 -c 'import uuid; print(uuid.uuid4())')"
./mvnw verify -Dcensus.runId="$run_id"
# Run in CI's always/finally step, even when the preceding command failed:
./mvnw com.developmentsprint:census-maven-plugin:0.1.0:report \
  -Dcensus.runId="$run_id" -Dcensus.testOutcome=failed
```

Supply the actual producer outcome as `passed`, `failed`, or `unknown`. A failed
`verify` may represent tests, compilation, or an earlier policy failure; the
caller reports the producer status without claiming which test suites passed.
`unknown` is the default. Report availability never means tests passed.

The module directory is `census.destDir/run-<runId>-<moduleHash>`, where
`moduleHash` is the lowercase SHA-256 of UTF-8 `groupId:artifactId`. Each directory
has an exclusively created `run.json` descriptor and a random module-run ID.
Reusing a module/run identity in another Maven invocation fails. Repeated
`prepare-agent` executions in the same invocation reuse its descriptor. Modules
sharing a destination remain isolated by module hash; two concurrent invocations
of the same module need different run IDs.

`report` reads only that exact registered directory. Missing, ambiguous,
malformed, symlinked, or changed evidence fails closed; timestamps and "latest"
files never establish freshness. The report emits:

- `revisions/1/evidence.json`: merged CycloneDX evidence;
- `revisions/1/manifest.json`: descriptor, raw input names and SHA-256 digests,
  output digest, effective policy/configuration, producer outcome, health,
  crypto-policy outcome, and finalization timestamp.

A policy failure or incomplete collection is recorded without failing `report`;
missing or corrupt evidence fails it. `enforce` remains responsible for the
configured crypto and collection gates. Raw JVM evidence and manifests are local
workflow state, not signed attestations or proof that every expected JVM ran.

Re-running `report` with the same inputs, policy, and producer outcome reuses the
original bytes. If late JVM evidence arrives or policy intentionally changes,
pass a new explicit `-Dcensus.revision=2`. Every revision is created exclusively;
there is no overwrite or automatic latest selection. A crash during finalization
can leave an unusable partial revision; preserve it for diagnosis and request a
new revision. Earlier valid revisions remain usable with their exact manifests,
provided their original referenced input bytes are still present and unchanged.

## Deliver and enforce independently

Keep the manifest path as an output of the producing job. It must refer to the
exact `run-.../revisions/N/manifest.json` selected for this attempt.

```sh
./mvnw com.developmentsprint:census-maven-plugin:0.1.0:publish \
  -Dcensus.manifest="$manifest" -Dcensus.runId="$run_id" \
  -Dcensus.delivery.endpoint=https://inventory.example.org/cboms \
  -Dcensus.delivery.destinationId=inventory \
  -Dcensus.delivery.application=payments \
  -Dcensus.delivery.tokenEnv=CBOM_UPLOAD_TOKEN
# A separate always/finally step; a failed upload must not suppress this:
./mvnw com.developmentsprint:census-maven-plugin:0.1.0:enforce \
  -Dcensus.manifest="$manifest" -Dcensus.runId="$run_id"
```

Use the original policy configuration for `enforce`; it rejects policy drift.
It verifies original and merged bytes and independently recomputes the same
policy as `check`. Delivery success never makes a policy or producer failure
pass. Keep the producer step failed; avoid blanket `continue-on-error` or a shell
script whose final successful command replaces its earlier failure status.

For [profiles](DELIVERY-PROFILES.md), add `census.manifest`, `census.runId`, and
`census.delivery.application` to every `delivery` action (`plan`, `preflight`,
`publish`). The profile's `CENSUS_MERGED_CBOM` input must identify the exact sealed
artifact/digest, application, module, version, and run. Validation happens on each
profile read before local outputs, secret resolution, or network operations.
Preparation outputs, plans, and receipts must remain outside the immutable run
directory and cannot alias any protected evidence file. External software SBOM
composition still follows the profile's separate validation/correlation rules.

Archive the selected sealed revision, descriptor, its original JVM reports, and
separate delivery receipts. `census.delivery.failOnError=false` may make a recorded
delivery outage reporting-only; it never relaxes evidence validation or policy
enforcement. A killed JVM may produce no report, and a cancelled or lost CI
runner may prevent all finalization. These conditions cannot be repaired by
publishing a previous run's artifact.

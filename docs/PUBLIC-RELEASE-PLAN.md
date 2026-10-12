# Public release checklist

This is the local task record for preparing Crypto Census 0.1.0. Completion of the
implementation tasks does not mean that artifacts have been published. The
maintainer performs signing and publication after accepting the verified candidate.

| Task | Scope | Acceptance evidence | Status |
|---|---|---|---|
| REL-1 | Policy composition and executable quickstart | Raw signatures require observed hashing or an exception; broken keys remain broken with unknown hashes/paddings; policy version advances; real Maven gates and README fixture agree | Implemented; verification recorded below |
| REL-2 | Bounded output, collection settings, strict encoding | Counts and assets survive attribution compaction; output satisfies byte and parser budgets; impossible output fails safely; agent/Maven settings propagate; malformed encodings fail | Implemented; verification recorded below |
| REL-3 | User, contributor, operator and release documentation | Complete linked guides, accurate examples, confirmed private reporting route and explicit staging/test positioning | Guides implemented; maintainer confirmed the private reporting mailbox |
| REL-4 | Hook correction and release integration | Mixed PATH/JAVA_HOME verification; integrated clean/quality/container checks; release artifact inspection; fresh external consumer and standalone smoke tests | Local candidate gates passed; hosted CI/signing/publication pending |

## Current publication status (2026-10-12)

The repository is public. The maintainer confirmed NVD credential revocation/rotation, GitHub private vulnerability reporting is enabled, and `develop`/`main` protections require the 13 supported CI gates, including runtime CBOM dogfooding. PRs #14–#16 passed their required hosted checks; the enterprise delivery merge is `35c4ffa`. These facts complete the original hosting and credential gates. They do not establish the final release candidate or Maven Central publication.

The enterprise changes add `census-delivery` and `census-cli` to the publication set. Reinspect all six coordinates and 22 POM/JAR files, including the delivery test-fixture classifier, after the final implementation freeze. Repeat release-profile, fresh-consumer and hosted checks for that exact revision. Namespace verification, signing, validated-deployment testing and public artifact resolution remain pending. The original REL task table and local verification evidence record the historical October 10 baseline, not acceptance of later source changes.

## Candidate invariants

- Policy verdict changes increment the policy version independently of the artifact version.
- Compaction preserves every asset, risk verdict and observation total. Lost attribution is
  represented explicitly by overflow observations and never treated as evidence of JDK-only use.
- The writer bounds UTF-8 bytes, parser complexity and serialization work. If the asset and metadata
  payload cannot fit even after compaction, output fails before replacing an existing destination.
- Configured collection limits have documented upper bounds. Increasing a limit never disables
  completeness or parser safety checks.
- One verified source revision identifies the signed release candidate. Publication does not
  rebuild unrelated or unreviewed changes into that candidate.

## Before the publication decision

- [ ] All REL tasks pass their acceptance checks and the combined diff is reviewed.
- [x] The maintainer confirmed `security@developmentsprint.com` as the private reporting address.
- [x] The maintainer confirmed exposed NVD credential revocation/rotation; the dependency scan passed for PR #16. Repeat scanning for the final candidate.
- [x] History-wide secret scanning and a commit-metadata privacy review are complete; session links remain for the maintainer to approve or remove explicitly.
- [x] The release-version reactor passes clean verification and required Docker conformance.
- [ ] Supported-platform CI passes for the candidate source revision.
- [x] The parent POM and the core, agent and plugin artifacts are inspected; demo and app-server
      artifacts are excluded from publication.
- [x] Fresh external consumers resolve a locally staged candidate; the documented quickstart and
      standalone agent produce valid evidence.

## Maintainer publication steps

1. Accept the verified work for integration and finalize the release source revision and changelog.
2. Sign and upload the candidate to the Central Portal with automatic publication disabled.
3. Test the validated, unpublished signed deployment and verify its signatures and source identity.
4. Publish the accepted deployment, then repeat public-coordinate and standalone download smoke
   tests with a fresh consumer cache.
5. Complete the release tag/branch flow and GitHub release, and advance development to the next
   snapshot. Remote pushes and publication remain maintainer-controlled.
6. Enable and verify private vulnerability reporting and required branch protections when the
   repository becomes public.

See the release guide for executable commands and the distinction between local candidate
staging, signed deployment testing, and public Maven Central verification.

## Deferred beyond 0.1.0

Production positioning requires representative overhead and concurrency benchmarks. Periodic
output, live health inspection, release SBOMs, provenance and reproducible builds remain roadmap
work. Partial attachment across separate test executions remains an explicitly documented
collection limitation.

## Local candidate verification (2026-10-10)

The reactor now uses `0.1.0` as an **unpublished candidate**. The implementation has been accepted for integration; the signed release commit/tag and its hosted CI evidence must still be established before signing.

- JDK 25 clean verification and unsigned release-profile clean installation pass: 398 discovered tests, 396 passed, two runtime-conditional skips, and 18 Maven Invoker scenarios. Formatting, static analysis, coverage floors and packaging gates pass.
- JDK 17 clean verification also passes against an isolated copy of the candidate source: 398 discovered tests, 393 passed, five runtime-conditional skips, and all 18 Maven Invoker scenarios.
- The required-Docker gate passes both Open Liberty and WildFly tests with no skips.
- Inspected 13 publishable files: the parent POM and main/source/Javadoc/POM files for core, agent and plugin. Agent relocation, manifest, license/notice contents and plugin parameter descriptors match the candidate. Release Enforcer negative tests reject both a snapshot project and a snapshot dependency.
- A fresh external Maven cache resolves the locally staged candidate. The exact README plugin succeeds at the default threshold and fails for the expected finding with `-Dcensus.failOn=quantum-vulnerable`.
- A separately acquired standalone agent matches the staged SHA-256 and writes healthy reports on JDK 17 and 25.
- Exact JaCoCo, Failsafe and parallel multimodule documentation examples pass from three fresh caches; downloaded Census artifacts match the final staged hashes. JaCoCo records application coverage, separate unit/integration JVMs both contribute evidence, and each module retains its own report.
- A dedicated history secret scan and a separate scan of the candidate source find no secrets within their scanned scope. Commit metadata includes session links; their removal remains an explicit privacy decision.
- Independent collection and documentation reviews report no findings. Final combined review identified an additional pre-existing signature-composition gap (a broken hash hidden by an unknown key type); its fix passes unit and real-provider Maven regressions. Targeted independent re-review reports no remaining material findings. A supported-maximum inventory probe preserves 2,000 assets and all observation counts across 200,000 call sites, compacts below the byte budget, and parses under a 256 MiB heap. A persistent maximum-collection regression passes under a 256 MiB heap; default-limit boundary regressions also pass under a 128 MiB heap.

The maintainer has confirmed the private security reporting address. Those October 10 local checks alone did not establish credential rotation, hosting controls, signatures, or published artifacts. The current publication-status section records subsequent operator confirmations; final candidate verification, signing and artifact publication remain pending.

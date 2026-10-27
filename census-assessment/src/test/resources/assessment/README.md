# Synthetic assessment contract fixtures

These files contain invented applications, declarations, identities and counts.
They are contract examples, not captured customer data or proof of a supported
runtime. Actual runtime conformance is tested separately by the agent fixtures.

- `sample-cbom.json` and `sample-evidence.json` are an exact-byte pair. The fixed
  UUID and timestamp make their hashes reproducible.
- `legacy-cbom.json` has no sidecar but carries a run nonce, so the report diagnoses
  `EXPECTED_SIDECAR_MISSING`. Its observed AES primitive remains visible, while
  flow association and evidence completeness remain unknown. True legacy input
  without a nonce retains `NO_FLOW_OR_CORRELATION_EVIDENCE` in the input unit tests.
- `context.json` supplies conflicting user metadata, imported flow metadata,
  application defaults and a retired flow with a stale saved method binding.
- `assessment-v1.json` is the canonical report for those inputs with the default
  policy profile. It includes 23 findings and eight business-flow summaries.
- `reasons-v1.json` is a readable golden projection of the ordered findings,
  including exact rules, reasons, priorities, next actions and technical axes.

The archive's 20-year sensitive confidentiality requirement remains urgent when
its nested helper declares one year. RSA-1024 technical remediation precedes
RSA-2048 migration planning. Three helper findings deliberately tie on priority,
subject, asset and rule, exercising path and occurrence ordering. Conflicting
classification requires review; application-wide defaults do not become
flow-specific requirements. Unobserved and overflowed areas remain explicit.

The synthetic TLS evidence separates hybrid confidentiality, classical peer
signatures and unknown local signatures. A resumed hybrid attempt with a known
classical PSK origin retains the resistant selected exchange and vulnerable
origin while leaving composite confidentiality unestablished. TLS attempt
counts do not become JCA entry-point counts or proof of data-flow causality.
The resumed attempt has no current peer signature and does not inherit the
origin attempt's authentication evidence.

`AssessmentContractTest` rebuilds the scenario, verifies independent policy
expectations, compares all golden artifacts, and replays the saved exact-byte
inputs. It also reverses and duplicates input runs to check canonical output
and count deduplication. It writes candidate artifacts only under
`target/assessment-contract`; it never updates these expected files. Review
policy changes and the focused assertions before deliberately replacing a
fixture. Preserve canonical CBOM and sidecar bytes because their exact hashes
appear in the paired evidence and report provenance.

Run from the repository root:

```sh
./mvnw -B -ntp -pl census-assessment -am verify
```

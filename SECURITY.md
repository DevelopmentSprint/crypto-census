# Security policy

## Supported versions

Security fixes target the latest released `0.x` version. Upgrade to that version before reporting a suspected issue when possible; older `0.x` versions do not receive promised backports. There is no published release yet; the `0.1.0` source candidate is under preparation.

## Report a vulnerability privately

Use [GitHub private vulnerability reporting](https://github.com/DevelopmentSprint/crypto-census/security/advisories/new) or email [security@developmentsprint.com](mailto:security@developmentsprint.com). GitHub requires a signed-in account; email is available without one. Do not post exploit details, credentials, or an unreviewed CBOM in a public issue.

Include the affected Census version or source revision, JDK/vendor, minimal reproduction, expected and actual behavior, and your assessment of impact. Use synthetic keys and sanitized output. Explain whether the issue affects the observed application, report correctness, evidence validation, or publication/build tooling.

We aim to acknowledge reports within **three business days**. The acknowledgement identifies a maintainer and the next assessment/update date. We assess severity and affected versions, agree on a mitigation or fix timeline with the reporter, and provide updates while the issue remains open; complex issues can require more investigation. We coordinate public disclosure after a fix or mitigation is available and credit reporters with their consent. These are response targets, not a guaranteed resolution deadline.

## Scope and evidence

Census observes cryptography; it does not repair an application's algorithms. Policy false negatives, hostile-input parsing flaws, unintended changes to application behavior, exposed credentials, and release integrity issues are security-relevant. Read the [threat boundaries and limitations](docs/SAFETY.md), including self-reported in-process evidence and unbenchmarked production overhead.

For ordinary usage questions or feature requests, use repository issues with sanitized details. Treat CBOMs as sensitive engineering evidence; see [retention and handling](docs/OPERATIONS.md#sensitivity-and-retention).

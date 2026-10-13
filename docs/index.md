---
layout: home
title: Runtime cryptographic evidence for Java
hero:
  name: Crypto Census
  text: Know your cryptography. Plan what comes next.
  tagline: Observe the cryptography your Java application actually uses. Trace it to your code, assess quantum exposure, and carry the evidence into your security workflow.
  actions:
    - theme: brand
      text: Start with Maven →
      link: /getting-started/
    - theme: alt
      text: Explore a real CBOM
      link: /examples/example-cbom
features:
  - title: Understand the evidence
    details: Learn what runtime observation reveals, where its limits are, and how a CBOM complements your software SBOM.
    link: /concepts/cryptographic-evidence
    linkText: Read the concepts
  - title: Make it part of CI
    details: Collect evidence from test JVMs, seal available reports after failures, and keep test, policy, and delivery outcomes separate.
    link: /guides/ci
    linkText: Build your workflow
  - title: Connect your security tools
    details: Preserve original evidence and choose an enterprise destination with explicit capabilities, verification, and limits.
    link: /guides/enterprise-delivery
    linkText: Compare delivery options
---

<div class="home-guidance">

## An inventory grounded in execution

An algorithm in a dependency does not tell you whether your application uses it. Crypto Census observes JCA and JSSE calls inside the JVM and records algorithms, observable key sizes, risk verdicts, and application call sites in a CycloneDX 1.6 CBOM. Representative tests and staging workloads determine the evidence you collect.

Start with a [small working example](./examples/crypto-walkthrough.md), follow the [system architecture](./architecture/overview.md), or jump to the [configuration map](./guides/configuration.md). If a report is missing or a delivery fails, the [troubleshooting guides](./troubleshooting/index.md) connect symptoms to specific checks.

<div class="home-note">

Java 17+ is required at runtime. Current positioning is tests and staging; production overhead is not benchmarked. Read the [compatibility matrix](./COMPATIBILITY.md) and [safety boundaries](./SAFETY.md) before deployment.

</div>

</div>

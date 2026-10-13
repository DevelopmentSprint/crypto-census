---
title: Example CBOM from CI
description: Explore the exact cryptographic evidence collected by Crypto Census from its payments demo tests in CI.
aside: false
pageClass: example-cbom-page
---

<script setup>
import ExampleCbom from '../.vitepress/theme/components/ExampleCbom.vue'
</script>

# Example CBOM from CI

Explore the cryptography our own Maven plugin observes in the **`census-demo` payments tests**. This is real build evidence, published from successful trusted `develop` CI runs.

::: warning A demonstration with deliberate weaknesses
The workload includes legacy and quantum-vulnerable cryptography so you can see findings and their call sites. This page covers **only the executed demo workload**. It is neither an exhaustive repository inventory nor a complete software SBOM, and it does not certify the project’s security. Unexecuted paths remain unobserved.
:::

<ExampleCbom />

## Read and reproduce this example

Follow the [cryptography walkthrough](./crypto-walkthrough.md) to run the same payments workload locally. See [the evidence pipeline](../architecture/evidence-pipeline.md) for how the Maven plugin seals evidence, and [risk policy](../POLICY.md) for the meaning of each verdict. Quantum-safe and CNSA 2.0 are separate classifications.

The page shows the latest successfully published build, identified by its commit and CI run. Pull request builds do not replace it, and a failed build or publication can leave an older result in place. The displayed finalization time identifies the evidence; it is not a claim that every later build succeeded. Raw input file digests remain in the manifest; the downloads are the merged CBOM and sealed run manifest.

The inventory-first layout follows the overview and asset inspection patterns of [CBOMkit](https://github.com/cbomkit/cbomkit) and the cryptographic asset model defined by [CycloneDX](https://cyclonedx.org/capabilities/cbom/). It renders the original Census evidence directly, without a third-party import that could discard cryptographic fields.

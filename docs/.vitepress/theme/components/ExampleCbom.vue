<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { withBase, useData } from 'vitepress'

type Property = { name: string; value: string }
type Occurrence = {
  location: string
  line?: number
  symbol?: string
  additionalContext?: string
}
type Asset = {
  type: string
  name: string
  'bom-ref': string
  properties: Property[]
  cryptoProperties?: Record<string, unknown>
  evidence?: { occurrences?: Occurrence[] }
}
type Bom = {
  bomFormat: string
  specVersion: string
  serialNumber?: string
  metadata: { timestamp?: string; properties?: Property[] }
  components: Asset[]
}
type Manifest = {
  schema: string
  sha256: string
  health: string
  testOutcome: string
  policyOutcome: string
  finalizedAt: string
  policy: Record<string, unknown>
  descriptor: Record<string, string>
  inputs: Record<string, string>
  revision: string
}
type Publication = {
  schemaVersion: number
  status: string
  repository: string
  sourceSha: string
  sourceBranch: string
  runId: string
  runAttempt: string
  generatedAt: string
  workload: { module: string; version: string; name: string; scope: string }
  collection: {
    runId: string
    moduleRunId: string
    revision: string
    testOutcome: string
    health: string
    policyOutcome: string
  }
  downloads: {
    evidence: { path: string; sha256: string }
    manifest: { path: string; sha256: string }
  }
}
const { frontmatter } = useData()
const repository = 'https://github.com/DevelopmentSprint/crypto-census'
const state = ref<'loading' | 'published' | 'unavailable' | 'error'>('loading')
const publication = ref<Publication>()
const bom = ref<Bom>()
const manifest = ref<Manifest>()
const search = ref('')
const riskFilter = ref('all')
const primitiveFilter = ref('all')
const sortBy = ref('recorded')
const risks = [
  'broken',
  'weak',
  'quantum-vulnerable',
  'unknown',
  'acceptable',
  'quantum-safe',
]
const labels: Record<string, string> = {
  broken: 'Broken',
  weak: 'Weak',
  'quantum-vulnerable': 'Quantum-vulnerable',
  unknown: 'Unknown',
  acceptable: 'Acceptable',
  'quantum-safe': 'Quantum-safe',
}
const property = (properties: Property[] | undefined, key: string) =>
  properties?.find((item) => item.name === `cryptocensus:${key}`)?.value
const assetProperty = (asset: Asset, key: string) =>
  property(asset.properties, key)
const risk = (asset: Asset) =>
  risks.includes(assetProperty(asset, 'risk') ?? '')
    ? assetProperty(asset, 'risk')!
    : 'unknown'
const primitive = (asset: Asset) =>
  assetProperty(asset, 'primitive') ?? 'Not recorded'
const operation = (asset: Asset) => {
  const observed = assetProperty(asset, 'operation')
  if (observed) return observed
  const algorithm = asset.cryptoProperties?.algorithmProperties as
    | Record<string, unknown>
    | undefined
  const functions = algorithm?.cryptoFunctions
  return Array.isArray(functions) &&
    functions.length &&
    functions.every((value) => typeof value === 'string')
    ? functions.join(', ')
    : 'Operation not recorded'
}
const assets = computed(
  () =>
    bom.value?.components.filter(
      (asset) => asset.type === 'cryptographic-asset',
    ) ?? [],
)
const metadata = computed(() => bom.value?.metadata.properties ?? [])
const primitives = computed(() =>
  [...new Set(assets.value.map(primitive))].sort(),
)
const riskCounts = computed(() =>
  risks.map((value) => ({
    value,
    label: labels[value],
    count: assets.value.filter((asset) => risk(asset) === value).length,
  })),
)
const sites = computed(
  () =>
    new Set(
      assets.value.flatMap((asset) =>
        (asset.evidence?.occurrences ?? []).map((site) =>
          JSON.stringify([site.location, site.line, site.symbol]),
        ),
      ),
    ).size,
)
const filtered = computed(() =>
  assets.value
    .filter(
      (asset) =>
        (riskFilter.value === 'all' || risk(asset) === riskFilter.value) &&
        (primitiveFilter.value === 'all' ||
          primitive(asset) === primitiveFilter.value) &&
        JSON.stringify(asset)
          .toLowerCase()
          .includes(search.value.toLowerCase().trim()),
    )
    .sort((a, b) =>
      sortBy.value === 'name'
        ? a.name.localeCompare(b.name)
        : sortBy.value === 'verdict'
          ? risks.indexOf(risk(a)) - risks.indexOf(risk(b))
          : 0,
    ),
)
const observedEvents = computed(() => {
  const counts = assets.value.map((asset) => assetProperty(asset, 'count'))
  if (counts.some((count) => !count || !/^\d+$/.test(count)))
    return 'Not recorded'
  return counts
    .reduce((total, count) => total + BigInt(count!), 0n)
    .toLocaleString('en-US')
})
const finalizedTime = computed(() => {
  const date = new Date(publication.value?.generatedAt ?? '')
  return Number.isNaN(date.getTime())
    ? 'Not recorded'
    : new Intl.DateTimeFormat('en-US', {
        dateStyle: 'medium',
        timeStyle: 'long',
        timeZone: 'UTC',
      }).format(date)
})
const commitUrl = computed(
  () => `${repository}/commit/${publication.value?.sourceSha}`,
)
const runUrl = computed(
  () =>
    `${repository}/actions/runs/${publication.value?.runId}/attempts/${publication.value?.runAttempt}`,
)
const evidenceUrl = withBase('/example-cbom/evidence.json')
const manifestUrl = withBase('/example-cbom/manifest.json')
const keySize = (asset: Asset) => {
  const value = assetProperty(asset, 'keySize')
  return value && /^[1-9]\d*$/.test(value) ? `${value} bits` : 'Not recorded'
}
const missingTargets = computed(() => {
  const value = property(metadata.value, 'missingTargets')
  return value === undefined
    ? 'Target status not recorded'
    : value || 'None reported in the evidence'
})
const sourceUrl = (site: Occurrence) => {
  if (
    !/^com\.developmentsprint\.cryptocensus\.demo\.[A-Za-z_$][A-Za-z0-9_$]*$/.test(
      site.location,
    )
  )
    return undefined
  const file = site.location.split('$')[0].replaceAll('.', '/')
  const line =
    Number.isSafeInteger(site.line) && site.line! > 0 ? `#L${site.line}` : ''
  return `${repository}/blob/${publication.value!.sourceSha}/census-demo/src/main/java/${file}.java${line}`
}
const pretty = (value: unknown) => JSON.stringify(value, null, 2)
const clear = () => {
  search.value = ''
  riskFilter.value = 'all'
  primitiveFilter.value = 'all'
}
const isProperties = (value: unknown): value is Property[] =>
  Array.isArray(value) &&
  value.every(
    (item) =>
      item && typeof item.name === 'string' && typeof item.value === 'string',
  )
async function verifiedJson(url: string, expected: string) {
  if (!/^[a-f0-9]{64}$/.test(expected)) throw new Error('Invalid digest')
  const response = await fetch(url, {
    cache: 'no-store',
    credentials: 'omit',
    redirect: 'error',
  })
  if (!response.ok) throw new Error('Evidence unavailable')
  const bytes = await response.arrayBuffer()
  const hash = Array.from(
    new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)),
    (byte) => byte.toString(16).padStart(2, '0'),
  ).join('')
  if (hash !== expected) throw new Error('Digest mismatch')
  return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes))
}
onMounted(async () => {
  try {
    const response = await fetch(withBase('/example-cbom/index.json'), {
      cache: 'no-store',
      credentials: 'omit',
      redirect: 'error',
    })
    if (response.status === 404) {
      state.value = 'unavailable'
      return
    }
    if (!response.ok) throw new Error('Publication unavailable')
    const index = (await response.json()) as Publication
    if (index.schemaVersion !== 1) throw new Error('Unsupported publication')
    if (index.status === 'unavailable') {
      state.value = 'unavailable'
      return
    }
    if (
      index.status !== 'published' ||
      index.repository !== 'DevelopmentSprint/crypto-census' ||
      index.sourceBranch !== 'develop' ||
      !/^[a-f0-9]{40}$/.test(index.sourceSha) ||
      !/^[1-9]\d*$/.test(index.runId) ||
      !/^[1-9]\d*$/.test(index.runAttempt) ||
      index.downloads.evidence.path !== 'evidence.json' ||
      index.downloads.manifest.path !== 'manifest.json'
    )
      throw new Error('Invalid publication')
    const [evidence, sealed] = (await Promise.all([
      verifiedJson(evidenceUrl, index.downloads.evidence.sha256),
      verifiedJson(manifestUrl, index.downloads.manifest.sha256),
    ])) as [Bom, Manifest]
    if (
      sealed.schema !== 'census.maven-evidence/1' ||
      sealed.sha256 !== index.downloads.evidence.sha256 ||
      evidence.bomFormat !== 'CycloneDX' ||
      typeof evidence.specVersion !== 'string' ||
      !Array.isArray(evidence.components) ||
      !isProperties(evidence.metadata.properties)
    )
      throw new Error('Invalid evidence')
    if (
      sealed.descriptor.module !== 'com.developmentsprint:census-demo' ||
      index.workload.module !== sealed.descriptor.module ||
      sealed.descriptor.runId !== index.collection.runId ||
      sealed.descriptor.moduleRunId !== index.collection.moduleRunId ||
      sealed.revision !== index.collection.revision ||
      sealed.finalizedAt !== index.generatedAt ||
      sealed.health !== index.collection.health ||
      sealed.testOutcome !== index.collection.testOutcome ||
      sealed.policyOutcome !== index.collection.policyOutcome
    )
      throw new Error('Provenance mismatch')
    if (
      !['healthy', 'incomplete'].includes(sealed.health) ||
      !['passed', 'failed'].includes(sealed.policyOutcome) ||
      !sealed.policy ||
      !sealed.inputs
    )
      throw new Error('Invalid outcomes')
    const refs = new Set<string>()
    for (const asset of evidence.components) {
      if (
        typeof asset.name !== 'string' ||
        typeof asset['bom-ref'] !== 'string' ||
        refs.has(asset['bom-ref']) ||
        !isProperties(asset.properties)
      )
        throw new Error('Invalid asset')
      refs.add(asset['bom-ref'])
      if (
        asset.evidence?.occurrences !== undefined &&
        (!Array.isArray(asset.evidence.occurrences) ||
          asset.evidence.occurrences.some(
            (site) => !site || typeof site.location !== 'string',
          ))
      )
        throw new Error('Invalid occurrence')
    }
    publication.value = index
    bom.value = evidence
    manifest.value = sealed
    state.value = 'published'
  } catch {
    state.value = 'error'
  }
})
</script>

<template>
  <div class="cbom-viewer">
    <div v-if="state === 'loading'" class="cbom-state" role="status">
      Loading the published CI evidence and checking its digests…
    </div>
    <div v-else-if="state === 'unavailable'" class="cbom-state" role="status">
      <strong>No CI evidence available</strong>
      <p v-if="frontmatter.docsRelease">
        Historical CI evidence is unavailable for this tagged documentation archive.
        The current snapshot’s evidence is not reused as evidence for this release.
      </p>
      <p v-else>
        This site build does not include published CI evidence. On the public
        site, a successful trusted develop build supplies the sealed census-demo
        outputs. Local previews can omit them; no sample findings or zero counts
        are substituted.
      </p>
    </div>
    <div
      v-else-if="state === 'error'"
      class="cbom-state cbom-error"
      role="alert"
    >
      <strong>The published evidence could not be verified</strong>
      <p>
        The inventory is hidden because its files are unavailable, inconsistent,
        or have different digests. Reload after the deployment finishes, or
        inspect the
        <a :href="repository + '/actions/workflows/ci.yml'">CI runs</a>
        .
      </p>
    </div>
    <template v-else-if="publication && bom && manifest">
      <section class="cbom-provenance" aria-label="Build provenance">
        <div>
          <span class="cbom-eyebrow">OBSERVED IN CI · DEVELOP</span>
          <h2>Payments demo workload</h2>
          <p>
            Finalized
            <time :datetime="publication.generatedAt">{{ finalizedTime }}</time>
          </p>
        </div>
        <div class="cbom-links">
          <a :href="runUrl">CI run {{ publication.runId }} ↗</a>
          <a :href="commitUrl">
            Commit {{ publication.sourceSha.slice(0, 10) }} ↗
          </a>
        </div>
      </section>
      <div class="cbom-metrics" aria-label="Inventory overview">
        <div>
          <strong>{{ assets.length }}</strong>
          <span>Cryptographic assets</span>
        </div>
        <div>
          <strong>{{ observedEvents }}</strong>
          <span>Entry-point events</span>
        </div>
        <div>
          <strong>{{ sites }}</strong>
          <span>Distinct reported sites</span>
        </div>
        <div>
          <strong>{{ bom.specVersion }}</strong>
          <span>CycloneDX version</span>
        </div>
      </div>
      <p class="cbom-inventory-jump">
        <a href="#cbom-inventory-heading">Explore the observed assets ↓</a>
      </p>
      <section class="cbom-panel" aria-labelledby="cbom-risk-heading">
        <div class="cbom-section-heading">
          <h2 id="cbom-risk-heading">Risk at a glance</h2>
          <a :href="withBase('/POLICY.html')">Understand the policy →</a>
        </div>
        <p>
          Asset verdicts recorded by Census. A passing build means its
          configured gate passed; it does not mean all cryptography is safe.
          Unknown requires review.
        </p>
        <div class="cbom-risk-grid">
          <button
            v-for="bucket in riskCounts"
            :key="bucket.value"
            type="button"
            :class="['cbom-risk-tile', 'risk-' + bucket.value]"
            :aria-pressed="riskFilter === bucket.value"
            @click="
              riskFilter = riskFilter === bucket.value ? 'all' : bucket.value
            "
          >
            <span class="cbom-dot" aria-hidden="true"></span>
            <span>{{ bucket.label }}</span>
            <strong>{{ bucket.count }}</strong>
          </button>
        </div>
      </section>
      <section
        class="cbom-panel cbom-health"
        aria-labelledby="cbom-health-heading"
      >
        <h2 id="cbom-health-heading">Collection &amp; build outcomes</h2>
        <dl class="cbom-outcomes">
          <div>
            <dt>Collection health</dt>
            <dd>{{ manifest.health }}</dd>
          </div>
          <div>
            <dt>Producer tests</dt>
            <dd>{{ manifest.testOutcome }}</dd>
          </div>
          <div>
            <dt>Crypto policy gate</dt>
            <dd>{{ manifest.policyOutcome }}</dd>
          </div>
          <div>
            <dt>Failure threshold</dt>
            <dd>{{ manifest.policy.failOn ?? 'Not recorded' }}</dd>
          </div>
        </dl>
        <p>
          Health describes the recorded collection checks, not workload
          coverage. Events count observed entry-point calls, not bytes or every
          cryptographic operation.
        </p>
        <div class="cbom-target-note">
          <strong>Unobserved instrumentation targets</strong>
          <p>{{ missingTargets }}</p>
          <small>
            Fail on missing targets:
            {{ manifest.policy.failOnMissingTargets ?? 'Not recorded' }}. A
            target that was not loaded cannot establish coverage for that API.
          </small>
        </div>
        <details>
          <summary>Collection counters, targets &amp; metadata</summary>
          <dl class="cbom-properties">
            <div v-for="(item, index) in metadata" :key="index">
              <dt>{{ item.name }}</dt>
              <dd>{{ item.value }}</dd>
            </div>
          </dl>
        </details>
      </section>
      <section class="cbom-inventory" aria-labelledby="cbom-inventory-heading">
        <div class="cbom-section-heading">
          <h2 id="cbom-inventory-heading">Cryptographic inventory</h2>
          <span aria-live="polite">
            {{ filtered.length }} of {{ assets.length }} assets
          </span>
        </div>
        <div class="cbom-filters">
          <label class="cbom-search">
            Search inventory
            <input
              v-model="search"
              type="search"
              placeholder="Algorithm, provider, operation or call site"
            />
          </label>
          <label>
            Risk
            <select v-model="riskFilter" aria-label="Risk">
              <option value="all">All verdicts</option>
              <option v-for="value in risks" :key="value" :value="value">
                {{ labels[value] }}
              </option>
            </select>
          </label>
          <label>
            Primitive
            <select v-model="primitiveFilter" aria-label="Primitive">
              <option value="all">All primitives</option>
              <option v-for="value in primitives" :key="value" :value="value">
                {{ value }}
              </option>
            </select>
          </label>
          <label>
            Sort by
            <select v-model="sortBy" aria-label="Sort by">
              <option value="recorded">Evidence order</option>
              <option value="name">Asset name</option>
              <option value="verdict">Verdict group</option>
            </select>
          </label>
          <button type="button" class="cbom-clear" @click="clear">
            Clear filters
          </button>
        </div>
        <p v-if="!filtered.length" class="cbom-state">
          No assets match these filters. Clear filters to see the complete
          observed inventory.
        </p>
        <div v-else class="cbom-asset-list">
          <details
            v-for="asset in filtered"
            :key="asset['bom-ref']"
            class="cbom-asset"
          >
            <summary>
              <span class="cbom-asset-name">
                <strong>{{ asset.name }}</strong>
                <small>{{ primitive(asset) }} · {{ operation(asset) }}</small>
              </span>
              <span class="cbom-provider">
                {{
                  assetProperty(asset, 'provider') ?? 'Provider not recorded'
                }}
              </span>
              <span :class="['cbom-badge', 'risk-' + risk(asset)]">
                {{ labels[risk(asset)] }}
              </span>
              <span class="cbom-expand" aria-hidden="true">+</span>
            </summary>
            <div class="cbom-asset-body">
              <p class="cbom-reason">
                {{
                  assetProperty(asset, 'reason') ??
                  'No assessment reason recorded. Review the original evidence.'
                }}
              </p>
              <dl class="cbom-outcomes">
                <div>
                  <dt>Observed key size</dt>
                  <dd>{{ keySize(asset) }}</dd>
                </div>
                <div>
                  <dt>Entry-point events</dt>
                  <dd>{{ assetProperty(asset, 'count') ?? 'Not recorded' }}</dd>
                </div>
                <div>
                  <dt>CNSA 2.0 flag</dt>
                  <dd>{{ assetProperty(asset, 'cnsa2') ?? 'Not recorded' }}</dd>
                </div>
              </dl>
              <h3>Observed call sites</h3>
              <p v-if="!asset.evidence?.occurrences?.length">
                No call-site evidence recorded.
              </p>
              <ol v-else class="cbom-sites">
                <li
                  v-for="(site, index) in asset.evidence.occurrences"
                  :key="index"
                >
                  <a v-if="sourceUrl(site)" :href="sourceUrl(site)">
                    <code>
                      {{ site.location
                      }}{{ site.line !== undefined ? ':' + site.line : '' }}
                    </code>
                  </a>
                  <code v-else>
                    {{ site.location
                    }}{{ site.line !== undefined ? ':' + site.line : '' }}
                  </code>
                  <span v-if="site.symbol">{{ site.symbol }}</span>
                  <small v-if="site.additionalContext">
                    {{ site.additionalContext }}
                  </small>
                </li>
              </ol>
              <p class="cbom-ref">
                <strong>Asset reference</strong>
                <code>{{ asset['bom-ref'] }}</code>
              </p>
              <details>
                <summary>
                  All asset properties &amp; native CycloneDX evidence
                </summary>
                <pre>{{ pretty(asset) }}</pre>
              </details>
            </div>
          </details>
        </div>
      </section>
      <section
        class="cbom-panel cbom-downloads"
        aria-labelledby="cbom-download-heading"
      >
        <h2 id="cbom-download-heading">Verify the source evidence</h2>
        <p>
          These are the exact sealed Maven outputs. The browser checked both
          SHA-256 digests and the manifest’s evidence binding before showing
          this inventory. Digests detect changed bytes; they are not a signature
          or independent attestation.
        </p>
        <div
          v-for="kind in ['evidence', 'manifest'] as const"
          :key="kind"
          class="cbom-download"
        >
          <a :href="kind === 'evidence' ? evidenceUrl : manifestUrl" download>
            {{
              kind === 'evidence' ? 'Download CBOM' : 'Download run manifest'
            }}
            ↓
          </a>
          <span>SHA-256</span>
          <code>{{ publication.downloads[kind].sha256 }}</code>
        </div>
        <details>
          <summary>Run identity, effective policy &amp; input digests</summary>
          <pre>{{ pretty(manifest) }}</pre>
        </details>
      </section>
    </template>
  </div>
</template>

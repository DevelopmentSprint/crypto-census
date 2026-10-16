import { test, expect, type Page } from '@playwright/test'
import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'

// Synthetic evidence is served only by request interception in these browser tests.
// It is never copied into the public documentation or presented as live CI output.
const properties = (values: Record<string, string>) =>
  Object.entries(values).map(([name, value]) => ({
    name: `cryptocensus:${name}`,
    value,
  }))
const digest = (text: string) => createHash('sha256').update(text).digest('hex')
const date = '2026-10-13T22:13:08Z'
const evidence = {
  bomFormat: 'CycloneDX',
  specVersion: '1.6',
  metadata: {
    properties: properties({
      policy: 'cryptocensus-default/6',
      missingTargets: 'sun.security.ssl.SSLSessionImpl=not-loaded',
      dropped: '0',
    }),
  },
  components: [
    {
      type: 'cryptographic-asset',
      'bom-ref': 'test:rsa-sign',
      name: 'SHA256withRSA-2048',
      properties: properties({
        risk: 'quantum-vulnerable',
        reason: 'Synthetic RSA signing finding',
        primitive: 'SIGNATURE',
        provider: 'TestProvider',
        operation: 'SIGN',
        keySize: '2048',
        count: '2',
      }),
      evidence: {
        occurrences: [
          {
            location: 'com.developmentsprint.cryptocensus.demo.PaymentSigner',
            line: 37,
            symbol: 'sign',
            additionalContext: 'count=2',
          },
        ],
      },
    },
    {
      type: 'cryptographic-asset',
      'bom-ref': 'test:rsa-verify',
      name: 'SHA256withRSA-2048',
      properties: properties({
        risk: 'quantum-vulnerable',
        primitive: 'SIGNATURE',
        provider: 'TestProvider',
        operation: 'VERIFY',
        count: '3',
      }),
    },
    {
      type: 'cryptographic-asset',
      'bom-ref': 'test:unknown',
      name: '<img src=x onerror=alert(1)>',
      properties: properties({ primitive: 'MAC', keySize: '0', count: '1' }),
    },
  ],
}
const evidenceText = JSON.stringify(evidence)
const manifest = {
  schema: 'census.maven-evidence/1',
  sha256: digest(evidenceText),
  health: 'healthy',
  testOutcome: 'passed',
  policyOutcome: 'passed',
  policy: { failOn: 'broken', failOnMissingTargets: false },
  descriptor: {
    module: 'com.developmentsprint:census-demo',
    runId: 'test-maven-run',
    moduleRunId: 'test-module-run',
  },
  revision: '1',
  finalizedAt: date,
  inputs: { 'cbom-test.json': '1'.repeat(64) },
}
const manifestText = JSON.stringify(manifest)
const index = {
  schemaVersion: 1,
  status: 'published',
  repository: 'DevelopmentSprint/crypto-census',
  sourceSha: 'a'.repeat(40),
  sourceBranch: 'develop',
  runId: '12345',
  runAttempt: '1',
  generatedAt: date,
  workload: {
    module: 'com.developmentsprint:census-demo',
    version: 'test',
    name: 'Payments demo workload',
    scope: 'Synthetic browser test',
  },
  collection: {
    runId: 'test-maven-run',
    moduleRunId: 'test-module-run',
    revision: '1',
    testOutcome: 'passed',
    health: 'healthy',
    policyOutcome: 'passed',
  },
  downloads: {
    evidence: { path: 'evidence.json', sha256: digest(evidenceText) },
    manifest: { path: 'manifest.json', sha256: digest(manifestText) },
  },
}

async function mockPublication(
  page: Page,
  publication: unknown = index,
  raw = evidenceText,
) {
  await page.route('**/example-cbom/*.json', async (route) => {
    const filename = new URL(route.request().url()).pathname.split('/').pop()
    const body =
      filename === 'index.json'
        ? JSON.stringify(publication)
        : filename === 'manifest.json'
          ? manifestText
          : raw
    await route.fulfill({ contentType: 'application/json', body })
  })
}

test('CBOM overview, independent outcomes, filtering, operation identity and exact downloads', async ({
  page,
}) => {
  await page.setViewportSize({ width: 1440, height: 1100 })
  await mockPublication(page)
  await page.goto('examples/example-cbom.html')
  await expect(
    page.getByRole('heading', { name: 'Payments demo workload' }),
  ).toBeVisible()
  await expect(page.locator('.cbom-asset')).toHaveCount(3)
  await expect(
    page
      .getByText('sun.security.ssl.SSLSessionImpl=not-loaded', { exact: true })
      .first(),
  ).toBeVisible()
  await expect(page.locator('.cbom-outcomes').first()).toContainText('healthy')
  await expect(page.getByRole('button', { name: 'Unknown 1' })).toBeVisible()
  await page.getByLabel('Search inventory').fill('verify')
  await expect(page.locator('.cbom-asset')).toHaveCount(1)
  await expect(page.locator('.cbom-asset')).toContainText('VERIFY')
  await page.getByRole('button', { name: 'Clear filters' }).click()
  await page.getByLabel('Risk', { exact: true }).selectOption('unknown')
  await expect(page.locator('.cbom-asset')).toHaveCount(1)
  await page.locator('.cbom-asset > summary').click()
  await expect(page.locator('.cbom-asset-body')).toContainText('Not recorded')
  await expect(page.locator('.cbom-asset-body')).not.toContainText('0 bits')
  await expect(page.locator('.cbom-asset img')).toHaveCount(0)
  await page.getByRole('button', { name: 'Clear filters' }).click()
  await page.getByLabel('Primitive', { exact: true }).selectOption('SIGNATURE')
  await expect(page.locator('.cbom-asset')).toHaveCount(2)
  await page.locator('.cbom-asset > summary').first().focus()
  await page.keyboard.press('Enter')
  await expect(
    page.getByText('Synthetic RSA signing finding', { exact: true }),
  ).toBeVisible()
  await expect(page.locator('.cbom-sites a')).toHaveAttribute(
    'href',
    /\/blob\/a{40}\/census-demo\/src\/main\/java\/.*PaymentSigner\.java#L37$/,
  )
  await expect(
    page.getByRole('link', { name: 'Download CBOM' }),
  ).toHaveAttribute('href', '/example-cbom/evidence.json')
  await expect(page.locator('.cbom-download').first()).toContainText(
    digest(evidenceText),
  )
  await page.getByLabel('Search inventory').fill('nothing-matches-this')
  await expect(page.getByText(/No assets match these filters/)).toBeVisible()
  await page.evaluate(() => window.scrollTo(0, 0))
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(0)
  await page.screenshot({
    path: 'test-results/cbom-desktop-filters.png',
    fullPage: true,
  })
})

test('CBOM mobile and dark theme preserve readable details without page overflow', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await mockPublication(page)
  await page.goto('examples/example-cbom.html')
  await expect(page.locator('.cbom-asset')).toHaveCount(3)
  await page.locator('.cbom-asset > summary').first().click()
  await expect(page.locator('.cbom-sites')).toBeVisible()
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true)
  await page.evaluate(() => window.scrollTo(0, 0))
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(0)
  await page.screenshot({
    path: 'test-results/cbom-mobile.png',
    fullPage: true,
  })
  await page.evaluate(() => document.documentElement.classList.add('dark'))
  await page.evaluate(() => window.scrollTo(0, 0))
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(0)
  await page.screenshot({
    path: 'test-results/cbom-mobile-dark.png',
    fullPage: true,
  })
  await page.getByRole('button', { name: 'Unknown 1' }).click()
  await expect(page.locator('.cbom-asset')).toHaveCount(1)
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true)
})

test('unavailable evidence never becomes an empty successful inventory', async ({
  page,
}) => {
  await mockPublication(page, { schemaVersion: 1, status: 'unavailable' })
  await page.goto('examples/example-cbom.html')
  await expect(page.getByText('No CI evidence available')).toBeVisible()
  await expect(page.locator('.cbom-metrics')).toHaveCount(0)
})

test('mismatched evidence is hidden instead of displaying tampered findings', async ({
  page,
}) => {
  await mockPublication(page, index, evidenceText + ' ')
  await page.goto('examples/example-cbom.html')
  await expect(page.getByRole('alert')).toContainText('could not be verified')
  await expect(page.locator('.cbom-asset')).toHaveCount(0)
})

test('publication cannot redirect evidence requests to an arbitrary destination', async ({
  page,
}) => {
  let outbound = false
  await page.route('https://invalid.example/**', (route) => {
    outbound = true
    return route.abort()
  })
  await mockPublication(page, {
    ...index,
    downloads: {
      ...index.downloads,
      evidence: {
        ...index.downloads.evidence,
        path: 'https://invalid.example/private',
      },
    },
  })
  await page.goto('examples/example-cbom.html')
  await expect(page.getByRole('alert')).toContainText('could not be verified')
  expect(outbound).toBe(false)
})

test('generated publication renders its actual CI evidence without request mocks', async ({
  page,
}) => {
  const directory = 'docs/.vitepress/dist/example-cbom'
  const publication = JSON.parse(
    await readFile(`${directory}/index.json`, 'utf8'),
  )
  await page.setViewportSize({ width: 1440, height: 1100 })
  await page.goto('examples/example-cbom.html')
  if (publication.status === 'unavailable') {
    await expect(page.getByText('No CI evidence available')).toBeVisible()
    await expect(page.locator('.cbom-metrics')).toHaveCount(0)
    await expect(page.locator('.cbom-viewer [role="alert"]')).toHaveCount(0)
    return
  }
  expect(publication.status).toBe('published')
  const raw = await readFile(`${directory}/evidence.json`, 'utf8')
  const evidence = JSON.parse(raw)
  const assets = evidence.components.filter(
    (component: { type: string }) => component.type === 'cryptographic-asset',
  )
  expect(digest(raw)).toBe(publication.downloads.evidence.sha256)
  await expect(
    page.getByRole('heading', { name: 'Payments demo workload' }),
  ).toBeVisible()
  await expect(page.locator('.cbom-asset')).toHaveCount(assets.length)
  await expect(page.locator('.cbom-asset-name strong')).toHaveText(
    assets.map((asset: { name: string }) => asset.name),
  )
  await expect(page.locator('.cbom-ref code')).toHaveText(
    assets.map((asset: { 'bom-ref': string }) => asset['bom-ref']),
  )
  await expect(
    page.getByRole('link', {
      name: `Commit ${publication.sourceSha.slice(0, 10)} ↗`,
    }),
  ).toHaveAttribute(
    'href',
    `https://github.com/DevelopmentSprint/crypto-census/commit/${publication.sourceSha}`,
  )
  await expect(
    page.getByRole('link', { name: `CI run ${publication.runId} ↗` }),
  ).toHaveAttribute(
    'href',
    `https://github.com/DevelopmentSprint/crypto-census/actions/runs/${publication.runId}/attempts/${publication.runAttempt}`,
  )
  await expect(page.locator('.cbom-provenance time')).toHaveAttribute(
    'datetime',
    publication.generatedAt,
  )
  await expect(page.locator('.cbom-outcomes').first()).toContainText(
    publication.collection.health,
  )
  await expect(page.locator('.cbom-viewer [role="alert"]')).toHaveCount(0)
  const download = await page.request.get(
    '/example-cbom/evidence.json',
  )
  expect(download.ok()).toBe(true)
  expect(await download.text()).toBe(raw)
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true)
  await page.evaluate(() => window.scrollTo(0, 0))
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(0)
  await page.screenshot({
    path: 'test-results/cbom-published-desktop.png',
    fullPage: true,
  })
  await page.setViewportSize({ width: 390, height: 844 })
  if (assets.length) await page.locator('.cbom-asset > summary').first().click()
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true)
  await page.evaluate(() => window.scrollTo(0, 0))
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(0)
  await page.screenshot({
    path: 'test-results/cbom-published-mobile.png',
    fullPage: true,
  })
})

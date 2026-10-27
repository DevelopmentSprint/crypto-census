import { test, expect } from '@playwright/test'

const tag = process.env.DOCS_TEST_RELEASE_TAG

test('Latest identifies snapshot installation without inventing a release', async ({ page }) => {
  await page.goto('./')
  await expect(page.locator('.release-notice')).toContainText('Latest development snapshot')
  await expect(page.locator('.release-notice a')).toHaveAttribute('href', '/SNAPSHOTS.html')
  await expect(page.locator('.VPNavBar').getByRole('button', { name: 'Latest · snapshot' })).toBeVisible()
  const versions = await (await page.request.get('/versions.json')).json()
  for (const version of versions) {
    expect(version.sha).toMatch(/^[a-f0-9]{40}$/)
    expect(version.tag).not.toMatch(/snapshot/i)
    expect(version.path).toBe(`/versions/${version.tag}/`)
  }
})

test('tagged archive styles, search, source links, evidence and version navigation', async ({ page }) => {
  test.skip(!tag, 'Synthetic release archives are exercised only in an isolated fixture repository.')
  const base = `/versions/${tag}/`
  await page.goto('getting-started/installation.html')
  await expect(page.getByText('Latest-only fixture content.')).toBeVisible()
  await expect(page.getByText('Tagged release fixture content.')).toHaveCount(0)
  await page.goto(base + 'getting-started/installation.html')
  await expect(page.getByText('Tagged release fixture content.')).toBeVisible()
  await expect(page.getByText('Latest-only fixture content.')).toHaveCount(0)
  await page.goto(base + 'examples/example-cbom.html')
  await expect(page.locator('.release-notice')).toContainText(`Archived ${tag}`)
  await expect(page.locator('.release-notice a')).toHaveText('Read Latest')
  await expect(page.getByText('Historical CI evidence is unavailable for this tagged documentation archive.')).toBeVisible()
  const metadata = await (await page.request.get(base + 'site-evidence.json')).json()
  expect(metadata.status).toBe('historical evidence unavailable')
  expect(metadata.tag).toBe(tag)
  await expect(page.locator('.edit-link-button')).toHaveAttribute('href', `https://github.com/DevelopmentSprint/crypto-census/blob/${metadata.siteSourceSha}/docs/examples/example-cbom.md`)
  expect((await (await page.request.get(base + 'example-cbom/index.json')).json()).status).toBe('unavailable')
  expect((await page.request.get(base + 'example-cbom/evidence.json')).status()).toBe(404)
  for (const stylesheet of await page.locator('link[rel~="stylesheet"]').evaluateAll(links => links.map(link => (link as HTMLLinkElement).href))) {
    expect(new URL(stylesheet).pathname).toContain(base)
    expect((await page.request.get(stylesheet)).status()).toBe(200)
  }
  const favicon = await page.locator('link[rel="icon"]').getAttribute('href')
  expect(favicon).toBe(base + 'favicon.svg')
  expect((await page.request.get(favicon!)).status()).toBe(200)
  await page.locator('.VPNavBarSearch button').click()
  await page.locator('#localsearch-input').fill('delivery profiles')
  const result = page.locator('.VPLocalSearchBox .results li a').first()
  await expect(result).toBeVisible()
  await result.click()
  await expect(page).toHaveURL(new RegExp(`/versions/${tag}/`))
  await page.locator('.VPNavBar').getByRole('button', { name: tag!, exact: true }).hover()
  await expect(page.locator('.VPNavBar').getByRole('link', { name: 'Latest · snapshot', exact: true })).toHaveAttribute('href', 'https://cryptocensus.developmentsprint.com/')
  await expect(page.locator('.VPNavBar').getByRole('link', { name: tag!, exact: true })).toHaveAttribute('href', `https://cryptocensus.developmentsprint.com${base}`)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto(base)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: 'test-results/release-archive-mobile.png', fullPage: true })
})

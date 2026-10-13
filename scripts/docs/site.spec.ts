import { test, expect } from '@playwright/test'
import { readdir, readFile } from 'node:fs/promises'
import path from 'node:path'
import { createMarkdownRenderer } from 'vitepress'

async function markdownFiles(directory: string): Promise<string[]> {
  const entries = await readdir(directory, { withFileTypes: true })
  const children = await Promise.all(entries.filter(entry => !entry.name.startsWith('.') && entry.name !== 'public').map(entry => {
    const filename = path.join(directory, entry.name)
    return entry.isDirectory() ? markdownFiles(filename) : Promise.resolve(filename.endsWith('.md') ? [filename] : [])
  }))
  return children.flat()
}

const markdownRenderer = await createMarkdownRenderer(path.resolve('docs'))
for (const file of await markdownFiles('docs')) {
  const markdown = await readFile(file, 'utf8')
  const diagrams = markdownRenderer.parse(markdown, {}).filter(token => token.type === 'fence' && token.info.trim() === 'mermaid').length
  if (!diagrams) continue
  test(`render all ${diagrams} diagrams: ${file}`, async ({ page }) => {
    const errors: string[] = []
    page.on('pageerror', error => errors.push(error.message))
    await page.goto(file.replace(/^docs\//, '').replace(/index\.md$/, '').replace(/\.md$/, '.html'))
    await expect(page.locator('.census-diagram svg')).toHaveCount(diagrams, { timeout: 60_000 })
    await expect(page.locator('[data-diagram-error]')).toHaveCount(0)
    // SVG labels avoid theme paragraph styles clipping HTML labels inside nodes.
    await expect(page.locator('.census-diagram svg foreignObject')).toHaveCount(0)
    expect(errors).toEqual([])
  })
}

test('desktop navigation, keyboard search, theme and page preview', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto('./')
  await expect(page.getByRole('heading', { name: /Know your cryptography/ })).toBeVisible()
  await page.screenshot({ path: 'test-results/home-desktop.png', fullPage: true })
  await page.locator('.VPNavBarSearch button').click()
  await page.locator('#localsearch-input').fill('delivery profiles')
  await expect(page.locator('.VPLocalSearchBox .results li').first()).toBeVisible()
  await page.keyboard.press('Escape')
  await page.locator('.VPNavBarAppearance button').click()
  await expect(page.locator('html')).toHaveClass(/dark/)
  await page.screenshot({ path: 'test-results/home-dark.png', fullPage: true })
  await page.goto('architecture/overview.html')
  await expect(page.locator('.census-diagram svg').first()).toBeVisible()
  await page.screenshot({ path: 'test-results/architecture.png', fullPage: true })
})

test('mobile layout and navigation remain usable', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('./')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  const heading = await page.locator('.hero-eyebrow').boundingBox()
  const navigation = await page.locator('.VPNavBar').boundingBox()
  expect(heading!.y).toBeGreaterThanOrEqual(navigation!.y + navigation!.height + 16)
  await page.screenshot({ path: 'test-results/home-mobile.png', fullPage: true })
  await page.locator('.VPNavBarHamburger').click()
  await page.locator('.VPNavScreen').getByRole('link', { name: 'Start here', exact: true }).click()
  await expect(page.locator('main h1')).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.goto('architecture/overview.html')
  await expect(page.locator('.census-diagram svg')).toHaveCount(2)
  expect(await page.locator('.census-diagram svg').evaluateAll(elements => elements.every(element => {
    const svg = element as SVGSVGElement
    return svg.getBoundingClientRect().width >= svg.viewBox.baseVal.width - 1
  }))).toBe(true)
  await page.screenshot({ path: 'test-results/architecture-mobile.png', fullPage: true })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})

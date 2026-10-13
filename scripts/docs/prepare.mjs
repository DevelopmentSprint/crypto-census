import { mkdir, readFile, writeFile, copyFile } from 'node:fs/promises'
import path from 'node:path'

const destination = 'docs/public/badges'
await mkdir(destination, { recursive: true })
const input = process.env.DOCS_COVERAGE_DIR
if (input) {
  const metadata = JSON.parse(await readFile(path.join(input, 'coverage.json'), 'utf8'))
  const expected = process.env.DOCS_SOURCE_SHA
  if (!expected || !/^[a-f0-9]{40}$/.test(expected) || metadata.sourceSha !== expected || metadata.schemaVersion !== 1) {
    throw new Error('Coverage provenance must match the exact documentation source SHA (DOCS_SOURCE_SHA).')
  }
  if (!process.env.DOCS_RUN_URL || metadata.runUrl !== process.env.DOCS_RUN_URL) {
    throw new Error('Coverage run URL must match the exact producing CI run (DOCS_RUN_URL).')
  }
  for (const metric of ['line', 'branch']) {
    const counter = metadata.aggregate?.[metric]
    if (!counter || !Number.isSafeInteger(counter.covered) || !Number.isSafeInteger(counter.missed) ||
        counter.covered < 0 || counter.missed < 0 || counter.covered + counter.missed === 0) {
      throw new Error(`Missing or invalid aggregate ${metric} coverage`)
    }
  }
  for (const filename of ['coverage.json', 'coverage.svg', 'coverage-branches.svg']) {
    await copyFile(path.join(input, filename), path.join(destination, filename))
  }
  console.log(`Prepared measured coverage from ${expected}`)
} else {
  if (process.env.DOCS_REQUIRE_COVERAGE === 'true') throw new Error('Trusted CI coverage artifact is required for deployment.')
  for (const [filename, label] of [['coverage.svg', 'lines'], ['coverage-branches.svg', 'branches']]) {
    await writeFile(path.join(destination, filename), `<svg xmlns="http://www.w3.org/2000/svg" width="205" height="20" role="img" aria-label="${label}: not measured locally"><rect width="205" height="20" rx="3" fill="#555"/><text x="102" y="14" text-anchor="middle" fill="white" font-family="Verdana,sans-serif" font-size="11">${label}: not measured locally</text></svg>\n`)
  }
  await writeFile(path.join(destination, 'coverage.json'), JSON.stringify({ schemaVersion: 1, status: 'not measured locally' }, null, 2) + '\n')
  console.log('Local preview: neutral unmeasured badges (never used for Pages deployment).')
}

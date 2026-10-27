import { execFileSync } from 'node:child_process'
import { mkdir, readFile, writeFile, copyFile } from 'node:fs/promises'
import path from 'node:path'

// A different producer SHA is allowed only after API selection and a fresh Git
// ancestry/code-equivalence check. Never change the producer metadata itself.
if (process.env.DOCS_EVIDENCE_PROOF || (process.env.DOCS_EVIDENCE_SHA && process.env.DOCS_EVIDENCE_SHA !== process.env.DOCS_SOURCE_SHA)) {
  execFileSync('python3', ['scripts/docs/evidence_source.py', 'verify'], { stdio: 'inherit' })
}
const archive = process.env.DOCS_RELEASE_TAG
if (archive && Object.keys(process.env).some(key => /^DOCS_(?:COVERAGE_DIR|CBOM_DIR|EVIDENCE_PROOF|EVIDENCE_SHA|REQUIRE_COVERAGE|REQUIRE_CBOM)$/.test(key) && process.env[key])) {
  throw new Error('Release archives cannot use Latest evidence or evidence requirements.')
}
const unmeasured = archive ? 'historical measurement unavailable' : 'not measured locally'
// Public synthetic measurements and schemas linked from the advisory documentation.
for (const asset of [
  'measurements/enrichment-2026-10-27.json',
  'schemas/source-catalog-v1.schema.json',
  'schemas/assessment-v1.schema.json'
]) {
  const target = path.join('docs/public', asset)
  await mkdir(path.dirname(target), { recursive: true })
  await copyFile(path.join('docs', asset), target)
}

const destination = 'docs/public/badges'
await mkdir(destination, { recursive: true })
const input = process.env.DOCS_COVERAGE_DIR
if (input) {
  const metadata = JSON.parse(await readFile(path.join(input, 'coverage.json'), 'utf8'))
  const expected = process.env.DOCS_EVIDENCE_SHA || process.env.DOCS_SOURCE_SHA
  if (!expected || !/^[a-f0-9]{40}$/.test(expected) || metadata.sourceSha !== expected || metadata.schemaVersion !== 1) {
    throw new Error('Coverage provenance must match the verified producing source SHA.')
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
    await writeFile(path.join(destination, filename), `<svg xmlns="http://www.w3.org/2000/svg" width="${archive ? 320 : 205}" height="20" role="img" aria-label="${label}: ${unmeasured}"><rect width="${archive ? 320 : 205}" height="20" rx="3" fill="#555"/><text x="${archive ? 160 : 102}" y="14" text-anchor="middle" fill="white" font-family="Verdana,sans-serif" font-size="11">${label}: ${unmeasured}</text></svg>\n`)
  }
  await writeFile(path.join(destination, 'coverage.json'), JSON.stringify({ schemaVersion: 1, status: unmeasured }, null, 2) + '\n')
  console.log(archive ? 'Tagged archive: historical measurements unavailable; Latest artifacts excluded.' : 'Local preview: neutral unmeasured badges (never used for Pages deployment).')
}

// The publication validator is shared with CI; only explicitly allowlisted files reach Pages.
execFileSync('python3', ['scripts/docs/example_cbom.py', 'prepare'], { stdio: 'inherit' })

let siteEvidence = { schemaVersion: 1, status: archive ? 'historical evidence unavailable' : 'unavailable in this preview', ...(archive ? { tag: archive, siteSourceSha: process.env.DOCS_RELEASE_SHA } : {}) }
if (process.env.DOCS_EVIDENCE_PROOF) {
  const proof = JSON.parse(await readFile(process.env.DOCS_EVIDENCE_PROOF, 'utf8'))
  siteEvidence = {
    schemaVersion: 1, status: 'published', siteSourceSha: proof.siteSourceSha,
    evidenceSourceSha: proof.evidenceSourceSha, runId: proof.runId,
    selectedRunAttempt: proof.runAttempt, runUrl: proof.runUrl, reused: proof.reused,
  }
}
await writeFile('docs/public/site-evidence.json', JSON.stringify(siteEvidence, null, 2) + '\n')

import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, readFileSync, existsSync, rmSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

// Override only for an isolated worker reviewing the integration branch's script.
const script = readFileSync(process.env.DOCS_PREPARE_SCRIPT || fileURLToPath(new URL('./prepare.mjs', import.meta.url)))
const sha = 'a'.repeat(40)
const runUrl = 'https://github.com/DevelopmentSprint/crypto-census/actions/runs/123'
const metric = { covered: 9, missed: 1, percent: 90 }

function fixture(t) {
  const directory = mkdtempSync(path.join(os.tmpdir(), 'census-docs-prepare-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  writeFileSync(path.join(directory, 'prepare.mjs'), script)
  mkdirSync(path.join(directory, 'scripts/docs'), { recursive: true })
  writeFileSync(path.join(directory, 'scripts/docs/example_cbom.py'), readFileSync(fileURLToPath(new URL('./example_cbom.py', import.meta.url))))
  for (const name of ['evidence_source.py', '../ci_changes.py']) {
    writeFileSync(path.join(directory, 'scripts/docs', name), readFileSync(fileURLToPath(new URL(name, import.meta.url))))
  }
  const input = path.join(directory, 'input')
  mkdirSync(input)
  const metadata = {
    schemaVersion: 1, sourceSha: sha, runUrl,
    generatedAt: '2026-10-13T00:00:00+00:00',
    scope: 'Synthetic test fixture, never published.',
    modules: [], aggregate: { line: { ...metric }, branch: { ...metric } },
  }
  const save = () => writeFileSync(path.join(input, 'coverage.json'), JSON.stringify(metadata))
  save()
  for (const name of ['coverage.svg', 'coverage-branches.svg']) {
    writeFileSync(path.join(input, name), '<svg xmlns="http://www.w3.org/2000/svg"><title>coverage: 90.0%</title></svg>\n')
  }
  const output = path.join(directory, 'docs/public/badges')
  const run = (overrides = {}) => {
    const env = { ...process.env }
    for (const key of ['DOCS_COVERAGE_DIR', 'DOCS_REQUIRE_COVERAGE', 'DOCS_SOURCE_SHA', 'DOCS_RUN_URL', 'DOCS_CBOM_DIR', 'DOCS_REQUIRE_CBOM', 'DOCS_EVIDENCE_SHA', 'DOCS_EVIDENCE_PROOF']) delete env[key]
    for (const [key, value] of Object.entries(overrides)) if (value !== undefined) env[key] = value
    return spawnSync(process.execPath, ['prepare.mjs'], { cwd: directory, env, encoding: 'utf8' })
  }
  const trusted = (overrides = {}) => run({
    DOCS_COVERAGE_DIR: input, DOCS_REQUIRE_COVERAGE: 'true', DOCS_SOURCE_SHA: sha,
    DOCS_RUN_URL: runUrl, ...overrides,
  })
  return { directory, input, output, metadata, save, run, trusted }
}

function rejected(result) {
  assert.notEqual(result.status, 0, `Unexpected success: ${result.stdout}`)
  assert.equal(result.signal, null)
}

test('deployment requires a coverage artifact', t => {
  const f = fixture(t)
  rejected(f.run({ DOCS_REQUIRE_COVERAGE: 'true', DOCS_SOURCE_SHA: sha, DOCS_RUN_URL: runUrl }))
  assert.equal(existsSync(path.join(f.output, 'coverage.svg')), false)
})

test('local preview creates explicitly unmeasured badges and metadata', t => {
  const f = fixture(t)
  const result = f.run()
  assert.equal(result.status, 0, result.stderr)
  for (const name of ['coverage.svg', 'coverage-branches.svg']) {
    const svg = readFileSync(path.join(f.output, name), 'utf8')
    assert.match(svg, /not measured locally/)
    assert.doesNotMatch(svg, /[0-9]+(?:\.[0-9]+)?%/)
  }
  assert.equal(JSON.parse(readFileSync(path.join(f.output, 'coverage.json'))).status, 'not measured locally')
})

test('matching SHA and exact run retain the artifact bytes', t => {
  const f = fixture(t)
  const result = f.trusted()
  assert.equal(result.status, 0, result.stderr)
  for (const name of ['coverage.svg', 'coverage-branches.svg', 'coverage.json']) {
    assert.deepEqual(readFileSync(path.join(f.output, name)), readFileSync(path.join(f.input, name)))
  }
})

test('wrong source SHA fails before publishing badge files', t => {
  const f = fixture(t)
  f.metadata.sourceSha = 'b'.repeat(40)
  f.save()
  rejected(f.trusted())
  assert.equal(existsSync(path.join(f.output, 'coverage.svg')), false)
})

test('missing or malformed expected source SHA fails closed', t => {
  const f = fixture(t)
  rejected(f.trusted({ DOCS_SOURCE_SHA: undefined }))
  rejected(f.trusted({ DOCS_SOURCE_SHA: 'not-a-commit' }))
})

test('matching source from a different CI run is rejected', t => {
  const f = fixture(t)
  f.metadata.runUrl = runUrl.replace('/123', '/456')
  f.save()
  rejected(f.trusted())
  assert.equal(existsSync(path.join(f.output, 'coverage.svg')), false)
})

test('missing expected run URL is rejected', t => {
  const f = fixture(t)
  rejected(f.trusted({ DOCS_RUN_URL: undefined }))
})

test('missing, malformed or unsupported metadata fails closed', t => {
  const f = fixture(t)
  const metadata = path.join(f.input, 'coverage.json')
  rmSync(metadata)
  rejected(f.trusted())
  writeFileSync(metadata, '{')
  rejected(f.trusted())
  f.metadata.schemaVersion = 2
  f.save()
  rejected(f.trusted())
})

test('missing SVG cannot be a successful deployment preparation', t => {
  const f = fixture(t)
  rmSync(path.join(f.input, 'coverage-branches.svg'))
  rejected(f.trusted())
})

test('invalid counters and empty denominators fail closed', t => {
  const f = fixture(t)
  for (const counter of [undefined, { covered: -1, missed: 1 }, { covered: '9', missed: 1 },
    { covered: 1.5, missed: 1 }, { covered: 0, missed: 0 }, { covered: Number.MAX_SAFE_INTEGER + 1, missed: 1 }]) {
    f.metadata.aggregate.line = counter
    f.save()
    rejected(f.trusted())
  }
})


test('reused coverage requires actual code equivalence and keeps original bytes and source', t => {
  const f = fixture(t)
  const git = (...args) => {
    const result = spawnSync('git', args, { cwd: f.directory, encoding: 'utf8' })
    assert.equal(result.status, 0, result.stderr)
    return result.stdout.trim()
  }
  git('init', '-q')
  git('config', 'user.name', 'Synthetic Test')
  git('config', 'user.email', 'test@example.invalid')
  writeFileSync(path.join(f.directory, 'runtime.java'), 'runtime')
  git('add', 'runtime.java')
  git('commit', '-qm', 'synthetic baseline')
  const producer = git('rev-parse', 'HEAD')
  mkdirSync(path.join(f.directory, 'docs'), { recursive: true })
  writeFileSync(path.join(f.directory, 'docs/page.md'), 'docs-only change')
  git('add', 'docs/page.md')
  git('commit', '-qm', 'synthetic documentation')
  const site = git('rev-parse', 'HEAD')
  f.metadata.sourceSha = producer
  f.save()
  const proof = { schemaVersion: 1, repository: 'DevelopmentSprint/crypto-census',
    siteSourceSha: site, evidenceSourceSha: producer, runId: '123', runAttempt: '1',
    runUrl, reused: true, coverageArtifactId: '12', cbomArtifactId: '13' }
  const proofPath = path.join(f.directory, 'selection.json')
  writeFileSync(proofPath, JSON.stringify(proof))
  const env = { DOCS_SOURCE_SHA: site, DOCS_EVIDENCE_SHA: producer, DOCS_RUN_ID: '123',
    DOCS_RUN_ATTEMPT: '1', DOCS_EVIDENCE_PROOF: proofPath }
  rejected(f.trusted({ ...env, DOCS_EVIDENCE_PROOF: undefined }))
  const result = f.trusted(env)
  assert.equal(result.status, 0, result.stderr)
  assert.deepEqual(readFileSync(path.join(f.output, 'coverage.json')), readFileSync(path.join(f.input, 'coverage.json')))
  const published = JSON.parse(readFileSync(path.join(f.directory, 'docs/public/site-evidence.json')))
  assert.equal(published.siteSourceSha, site)
  assert.equal(published.evidenceSourceSha, producer)
  assert.equal(published.selectedRunAttempt, '1')
  writeFileSync(path.join(f.directory, 'runtime.java'), 'changed runtime')
  git('add', 'runtime.java')
  git('commit', '-qm', 'synthetic runtime change')
  const changed = git('rev-parse', 'HEAD')
  writeFileSync(proofPath, JSON.stringify({ ...proof, siteSourceSha: changed }))
  rejected(f.trusted({ ...env, DOCS_SOURCE_SHA: changed }))
})

test('release archives label missing historical measurements without reusing Latest evidence', t => {
  const f = fixture(t)
  const archive = { DOCS_RELEASE_TAG: 'v9.9.9', DOCS_RELEASE_SHA: sha }
  const result = f.run(archive)
  assert.equal(result.status, 0, result.stderr)
  assert.equal(JSON.parse(readFileSync(path.join(f.output, 'coverage.json'))).status, 'historical measurement unavailable')
  assert.match(readFileSync(path.join(f.output, 'coverage.svg'), 'utf8'), /historical measurement unavailable/)
  const cbom = JSON.parse(readFileSync(path.join(f.directory, 'docs/public/example-cbom/index.json')))
  assert.equal(cbom.status, 'unavailable')
  assert.match(cbom.message, /Historical CI evidence is unavailable/)
  const evidence = JSON.parse(readFileSync(path.join(f.directory, 'docs/public/site-evidence.json')))
  assert.equal(evidence.tag, 'v9.9.9')
  assert.equal(evidence.siteSourceSha, sha)
  assert.equal(evidence.status, 'historical evidence unavailable')
  rejected(f.trusted(archive))
  rejected(f.run({ ...archive, DOCS_CBOM_DIR: '/must-not-reuse-latest' }))
})

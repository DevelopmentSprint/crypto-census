import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import { analyticsHead } from './analytics.mjs'

function browser(url, doNotTrack = '') {
  const scripts = []
  const location = new URL(url)
  const context = vm.createContext({
    location,
    navigator: { doNotTrack },
    window: {},
    document: {
      createElement: name => ({ tagName: name }),
      head: { appendChild: script => scripts.push(script) },
    },
  })
  return { context, scripts }
}

test('unconfigured builds contain no analytics script', () => {
  assert.deepEqual(analyticsHead(), [])
  assert.deepEqual(analyticsHead('  '), [])
})

test('rejects malformed IDs and script injection', () => {
  for (const id of ['UA-123', '123', 'G-', 'G-TEST&extra=1', 'G-TEST</script>']) {
    assert.throws(() => analyticsHead(id), /DOCS_GA4_MEASUREMENT_ID/)
  }
})

test('production loads one async tag with advertising signals disabled', () => {
  const { context, scripts } = browser('https://cryptocensus.developmentsprint.com/examples/example-cbom.html')
  const script = analyticsHead(' G-TEST123 ')[0][2]
  vm.runInContext(script, context)
  vm.runInContext(script, context)
  assert.equal(scripts.length, 1)
  assert.equal(scripts[0].async, true)
  assert.equal(scripts[0].src, 'https://www.googletagmanager.com/gtag/js?id=G-TEST123')
  const commands = context.window.dataLayer.map(command => Array.from(command))
  assert.equal(commands.length, 2)
  assert.equal(commands[0][0], 'js')
  assert.equal(commands[1][0], 'config')
  assert.equal(commands[1][1], 'G-TEST123')
  assert.equal(commands[1][2].allow_google_signals, false)
  assert.equal(commands[1][2].allow_ad_personalization_signals, false)
  assert.equal(commands[1][2].send_page_view, undefined)
})

test('published artifacts never load analytics on local or alternate hosts', () => {
  for (const url of [
    'http://127.0.0.1:5173/',
    'http://localhost:5173/',
    'https://preview.example/',
    'https://developmentsprint.github.io/crypto-census/',
    'https://cryptocensus.developmentsprint.com.preview.example/',
    'http://cryptocensus.developmentsprint.com/',
  ]) {
    const { context, scripts } = browser(url)
    vm.runInContext(analyticsHead('G-TEST123')[0][2], context)
    assert.equal(scripts.length, 0, url)
    assert.equal(context.window.dataLayer, undefined, url)
  }
})

test('honors the browser Do Not Track preference', () => {
  const { context, scripts } = browser('https://cryptocensus.developmentsprint.com/', '1')
  vm.runInContext(analyticsHead('G-TEST123')[0][2], context)
  assert.equal(scripts.length, 0)
  assert.equal(context.window.dataLayer, undefined)
})

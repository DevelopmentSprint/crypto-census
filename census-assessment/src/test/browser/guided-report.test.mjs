import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtemp, readFile, rm, mkdir } from 'node:fs/promises';
import { resolve, delimiter } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { chromium } from 'playwright';

const root = fileURLToPath(new URL('../../../../', import.meta.url));
const target = resolve(root, 'census-assessment/target');
const java = process.env.JAVA_HOME ? resolve(process.env.JAVA_HOME, 'bin/java') : 'java';
const classpath = ['census-assessment/target/test-classes', 'census-assessment/target/classes', 'census-core/target/classes'].map(path => resolve(root, path)).join(delimiter);
function generate(directory, changes) {
  execFileSync(java, ['-cp', classpath, 'com.developmentsprint.cryptocensus.assessment.GuidedReportFixture', directory, ...(changes ? [changes] : [])], {stdio: 'pipe'});
}
async function fixture(t) {
  await mkdir(target, {recursive:true});
  const directory = await mkdtemp(resolve(target, 'browser-'));
  generate(directory);
  const browser = await chromium.launch({headless:true, ...(process.env.CENSUS_BROWSER_EXECUTABLE ? {executablePath:process.env.CENSUS_BROWSER_EXECUTABLE} : {})});
  const page = await browser.newPage({acceptDownloads:true});
  const requests = [], errors = [], violations = [];
  page.on('request', request => { if (/^https?:/.test(request.url())) requests.push(request.url()); });
  page.on('pageerror', error => errors.push(error.message));
  await page.addInitScript(() => {
    window.censusCspViolations = [];
    document.addEventListener('securitypolicyviolation', event => window.censusCspViolations.push(event.violatedDirective));
  });
  await page.goto(pathToFileURL(resolve(directory, 'assessment.html')).href);
  await page.locator('#flow-navigation').waitFor();
  t.after(async () => {
    violations.push(...await page.evaluate(() => window.censusCspViolations || []));
    await browser.close(); await rm(directory, {recursive:true, force:true});
    assert.deepEqual(requests, [], 'offline page must not request network resources');
    assert.deepEqual(errors, [], 'page must not raise JavaScript errors');
    assert.deepEqual(violations, [], 'bundled resources must satisfy CSP');
  });
  return {page, directory};
}
async function exportChanges(page, directory) {
  const downloaded = page.waitForEvent('download');
  await page.getByRole('button', {name:'Export proposed changes', exact:true}).click();
  const download = await downloaded;
  assert.equal(download.suggestedFilename(), 'context-changes.json');
  const path = resolve(directory, 'changes.json'); await download.saveAs(path);
  return {path, value:JSON.parse(await readFile(path, 'utf8'))};
}

test('hostile strings remain inert and keyboard navigation reaches unassigned evidence', async t => {
  const {page} = await fixture(t);
  assert.match(await page.locator('#selection h2').textContent(), /<\/script><img/);
  assert.equal(await page.locator('img').count(), 0);
  assert.equal(await page.locator('script').count(), 2);
  await page.locator('#flow-navigation').selectOption('');
  assert.match(await page.locator('#selection h2').textContent(), /Unlisted/);
  const summary = page.getByText('Name a business flow or add application defaults', {exact:true});
  await summary.focus(); await page.keyboard.press('Enter');
  assert.equal(await summary.evaluate(node => node.parentElement.open), true);
  await page.keyboard.press('Tab');
  assert.equal(await page.evaluate(() => document.activeElement.id), 'new-application');
  await page.reload(); await page.locator('#flow-navigation').waitFor();
  assert.match(await page.locator('#status').textContent(), /No pending/);
});

test('unmodified flow can be enriched, exported, applied and reassessed without JSON authoring', async t => {
  const {page, directory} = await fixture(t);
  await page.locator('#flow-navigation').selectOption('');
  await page.getByText('Name a business flow or add application defaults', {exact:true}).click();
  await page.locator('#new-application').fill('demo'); await page.locator('#new-flow').fill('archive');
  await page.locator('#new-label').fill('Records archive'); await page.locator('#new-binding').selectOption('0');
  await page.getByRole('button', {name:'Start context draft', exact:true}).click();
  await page.locator('#draft-field-CLASSIFICATION').fill('sensitive'); await page.locator('#draft-field-CONFIDENTIALITY_YEARS').fill('20');
  await page.locator('#draft-field-OWNER').fill('Records team');
  await page.getByRole('button', {name:'Add answers to draft', exact:true}).click();
  assert.match(await page.locator('#status').textContent(), /stale/);
  const {path, value} = await exportChanges(page, directory);
  assert.equal(value.edits.length, 4); assert.ok(value.edits.every(edit => edit.id === '' && edit.base === null));
  assert.ok(value.edits.filter(edit => edit.section === 'DECLARATIONS').every(edit => edit.proposed.source.revision === ''));
  generate(resolve(directory, 'after'), path);
  const after = JSON.parse(await readFile(resolve(directory, 'after/assessment.json'), 'utf8'));
  const flow = after.flows.find(item => item.subject.flowId === 'archive');
  assert.equal(flow.priority, 'URGENT_PLANNING'); assert.equal(flow.directCount, 3);
  assert.equal(flow.savedContextFields.find(item => item.field === 'OWNER').value, 'Records team');
  const before = JSON.parse(await readFile(resolve(directory, 'assessment.json'), 'utf8'));
  assert.notEqual(after.contextHash, before.contextHash);
  assert.equal(after.runs[0].cbomHash, before.runs[0].cbomHash);
  assert.equal(after.runs[0].evidenceHash, before.runs[0].evidenceHash);
  await page.locator('#import-context').setInputFiles(path);
  assert.match(await page.locator('#status').textContent(), /Imported 4/);
});

test('conflict resolution copies exact Java source references and survives apply', async t => {
  const {page, directory} = await fixture(t);
  await page.locator('#reviewer').fill('Security reviewer');
  await page.locator('#field-resolve-CONFIDENTIALITY_YEARS').fill('20');
  await page.getByRole('button', {name:'Propose reviewed resolution', exact:true}).click();
  const {path, value} = await exportChanges(page, directory);
  assert.equal(value.edits[0].section, 'RESOLUTIONS');
  assert.equal(value.edits[0].proposed.sources.length, 2);
  assert.ok(value.edits[0].proposed.sources.every(source => /^[a-f0-9]{64}$/.test(source.declarationHash)));
  generate(resolve(directory, 'resolved'), path);
  const after = JSON.parse(await readFile(resolve(directory, 'resolved/assessment.json'), 'utf8'));
  const field = after.flows[0].instances[0].fields.find(value => value.field === 'CONFIDENTIALITY_YEARS');
  assert.equal(field.status, 'REVIEWED'); assert.equal(field.value, '20');
});

test('bounded invalid imports preserve draft and unsaved forms prevent incomplete export', async t => {
  const {page, directory} = await fixture(t);
  await page.locator('#field-OWNER').fill('Pending owner');
  assert.match(await page.locator('#status').textContent(), /form changes/);
  await page.getByRole('button', {name:'Add answers to draft', exact:true}).click();
  await page.locator('#import-context').setInputFiles({name:'bad.json', mimeType:'application/json', buffer:Buffer.from('{broken')});
  await page.waitForFunction(() => document.getElementById('status').textContent.includes('Import failed'));
  assert.match(await page.locator('#status').textContent(), /Import failed/);
  await page.locator('#import-context').setInputFiles({name:'large.json', mimeType:'application/json', buffer:Buffer.alloc(8 * 1024 * 1024 + 1, 32)});
  await page.waitForFunction(() => document.getElementById('status').textContent.includes('8 MiB'));
  assert.match(await page.locator('#status').textContent(), /8 MiB/);
  const {value} = await exportChanges(page, directory);
  assert.equal(value.edits.length, 1);
  await page.locator('#field-IMPACT').selectOption('high');
  await page.getByRole('button', {name:'Export proposed changes', exact:true}).click();
  assert.match(await page.locator('#status').textContent(), /Add form answers/);
});

test('unchanged context import is a no-op and changed records export alone', async t => {
  const {page, directory} = await fixture(t);
  const context = JSON.parse(await readFile(resolve(directory, 'context.json'), 'utf8'));
  await page.locator('#import-context').setInputFiles(resolve(directory, 'context.json'));
  await page.waitForFunction(() => document.getElementById('status').textContent.includes('Imported 0'));
  assert.equal(await page.locator('#export-changes').isDisabled(), true);
  context.declarations.push({...context.declarations[0], field:'OWNER', value:'Records team'});
  await page.locator('#import-context').setInputFiles({name:'context.json', mimeType:'application/json', buffer:Buffer.from(JSON.stringify(context))});
  await page.waitForFunction(() => document.getElementById('status').textContent.includes('Imported 1'));
  const {path, value} = await exportChanges(page, directory);
  assert.equal(value.edits.length, 1); assert.equal(value.edits[0].proposed.field, 'OWNER');
  generate(resolve(directory, 'imported'), path);
});

test('saved-only context remains selectable and other dirty editors cannot be cleared by answers', async t => {
  const {page} = await fixture(t);
  await page.locator('#instance').selectOption('');
  assert.equal(await page.locator('#instance').inputValue(), '');
  assert.equal(await page.locator('#field-CONFIDENTIALITY_YEARS').inputValue(), '5');
  await page.getByText('Flow name, lifecycle and readiness', {exact:true}).click();
  await page.locator('#flow-state').selectOption('RETIRED');
  await page.locator('#field-OWNER').fill('Records team');
  await page.getByRole('button', {name:'Add answers to draft', exact:true}).click();
  assert.match(await page.locator('#status').textContent(), /form changes/);
  await page.locator('#export-changes').click();
  assert.match(await page.locator('#status').textContent(), /Add form answers/);
  await page.getByRole('button', {name:'Propose flow metadata', exact:true}).click();
  assert.doesNotMatch(await page.locator('#status').textContent(), /form changes/);
});

test('existing flow creation is rejected and filters consume recorded verdicts', async t => {
  const {page} = await fixture(t);
  await page.getByText('Name a business flow or add application defaults', {exact:true}).click();
  await page.locator('#new-application').fill('demo'); await page.locator('#new-flow').fill('annotated');
  await page.getByRole('button', {name:'Start context draft', exact:true}).click();
  assert.match(await page.locator('#status').textContent(), /already exists/);
  assert.equal(await page.locator('#export-changes').isDisabled(), true);
  page.once('dialog', dialog => dialog.accept());
  await page.getByRole('button', {name:'Discard draft', exact:true}).click();
  await page.getByText('Filter by owner, priority, technical verdict or evidence gap', {exact:true}).click();
  await page.locator('#filter-gap').selectOption('unassigned');
  await page.getByRole('button', {name:'Apply filters', exact:true}).click();
  assert.equal(await page.locator('#flow-navigation option').count(), 1);
  assert.match(await page.locator('#selection h2').textContent(), /Unlisted/);
  await page.locator('#filter-gap').selectOption('');
  await page.locator('#filter-owner').fill('not-present');
  await page.getByRole('button', {name:'Apply filters', exact:true}).click();
  assert.equal(await page.locator('#flow-navigation option').count(), 1);
});

test('pending answers hydrate after navigation and reverting removes the old proposal', async t => {
  const {page, directory} = await fixture(t);
  const flowId = await page.locator('#flow-navigation').inputValue();
  await page.locator('#field-CLASSIFICATION').fill('restricted');
  await page.locator('#field-OWNER').fill('Records team');
  await page.getByRole('button', {name:'Add answers to draft', exact:true}).click();
  await page.locator('#flow-navigation').selectOption('');
  await page.locator('#flow-navigation').selectOption(flowId);
  assert.equal(await page.locator('#field-CLASSIFICATION').inputValue(), 'restricted');
  assert.equal(await page.locator('#field-OWNER').inputValue(), 'Records team');
  await page.locator('#field-CLASSIFICATION').fill('sensitive');
  await page.getByRole('button', {name:'Add answers to draft', exact:true}).click();
  const {path, value} = await exportChanges(page, directory);
  assert.equal(value.edits.length, 1); assert.equal(value.edits[0].proposed.field, 'OWNER');
  generate(resolve(directory, 'reverted'), path);
});

test('renaming a flow preserves imported pending exact bindings', async t => {
  const {page, directory} = await fixture(t);
  const context = JSON.parse(await readFile(resolve(directory, 'context.json'), 'utf8'));
  const binding = {className:'example.Archive', method:'save', descriptor:'()V', sourceArtifact:'example:archive', origin:'MAIN'};
  context.flows.push({subject:{applicationId:'demo', flowId:'annotated'}, label:'Imported label', state:'RETIRED', bindings:[binding]});
  await page.locator('#import-context').setInputFiles({name:'context.json', mimeType:'application/json', buffer:Buffer.from(JSON.stringify(context))});
  await page.waitForFunction(() => document.getElementById('status').textContent.includes('Imported 1'));
  await page.getByText('Flow name, lifecycle and readiness', {exact:true}).click();
  await page.locator('#flow-label').fill('Renamed label');
  await page.getByRole('button', {name:'Propose flow metadata', exact:true}).click();
  const {path, value} = await exportChanges(page, directory);
  assert.equal(value.edits.length, 1); assert.equal(value.edits[0].proposed.label, 'Renamed label');
  assert.deepEqual(value.edits[0].proposed.bindings, [binding]);
  assert.equal(value.edits[0].proposed.state, 'RETIRED');
  generate(resolve(directory, 'renamed'), path);
});

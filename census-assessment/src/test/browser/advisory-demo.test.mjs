import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { copyFile, mkdir, mkdtemp, readFile, readdir, rm } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { chromium } from 'playwright';

const root = fileURLToPath(new URL('../../../../', import.meta.url));
const demo = resolve(root, 'census-demo/target');
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
async function hashes(directory) {
  const result = {};
  for (const name of (await readdir(directory)).sort()) {
    if (name.endsWith('.json') || name.endsWith('.xml')) result[name] = digest(await readFile(resolve(directory, name)));
  }
  return result;
}
function maven(goal, options) {
  return execFileSync(resolve(root, 'mvnw'), ['-B', '-ntp', '-pl', 'census-demo', `census:${goal}`,
    '-Dgitbuildhook.gitconfig.skip=true', ...options], {cwd:root, encoding:'utf8', timeout:120000, maxBuffer:8 * 1024 * 1024});
}

test('real demo browser export applies through Maven and reassesses unchanged saved evidence', async t => {
  const reportDirectory = resolve(demo, 'cryptocensus/assessment');
  const original = JSON.parse(await readFile(resolve(reportDirectory, 'assessment.json'), 'utf8'));
  assert.ok(original.runs.length > 0 && original.runs.every(run => run.evidenceAccepted));
  const expectedHashes = original.runs.map(run => run.cbomHash).sort();
  const matching = [];
  const censusDirectory = resolve(demo, 'cryptocensus');
  for (const entry of await readdir(censusDirectory, {withFileTypes:true})) {
    if (!entry.isDirectory() || entry.name === 'assessment') continue;
    const directory = resolve(censusDirectory, entry.name);
    const files = await hashes(directory);
    const cboms = Object.entries(files).filter(([name]) => /^cbom-.*\.json$/.test(name) && !name.endsWith('.evidence.json'));
    if (JSON.stringify(cboms.map(([, hash]) => hash).sort()) === JSON.stringify(expectedHashes)) matching.push(directory);
  }
  assert.equal(matching.length, 1, 'select the exact report input run, never the newest directory by guess');
  const source = matching[0], sourceBefore = await hashes(source);
  const testsBefore = await hashes(resolve(demo, 'surefire-reports'));
  const directory = await mkdtemp(resolve(demo, 'advisory-browser-'));
  const saved = resolve(directory, 'saved-evidence'); await mkdir(saved);
  for (const name of Object.keys(sourceBefore).filter(name => name.startsWith('cbom-'))) await copyFile(resolve(source, name), resolve(saved, name));
  const savedBefore = await hashes(saved);
  const browser = await chromium.launch({headless:true, ...(process.env.CENSUS_BROWSER_EXECUTABLE ? {executablePath:process.env.CENSUS_BROWSER_EXECUTABLE} : {})});
  const page = await browser.newPage({acceptDownloads:true});
  const network = [], errors = [];
  page.on('request', request => { if (/^https?:/.test(request.url())) network.push(request.url()); });
  page.on('pageerror', error => errors.push(error.message));
  page.on('dialog', dialog => dialog.accept());
  await page.addInitScript(() => {
    window.censusViolations = [];
    document.addEventListener('securitypolicyviolation', event => window.censusViolations.push(event.violatedDirective));
  });
  t.after(async () => {
    await browser.close(); await rm(directory, {recursive:true, force:true});
    assert.deepEqual(network, []); assert.deepEqual(errors, []);
  });
  await page.goto(pathToFileURL(resolve(reportDirectory, 'assessment.html')).href);
  assert.match(await page.locator('body').textContent(), /context-apply/);
  assert.match(await page.locator('body').textContent(), /census:assess/);
  await page.locator('#flow-navigation').selectOption('');
  await page.getByText('Name a business flow or add application defaults', {exact:true}).click();
  await page.locator('#new-flow').fill('reviewed-helper');
  const candidates = await page.locator('#new-binding option').evaluateAll(options => options.map(option => ({value:option.value, text:option.textContent})));
  const helper = candidates.find(option => option.text.includes('ArchiveFlows.protect('));
  assert.ok(helper, 'actual unannotated project helper must be available for guided binding');
  await page.locator('#new-binding').selectOption(helper.value);
  assert.equal(await page.locator('#new-application').inputValue(), 'demo-payments');
  await page.getByRole('button', {name:'Start context draft', exact:true}).click();
  await page.locator('#draft-field-CLASSIFICATION').selectOption('sensitive');
  await page.locator('#draft-field-CONFIDENTIALITY_YEARS').fill('20');
  await page.locator('#draft-field-OWNER').fill('Synthetic records team');
  await page.getByRole('button', {name:'Add answers to draft', exact:true}).click();
  const downloadEvent = page.waitForEvent('download');
  await page.getByRole('button', {name:'Export proposed changes', exact:true}).click();
  const changes = resolve(directory, 'changes.json'); await (await downloadEvent).saveAs(changes);
  const context = resolve(directory, 'context.json');
  const applied = maven('context-apply', [`-Dcensus.contextFile=${context}`, `-Dcensus.changesFile=${changes}`]);
  assert.match(applied, /BUILD SUCCESS/);
  const destination = resolve(directory, 'reassessed');
  const reassessed = maven('assess', [`-Dcensus.inputDir=${saved}`, `-Dcensus.contextFile=${context}`, `-Dcensus.assessmentOutputDirectory=${destination}`]);
  assert.match(reassessed, /no application tests were run/);
  const after = JSON.parse(await readFile(resolve(destination, 'assessment.json'), 'utf8'));
  const flow = after.flows.find(flow => flow.subject.applicationId === 'demo-payments' && flow.subject.flowId === 'reviewed-helper');
  assert.ok(flow); assert.equal(flow.directCount, 2); assert.equal(flow.priority, 'URGENT_PLANNING');
  assert.equal(flow.savedContextFields.find(field => field.field === 'OWNER').value, 'Synthetic records team');
  assert.notEqual(after.contextHash, original.contextHash);
  assert.deepEqual(after.runs.map(run => [run.id, run.cbomHash, run.evidenceHash]).sort(), original.runs.map(run => [run.id, run.cbomHash, run.evidenceHash]).sort());
  assert.deepEqual(await hashes(source), sourceBefore);
  assert.deepEqual(await hashes(saved), savedBefore);
  assert.deepEqual(await hashes(resolve(demo, 'surefire-reports')), testsBefore, 'assess/apply must not rerun tests');
  assert.deepEqual(await page.evaluate(() => window.censusViolations), []);
  await page.goto(pathToFileURL(resolve(destination, 'assessment.html')).href);
  await page.locator('#flow-navigation').selectOption(JSON.stringify(['demo-payments', 'reviewed-helper']));
  assert.match(await page.locator('#selection').textContent(), /Urgent migration planning/);
  assert.deepEqual(await page.evaluate(() => window.censusViolations), []);
});

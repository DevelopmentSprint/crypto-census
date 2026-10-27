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
function generate(directory, changes, scenario) {
  execFileSync(java, ['-cp', classpath, 'com.developmentsprint.cryptocensus.assessment.GuidedReportFixture', directory, ...(changes || scenario ? [changes || '-', ...(scenario ? [scenario] : [])] : [])], {stdio: 'pipe'});
}
async function fixture(t, scenario) {
  await mkdir(target, {recursive:true});
  const directory = await mkdtemp(resolve(target, 'browser-'));
  generate(directory, null, scenario);
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
  await page.locator('#draft-field-CLASSIFICATION').selectOption('sensitive'); await page.locator('#draft-field-CONFIDENTIALITY_YEARS').fill('20');
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
  await page.waitForFunction(() => document.getElementById('status').textContent.includes('Imported 4'));
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
  await page.locator('#field-CLASSIFICATION').selectOption('restricted');
  await page.locator('#field-OWNER').fill('Records team');
  await page.getByRole('button', {name:'Add answers to draft', exact:true}).click();
  await page.locator('#flow-navigation').selectOption('');
  await page.locator('#flow-navigation').selectOption(flowId);
  assert.equal(await page.locator('#field-CLASSIFICATION').inputValue(), 'restricted');
  assert.equal(await page.locator('#field-OWNER').inputValue(), 'Records team');
  await page.locator('#field-CLASSIFICATION').selectOption('sensitive');
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


test('exact binding selects its owning application and rejects a mismatched draft', async t => {
  const {page, directory} = await fixture(t);
  await page.locator('#flow-navigation').selectOption('');
  await page.getByText('Name a business flow or add application defaults', {exact:true}).click();
  await page.locator('#new-application').fill('wrong-app');
  await page.locator('#new-flow').fill('owned-flow');
  await page.locator('#new-binding').selectOption('0');
  assert.equal(await page.locator('#new-application').inputValue(), 'demo');
  assert.equal(await page.locator('#new-application').getAttribute('readonly'), '');
  await page.locator('#new-application').evaluate(node => { node.value = 'wrong-app'; });
  await page.getByRole('button', {name:'Start context draft', exact:true}).click();
  assert.match(await page.locator('#status').textContent(), /different application/);
  assert.equal(await page.locator('#export-changes').isDisabled(), true);
  await page.locator('#new-binding').selectOption('');
  await page.locator('#new-binding').selectOption('0');
  await page.getByRole('button', {name:'Start context draft', exact:true}).click();
  const {path, value} = await exportChanges(page, directory);
  assert.equal(value.edits[0].proposed.subject.applicationId, 'demo');
  generate(resolve(directory, 'owned'), path);
  const after = JSON.parse(await readFile(resolve(directory, 'owned/assessment.json'), 'utf8'));
  assert.equal(after.flows.find(flow => flow.subject.flowId === 'owned-flow').directCount, 3);
});

test('unresolved declaring ownership remains visible and cannot be bound', async t => {
  const {page} = await fixture(t, 'unresolved');
  await page.locator('#flow-navigation').selectOption('');
  await page.getByText('Name a business flow or add application defaults', {exact:true}).click();
  assert.equal(await page.locator('#new-binding option').count(), 1);
  assert.match(await page.locator('#selection').textContent(), /declaring application or exact method is unresolved/);
  assert.match(await page.locator('#selection').textContent(), /observations remain unassigned/);
});

test('unknown classification stays unanswered while deliberate custom IDs round-trip', async t => {
  const {page, directory} = await fixture(t);
  await page.locator('#flow-navigation').selectOption('');
  await page.getByText('Name a business flow or add application defaults', {exact:true}).click();
  await page.locator('#new-flow').fill('unknown-class');
  await page.locator('#new-binding').selectOption('0');
  await page.getByRole('button', {name:'Start context draft', exact:true}).click();
  assert.equal(await page.locator('#draft-field-CLASSIFICATION').inputValue(), '');
  await page.locator('#draft-field-CLASSIFICATION').selectOption('sensitive');
  await page.locator('#draft-field-CLASSIFICATION').selectOption('');
  await page.locator('#draft-field-CONFIDENTIALITY_YEARS').fill('20');
  await page.getByRole('button', {name:'Add answers to draft', exact:true}).click();
  let exported = await exportChanges(page, directory);
  assert.ok(exported.value.edits.every(edit => edit.proposed.field !== 'CLASSIFICATION'));
  generate(resolve(directory, 'unknown'), exported.path);
  let after = JSON.parse(await readFile(resolve(directory, 'unknown/assessment.json'), 'utf8'));
  let flow = after.flows.find(flow => flow.subject.flowId === 'unknown-class');
  assert.equal(flow.savedContextFields.find(field => field.field === 'CLASSIFICATION').status, 'UNKNOWN');
  for (const identifier of ['pii', 'unknown']) {
    await page.locator('#draft-field-CLASSIFICATION').selectOption('__custom');
    await page.locator('#draft-field-CLASSIFICATION-custom').fill(identifier);
    await page.getByRole('button', {name:'Add answers to draft', exact:true}).click();
    exported = await exportChanges(page, directory);
    assert.equal(exported.value.edits.find(edit => edit.proposed.field === 'CLASSIFICATION').proposed.value, identifier);
    generate(resolve(directory, identifier + '-custom'), exported.path);
    after = JSON.parse(await readFile(resolve(directory, identifier + '-custom/assessment.json'), 'utf8'));
    flow = after.flows.find(flow => flow.subject.flowId === 'unknown-class');
    assert.equal(flow.savedContextFields.find(field => field.field === 'CLASSIFICATION').value, identifier);
  }
});


test('lost scope with a resolved MAIN frame cannot be offered as a recoverable binding', async t => {
  const {page, directory} = await fixture(t, 'lost');
  const report = JSON.parse(await readFile(resolve(directory, 'assessment.json'), 'utf8'));
  const finding = report.findings.find(item => !item.subject && item.projectFrame?.origin === 'MAIN');
  assert.equal(finding.association, 'SCOPE_LOST');
  assert.equal(report.runs[0].identities[0].status, 'RESOLVED');
  await page.locator('#flow-navigation').selectOption('');
  await page.getByText('Name a business flow or add application defaults', {exact:true}).click();
  assert.equal(await page.locator('#new-binding option').count(), 1);
  assert.match(await page.locator('#selection').textContent(), /scope evidence is lost or unverified/);
});

test('one draft cannot create competing flow bindings for the same observed application site', async t => {
  const {page} = await fixture(t);
  await page.locator('#flow-navigation').selectOption('');
  await page.getByText('Name a business flow or add application defaults', {exact:true}).click();
  await page.locator('#new-flow').fill('first');
  await page.locator('#new-binding').selectOption('0');
  await page.getByRole('button', {name:'Start context draft', exact:true}).click();
  await page.locator('#new-flow').fill('second');
  await page.getByRole('button', {name:'Start context draft', exact:true}).click();
  assert.match(await page.locator('#status').textContent(), /already bound to another flow/);
});

test('ambiguous saved bindings are not offered as another recoverable call site', async t => {
  const {page, directory} = await fixture(t);
  const context = JSON.parse(await readFile(resolve(directory, 'context.json'), 'utf8'));
  const binding = {className:'example.Archive', method:'save', descriptor:'()V', sourceArtifact:'example:archive', origin:'MAIN'};
  for (const flowId of ['first-owner', 'second-owner']) {
    context.flows.push({subject:{applicationId:'demo', flowId}, label:flowId, state:'ACTIVE', bindings:[binding]});
  }
  await page.locator('#import-context').setInputFiles({name:'context.json', mimeType:'application/json', buffer:Buffer.from(JSON.stringify(context))});
  await page.waitForFunction(() => document.getElementById('status').textContent.includes('Imported 2'));
  const {path} = await exportChanges(page, directory);
  const destination = resolve(directory, 'ambiguous');
  generate(destination, path);
  const report = JSON.parse(await readFile(resolve(destination, 'assessment.json'), 'utf8'));
  assert.ok(report.gaps.includes('AMBIGUOUS_SAVED_BINDING'));
  page.on('dialog', dialog => dialog.accept());
  await page.goto(pathToFileURL(resolve(destination, 'assessment.html')).href);
  await page.locator('#flow-navigation').selectOption('');
  await page.getByText('Name a business flow or add application defaults', {exact:true}).click();
  assert.equal(await page.locator('#new-binding option').count(), 1);
  assert.match(await page.locator('#selection').textContent(), /saved bindings conflict/);
});

test('application defaults create, export, import and reassess as broad assumptions', async t => {
  const {page, directory} = await fixture(t);
  const before = JSON.parse(await readFile(resolve(directory, 'assessment.json'), 'utf8'));
  await page.locator('#flow-navigation').selectOption('');
  await page.getByText('Name a business flow or add application defaults', {exact:true}).click();
  await page.locator('#new-application').fill('demo');
  await page.getByRole('button', {name:'Start context draft', exact:true}).click();
  assert.match(await page.locator('#draft-context').textContent(), /broad assumptions/);
  await page.locator('#draft-field-CLASSIFICATION').selectOption('sensitive');
  await page.locator('#draft-field-CONFIDENTIALITY_YEARS').fill('20');
  await page.getByRole('button', {name:'Add answers to draft', exact:true}).click();
  await page.locator('#new-flow').fill('defaulted-flow');
  await page.locator('#new-binding').selectOption('0');
  await page.getByRole('button', {name:'Start context draft', exact:true}).click();
  const {path, value} = await exportChanges(page, directory);
  assert.equal(value.edits.filter(edit => edit.section === 'DECLARATIONS').length, 2);
  assert.ok(value.edits.filter(edit => edit.section === 'DECLARATIONS').every(edit => edit.proposed.subject.flowId === ''));
  page.on('dialog', dialog => dialog.accept());
  await page.reload();
  await page.locator('#import-context').setInputFiles(path);
  await page.waitForFunction(() => document.getElementById('status').textContent.includes('Imported 3'));
  const imported = await exportChanges(page, directory);
  assert.deepEqual(imported.value, value);
  generate(resolve(directory, 'defaulted'), imported.path);
  const after = JSON.parse(await readFile(resolve(directory, 'defaulted/assessment.json'), 'utf8'));
  const flow = after.flows.find(flow => flow.subject.flowId === 'defaulted-flow');
  assert.equal(flow.directCount, 3);
  assert.equal(flow.priority, 'NEEDS_CONTEXT');
  for (const field of ['CLASSIFICATION', 'CONFIDENTIALITY_YEARS']) {
    assert.equal(flow.savedContextFields.find(item => item.field === field).status, 'APPLICATION_DEFAULT');
  }
  assert.ok(after.findings.filter(item => item.subject?.flowId === 'defaulted-flow')
    .some(item => item.decision.reasons.includes('APPLICABLE_CONTEXT_MISSING_OR_BROAD')));
  assert.deepEqual(after.runs.map(run => [run.cbomHash, run.evidenceHash]), before.runs.map(run => [run.cbomHash, run.evidenceHash]));
  await page.goto(pathToFileURL(resolve(directory, 'defaulted/assessment.html')).href);
  await page.locator('#flow-navigation').selectOption(JSON.stringify(['demo', 'defaulted-flow']));
  assert.ok(await page.getByRole('button', {name:'Confirm for this flow', exact:true}).count() >= 2);
});


test('ambiguous generated deployment ownership is not offered despite resolved raw identities', async t => {
  const {page, directory} = await fixture(t, 'ambiguous-deployment');
  const report = JSON.parse(await readFile(resolve(directory, 'assessment.json'), 'utf8'));
  assert.equal(report.runs[0].identities.length, 2);
  assert.ok(report.runs[0].identities.every(identity => identity.status === 'RESOLVED'));
  const unscoped = report.findings.filter(finding => finding.association === 'NO_SCOPE');
  assert.equal(unscoped.length, 2);
  assert.equal(unscoped.reduce((sum, finding) => sum + finding.count, 0), 3);
  assert.ok(unscoped.every(finding => finding.gaps.includes('AMBIGUOUS_DEPLOYMENT_OWNERSHIP')));
  await page.locator('#flow-navigation').selectOption('');
  await page.getByText('Name a business flow or add application defaults', {exact:true}).click();
  assert.equal(await page.locator('#new-binding option').count(), 1);
  assert.match(await page.locator('#selection').textContent(), /deployment ownership is ambiguous/);
});


test('an explicitly mapped peer remains bindable beside ambiguous deployment ownership', async t => {
  const {page, directory} = await fixture(t, 'mixed-deployment');
  const report = JSON.parse(await readFile(resolve(directory, 'assessment.json'), 'utf8'));
  assert.ok(report.gaps.includes('AMBIGUOUS_DEPLOYMENT_OWNERSHIP'));
  const peer = report.findings.find(finding => finding.identityId === 'second' && finding.association === 'NO_SCOPE');
  assert.ok(peer); assert.equal(peer.count, 2);
  assert.ok(!peer.gaps.includes('AMBIGUOUS_DEPLOYMENT_OWNERSHIP'));
  await page.locator('#flow-navigation').selectOption('');
  await page.getByText('Name a business flow or add application defaults', {exact:true}).click();
  assert.equal(await page.locator('#new-binding option').count(), 2);
  await page.locator('#new-flow').fill('explicit-peer');
  await page.locator('#new-binding').selectOption('0');
  await page.getByRole('button', {name:'Start context draft', exact:true}).click();
  const {path} = await exportChanges(page, directory);
  generate(resolve(directory, 'explicit-peer'), path, 'mixed-deployment');
  const after = JSON.parse(await readFile(resolve(directory, 'explicit-peer/assessment.json'), 'utf8'));
  assert.equal(after.flows.find(flow => flow.subject.flowId === 'explicit-peer').directCount, 2);
  const unresolved = after.findings.filter(finding => finding.identityId === 'identity' && finding.subject === null);
  assert.equal(unresolved.reduce((sum, finding) => sum + finding.count, 0), 1);
  assert.ok(unresolved.every(finding => finding.gaps.includes('AMBIGUOUS_DEPLOYMENT_OWNERSHIP')));
  assert.deepEqual(after.runs.map(run => [run.cbomHash, run.evidenceHash]), report.runs.map(run => [run.cbomHash, run.evidenceHash]));
});


test('one visible review resolves all observed deployment revisions and saved context', async t => {
  const {page, directory} = await fixture(t, 'revision-conflict');
  const report = JSON.parse(await readFile(resolve(directory, 'assessment.json'), 'utf8'));
  const flow = report.flows.find(flow => flow.subject.flowId === 'annotated');
  assert.equal(flow.instances.length, 2);
  assert.ok(flow.instances.every(instance => instance.fields.find(field => field.field === 'CONFIDENTIALITY_YEARS').status === 'CONFLICT'));
  assert.match(await page.locator('#selection').textContent(), /all 2 observed contexts and saved context/);
  const sourceDetails = page.getByText('All participating contexts, sources and revisions', {exact:true});
  assert.equal(await sourceDetails.evaluate(node => node.parentElement.open), true);
  for (const instance of flow.instances) assert.match(await sourceDetails.evaluate(node => node.parentElement.textContent), new RegExp(instance.runId));
  await page.locator('#reviewer').fill('Synthetic reviewer');
  await page.locator('#field-resolve-CONFIDENTIALITY_YEARS').fill('20');
  await page.getByRole('button', {name:'Propose reviewed resolution', exact:true}).click();
  const {path, value} = await exportChanges(page, directory);
  assert.equal(value.edits[0].proposed.sources.length, 3, 'both annotation revisions plus shared user source, deduplicated');
  generate(resolve(directory, 'resolved-all'), path, 'revision-conflict');
  const after = JSON.parse(await readFile(resolve(directory, 'resolved-all/assessment.json'), 'utf8'));
  const reviewed = after.flows.find(flow => flow.subject.flowId === 'annotated');
  assert.ok(reviewed.instances.every(instance => {
    const field = instance.fields.find(field => field.field === 'CONFIDENTIALITY_YEARS');
    return field.status === 'REVIEWED' && field.value === '20';
  }));
  assert.equal(reviewed.savedContextFields.find(field => field.field === 'CONFIDENTIALITY_YEARS').status, 'REVIEWED');
  assert.deepEqual(after.runs.map(run => [run.cbomHash, run.evidenceHash]), report.runs.map(run => [run.cbomHash, run.evidenceHash]));
});

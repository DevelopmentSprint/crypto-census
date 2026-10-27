(() => {
  'use strict';
  let report = JSON.parse(document.getElementById('report-data').textContent);
  const root = document.getElementById('app');
  const drafts = new Map();
  let selected = '', selectedInstance = null;
  const filterState = {owner: '', priority: '', technical: '', gap: ''};
  const dirtyControls = new Set();
  const dirty = () => dirtyControls.size > 0;
  const clearControls = ids => ids.forEach(id => dirtyControls.delete(id));
  const fields = [
    ['CLASSIFICATION', 'Data classification', 'public, internal, sensitive, restricted or your own identifier'],
    ['CONFIDENTIALITY_YEARS', 'Required confidentiality (years)', 'How long must the information remain secret?'],
    ['AUTHENTICITY_YEARS', 'Required authenticity (years)', 'How long must signatures remain trustworthy?'],
    ['OWNER', 'Accountable owner', 'Team or role; avoid personal or confidential details'],
    ['EXPOSURE', 'Exposure', 'Where can the protected information be accessed?'],
    ['IMPACT', 'Business impact', 'unknown, low, medium or high'],
    ['PURPOSE', 'Protection purpose', 'Select the intended protection use.']
  ];
  const names = {
    TECHNICAL_REMEDIATION_FIRST: 'Fix current cryptographic weakness',
    URGENT_PLANNING: 'Urgent migration planning', PLANNED_MIGRATION: 'Plan migration',
    NEEDS_CONTEXT: 'Needs context or evidence',
    NO_PQC_ACTION_IDENTIFIED_IN_OBSERVED_SCOPE: 'No PQC action identified in observed scope'
  };
  const clone = value => JSON.parse(JSON.stringify(value));
  const subjectKey = subject => JSON.stringify([subject.applicationId, subject.flowId]);
  const sameSubject = (a, b) => a && b && subjectKey(a) === subjectKey(b);
  const el = (tag, text, className) => {
    const value = document.createElement(tag);
    if (text !== undefined) value.textContent = text;
    if (className) value.className = className;
    return value;
  };
  const append = (parent, ...children) => { children.forEach(child => parent.append(child)); return parent; };
  const button = (text, action, secondary = false) => {
    const value = el('button', text, secondary ? 'secondary' : '');
    value.type = 'button'; value.addEventListener('click', action); return value;
  };
  function detail(parent, title, data) {
    append(parent, append(el('details'), el('summary', title), el('pre', JSON.stringify(data, null, 2))));
  }
  function input(parent, id, title, value = '', hint = '', multiline = false) {
    const wrapper = el('div'), control = el(multiline ? 'textarea' : 'input');
    control.id = id; control.value = value; control.maxLength = multiline ? 4096 : 512;
    const label = el('label', title); label.htmlFor = id;
    append(wrapper, label, control);
    if (hint) { const help = el('small', hint, 'muted'); help.id = id + '-help'; control.setAttribute('aria-describedby', help.id); wrapper.append(help); }
    control.addEventListener('input', () => { if (id !== 'reviewer') dirtyControls.add(id); status(); });
    parent.append(wrapper); return control;
  }
  function option(select, value, label) { const item = el('option', label); item.value = value; select.append(item); }
  function status(message = '') {
    const value = document.getElementById('status');
    if (!value) return;
    value.textContent = message || (drafts.size || dirty()
      ? `Unsaved context: ${drafts.size} proposed edits${dirty() ? ', form changes not yet added' : ''}. Displayed findings are stale until apply and reassess.`
      : 'Showing the saved assessment. No pending context edits.');
    if (message && (drafts.size || dirty()) && !message.includes('stale')) value.textContent += ' Displayed findings are stale until apply and reassess.';
    document.getElementById('export-changes').disabled = drafts.size === 0;
    const preview = document.getElementById('draft-preview');
    if (preview) preview.textContent = JSON.stringify(Array.from(drafts.values()), null, 2);
  }
  function key(section, value) {
    const parts = [section, value.subject.applicationId, value.subject.flowId];
    if (section === 'DECLARATIONS') parts.push(value.field, value.source.kind, value.source.id);
    if (section === 'RESOLUTIONS') parts.push(value.field);
    return JSON.stringify(parts);
  }
  function equal(a, b) {
    if (a === b) return true;
    if (!a || !b || typeof a !== 'object' || typeof b !== 'object' || Array.isArray(a) !== Array.isArray(b)) return false;
    const keys = Object.keys(a);
    return keys.length === Object.keys(b).length && keys.every(key => Object.hasOwn(b, key) && equal(a[key], b[key]));
  }
  function baseField(section, value) {
    return report.baseFields.find(item => item.section === section && key(section, item.value) === key(section, value));
  }
  function propose(section, value) {
    const base = baseField(section, value);
    if (base && equal(base.value, value)) { drafts.delete(key(section, value)); status(); return; }
    const edit = {section, id: base ? base.id : '', base: base ? clone(base.value) : null,
      baseHash: base ? base.hash : report.emptyFieldHash, proposed: clone(value)};
    drafts.set(key(section, value), edit);
    status();
  }
  function removeSaved(item) {
    drafts.set(key(item.section, item.value), {section: item.section, id: item.id,
      base: clone(item.value), baseHash: item.hash, proposed: null});
    status();
  }
  function download(name, value) {
    const bytes = new Blob([JSON.stringify(value, null, 2) + '\n'], {type: 'application/json'});
    const url = URL.createObjectURL(bytes), link = el('a');
    link.href = url; link.download = name; root.append(link); link.click(); link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  function changes() {
    return {schemaVersion: 1, base: clone(report.baseContext), baseContextHash: report.contextHash,
      edits: Array.from(drafts.values()).map(clone)};
  }
  function validatedTree(value, depth = 0, budget = {count: 0}) {
    if (++budget.count > 262144 || depth > 32) throw new Error('Import exceeds structural limits.');
    if (typeof value === 'string' && value.length > 65536) throw new Error('Import string is too long.');
    if (typeof value === 'number' && !Number.isSafeInteger(value)) throw new Error('Import numbers must be safe integers.');
    if (value && typeof value === 'object') {
      const values = Object.values(value);
      if (values.length > 32768) throw new Error('Import collection is too large.');
      values.forEach(item => validatedTree(item, depth + 1, budget));
    }
  }
  function validateValue(section, value) {
    if (!value || !value.subject || typeof value.subject.applicationId !== 'string'
        || typeof value.subject.flowId !== 'string') throw new Error('Context item requires an application and flow identity.');
    key(section, value); // Shape needed for matching only; Java validates authoritative semantics.
  }
  async function importFile(file) {
    if (!file) return;
    try {
      if (file.size > 8 * 1024 * 1024) throw new Error('Import exceeds the 8 MiB limit.');
      const data = JSON.parse(await file.text()); validatedTree(data);
      if (data.schemaVersion !== 1) throw new Error('Unsupported schema version.');
      const pending = [];
      if (Array.isArray(data.edits)) {
        if (data.baseContextHash !== report.contextHash || !equal(data.base, report.baseContext))
          throw new Error('This change set uses a different base. Apply it through Maven and regenerate before combining reviews.');
        for (const edit of data.edits) {
          const sample = edit.proposed || edit.base;
          if (!['FLOWS', 'DECLARATIONS', 'RESOLUTIONS', 'READINESS'].includes(edit.section)) throw new Error('Unknown edit section.');
          validateValue(edit.section, sample);
          const base = baseField(edit.section, sample);
          if (edit.id !== (base ? base.id : '') || edit.baseHash !== (base ? base.hash : report.emptyFieldHash)
              || !equal(edit.base, base ? base.value : null)) throw new Error('Edit base does not match this report.');
          pending.push([key(edit.section, sample), clone(edit)]);
        }
      } else {
        for (const [section, property] of [['FLOWS', 'flows'], ['DECLARATIONS', 'declarations'], ['RESOLUTIONS', 'resolutions'], ['READINESS', 'readiness']]) {
          if (!Array.isArray(data[property])) throw new Error('Choose a complete context or change-set JSON file.');
          for (const value of data[property]) {
            validateValue(section, value); const base = baseField(section, value);
            if (base && equal(base.value, value)) continue;
            pending.push([key(section, value), {section, id: base ? base.id : '', base: base ? clone(base.value) : null,
              baseHash: base ? base.hash : report.emptyFieldHash, proposed: clone(value)}]);
          }
        }
      }
      const incoming = new Map();
      for (const [id, edit] of pending) {
        const prior = incoming.get(id) || drafts.get(id);
        if (prior && JSON.stringify(prior) !== JSON.stringify(edit)) throw new Error('Import conflicts with another draft edit. Export or discard your draft first.');
        incoming.set(id, edit);
      }
      incoming.forEach((edit, id) => drafts.set(id, edit));
      status(`Imported ${incoming.size} proposed edits. Findings are stale. Java will validate and merge on apply; missing imported records were not deleted.`);
    } catch (error) { status('Import failed: ' + error.message); }
  }
  function source(id) {
    return {kind: 'USER', id, revision: '', reviewer: document.getElementById('reviewer').value.trim(), reviewedAt: new Date().toISOString()};
  }
  function currentFields(flow) {
    const instance = flow && flow.instances.find(value => JSON.stringify([value.runId, value.identityId]) === selectedInstance);
    return instance ? instance.fields : flow ? flow.savedContextFields : [];
  }
  function contextEditor(parent, subject, flow, prefix = 'field') {
    const panel = el('section', undefined, 'panel'); append(panel, el('h3', subject.flowId ? 'Business context' : 'Application defaults'));
    panel.append(el('p', subject.flowId ? 'Answers are declared requirements, not detected data content. Saved assessment values and your pending answers appear below; imported source changes remain in the proposed-edits review until Java reassessment. Existing sources stay preserved.' : 'These broad assumptions fill undeclared fields; they do not establish flow-specific business requirements.', 'muted'));
    const values = currentFields(flow), grid = el('div', undefined, 'grid');
    const controls = new Map(), customControls = new Map();
    const answer = control => customControls.has(control.id) && control.value === '__custom'
      ? customControls.get(control.id).value.trim() : control.value.trim();
    const restore = (control, value) => {
      const custom = customControls.get(control.id);
      if (!custom) { control.value = value; return; }
      control.value = !value || ['public', 'internal', 'sensitive', 'restricted'].includes(value) ? value : '__custom';
      custom.value = control.value === '__custom' ? value : '';
      custom.parentElement.hidden = control.value !== '__custom'; custom.required = !custom.parentElement.hidden; custom.disabled = custom.parentElement.hidden;
    };
    for (const [field, title, hint] of fields) {
      const existing = values.find(value => value.field === field);
      const pending = drafts.get(key('DECLARATIONS', {subject, field, source: {kind: 'USER', id: 'guided-review'}}));
      const displayed = pending && pending.proposed ? pending.proposed.value : existing ? existing.value : '';
      const options = {
        CLASSIFICATION: ['public', 'internal', 'sensitive', 'restricted', '__custom'],
        EXPOSURE: ['unknown', 'public-network', 'external-network', 'internal-network', 'local'],
        IMPACT: ['unknown', 'low', 'medium', 'high'],
        PURPOSE: ['UNSPECIFIED', 'TRANSPORT', 'STORAGE', 'KEY_PROTECTION', 'SIGNING', 'VERIFICATION']
      }[field];
      let control;
      if (options) {
        const wrapper = el('div'), label = el('label', title);
        control = el('select'); control.id = prefix + '-' + field; label.htmlFor = control.id;
        option(control, '', field === 'CLASSIFICATION' ? 'Not yet known (no declaration)' : 'Not declared'); options.forEach(value => option(control, value, value === '__custom' ? 'Custom classification identifier' : value));
        control.value = displayed;
        control.addEventListener('change', () => { dirtyControls.add(control.id); status(); });
        append(wrapper, label, control, el('small', hint, 'muted')); grid.append(wrapper);
      } else {
        control = input(grid, prefix + '-' + field, title, displayed, hint);
        control.maxLength = 256;
        if (field.endsWith('_YEARS')) { control.type = 'number'; control.min = '1'; control.max = '1000'; control.step = '1'; }
      }
      if (field === 'CLASSIFICATION') {
        const custom = input(grid, control.id + '-custom', 'Custom classification identifier', '',
          'Organization identifiers remain unmapped until your policy defines them. The literal identifier unknown is a custom value, not an unanswered question.');
        custom.pattern = '[a-z0-9][a-z0-9._-]{0,63}'; custom.maxLength = 64;
        customControls.set(control.id, custom); restore(control, displayed);
        control.addEventListener('change', () => {
          custom.parentElement.hidden = control.value !== '__custom'; custom.required = !custom.parentElement.hidden; custom.disabled = custom.parentElement.hidden;
        });
        custom.addEventListener('input', () => dirtyControls.add(control.id));
      }
      controls.set(field, control);
      if (existing && existing.status === 'APPLICATION_DEFAULT' && subject.flowId) {
        control.parentElement.append(button('Confirm for this flow', () => {
          if (!answer(control) || !control.reportValidity() || (customControls.has(control.id) && !customControls.get(control.id).reportValidity())) return;
          propose('DECLARATIONS', {subject: clone(subject), field, value: answer(control), source: source('guided-review')});
          clearControls([control.id, control.id + '-custom']); status();
        }, true));
      }
    }
    panel.append(grid);
    panel.append(button('Add answers to draft', () => {
      if ([...controls.values(), ...customControls.values()].some(control => !control.reportValidity())) return;
      let count = 0;
      for (const [field, control] of controls) {
        if (!dirtyControls.has(control.id)) continue;
        const value = answer(control);
        const previous = values.find(item => item.field === field);
        const draftKey = key('DECLARATIONS', {subject, field, source: {kind: 'USER', id: 'guided-review'}});
        if (!value || value === (previous ? previous.value : '')) {
          if (drafts.delete(draftKey)) count++;
          restore(control, previous ? previous.value : '');
        } else {
          propose('DECLARATIONS', {subject: clone(subject), field, value, source: source('guided-review')}); count++;
        }
      }
      clearControls([...controls.values(), ...customControls.values()].map(control => control.id)); status(count ? '' : 'No changed answers. Blank fields stay unknown; use explicit removal below to delete a saved declaration.');
    }));
    const conflicts = values.filter(value => ['CONFLICT', 'STALE_RESOLUTION'].includes(value.status));
    for (const conflict of conflicts) {
      const box = el('div', undefined, 'notice');
      append(box, el('h4', 'Review conflicting ' + conflict.field.toLowerCase().replaceAll('_', ' ')));
      detail(box, 'Preserved sources and revisions', conflict);
      const value = input(box, prefix + '-resolve-' + conflict.field, 'Reviewed value');
      box.append(button('Propose reviewed resolution', () => {
        const reviewer = document.getElementById('reviewer').value.trim();
        if (!reviewer || !value.value.trim()) { status('A reviewed value and reviewer are required.'); return; }
        propose('RESOLUTIONS', {subject: clone(subject), field: conflict.field, value: value.value.trim(),
          sources: clone(conflict.sourceRefs), reviewer, reviewedAt: new Date().toISOString()});
        clearControls([value.id]); status('Resolution proposed for these exact source revisions. Different deployment revisions may still require review.');
      })); panel.append(box);
    }
    detail(panel, 'Effective values and all contributing sources', values);
    parent.append(panel);
  }
  function metadataEditor(parent, flow) {
    const box = append(el('details', undefined, 'panel'), el('summary', 'Flow name, lifecycle and readiness'));
    const flowDraft = drafts.get(key('FLOWS', flow));
    const metadata = flowDraft && flowDraft.proposed ? flowDraft.proposed : flow;
    const label = input(box, 'flow-label', 'Display name', metadata.label);
    const stateLabel = el('label', 'Flow lifecycle'); stateLabel.htmlFor = 'flow-state';
    const state = el('select'); state.id = 'flow-state'; option(state, 'ACTIVE', 'Active'); option(state, 'RETIRED', 'Retired'); state.value = metadata.state; state.addEventListener('change', () => { dirtyControls.add(state.id); status(); });
    append(box, stateLabel, state);
    box.append(button('Propose flow metadata', () => {
      const saved = baseField('FLOWS', {subject: flow.subject});
      const latest = drafts.get(key('FLOWS', flow));
      const pending = latest && latest.proposed;
      const nextLabel = dirtyControls.has(label.id) ? label.value.trim() : pending ? pending.label : label.value.trim();
      const nextState = dirtyControls.has(state.id) ? state.value : pending ? pending.state : state.value;
      propose('FLOWS', {subject: clone(flow.subject), label: nextLabel, state: nextState,
        bindings: pending ? clone(pending.bindings) : saved ? clone(saved.value.bindings) : []});
      label.value = nextLabel; state.value = nextState;
      clearControls([label.id, state.id]); status();
    }));
    const readinessLabel = el('label', 'Asserted readiness'); readinessLabel.htmlFor = 'readiness-state';
    const readiness = el('select'); readiness.id = 'readiness-state';
    for (const item of ['UNASSESSED', 'BLOCKED', 'CANDIDATE', 'VALIDATED_IN_TESTED_SCOPE']) option(readiness, item, item.replaceAll('_', ' '));
    const readinessDraft = drafts.get(key('READINESS', flow));
    const readinessValue = readinessDraft && readinessDraft.proposed ? readinessDraft.proposed : flow.readiness;
    readiness.value = readinessValue.state || readinessValue.assertedState || 'UNASSESSED'; readiness.addEventListener('change', () => { dirtyControls.add(readiness.id); status(); }); append(box, readinessLabel, readiness);
    const note = input(box, 'readiness-note', 'Prerequisites, blockers or evidence note', readinessValue.note, 'A validation assertion remains unverified until its evidence is independently checked.', true);
    const refs = input(box, 'readiness-refs', 'Evidence references (one per line)', readinessValue.validationRefs.join('\n'), 'References are never fetched by this page.', true);
    box.append(button('Propose readiness assertion', () => {
      if (![note.id, refs.id, readiness.id].some(id => dirtyControls.has(id))) { status('No changed readiness fields.'); return; }
      const latest = drafts.get(key('READINESS', flow));
      const pending = latest && latest.proposed;
      const nextState = dirtyControls.has(readiness.id) || !pending ? readiness.value : pending.state;
      const nextNote = dirtyControls.has(note.id) || !pending ? note.value.trim() : pending.note;
      const nextRefs = dirtyControls.has(refs.id) || !pending ? refs.value.split('\n').map(v => v.trim()).filter(Boolean) : clone(pending.validationRefs);
      propose('READINESS', {subject: clone(flow.subject), state: nextState, note: nextNote, source: source('guided-readiness'), validationRefs: nextRefs});
      readiness.value = nextState; note.value = nextNote; refs.value = nextRefs.join('\n');
      clearControls([note.id, refs.id, readiness.id]); status();
    }));
    const saved = report.baseFields.filter(item => sameSubject(item.value.subject, flow.subject));
    if (saved.length) {
      const removals = append(el('details'), el('summary', 'Explicit removal of saved context'));
      removals.append(el('p', 'Removal exports a deletion proposal with the original base. It does not delete observed annotations or retire a flow.'));
      saved.forEach(item => removals.append(button('Remove saved ' + item.section.toLowerCase() + (item.value.field ? ': ' + item.value.field : ''), () => removeSaved(item), true)));
      box.append(removals);
    }
    parent.append(box);
  }
  function matchesFinding(value) {
    return (!filterState.technical || value.technical.risk === filterState.technical)
      && (!filterState.priority || value.decision.displayPriority === filterState.priority)
      && (!filterState.gap || (filterState.gap === 'incomplete' ? value.gaps.length > 0 || value.decision.incomplete : !value.subject));
  }
  function owners(flow) {
    return [...new Set(flow.instances.flatMap(value => value.fields).concat(flow.savedContextFields)
      .filter(value => value.field === 'OWNER' && value.value).map(value => value.value))];
  }
  function updateNavigation() {
    const nav = document.getElementById('flow-navigation'); nav.replaceChildren();
    const flows = report.flows.filter(flow => (!filterState.owner || owners(flow).join(' ').toLowerCase().includes(filterState.owner.toLowerCase()))
      && (!filterState.priority || flow.priority === filterState.priority)
      && (!filterState.gap || (filterState.gap === 'incomplete' && flow.incomplete))
      && (!filterState.technical || report.findings.some(value => sameSubject(value.subject, flow.subject) && value.technical.risk === filterState.technical)));
    flows.forEach(flow => option(nav, subjectKey(flow.subject), `${flow.label} — ${names[flow.priority] || flow.priority} — ${owners(flow).join(', ') || 'Owner unknown'}`));
    option(nav, '', 'Unlisted areas / lost attribution');
    if (selected && !flows.some(flow => subjectKey(flow.subject) === selected)) { selected = flows.length ? subjectKey(flows[0].subject) : ''; selectedInstance = null; }
    nav.value = selected;
  }
  function filters(parent) {
    const box = append(el('details', undefined, 'panel'), el('summary', 'Filter by owner, priority, technical verdict or evidence gap'));
    const grid = el('div', undefined, 'grid');
    for (const [key, title, values] of [
      ['owner', 'Owner contains', null], ['priority', 'Priority', Object.keys(names)],
      ['technical', 'Technical verdict', [...new Set(report.findings.map(value => value.technical.risk))].sort()],
      ['gap', 'Evidence gap', ['incomplete', 'unassigned']]]) {
      const wrapper = el('div'), label = el('label', title), control = el(values ? 'select' : 'input');
      control.id = 'filter-' + key; label.htmlFor = control.id;
      if (values) { option(control, '', 'All'); values.forEach(value => option(control, value, names[value] || value)); }
      append(wrapper, label, control); grid.append(wrapper);
    }
    append(box, grid, button('Apply filters', () => {
      if (dirty() && !confirm('Discard form changes that have not been added to the draft before filtering?')) return;
      dirtyControls.clear(); Object.keys(filterState).forEach(key => { filterState[key] = document.getElementById('filter-' + key).value.trim(); });
      updateNavigation(); renderSelection(); status();
    })); parent.append(box);
  }
  function findings(parent, values) {
    values = values.filter(matchesFinding);
    append(parent, el('h3', 'Findings'));
    if (!values.length) parent.append(el('p', 'No observations are shown here. Check active filters and the coverage details; absence does not establish safety.'));
    for (const finding of values) {
      const box = el('article', undefined, 'panel');
      append(box, el('h4', names[finding.decision.displayPriority] || finding.decision.displayPriority),
        el('p', finding.decision.nextAction || 'Review the recorded evidence.'));
      if (finding.kind !== 'PRIMITIVE') box.append(el('p', 'TLS ' + finding.kind.slice(4).toLowerCase().replaceAll('_', ' ') + ': handshake initiation only; later request use is not established.'));
      detail(box, 'Technical evidence, reasons and provenance', finding); parent.append(box);
    }
  }
  function createFlow(parent) {
    const box = append(el('details', undefined, 'panel'), el('summary', 'Name a business flow or add application defaults'));
    const app = input(box, 'new-application', 'Stable application ID');
    const id = input(box, 'new-flow', 'Stable flow ID', '', 'Lowercase letters, numbers, dots, underscores or hyphens. Leave empty for application defaults.');
    const label = input(box, 'new-label', 'Display name');
    const bindingLabel = el('label', 'Optional exact project call site'); bindingLabel.htmlFor = 'new-binding';
    const binding = el('select'); binding.id = 'new-binding'; option(binding, '', 'No binding — declared but unobserved flow');
    const projectFindings = report.findings.filter(value => !value.subject && value.projectFrame && ['MAIN', 'TEST'].includes(value.projectFrame.origin));
    const candidates = projectFindings.map(finding => ({finding, identity: report.runs.find(run => run.id === finding.runId)?.identities.find(identity => identity.id === finding.identityId)}))
      .filter(({finding, identity}) => finding.association === 'NO_SCOPE' && !finding.gaps.includes('AMBIGUOUS_DEPLOYMENT_OWNERSHIP') && identity && identity.status === 'RESOLVED' && identity.applicationId
        && identity.sourceArtifact === finding.projectFrame.sourceArtifact && finding.projectFrame.sourceArtifact && finding.projectFrame.descriptor
        && !report.baseContext.flows.some(flow => flow.subject.applicationId === identity.applicationId && flow.bindings.some(saved => equal(saved, finding.projectFrame))));
    candidates.forEach(({finding, identity}, index) => option(binding, String(index), `${identity.applicationId}: ${finding.projectFrame.className}.${finding.projectFrame.method}${finding.projectFrame.descriptor} (${finding.projectFrame.origin}, ${identity.sourceArtifact}, ${identity.deploymentId})`));
    binding.addEventListener('change', () => {
      dirtyControls.add(binding.id); app.readOnly = binding.value !== '';
      if (app.readOnly) { app.value = candidates[Number(binding.value)].identity.applicationId; dirtyControls.add(app.id); }
      status();
    });
    append(box, bindingLabel, binding);
    box.append(el('p', 'Bindings apply only to an exact artifact, method descriptor and project origin under resolved application identity. Unresolved or ambiguous evidence remains unassigned.', 'muted'));
    if (candidates.length < projectFindings.length) box.append(el('p', 'Some project call sites cannot be bound because their declaring application or exact method is unresolved, their deployment ownership is ambiguous, their scope evidence is lost or unverified, or saved bindings conflict. Review the recorded gaps and regenerate after correcting the cause; these observations remain unassigned.', 'notice'));
    box.append(button('Start context draft', () => {
      const candidate = binding.value === '' ? null : candidates[Number(binding.value)];
      if (binding.value !== '' && (!candidate || candidate.identity.applicationId !== app.value.trim())) { status('The selected call site belongs to a different application. Select its resolved application identity before creating a binding.'); return; }
      if (candidate && Array.from(drafts.values()).some(edit => edit.section === 'FLOWS' && edit.proposed
        && edit.proposed.subject.applicationId === candidate.identity.applicationId && edit.proposed.subject.flowId !== id.value
        && edit.proposed.bindings.some(saved => equal(saved, candidate.finding.projectFrame)))) { status('This call site is already bound to another flow in your draft. Review that binding before creating a competing flow.'); return; }
      if (candidate && !id.value) { status('An exact call-site binding requires a flow ID; application defaults cannot own a binding.'); return; }
      if (!app.value.trim() || (id.value && !/^[a-z0-9][a-z0-9._-]{0,63}$/.test(id.value))) { status('Provide an application ID and a valid stable flow ID, or leave flow ID empty for defaults.'); return; }
      const subject = {applicationId: app.value.trim(), flowId: id.value};
      if (id.value && report.flows.some(flow => sameSubject(flow.subject, subject))) { status('That flow already exists. Select its existing review area to change metadata; creation will not replace its lifecycle or bindings.'); return; }
      const target = document.getElementById('draft-context');
      if (target.childNodes.length && Array.from(dirtyControls).some(key => key.startsWith('draft-field-'))) { status('Add the current business answers to the draft before starting another context editor.'); return; }
      if (id.value && !drafts.has(key('FLOWS', {subject}))) propose('FLOWS', {subject, label: label.value.trim() || id.value, state: 'ACTIVE', bindings: binding.value === '' ? [] : [clone(candidate.finding.projectFrame)]});
      target.replaceChildren(); contextEditor(target, subject, null, 'draft-field');
      clearControls([app.id, id.id, label.id, binding.id]); status('Add business answers below. New bindings take effect only after apply and reassessment.');
      target.scrollIntoView();
    }));
    parent.append(box); parent.append(el('section')); parent.lastChild.id = 'draft-context';
  }
  function formatCount(value) { return Number.isSafeInteger(value) ? String(value) : 'large count (see canonical JSON)'; }
  function renderSelection() {
    const panel = document.getElementById('selection'); panel.replaceChildren();
    const flow = report.flows.find(value => subjectKey(value.subject) === selected);
    if (!flow) {
      append(panel, el('h2', 'Unlisted areas and lost attribution'), el('p', 'These observations have no verified business-flow association. They remain part of the technical inventory.'));
      findings(panel, report.findings.filter(value => !value.subject)); createFlow(panel); return;
    }
    append(panel, el('h2', flow.label), el('p', names[flow.priority] || flow.priority, 'badge'),
      el('p', `Observed entry points: ${formatCount(flow.inclusiveCount)}; direct: ${formatCount(flow.directCount)}. TLS attempts: ${formatCount(flow.handshakeAttempts)}. Counts are not data volume or business impact.`),
      el('p', `Readiness: ${flow.readiness.effectiveState.toLowerCase().replaceAll('_', ' ')}. ${flow.incomplete ? 'Assessment or coverage is incomplete.' : 'Assessment has no recorded completeness gaps for these inputs.'}`));
    const instanceLabel = el('label', 'Context source'); instanceLabel.htmlFor = 'instance';
    const instances = el('select'); instances.id = 'instance'; option(instances, '', 'Saved business context only');
    flow.instances.forEach(value => option(instances, JSON.stringify([value.runId, value.identityId]), `${value.identity ? value.identity.deploymentId : value.identityId} — ${value.observed ? 'observed' : 'not observed'} (${value.runId})`));
    if (selectedInstance === null) selectedInstance = flow.instances.length ? JSON.stringify([flow.instances[0].runId, flow.instances[0].identityId]) : '';
    instances.value = selectedInstance;
    instances.addEventListener('change', () => { if (dirty() && !confirm('Discard form changes that have not been added to the draft?')) { instances.value = selectedInstance; return; } selectedInstance = instances.value; dirtyControls.clear(); renderSelection(); status(); });
    append(panel, instanceLabel, instances); contextEditor(panel, flow.subject, flow); metadataEditor(panel, flow);
    findings(panel, report.findings.filter(value => sameSubject(value.subject, flow.subject)));
    detail(panel, 'Counts, coverage, bindings and deployment details', flow);
    createFlow(panel);
  }
  function render() {
    root.replaceChildren(); append(root, el('h1', 'Crypto Census migration review'), el('p', 'Prioritize observed cryptography using declared business requirements. This advisory report is not a compliance or migration-readiness certification.', 'muted'));
    const statusBox = el('p', '', 'notice'); statusBox.id = 'status'; statusBox.setAttribute('role', 'status'); statusBox.setAttribute('aria-live', 'polite'); root.append(statusBox);
    const toolbar = el('div', undefined, 'toolbar');
    const exportButton = button('Export proposed changes', () => { if (dirty()) { status('Add form answers to the draft before exporting.'); return; } download('context-changes.json', changes()); status('Changes exported. They remain unapplied; findings are stale until apply and reassessment.'); }); exportButton.id = 'export-changes';
    append(toolbar, exportButton, button('Export saved context', () => download('context.json', report.baseContext), true), button('Discard draft', () => { if (confirm('Discard all pending context edits?')) { drafts.clear(); dirtyControls.clear(); renderSelection(); status(); } }, true));
    root.append(toolbar); input(root, 'reviewer', 'Reviewer (asserted identity)', '', 'Optional for answers; required for explicit conflict resolutions.');
    const fileLabel = el('label', 'Import context or a change set'); fileLabel.htmlFor = 'import-context';
    const file = el('input'); file.type = 'file'; file.accept = '.json,application/json'; file.id = 'import-context'; file.addEventListener('change', () => { importFile(file.files[0]); file.value = ''; }); append(root, fileLabel, file);
    const help = append(el('details', undefined, 'panel'), el('summary', 'Apply changes and regenerate without rerunning tests'));
    append(help, el('p', 'Choose a durable private context path explicitly. Do not use target/ for the authoritative context. If you choose a project path, add it to your ignore rules. Imported files never authorize a destination write.'),
      el('pre', './mvnw census:context-apply -Dcensus.contextFile=/private/path/context.json -Dcensus.changesFile=/path/context-changes.json\n./mvnw census:assess -Dcensus.inputDir=/path/saved-evidence -Dcensus.contextFile=/private/path/context.json'),
      el('p', 'Open the regenerated HTML to review authoritative findings. Conflicts are reported together and leave the context unchanged. A new answer that conflicts with annotations may require a second explicit source-resolution review.')); root.append(help);
    const draftView = append(el('details', undefined, 'panel'), el('summary', 'Review proposed edits'));
    const preview = el('pre'); preview.id = 'draft-preview'; draftView.append(preview); root.append(draftView);
    filters(root);
    const navLabel = el('label', 'Choose an area to review'); navLabel.htmlFor = 'flow-navigation';
    const nav = el('select'); nav.id = 'flow-navigation';
    if (!selected && report.flows.length) selected = subjectKey(report.flows[0].subject);
    nav.value = selected; nav.addEventListener('change', () => { if (dirty() && !confirm('Discard form changes that have not been added to the draft?')) { nav.value = selected; return; } selected = nav.value; selectedInstance = null; dirtyControls.clear(); renderSelection(); status(); }); append(root, navLabel, nav); updateNavigation();
    const selection = el('section'); selection.id = 'selection'; root.append(selection); renderSelection();
    detail(root, 'Collection gaps and limitations', {incomplete: report.incomplete, gaps: report.gaps, limitations: report.limitations});
    detail(root, 'Input provenance and policy', {runs: report.runs, profile: report.profile, contextHash: report.contextHash, profileHash: report.profileHash});
    status();
  }
  window.addEventListener('beforeunload', event => { if (drafts.size || dirty()) { event.preventDefault(); event.returnValue = ''; } });
  render();
})();

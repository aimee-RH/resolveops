const cases = {
  subscription: {
    id: 'CASE-2048', type: 'SUBSCRIPTION ACTIVATION', title: 'Subscription activation', description: 'Payment succeeded, but the subscription remains pending.', customer: 'Acme Cloud', resource: 'SUB-8042', scenario: 'Subscription status',
    stepOne: 'Read subscription snapshot and payment events', stepTwo: 'Match a registered activation runbook', outcome: 'Repair proposed', diagnosis: 'Payment complete; activation pending', body: 'A payment success event is recorded, while the subscription snapshot remains pending. A registered runbook matches the case, so a controlled repair can be proposed.', footnote: 'A separate approver is required', repair: true,
    evidence: [
      { kind: 'Business snapshot', id: 'EV-101', title: 'Subscription status', body: 'SUB-8042 · Status: pending', source: 'Subscription record / read-only snapshot' },
      { kind: 'Event record', id: 'EV-102', title: 'Payment succeeded', body: 'A payment success event exists for this subscription', source: 'Business event / read-only snapshot' },
      { kind: 'Runbook', id: 'EV-103', title: 'Activation reconciliation procedure', body: 'Registered version matches this case', source: 'Runbook collection / source hash' }
    ]
  },
  api: {
    id: 'CASE-2106', type: 'API AUTHORIZATION', title: 'API authorization issue', description: 'The customer reports recent 403 responses from an integration API.', customer: 'Northstar', resource: 'INT-1907', scenario: 'API authorization',
    stepOne: 'Read integration status and 401/403 events', stepTwo: 'Connect records in the request window', outcome: 'Human handoff', diagnosis: 'Authorization denial needs review', body: 'The event window contains a 403 denial. The investigation describes the observation, but the evidence does not justify changing integration permissions. Hand off to an authorized person.', footnote: 'Read-only diagnosis for this scenario', repair: false,
    evidence: [
      { kind: 'Integration status', id: 'EV-201', title: 'Integration configuration snapshot', body: 'INT-1907 · Connected', source: 'Integration record / safe fields' },
      { kind: 'Request event', id: 'EV-202', title: 'Authorization denied', body: 'HTTP 403 recorded in the observation window', source: 'Authorization events / bounded window' }
    ]
  }
};

let active = 'subscription';
const byId = (id) => document.getElementById(id);
const setText = (id, value) => { byId(id).textContent = value; };

function renderEvidence(items) {
  const list = byId('evidence-list');
  list.replaceChildren();
  items.forEach((item, index) => {
    const card = document.createElement('article');
    card.className = 'evidence-card';
    card.id = `evidence-${index + 1}`;
    const head = document.createElement('div');
    head.className = 'evidence-card-head';
    const kind = document.createElement('span'); kind.textContent = item.kind;
    const id = document.createElement('small'); id.textContent = item.id;
    head.append(kind, id);
    const title = document.createElement('strong'); title.textContent = item.title;
    const body = document.createElement('p'); body.textContent = item.body;
    const source = document.createElement('div'); source.className = 'evidence-source'; source.textContent = `↗ ${item.source}`;
    card.append(head, title, body, source);
    list.append(card);
  });
}

function selectCase(key) {
  active = key;
  const item = cases[key];
  document.querySelectorAll('.case-item').forEach((button) => {
    const selected = button.dataset.case === key;
    button.classList.toggle('active', selected);
    button.setAttribute('aria-pressed', String(selected));
  });
  setText('crumb-case', item.id);
  setText('case-id', `${item.id} / ${item.type}`);
  setText('case-title', item.title);
  setText('case-description', item.description);
  setText('case-customer', item.customer);
  setText('case-resource', item.resource);
  setText('case-scenario', item.scenario);
  setText('case-status', 'Open');
  setText('run-state', '· Not started');
  setText('evidence-count', '00');
  byId('investigation').hidden = true;
  byId('empty-state').hidden = false;
  byId('run-button').textContent = 'Start read-only investigation →';
  byId('evidence-list').innerHTML = '<div class="evidence-placeholder"><span>◌</span><p>Cited records appear here after the investigation starts.</p></div>';
}

function runInvestigation() {
  const item = cases[active];
  setText('run-state', '· Completed');
  setText('case-status', 'Investigated');
  setText('step-one', item.stepOne);
  setText('step-two', item.stepTwo);
  setText('outcome-tag', item.outcome);
  setText('diagnosis-title', item.diagnosis);
  setText('diagnosis-body', item.body);
  setText('diagnosis-footnote', item.footnote);
  setText('evidence-count', String(item.evidence.length).padStart(2, '0'));
  byId('approval').hidden = !item.repair;
  byId('empty-state').hidden = true;
  byId('investigation').hidden = false;
  byId('run-button').textContent = 'Run again ↻';
  renderEvidence(item.evidence);
}

document.querySelectorAll('.case-item').forEach((button) => button.addEventListener('click', () => selectCase(button.dataset.case)));
byId('run-button').addEventListener('click', runInvestigation);
byId('evidence-button').addEventListener('click', () => {
  const panel = byId('evidence-panel');
  panel.scrollIntoView({ behavior: 'smooth', block: 'center' });
  panel.classList.remove('highlight');
  void panel.offsetWidth;
  panel.classList.add('highlight');
});
selectCase(active);

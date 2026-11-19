const cases = {
  subscription: {
    id: 'CASE-2048', type: 'SUBSCRIPTION ACTIVATION', title: '订阅开通异常', description: '客户付款成功后，订阅仍处于待开通状态。', customer: 'Acme Cloud', resource: 'SUB-8042', scenario: '订阅状态',
    stepOne: '读取订阅快照与付款事件', stepTwo: '匹配已登记的开通手册', outcome: '建议修复', diagnosis: '支付已完成，订阅尚未开通', body: '已记录付款成功事件，订阅快照仍为待开通。已登记的操作手册与当前情况匹配，因此可提出受控修复建议。', footnote: '下一步需要不同角色审批', repair: true,
    evidence: [
      { kind: '业务快照', id: 'EV-101', title: '订阅状态', body: 'SUB-8042 · 状态：待开通', source: '订阅记录 / 只读快照' },
      { kind: '事件记录', id: 'EV-102', title: '付款成功事件', body: '同一订阅已收到付款成功事件', source: '业务事件 / 只读快照' },
      { kind: '操作手册', id: 'EV-103', title: '开通状态核对流程', body: '已登记版本与当前问题匹配', source: '手册集合 / 来源哈希' }
    ]
  },
  api: {
    id: 'CASE-2106', type: 'API AUTHORIZATION', title: 'API 授权故障', description: '客户报告集成接口近期返回 403，调用无法完成。', customer: 'Northstar', resource: 'INT-1907', scenario: 'API 授权',
    stepOne: '读取集成状态与 401/403 事件', stepTwo: '关联请求时间窗口中的记录', outcome: '转交人工处理', diagnosis: '授权拒绝需要人工核查', body: '事件窗口中存在 403 拒绝记录。调查可以说明观察到的现象，但现有证据不足以自动更改集成权限，因此转交有权限的人员核查。', footnote: '此场景只提供只读诊断', repair: false,
    evidence: [
      { kind: '集成状态', id: 'EV-201', title: '集成配置快照', body: 'INT-1907 · 已连接', source: '集成记录 / 安全字段' },
      { kind: '请求事件', id: 'EV-202', title: '授权拒绝', body: '观察窗口内记录 HTTP 403', source: '授权事件 / 有界窗口' }
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
  setText('case-status', '待处理');
  setText('run-state', '· 等待开始');
  setText('evidence-count', '00');
  byId('investigation').hidden = true;
  byId('empty-state').hidden = false;
  byId('run-button').textContent = '开始只读调查 →';
  byId('evidence-list').innerHTML = '<div class="evidence-placeholder"><span>◌</span><p>启动调查后，引用的记录会显示在这里。</p></div>';
}

function runInvestigation() {
  const item = cases[active];
  setText('run-state', '· 已完成');
  setText('case-status', '已调查');
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
  byId('run-button').textContent = '重新演示 ↻';
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

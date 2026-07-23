const $ = selector => document.querySelector(selector);
let currentUser;
let availableTenants = [];
let availableWorkItems = [];
let assignmentWorkItems = [];
let emailProviders = [];
let gmailPopup;
let myWorkPage = 0;

async function api(path, options = {}) {
  const response = await fetch(path, {
    credentials: 'same-origin',
    headers: {'Content-Type': 'application/json', ...(options.headers || {})},
    ...options
  });
  if (!response.ok) {
    let body = {};
    try { body = await response.json(); } catch (_) {}
    throw new Error(body.error || `Request failed (${response.status})`);
  }
  return response.status === 204 ? null : response.json();
}

function show(id) {
  ['login-view', 'forced-password-view', 'dashboard'].forEach(name =>
    $(`#${name}`).classList.toggle('hidden', name !== id));
  $('#account-menu').classList.toggle('hidden', id === 'login-view');
}

function route(user) {
  currentUser = user;
  $('#account-name').textContent = `${user.companyCode} · ${user.username}`;
  if (user.mustChangePassword) { show('forced-password-view'); return; }
  show('dashboard'); renderProfile(); loadMyWork();
  if (isAdmin()) {
    $('#admin-panel').classList.remove('hidden'); configureCreateForm(); loadUsers(); loadWorkAccounts(); loadEmailProviders();
    const globalAdmin = currentUser.role === 'GLOBAL_ADMIN';
    $('#tenant-panel').classList.toggle('hidden', !globalAdmin);
    $('#work-item-panel').classList.toggle('hidden', !globalAdmin);
    $('#work-account-tenant-field').classList.toggle('hidden', !globalAdmin);
    $('#assignment-tenant-field').classList.toggle('hidden', !globalAdmin);
    if (globalAdmin) {
      loadTenants().then(loadAssignmentContext);
      loadWorkItemDefinitions();
    } else loadAssignmentContext();
  } else $('#admin-panel').classList.add('hidden');
}

function isAdmin() { return ['GLOBAL_ADMIN', 'ADMIN'].includes(currentUser.role); }

function renderProfile() {
  $('#workspace-label').textContent = `${currentUser.companyCode} workspace`;
  $('#role-badge').textContent = currentUser.role.replaceAll('_', ' ');
  $('#profile-username').textContent = currentUser.username;
  $('#profile-company').textContent = currentUser.companyCode;
  $('#profile-role').textContent = currentUser.role.replaceAll('_', ' ');
  $('#avatar').textContent = initials(currentUser.username);
}

function configureCreateForm() {
  const roles = currentUser.role === 'GLOBAL_ADMIN'
    ? ['GLOBAL_ADMIN', 'ADMIN', 'PROCESSOR', 'BASE_USER'] : ['PROCESSOR', 'BASE_USER'];
  $('#new-role').replaceChildren(...roles.map(role => new Option(role.replaceAll('_', ' '), role)));
  $('#company-field').classList.toggle('hidden', currentUser.role !== 'GLOBAL_ADMIN');
  $('#new-company-code').required = currentUser.role === 'GLOBAL_ADMIN';
  $('#new-company-code').value = currentUser.companyCode;
}

async function loadTenants() {
  try {
    const tenants = await api('/api/v1/tenants');
    availableTenants = tenants;
    $('#work-account-tenant').replaceChildren(...tenants.filter(tenant => tenant.active)
      .map(tenant => new Option(`${tenant.displayName} (${tenant.companyCode})`, tenant.id)));
    $('#work-item-tenant').replaceChildren(...tenants.filter(tenant => tenant.active && tenant.id !== currentUser.tenantId)
      .map(tenant => new Option(`${tenant.displayName} (${tenant.companyCode})`, tenant.id)));
    $('#assignment-tenant').replaceChildren(...tenants.filter(tenant => tenant.active)
      .map(tenant => new Option(`${tenant.displayName} (${tenant.companyCode})`, tenant.id)));
    $('#assignment-tenant').value = currentUser.tenantId;
    const root = $('#tenants');
    if (!tenants.length) { root.innerHTML = '<div class="empty">No tenants found.</div>'; return; }
    root.replaceChildren(...tenants.map(tenant => {
      const row = document.createElement('article'); row.className = 'user-row';
      const identity = document.createElement('div'); identity.className = 'user-identity';
      const avatar = document.createElement('span'); avatar.className = 'mini-avatar'; avatar.textContent = initials(tenant.displayName);
      const copy = document.createElement('div');
      const name = document.createElement('h4'); name.textContent = tenant.displayName;
      const code = document.createElement('p'); code.textContent = tenant.companyCode;
      copy.append(name, code); identity.append(avatar, copy);
      const actions = document.createElement('div'); actions.className = 'user-actions';
      const status = document.createElement('span'); status.className = `tenant-status${tenant.active ? ' enabled' : ''}`;
      status.textContent = tenant.active ? 'ACTIVE' : 'INACTIVE';
      const edit = document.createElement('button'); edit.className = 'tenant-edit'; edit.textContent = 'Edit';
      edit.onclick = () => openTenantForm(tenant);
      actions.append(status, edit); row.append(identity, actions); return row;
    }));
  } catch (cause) { notice(cause.message, true); }
}

async function loadWorkItemDefinitions() {
  try {
    const definitions = await api('/api/v1/work-items/definitions');
    const root = $('#work-items');
    if (!definitions.length) { root.innerHTML = '<div class="empty">No work item definitions found.</div>'; return; }
    root.replaceChildren(...definitions.map(definition => {
      const row = document.createElement('article'); row.className = 'user-row';
      const identity = document.createElement('div'); identity.className = 'user-identity';
      const avatar = document.createElement('span'); avatar.className = 'mini-avatar'; avatar.textContent = definition.statuses.length;
      const copy = document.createElement('div');
      const name = document.createElement('h4'); name.textContent = `${definition.displayName} (${definition.type})`;
      const graph = document.createElement('div'); graph.className = 'graph-summary';
      definition.statuses.forEach((status, index) => {
        if (index) { const arrow = document.createElement('span'); arrow.className = 'graph-edge'; arrow.textContent = '•'; graph.append(arrow); }
        const node = document.createElement('span'); node.className = 'graph-node';
        node.textContent = `${status.initialStatus ? '▶ ' : ''}${status.code}${status.terminalStatus ? ' ✓' : ''}`; graph.append(node);
      });
      const transitions = document.createElement('p');
      transitions.textContent = definition.transitions.map(edge => `${edge.fromStatus} → ${edge.toStatus}`).join(' · ') || 'No transitions';
      copy.append(name, graph, transitions); identity.append(avatar, copy);
      const actions = document.createElement('div'); actions.className = 'user-actions';
      const scope = document.createElement('span'); scope.className = 'scope-badge';
      scope.textContent = definition.globalScope ? 'CASIQ-WIDE' : `OVERRIDE · ${definition.companyCode}`;
      const status = document.createElement('span'); status.className = `tenant-status${definition.active ? ' enabled' : ''}`;
      status.textContent = definition.active ? 'ACTIVE' : 'INACTIVE';
      const edit = document.createElement('button'); edit.className = 'tenant-edit'; edit.textContent = 'Edit';
      edit.onclick = () => openWorkItemForm(definition);
      actions.append(scope, status, edit); row.append(identity, actions); return row;
    }));
  } catch (cause) { notice(cause.message, true); }
}

function openWorkItemForm(definition = null) {
  $('#work-item-form').reset();
  $('#work-item-id').value = definition?.id || '';
  $('#work-item-scope').value = definition && !definition.globalScope ? 'TENANT' : 'GLOBAL';
  $('#work-item-tenant').value = definition?.tenantId || '';
  $('#work-item-type').value = definition?.type || '';
  $('#work-item-name').value = definition?.displayName || '';
  $('#work-item-active').checked = definition?.active ?? true;
  $('#work-item-statuses').value = definition?.statuses.map(status =>
    `${status.code} | ${status.displayName} | ${status.initialStatus ? 'INITIAL' : ''} | ${status.terminalStatus ? 'TERMINAL' : ''}`).join('\n') ||
    'NEW | New | INITIAL |\nIN_PROGRESS | In progress | |\nCOMPLETED | Completed | | TERMINAL';
  $('#work-item-transitions').value = definition?.transitions.map(edge =>
    `${edge.fromStatus} | ${edge.toStatus} | ${edge.label}`).join('\n') ||
    'NEW | IN_PROGRESS | Start\nIN_PROGRESS | COMPLETED | Complete';
  $('#work-item-scope').disabled = Boolean(definition);
  $('#work-item-tenant').disabled = Boolean(definition);
  $('#work-item-type').readOnly = Boolean(definition);
  toggleWorkItemTenant();
  $('#work-item-form-title').textContent = definition ? 'Update work item' : 'Create work item';
  $('#save-work-item').textContent = definition ? 'Save graph' : 'Create work item';
  $('#work-item-error').classList.add('hidden'); $('#work-item-form').classList.remove('hidden');
}

function toggleWorkItemTenant() {
  const tenantScope = $('#work-item-scope').value === 'TENANT';
  $('#work-item-tenant-field').classList.toggle('hidden', !tenantScope);
  $('#work-item-tenant').required = tenantScope;
}

function parseWorkItemGraph() {
  const lines = value => value.split('\n').map(line => line.trim()).filter(Boolean)
    .map(line => line.split('|').map(part => part.trim()));
  return {
    statuses: lines($('#work-item-statuses').value).map((parts, sortOrder) => ({
      code: parts[0], displayName: parts[1], initialStatus: parts[2]?.toUpperCase() === 'INITIAL',
      terminalStatus: parts[3]?.toUpperCase() === 'TERMINAL', sortOrder
    })),
    transitions: lines($('#work-item-transitions').value).map(parts =>
      ({fromStatus: parts[0], toStatus: parts[1], label: parts[2]}))
  };
}

async function loadEffectiveWorkItems(tenantId) {
  if (!tenantId) { availableWorkItems = []; $('#work-account-item').replaceChildren(); return; }
  availableWorkItems = await api(`/api/v1/work-items/effective?tenantId=${encodeURIComponent(tenantId)}`);
  $('#work-account-item').replaceChildren(...availableWorkItems.map(item => new Option(`${item.displayName} (${item.type})`, item.id)));
}

async function loadEmailProviders() {
  try {
    emailProviders = await api('/api/v1/work-accounts/providers');
    $('#work-account-provider').replaceChildren(...emailProviders.map(provider =>
      new Option(provider.displayName, provider.code)));
  } catch (cause) { notice(cause.message, true); }
}

function assignmentTenantId() {
  return currentUser.role === 'GLOBAL_ADMIN' ? $('#assignment-tenant').value : currentUser.tenantId;
}

async function loadAssignmentContext() {
  const tenantId = assignmentTenantId();
  if (!tenantId) return;
  try {
    const [definitions, users, assignments] = await Promise.all([
      api(`/api/v1/work-items/effective?tenantId=${encodeURIComponent(tenantId)}`),
      api('/api/v1/users'),
      api(`/api/v1/work-items/assignments?tenantId=${encodeURIComponent(tenantId)}`)
    ]);
    assignmentWorkItems = definitions;
    $('#assignment-definition').replaceChildren(...definitions.map(item =>
      new Option(`${item.displayName} (${item.type})`, item.id)));
    renderCheckboxOptions($('#assignment-user'), users.filter(user => user.active && user.tenantId === tenantId)
      .map(user => ({value:user.id, label:`${user.username} · ${user.role.replaceAll('_', ' ')}`})));
    updateAssignmentTargets();
    renderAssignments(assignments);
  } catch (cause) { notice(cause.message, true); }
}

function updateAssignmentTargets() {
  const definition = assignmentWorkItems.find(item => item.id === $('#assignment-definition').value);
  const targets = $('#assignment-type').value === 'STATUS'
    ? (definition?.statuses || []).map(status => ({value:status.id, label:`${status.displayName} (${status.code})`}))
    : (definition?.transitions || []).map(edge => ({
      value:edge.id, label:`${edge.label}: ${edge.fromStatus} → ${edge.toStatus}`
    }));
  renderCheckboxOptions($('#assignment-target'), targets);
}

function renderCheckboxOptions(container, options) {
  if (!options.length) {
    const empty = document.createElement('div'); empty.className = 'checkbox-empty'; empty.textContent = 'No options available.';
    container.replaceChildren(empty); return;
  }
  container.replaceChildren(...options.map(option => {
    const label = document.createElement('label'); label.className = 'checkbox-option';
    const input = document.createElement('input'); input.type = 'checkbox'; input.value = option.value;
    const text = document.createElement('span'); text.textContent = option.label;
    label.append(input, text); return label;
  }));
}

function renderAssignments(assignments) {
  const root = $('#assignments');
  if (!assignments.length) { root.innerHTML = '<div class="empty">No workflow assignments for this tenant.</div>'; return; }
  root.replaceChildren(...assignments.map(assignment => {
    const row = document.createElement('article'); row.className = 'user-row';
    const identity = document.createElement('div'); identity.className = 'user-identity';
    const avatar = document.createElement('span'); avatar.className = 'mini-avatar'; avatar.textContent = initials(assignment.username);
    const copy = document.createElement('div');
    const name = document.createElement('h4'); name.textContent = assignment.username;
    const target = document.createElement('p');
    target.textContent = `${assignment.workItemType} · ${assignment.assignmentType === 'STATUS'
      ? `Status ${assignment.statusCode}` : `Activity ${assignment.transitionLabel}`}`;
    copy.append(name, target); identity.append(avatar, copy);
    const actions = document.createElement('div'); actions.className = 'user-actions';
    const kind = document.createElement('span'); kind.className = 'assignment-kind'; kind.textContent = assignment.assignmentType;
    const remove = document.createElement('button'); remove.className = 'reset'; remove.textContent = 'Remove';
    remove.onclick = () => removeAssignment(assignment);
    actions.append(kind, remove); row.append(identity, actions); return row;
  }));
}

async function removeAssignment(assignment) {
  try {
    await api(`/api/v1/work-items/assignments/${assignment.assignmentType}/${assignment.id}`, {method:'DELETE'});
    notice('Workflow assignment removed.'); await Promise.all([loadAssignmentContext(), loadMyWork()]);
  } catch (cause) { notice(cause.message, true); }
}

async function loadMyWork() {
  try {
    const params = new URLSearchParams();
    const type = $('#my-work-type').value.trim();
    const status = $('#my-work-status').value.trim();
    const email = $('#my-work-email').value.trim();
    if (type) params.set('workItemType', type);
    if (status) params.set('status', status);
    if (email) params.set('email', email);
    if ($('#my-work-terminal').checked) params.set('includeTerminal', 'true');
    params.set('page', String(myWorkPage));
    params.set('size', $('#my-work-size').value);
    params.set('sortBy', $('#my-work-sort').value);
    params.set('sortDirection', $('#my-work-direction').value);
    const result = await api(`/api/v1/work-items/my-work?${params}`);
    if (result.totalPages > 0 && myWorkPage >= result.totalPages) {
      myWorkPage = result.totalPages - 1;
      return loadMyWork();
    }
    const executions = result.items;
    const root = $('#my-work');
    updateMyWorkPagination(result);
    if (!executions.length) { root.innerHTML = '<div class="empty">No work item activities are assigned to you.</div>'; return; }
    root.replaceChildren(...executions.map(execution => {
      const card = document.createElement('article'); card.className = 'card workflow-card';
      const title = document.createElement('h3'); title.textContent = execution.emailId;
      const subtitle = document.createElement('p'); subtitle.textContent = `${execution.workItemDisplayName} · ${execution.workItemType}`;
      const state = document.createElement('div'); state.className = 'workflow-state';
      const stateLabel = document.createElement('span'); stateLabel.textContent = execution.currentStatusDisplayName;
      state.append(stateLabel);
      const actions = document.createElement('div'); actions.className = 'workflow-actions';
      const open = document.createElement('button');
      open.textContent = execution.conversationId ? 'Open email and decide' : 'Open work item';
      open.onclick = () => openWorkItem(execution.id, open);
      actions.append(open);
      execution.allowedTransitions.forEach(transition => {
        const quick = document.createElement('button');
        quick.className = 'quick-transition';
        quick.textContent = `${transition.label} → ${transition.toStatus}`;
        quick.title = 'Apply this transition without opening the email';
        quick.onclick = () => performTransition(execution.id, transition.id, quick);
        actions.append(quick);
      });
      card.append(title, subtitle, state, actions);
      if (execution.activities.length) {
        const history = document.createElement('div'); history.className = 'activity-history';
        execution.activities.slice(0, 5).forEach(activity => {
          const line = document.createElement('div');
          line.textContent = `${activity.fromStatus} → ${activity.toStatus} by ${activity.performedByUsername} · ${new Date(activity.performedAt).toLocaleString()}`;
          history.append(line);
        });
        card.append(history);
      }
      return card;
    }));
  } catch (cause) { notice(cause.message, true); }
}

function updateMyWorkPagination(result) {
  const bar = $('#my-work-pagination');
  bar.classList.toggle('hidden', result.totalPages === 0);
  $('#my-work-page-label').textContent = result.totalPages
    ? `Page ${result.page + 1} of ${result.totalPages} · ${result.totalElements} item(s)`
    : 'No items';
  $('#my-work-previous').disabled = result.page <= 0;
  $('#my-work-next').disabled = result.page + 1 >= result.totalPages;
}

function escapeHtml(value) {
  const holder = document.createElement('div');
  holder.textContent = value || '';
  return holder.innerHTML;
}

async function openWorkItem(executionId, button) {
  button.disabled = true;
  try {
    const detail = await api(`/api/v1/work-items/executions/${executionId}`);
    const execution = detail.execution;
    const conversation = detail.conversation;
    $('#work-detail-title').textContent = conversation?.subject || `${execution.workItemDisplayName} · ${execution.emailId}`;
    const metadata = [];
    metadata.push(`Account: ${execution.emailId}`);
    metadata.push(`Work item: ${execution.workItemDisplayName}`);
    metadata.push(`Status: ${execution.currentStatusDisplayName}`);
    if (conversation?.sender) metadata.push(`From: ${conversation.sender}`);
    if (conversation?.recipients) metadata.push(`To: ${conversation.recipients}`);
    if (conversation?.sentAt) metadata.push(`Sent: ${new Date(conversation.sentAt).toLocaleString()}`);
    $('#work-detail-meta').replaceChildren(...metadata.map(value => {
      const line = document.createElement('div'); line.textContent = value; return line;
    }));

    const text = $('#work-detail-text');
    const frame = $('#work-detail-html');
    text.classList.add('hidden');
    frame.classList.remove('hidden');
    const renderedHtml = conversation?.contentHtml
      || `<pre style="white-space:pre-wrap;font:14px/1.6 system-ui;margin:0">${escapeHtml(conversation?.contentText || conversation?.snippet || 'No email body is available for this conversation.')}</pre>`;
    frame.srcdoc = `<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'"><meta name="color-scheme" content="light">${renderedHtml}`;

    const actions = $('#work-detail-actions');
    actions.replaceChildren(...execution.allowedTransitions.map(transition => {
      const action = document.createElement('button');
      action.textContent = `${transition.label} → ${transition.toStatus}`;
      action.onclick = () => performTransition(execution.id, transition.id, action);
      return action;
    }));
    if (!execution.allowedTransitions.length) {
      const complete = document.createElement('span');
      complete.className = 'terminal-note';
      complete.textContent = execution.terminal ? 'This work item is completed.' : 'No assigned decision is available.';
      actions.append(complete);
    }
    const history = $('#work-detail-history');
    history.replaceChildren(...execution.activities.map(activity => {
      const line = document.createElement('div');
      line.textContent = `${activity.fromStatus} → ${activity.toStatus} by ${activity.performedByUsername} · ${new Date(activity.performedAt).toLocaleString()}`;
      return line;
    }));
    $('#work-item-detail').showModal();
  } catch (cause) {
    notice(cause.message, true);
  } finally {
    button.disabled = false;
  }
}

async function performTransition(executionId, transitionId, button) {
  button.disabled = true;
  try {
    await api(`/api/v1/work-items/executions/${executionId}/transitions/${transitionId}`, {method:'POST'});
    if ($('#work-item-detail').open) $('#work-item-detail').close();
    notice('Work item activity completed.'); await loadMyWork();
  } catch (cause) { notice(cause.message, true); button.disabled = false; }
}

async function loadWorkAccounts() {
  try {
    const accounts = await api('/api/v1/work-accounts');
    const root = $('#work-accounts');
    if (!accounts.length) { root.innerHTML = '<div class="empty">No work accounts found.</div>'; return; }
    root.replaceChildren(...accounts.map(account => {
      const row = document.createElement('article'); row.className = 'user-row';
      const identity = document.createElement('div'); identity.className = 'user-identity';
      const avatar = document.createElement('span'); avatar.className = 'mini-avatar'; avatar.textContent = '@';
      const copy = document.createElement('div');
      const email = document.createElement('h4'); email.textContent = account.emailId;
      const company = document.createElement('p');
      const pollingSchedule = account.accessTokenExpiresAt
        ? ` · token expires ${new Date(account.accessTokenExpiresAt).toLocaleString()} · next refresh ${new Date(account.nextRefreshAt).toLocaleString()}`
        : '';
      company.textContent = account.connected
        ? `${account.companyCode} · ${account.providerDisplayName}${pollingSchedule}`
        : `${account.companyCode} · ${account.providerDisplayName}`;
      copy.append(email, company); identity.append(avatar, copy);
      const actions = document.createElement('div'); actions.className = 'user-actions';
      const item = document.createElement('span'); item.className = 'work-item'; item.textContent = account.workItemDisplayName;
      const status = document.createElement('span'); status.className = `connection-status${account.connected ? ' connected' : ''}`;
      status.textContent = account.connected ? `${account.provider} CONNECTED` : 'NOT CONNECTED';
      const connect = document.createElement('button'); connect.className = 'connect-gmail';
      connect.textContent = account.connected ? `Reconnect ${account.providerDisplayName}` : `Connect ${account.providerDisplayName}`;
      connect.onclick = () => connectEmailProvider(account);
      const edit = document.createElement('button'); edit.className = 'tenant-edit'; edit.textContent = 'Edit';
      edit.onclick = () => openWorkAccountForm(account);
      actions.append(item, status, connect, edit); row.append(identity, actions); return row;
    }));
  } catch (cause) { notice(cause.message, true); }
}

async function openWorkAccountForm(account = null) {
  $('#work-account-form').reset();
  $('#work-account-id').value = account?.id || '';
  $('#work-account-email').value = account?.emailId || '';
  $('#work-account-provider').value = account?.provider || emailProviders[0]?.code || '';
  const tenantId = currentUser.role === 'GLOBAL_ADMIN'
    ? (account?.tenantId || availableTenants.find(tenant => tenant.active)?.id || '') : currentUser.tenantId;
  await loadEffectiveWorkItems(tenantId);
  $('#work-account-item').value = account?.workItemId || availableWorkItems[0]?.id || '';
  if (currentUser.role === 'GLOBAL_ADMIN') {
    $('#work-account-tenant').value = tenantId;
    $('#work-account-tenant').disabled = Boolean(account);
  }
  $('#work-account-form-title').textContent = account ? 'Update work account' : 'Create work account';
  $('#save-work-account').textContent = account ? 'Save changes' : 'Create work account';
  $('#work-account-error').classList.add('hidden');
  $('#work-account-form').classList.remove('hidden');
}

async function connectEmailProvider(account) {
  try {
    const authorization = await api(`/api/v1/work-accounts/${account.id}/authorize`, {method:'POST'});
    gmailPopup = window.open(authorization.authorizationUrl, 'casiq-work-account-email', 'popup,width=560,height=720');
    if (!gmailPopup) throw new Error('The OAuth popup was blocked. Allow popups and try again.');
    gmailPopup.focus();
  } catch (cause) { notice(cause.message, true); }
}

function openTenantForm(tenant = null) {
  $('#tenant-form').reset();
  $('#tenant-id').value = tenant?.id || '';
  $('#tenant-company-code').value = tenant?.companyCode || '';
  $('#tenant-display-name').value = tenant?.displayName || '';
  $('#tenant-active').checked = tenant?.active ?? true;
  $('#tenant-form-title').textContent = tenant ? 'Update tenant' : 'Create tenant';
  $('#save-tenant').textContent = tenant ? 'Save changes' : 'Create tenant';
  $('#tenant-error').classList.add('hidden');
  $('#tenant-form').classList.remove('hidden');
}

async function loadUsers() {
  try {
    const users = await api('/api/v1/users');
    const root = $('#users');
    if (!users.length) { root.innerHTML = '<div class="empty">No users found.</div>'; return; }
    root.replaceChildren(...users.map(user => {
      const row = document.createElement('article'); row.className = 'user-row';
      const identity = document.createElement('div'); identity.className = 'user-identity';
      const avatar = document.createElement('span'); avatar.className = 'mini-avatar'; avatar.textContent = initials(user.username);
      const copy = document.createElement('div');
      const name = document.createElement('h4'); name.textContent = user.username;
      const company = document.createElement('p'); company.textContent = user.companyCode;
      copy.append(name, company); identity.append(avatar, copy);
      const actions = document.createElement('div'); actions.className = 'user-actions';
      if (user.mustChangePassword) { const force = document.createElement('span'); force.className = 'force'; force.textContent = 'PASSWORD CHANGE DUE'; actions.append(force); }
      const role = document.createElement('span'); role.className = 'user-role'; role.textContent = user.role.replaceAll('_', ' '); actions.append(role);
      if (canReset(user)) {
        const reset = document.createElement('button'); reset.className = 'reset'; reset.textContent = 'Reset password';
        reset.onclick = () => resetPassword(user); actions.append(reset);
      }
      row.append(identity, actions); return row;
    }));
  } catch (cause) { notice(cause.message, true); }
}

function canReset(user) {
  if (user.id === currentUser.id) return false;
  return currentUser.role === 'GLOBAL_ADMIN' || !['GLOBAL_ADMIN', 'ADMIN'].includes(user.role);
}

async function resetPassword(user) {
  const temporaryPassword = prompt(`Temporary password for ${user.username} (minimum 12 characters):`);
  if (!temporaryPassword) return;
  if (temporaryPassword.length < 12) { notice('Temporary password must contain at least 12 characters.', true); return; }
  try {
    await api(`/api/v1/users/${user.id}/reset-password`, {method:'POST', body:JSON.stringify({temporaryPassword})});
    notice(`Password reset for ${user.username}. Existing sessions were revoked.`); await loadUsers();
  } catch (cause) { notice(cause.message, true); }
}

function notice(message, bad = false) {
  const element = $('#notice'); element.textContent = message;
  element.classList.toggle('bad', bad); element.classList.remove('hidden');
  setTimeout(() => element.classList.add('hidden'), 6000);
}

function initials(value) { return value.split(/[. _-]+/).slice(0, 2).map(part => part[0]?.toUpperCase() || '').join(''); }
function formError(id, message) { const el = $(id); el.textContent = message; el.classList.remove('hidden'); }

$('#login-form').onsubmit = async event => {
  event.preventDefault(); const button = event.submitter; button.disabled = true; $('#login-error').classList.add('hidden');
  try {
    const user = await api('/api/v1/auth/login', {method:'POST', body:JSON.stringify({companyCode:$('#company-code').value, username:$('#username').value, password:$('#password').value})});
    route(user); $('#password').value = '';
  } catch (cause) { formError('#login-error', cause.message); }
  finally { button.disabled = false; }
};

$('#forced-password-form').onsubmit = async event => {
  event.preventDefault(); const button = event.submitter; button.disabled = true; $('#forced-error').classList.add('hidden');
  try {
    const user = await api('/api/v1/auth/password', {method:'POST', body:JSON.stringify({currentPassword:$('#forced-current-password').value, newPassword:$('#forced-new-password').value})});
    $('#forced-password-form').reset(); route(user); notice('Password updated successfully.');
  } catch (cause) { formError('#forced-error', cause.message); }
  finally { button.disabled = false; }
};

$('#self-password-form').onsubmit = async event => {
  event.preventDefault(); const button = event.submitter; button.disabled = true; $('#self-error').classList.add('hidden');
  try {
    currentUser = await api('/api/v1/auth/password', {method:'POST', body:JSON.stringify({currentPassword:$('#self-current-password').value, newPassword:$('#self-new-password').value})});
    event.target.reset(); notice('Your password was changed. Other sessions were signed out.');
  } catch (cause) { formError('#self-error', cause.message); }
  finally { button.disabled = false; }
};

$('#create-user-form').onsubmit = async event => {
  event.preventDefault(); const button = event.submitter; button.disabled = true; $('#create-error').classList.add('hidden');
  try {
    await api('/api/v1/users', {method:'POST', body:JSON.stringify({companyCode:$('#new-company-code').value, username:$('#new-username').value, role:$('#new-role').value, temporaryPassword:$('#new-temporary-password').value})});
    event.target.reset(); configureCreateForm(); event.target.classList.add('hidden'); notice('User created with a required first-login password change.');
    await Promise.all([loadUsers(), loadAssignmentContext()]);
  } catch (cause) { formError('#create-error', cause.message); }
  finally { button.disabled = false; }
};

$('#tenant-form').onsubmit = async event => {
  event.preventDefault(); const button = event.submitter; button.disabled = true; $('#tenant-error').classList.add('hidden');
  const tenantId = $('#tenant-id').value;
  const body = JSON.stringify({
    companyCode: $('#tenant-company-code').value,
    displayName: $('#tenant-display-name').value,
    active: $('#tenant-active').checked
  });
  try {
    await api(tenantId ? `/api/v1/tenants/${tenantId}` : '/api/v1/tenants', {method: tenantId ? 'PUT' : 'POST', body});
    $('#tenant-form').classList.add('hidden');
    notice(tenantId ? 'Tenant updated.' : 'Tenant created.');
    await Promise.all([loadTenants(), loadUsers()]);
  } catch (cause) { formError('#tenant-error', cause.message); }
  finally { button.disabled = false; }
};

$('#work-item-form').onsubmit = async event => {
  event.preventDefault(); const button = event.submitter; button.disabled = true; $('#work-item-error').classList.add('hidden');
  const definitionId = $('#work-item-id').value;
  const graph = parseWorkItemGraph();
  const globalScope = $('#work-item-scope').value === 'GLOBAL';
  const payload = {
    tenantId: globalScope ? currentUser.tenantId : $('#work-item-tenant').value,
    globalScope,
    type: $('#work-item-type').value,
    displayName: $('#work-item-name').value,
    active: $('#work-item-active').checked,
    ...graph
  };
  try {
    await api(definitionId ? `/api/v1/work-items/definitions/${definitionId}` : '/api/v1/work-items/definitions',
      {method: definitionId ? 'PUT' : 'POST', body:JSON.stringify(payload)});
    $('#work-item-form').classList.add('hidden');
    notice(definitionId ? 'Work item graph updated.' : 'Work item graph created.');
    await Promise.all([loadWorkItemDefinitions(), loadWorkAccounts()]);
  } catch (cause) { formError('#work-item-error', cause.message); }
  finally { button.disabled = false; }
};

$('#assignment-form').onsubmit = async event => {
  event.preventDefault(); const button = event.submitter; button.disabled = true; $('#assignment-error').classList.add('hidden');
  const statusAssignment = $('#assignment-type').value === 'STATUS';
  const targetIds = [...$('#assignment-target').querySelectorAll('input:checked')].map(input => input.value);
  const userIds = [...$('#assignment-user').querySelectorAll('input:checked')].map(input => input.value);
  if (!targetIds.length || !userIds.length) {
    formError('#assignment-error', 'Select at least one user and one status or transition.');
    button.disabled = false; return;
  }
  const payloads = userIds.flatMap(userId => targetIds.map(targetId => ({
    tenantId: assignmentTenantId(),
    definitionId: $('#assignment-definition').value,
    statusId: statusAssignment ? targetId : null,
    transitionId: statusAssignment ? null : targetId,
    userId
  })));
  try {
    const results = await Promise.allSettled(payloads.map(payload =>
      api('/api/v1/work-items/assignments', {method:'POST', body:JSON.stringify(payload)})));
    const failures = results.filter(result => result.status === 'rejected');
    const created = results.length - failures.length;
    await Promise.all([loadAssignmentContext(), loadMyWork()]);
    if (failures.length) {
      const reason = failures[0].reason?.message || 'Assignment failed';
      formError('#assignment-error', `${created} assignment(s) created; ${failures.length} failed. ${reason}`);
    } else {
      $('#assignment-form').classList.add('hidden');
      notice(`${created} workflow assignment(s) created.`);
    }
  } catch (cause) { formError('#assignment-error', cause.message); }
  finally { button.disabled = false; }
};

$('#work-account-form').onsubmit = async event => {
  event.preventDefault(); const button = event.submitter; button.disabled = true; $('#work-account-error').classList.add('hidden');
  const accountId = $('#work-account-id').value;
  const payload = {
    tenantId: currentUser.role === 'GLOBAL_ADMIN' ? $('#work-account-tenant').value : currentUser.tenantId,
    emailId: $('#work-account-email').value,
    provider: $('#work-account-provider').value,
    workItemId: $('#work-account-item').value
  };
  try {
    await api(accountId ? `/api/v1/work-accounts/${accountId}` : '/api/v1/work-accounts',
      {method: accountId ? 'PUT' : 'POST', body:JSON.stringify(payload)});
    $('#work-account-form').classList.add('hidden');
    notice(accountId ? 'Work account updated.' : 'Work account created. Connect Gmail when ready.');
    await Promise.all([loadWorkAccounts(), loadMyWork()]);
  } catch (cause) { formError('#work-account-error', cause.message); }
  finally { button.disabled = false; }
};

$('#show-create').onclick = () => $('#create-user-form').classList.remove('hidden');
$('#cancel-create').onclick = () => $('#create-user-form').classList.add('hidden');
$('#show-tenant-form').onclick = () => openTenantForm();
$('#cancel-tenant').onclick = () => $('#tenant-form').classList.add('hidden');
$('#show-work-item-form').onclick = () => openWorkItemForm();
$('#cancel-work-item').onclick = () => $('#work-item-form').classList.add('hidden');
$('#work-item-scope').onchange = toggleWorkItemTenant;
$('#show-assignment-form').onclick = () => { $('#assignment-error').classList.add('hidden'); $('#assignment-form').classList.remove('hidden'); };
$('#cancel-assignment').onclick = () => $('#assignment-form').classList.add('hidden');
$('#assignment-tenant').onchange = loadAssignmentContext;
$('#assignment-definition').onchange = updateAssignmentTargets;
$('#assignment-type').onchange = updateAssignmentTargets;
$('#show-work-account-form').onclick = () => openWorkAccountForm();
$('#cancel-work-account').onclick = () => $('#work-account-form').classList.add('hidden');
$('#work-account-tenant').onchange = event => loadEffectiveWorkItems(event.target.value).catch(cause => notice(cause.message, true));
$('#refresh-my-work').onclick = loadMyWork;
$('#my-work-filters').onsubmit = event => { event.preventDefault(); myWorkPage = 0; loadMyWork(); };
$('#clear-my-work-filters').onclick = () => {
  $('#my-work-filters').reset();
  myWorkPage = 0;
  loadMyWork();
};
$('#my-work-previous').onclick = () => { if (myWorkPage > 0) { myWorkPage--; loadMyWork(); } };
$('#my-work-next').onclick = () => { myWorkPage++; loadMyWork(); };
$('#logout').onclick = async () => { await api('/api/v1/auth/logout', {method:'POST'}); currentUser = null; show('login-view'); };

window.addEventListener('message', event => {
  if (event.origin !== window.location.origin || event.data?.type !== 'casiq-google-oauth') return;
  if (event.data.error) notice(event.data.error, true);
  if (event.data.workAccount) { notice(`Google connected for ${event.data.workAccount.emailId}.`); loadWorkAccounts(); }
});

(async function initialize() {
  try { route(await api('/api/v1/auth/me')); }
  catch (_) { show('login-view'); }
})();

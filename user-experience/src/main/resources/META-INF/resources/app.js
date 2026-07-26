const $ = selector => document.querySelector(selector);
let currentUser;
let availableTenants = [];
let availableWorkItems = [];
let effectiveWorkItems = [];
let assignmentWorkItems = [];
let emailProviders = [];
let gmailPopup;
let myWorkPage = 0;
let activeWorkItemId;
let workQueueScope = 'MY';

async function api(path, options = {}) {
  const response = await fetch(path, {
    credentials: 'same-origin',
    headers: {'Content-Type': 'application/json', ...(options.headers || {})},
    ...options
  });
  if (!response.ok) {
    let body = {};
    try { body = await response.json(); } catch (_) {}
    const error = new Error(body.error || `Request failed (${response.status})`);
    error.status = response.status;
    throw error;
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
  const displayName = [user.firstName, user.lastName].filter(Boolean).join(' ') || user.username;
  $('#account-name').textContent = `${user.companyCode} · ${displayName}`;
  if (user.mustChangePassword) { show('forced-password-view'); return; }
  show('dashboard');
  renderProfile();
  $('#navigate-administration').classList.toggle('hidden', !isAdmin());
  showWorkspaceScreen('work-items');
  loadMyWorkTypeOptions()
    .then(loadMyWork)
    .catch(cause => {
      notice(cause.message, true);
      loadMyWork();
    });
  if (isAdmin()) {
    $('#admin-panel').classList.remove('hidden'); configureCreateForm(); loadUsers();
    const globalAdmin = currentUser.role === 'GLOBAL_ADMIN';
    const tenantAdmin = currentUser.role === 'ADMIN';
    $('#tenant-panel').classList.toggle('hidden', !globalAdmin);
    $('#work-item-panel').classList.toggle('hidden', !globalAdmin);
    $('#work-account-panel').classList.toggle('hidden', !tenantAdmin);
    $('#work-account-tenant-field').classList.add('hidden');
    $('#assignment-tenant-field').classList.toggle('hidden', !globalAdmin);
    if (tenantAdmin) {
      loadWorkAccounts();
      loadEmailProviders();
    }
    if (globalAdmin) {
      loadTenants().then(loadAssignmentContext);
      loadWorkItemDefinitions();
    } else loadAssignmentContext();
  } else $('#admin-panel').classList.add('hidden');
}

function isAdmin() { return ['GLOBAL_ADMIN', 'ADMIN'].includes(currentUser.role); }

function showWorkspaceScreen(screen) {
  const administration = screen === 'administration' && isAdmin();
  $('#work-items-screen').classList.toggle('hidden', administration);
  $('#administration-screen').classList.toggle('hidden', !administration);
  $('#navigate-work-items').classList.toggle('active', !administration);
  $('#navigate-administration').classList.toggle('active', administration);
  $('#page-eyebrow').textContent = administration ? 'USER MANAGEMENT' : 'WORK ITEM MANAGEMENT';
  $('#page-title').textContent = administration ? 'Workspace administration' : 'Work items';
}

function renderProfile() {
  $('#workspace-label').textContent = `${currentUser.companyCode} workspace`;
  $('#role-badge').textContent = currentUser.role.replaceAll('_', ' ');
  const displayName = [currentUser.firstName, currentUser.lastName].filter(Boolean).join(' ')
    || currentUser.username;
  $('#profile-username').textContent = displayName;
  $('#profile-company').textContent = `${currentUser.username} · ${currentUser.companyCode}`;
  $('#profile-role').textContent = currentUser.role.replaceAll('_', ' ');
  $('#avatar').textContent = initials(displayName);
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
  $('#work-item-transitions').value = definition?.transitions.map(edge =>
    `${edge.fromStatus} | ${edge.toStatus} | ${edge.label}`).join('\n') ||
    'AWAITING_FIRST_RESPONSE | IN_PROGRESS | Start work\n' +
    'IN_PROGRESS | AWAITING_CUSTOMER_RESPONSE | Request customer response\n' +
    'IN_PROGRESS | COMPLETED | Complete\n' +
    'IN_PROGRESS | CANCELLED | Cancel\n' +
    'AWAITING_CUSTOMER_RESPONSE | READY_TO_PICK | Customer responded\n' +
    'READY_TO_PICK | IN_PROGRESS | Start work';
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
      .map(user => ({
        value:user.id,
        label:`${[user.firstName, user.lastName].filter(Boolean).join(' ') || user.username} · ${user.username} · ${user.role.replaceAll('_', ' ')}`
      })));
    updateAssignmentTargets();
    renderAssignments(assignments);
  } catch (cause) { notice(cause.message, true); }
}

function updateAssignmentTargets() {
  const selectedDefinitionId = $('#assignment-definition').value;
  const definition = assignmentWorkItems.find(item => String(item.id) === selectedDefinitionId);
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
  const workflowGroups = new Map();
  assignments.forEach(assignment => {
    const workflowKey = String(assignment.definitionId);
    if (!workflowGroups.has(workflowKey)) workflowGroups.set(workflowKey, []);
    workflowGroups.get(workflowKey).push(assignment);
  });
  root.replaceChildren(...[...workflowGroups.values()].map(renderAssignmentWorkflowGroup));
}

function renderAssignmentWorkflowGroup(assignments) {
  const definition = assignmentWorkItems.find(item => String(item.id) === String(assignments[0].definitionId));
  const group = document.createElement('section'); group.className = 'assignment-workflow-group';
  const heading = document.createElement('div'); heading.className = 'assignment-workflow-heading';
  const title = document.createElement('h3');
  title.textContent = definition
    ? `${definition.displayName} (${definition.type})`
    : assignments[0].workItemType;
  const count = document.createElement('span');
  count.textContent = `${assignments.length} assignment${assignments.length === 1 ? '' : 's'}`;
  heading.append(title, count);

  const targetGroups = new Map();
  assignments.forEach(assignment => {
    const targetId = assignment.assignmentType === 'STATUS'
      ? assignment.statusId : assignment.transitionId;
    const key = `${assignment.assignmentType}:${targetId}`;
    if (!targetGroups.has(key)) targetGroups.set(key, []);
    targetGroups.get(key).push(assignment);
  });
  const targets = document.createElement('div'); targets.className = 'assignment-target-list';
  targets.append(...[...targetGroups.values()].map(grouped => renderAssignmentTarget(grouped, definition)));
  group.append(heading, targets);
  return group;
}

function renderAssignmentTarget(assignments, definition) {
  const assignment = assignments[0];
  const statusAssignment = assignment.assignmentType === 'STATUS';
  const status = definition?.statuses?.find(item => String(item.id) === String(assignment.statusId));
  const transition = definition?.transitions?.find(item => String(item.id) === String(assignment.transitionId));
  const row = document.createElement('article'); row.className = 'assignment-target-row';
  const target = document.createElement('div'); target.className = 'assignment-target-name';
  const kind = document.createElement('span'); kind.className = 'assignment-kind';
  kind.textContent = statusAssignment ? 'STATUS' : 'TRANSITION';
  const label = document.createElement('strong');
  label.textContent = statusAssignment
    ? (status ? `${status.displayName} (${status.code})` : assignment.statusCode)
    : (transition
      ? `${transition.label}: ${transition.fromStatus} → ${transition.toStatus}`
      : assignment.transitionLabel);
  target.append(kind, label);

  const users = document.createElement('div'); users.className = 'assignment-users';
  users.append(...assignments.map(item => {
    const user = document.createElement('span'); user.className = 'assignment-user';
    const avatar = document.createElement('span'); avatar.className = 'assignment-user-avatar';
    avatar.textContent = initials(item.username);
    const username = document.createElement('span'); username.textContent = item.username;
    const remove = document.createElement('button');
    remove.type = 'button'; remove.textContent = '×';
    remove.setAttribute('aria-label', `Remove ${item.username} from this ${item.assignmentType.toLowerCase()}`);
    remove.onclick = () => removeAssignment(item);
    user.append(avatar, username, remove);
    return user;
  }));
  row.append(target, users);
  return row;
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
    const summaryParams = new URLSearchParams();
    params.set('queueScope', workQueueScope);
    summaryParams.set('queueScope', workQueueScope);
    const type = $('#my-work-type').value.trim();
    const status = $('#my-work-status').value.trim();
    const email = $('#my-work-email').value.trim();
    if (type) {
      params.set('workItemType', type);
      summaryParams.set('workItemType', type);
    }
    if (status) params.set('status', status);
    if (email) {
      params.set('email', email);
      summaryParams.set('email', email);
    }
    if ($('#my-work-terminal').checked) {
      params.set('includeTerminal', 'true');
      summaryParams.set('includeTerminal', 'true');
    }
    params.set('page', String(myWorkPage));
    params.set('size', $('#my-work-size').value);
    params.set('sortBy', $('#my-work-sort').value);
    params.set('sortDirection', $('#my-work-direction').value);
    const [result, statusSummary] = await Promise.all([
      api(`/api/v1/work-items/my-work?${params}`),
      api(`/api/v1/work-items/my-work/status-summary?${summaryParams}`)
    ]);
    if (result.totalPages > 0 && myWorkPage >= result.totalPages) {
      myWorkPage = result.totalPages - 1;
      return loadMyWork();
    }
    renderWorkStatusSummary(statusSummary, status);
    const executions = result.items;
    const root = $('#my-work');
    updateMyWorkPagination(result);
    if (!executions.length) {
      root.innerHTML = `<div class="empty">${workQueueScope === 'MY'
        ? 'No tasks are currently assigned to you.'
        : 'No other available or assigned tasks were found.'}</div>`;
      return;
    }
    root.replaceChildren(...executions.map(execution => {
      const row = document.createElement('article');
      row.className = 'workflow-row';
      const number = documentNode(
        'div',
        'workflow-cell workflow-number',
        String(execution.workItemNumber));
      const subject = documentNode('div', 'workflow-cell workflow-subject');
      subject.append(
        documentNode('strong', '', abbreviatedSubject(execution.emailSubject)),
        documentNode('small', '', execution.emailSubject ? 'Email subject' : 'No email subject')
      );
      const sender = documentNode('div', 'workflow-cell workflow-sender');
      sender.append(
        documentNode('strong', '', execution.emailSender || '—'),
        documentNode('small', '', execution.emailSender ? 'Email sender' : 'No email sender')
      );
      const typeCell = documentNode('div', 'workflow-cell workflow-type');
      typeCell.append(
        documentNode('strong', '', execution.workItemDisplayName),
        documentNode('small', '', execution.workItemType)
      );
      const state = documentNode('div', 'workflow-cell workflow-state');
      state.append(documentNode('span', '', execution.currentStatusDisplayName));
      state.append(documentNode(
        'small',
        '',
        `${execution.assignedUsername
          ? `Worked by ${execution.assignedUsername}`
          : 'Unassigned'}${execution.dataMigrated ? ' · Archived' : ''}`));
      const updated = documentNode(
        'div',
        'workflow-cell workflow-updated',
        new Date(execution.updatedAt).toLocaleString());
      const actions = document.createElement('div'); actions.className = 'workflow-actions';
      const open = document.createElement('button');
      open.className = 'workflow-open-icon';
      open.textContent = '→';
      const openLabel = execution.conversationId ? 'Open email and work item' : 'Open work item';
      open.title = openLabel;
      open.setAttribute('aria-label', openLabel);
      open.onclick = () => pickAndOpenWorkItem(execution, open);
      actions.append(open);

      const actionMenu = document.createElement('details');
      actionMenu.className = 'row-action-menu';
      const actionMenuToggle = document.createElement('summary');
      actionMenuToggle.textContent = '⋯';
      actionMenuToggle.title = 'Work-item actions';
      actionMenuToggle.setAttribute('aria-label', 'Work-item actions');
      const actionMenuItems = document.createElement('div');
      actionMenuItems.className = 'row-action-menu-items';
      execution.allowedTransitions.forEach(transition => {
        const quick = document.createElement('button');
        quick.className = 'quick-transition';
        quick.textContent = `${transition.label} → ${transition.toStatus}`;
        quick.title = 'Apply this transition without opening the email';
        quick.onclick = () => {
          actionMenu.open = false;
          performTransition(execution.id, transition.id, quick);
        };
        actionMenuItems.append(quick);
      });
      if (!execution.allowedTransitions.length) {
        actionMenuItems.append(documentNode('span', 'row-action-menu-empty', 'No actions available'));
      }
      actionMenu.append(actionMenuToggle, actionMenuItems);
      actionMenu.addEventListener('toggle', () => {
        if (!actionMenu.open) return;
        document.querySelectorAll('.row-action-menu[open]').forEach(menu => {
          if (menu !== actionMenu) menu.open = false;
        });
      });
      actions.append(actionMenu);
      row.append(number, subject, sender, typeCell, state, updated, actions);
      return row;
    }));
  } catch (cause) { notice(cause.message, true); }
}

async function loadMyWorkTypeOptions() {
  const select = $('#my-work-type');
  const selectedType = select.value;
  const definitions = await api('/api/v1/work-items/effective');
  effectiveWorkItems = definitions.filter(definition => definition.active);
  const types = [...new Map(
    effectiveWorkItems
      .map(definition => [
        definition.type,
        definition.displayName || definition.type
      ])
  ).entries()].sort((left, right) => left[1].localeCompare(right[1]));
  select.replaceChildren(
    new Option('All work-item types', ''),
    ...types.map(([type, displayName]) =>
      new Option(`${displayName} (${type})`, type))
  );
  if (types.some(([type]) => type === selectedType)) {
    select.value = selectedType;
  }
}

function abbreviatedSubject(subject) {
  if (!subject) return '—';
  const value = subject.trim();
  return value.length > 60 ? `${value.slice(0, 60)}…` : value;
}

function renderWorkStatusSummary(statuses, selectedStatus) {
  const root = $('#my-work-status-summary');
  const total = statuses.reduce((sum, status) => sum + status.count, 0);
  const options = [{status:'', displayName:'All', count:total}, ...statuses];
  root.replaceChildren(...options.map(option => {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'work-status-tile';
    button.classList.toggle(
      'active',
      (selectedStatus || '').toLowerCase() === option.status.toLowerCase());
    button.append(
      documentNode('span', 'work-status-count', option.count),
      documentNode('span', 'work-status-name', option.displayName)
    );
    button.onclick = () => {
      $('#my-work-status').value = option.status;
      myWorkPage = 0;
      loadMyWork();
    };
    return button;
  }));
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
    activeWorkItemId = execution.id;
    const readOnly = Boolean(detail.readOnly);
    $('#work-detail-readonly').classList.toggle('hidden', !readOnly);
    $('#work-detail-readonly').textContent = readOnly
      ? (execution.dataMigrated
        ? 'Read-only: detailed data is being served from the completed work-item archive.'
        : execution.terminal
          ? 'Read-only: this work item is in a terminal status.'
          : `Read-only: this task is being worked by ${execution.assignedUsername}.`)
      : '';
    $('#work-detail-title').textContent = `${execution.workItemNumber} · ${conversation?.subject || execution.workItemDisplayName}`;
    const metadata = [];
    metadata.push(`Work item number: ${execution.workItemNumber}`);
    metadata.push(`Account: ${execution.emailId}`);
    metadata.push(`Work item: ${execution.workItemDisplayName}`);
    metadata.push(`Status: ${execution.currentStatusDisplayName}`);
    if (execution.dataMigrated) metadata.push('Data source: Archived JSON');
    if (conversation?.sender) metadata.push(`From: ${conversation.sender}`);
    if (conversation?.recipients) metadata.push(`To: ${conversation.recipients}`);
    if (conversation?.sentAt) metadata.push(`Sent: ${new Date(conversation.sentAt).toLocaleString()}`);
    $('#work-detail-meta').replaceChildren(...metadata.map(value => {
      const line = document.createElement('div'); line.textContent = value; return line;
    }));

    const communications = detail.communications?.length
      ? detail.communications
      : (conversation ? [conversation] : []);
    const documents = detail.documents || [];
    renderCommunications(communications, documents, execution.id);

    renderWorkItemDocuments(execution.id, documents, communications);
    renderInternalNotes(detail.internalNotes || []);
    $('#work-note-content').value = '';
    $('#work-note-error').classList.add('hidden');
    $('#work-reply-editor').innerHTML = '';
    $('#work-reply-error').classList.add('hidden');
    $('#work-document-error').classList.add('hidden');
    $('#work-document-file').value = '';
    $('#work-reply-files').value = '';
    $('#work-reply-section').classList.toggle('hidden', readOnly || !conversation?.sender);
    $('#work-document-form').classList.toggle('hidden', readOnly);
    $('#work-note-form').classList.toggle('hidden', readOnly);
    if (!effectiveWorkItems.length) {
      effectiveWorkItems = (await api('/api/v1/work-items/effective'))
        .filter(definition => definition.active);
    }
    const canChangeType = !readOnly
      && !execution.terminal
      && execution.allowedTransitions.length > 0;
    $('#work-detail-type-section').classList.toggle('hidden', !canChangeType);
    $('#work-detail-type-error').classList.add('hidden');
    $('#work-detail-type').replaceChildren(...effectiveWorkItems
      .map(definition => new Option(
        `${definition.displayName} (${definition.type})`,
        definition.id)));
    $('#work-detail-type').value = String(execution.definitionId);

    const actions = $('#work-detail-actions');
    actions.replaceChildren(...(readOnly ? [] : execution.allowedTransitions).map(transition => {
      const action = document.createElement('button');
      action.textContent = `${transition.label} → ${transition.toStatus}`;
      action.onclick = () => performTransition(execution.id, transition.id, action);
      return action;
    }));
    if (readOnly || !execution.allowedTransitions.length) {
      const complete = document.createElement('span');
      complete.className = 'terminal-note';
      complete.textContent = readOnly
        ? 'Open in read-only mode. Reassign the task to yourself to take action.'
        : (execution.terminal ? 'This work item is completed.' : 'No assigned decision is available.');
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

async function pickAndOpenWorkItem(execution, button) {
  if (execution.terminal) {
    return openWorkItem(execution.id, button);
  }
  button.disabled = true;
  try {
    if (!execution.assignedToCurrentUser) {
      try {
        await api(`/api/v1/work-items/executions/${execution.id}/pick`, {
          method: 'POST'
        });
      } catch (cause) {
        if (cause.status !== 409) throw cause;
        const takeOver = window.confirm(
          `${cause.message}.\n\nSelect OK to assign it to yourself, or Cancel to open it read-only.`);
        if (!takeOver) {
          button.disabled = false;
          return openWorkItem(execution.id, button);
        }
        await api(`/api/v1/work-items/executions/${execution.id}/pick?force=true`, {
          method: 'POST'
        });
      }
      await loadMyWork();
    }
    button.disabled = false;
    return openWorkItem(execution.id, button);
  } catch (cause) {
    notice(cause.message, true);
    button.disabled = false;
  }
}

function renderCommunications(communications, documents = [], executionId = activeWorkItemId) {
  const root = $('#work-communications');
  if (!communications.length) {
    root.innerHTML = '<div class="empty">No email communication is linked to this work item.</div>';
    return;
  }
  root.replaceChildren(...communications.map((communication, index) => {
    const card = documentNode('details', 'communication-card');
    card.open = index === communications.length - 1;
    const heading = documentNode('summary', 'communication-heading');
    const description = documentNode('div');
    description.append(
      documentNode('strong', '', communication.subject || '(No subject)'),
      documentNode('small', '', `${communication.sender || 'Unknown sender'} → ${communication.recipients || 'Unknown recipient'}${communication.sentAt ? ` · ${new Date(communication.sentAt).toLocaleString()}` : ''}`)
    );
    if (communication.contentSource) {
      description.append(documentNode(
        'small',
        `communication-source${communication.staleFallback ? ' stale' : ''}`,
        communication.contentSource === 'CONVERSATION_TABLE'
          ? 'Loaded from conversation store'
          : communication.contentSource === 'PROVIDER'
            ? 'Loaded from email provider'
            : communication.contentSource === 'FALLBACK_CACHE'
              ? 'Conversation store and provider unavailable · showing saved fallback'
              : communication.contentSource === 'METADATA_ONLY'
                ? 'Content unavailable · metadata only'
                : 'Loaded from short-lived cache'));
    }
    const direction = documentNode('span', `communication-direction ${communication.direction?.toLowerCase() || ''}`, communication.direction || 'EMAIL');
    heading.append(description, direction);
    const frame = documentNode('iframe', 'communication-frame');
    frame.setAttribute('sandbox', '');
    frame.title = `${communication.direction || 'Email'} communication`;
    const renderedHtml = communication.contentHtml
      || `<pre style="white-space:pre-wrap;font:14px/1.6 system-ui;margin:0">${escapeHtml(communication.contentText || communication.snippet || 'No email body is available.')}</pre>`;
    frame.srcdoc = `<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'"><meta name="color-scheme" content="light">${renderedHtml}`;
    const attachments = documents.filter(
      document => document.sourceConversationId === communication.id);
    card.append(heading);
    if (attachments.length) {
      const attachmentSection = documentNode('section', 'communication-attachments');
      attachmentSection.append(documentNode(
        'div',
        'communication-attachments-title',
        `Attachments (${attachments.length})`));
      attachments.forEach(document => {
        const link = documentNode('a', 'communication-attachment');
        link.href = `/api/v1/work-items/executions/${executionId}/documents/${document.id}`;
        link.append(
          documentNode('strong', '', document.filename),
          documentNode(
            'span',
            '',
            `${document.contentType || 'File'} · ${formatBytes(document.size)} · ${document.origin}`)
        );
        attachmentSection.append(link);
      });
      card.append(attachmentSection);
    }
    card.append(frame);
    return card;
  }));
}

function renderWorkItemDocuments(executionId, documents, communications = []) {
  const root = $('#work-detail-documents');
  if (!documents.length) {
    root.innerHTML = '<div class="empty">No attachments</div>';
    return;
  }
  const conversationsById = new Map(
    communications.map(communication => [communication.id, communication]));
  const origins = ['INBOUND', 'INTERNAL', 'OUTBOUND'];
  root.replaceChildren(...origins.flatMap(origin => {
    const matching = documents.filter(document => document.origin === origin);
    if (!matching.length) return [];
    const group = documentNode('section', 'document-group');
    group.append(documentNode('div', 'document-group-title', origin));
    matching.forEach(document => {
      const row = documentNode('div', 'document-item');
      const select = documentNode('input');
      select.type = 'checkbox';
      select.className = 'reply-document';
      select.value = document.id;
      select.title = 'Attach this document to the next email reply';
      select.onchange = updateReplyAttachmentSummary;
      const link = documentNode('a');
      link.href = `/api/v1/work-items/executions/${executionId}/documents/${document.id}`;
      link.append(
        documentNode('strong', '', document.filename),
        documentNode('span', '', documentDescription(
          document, conversationsById.get(document.sourceConversationId)))
      );
      row.append(select, link);
      group.append(row);
    });
    return [group];
  }));
  updateReplyAttachmentSummary();
}

function documentDescription(document, conversation) {
  const details = [document.contentType || 'File', formatBytes(document.size)];
  if (document.uploadedByUsername) details.push(document.uploadedByUsername);
  if (conversation) {
    details.push(`Email: ${conversation.subject || '(No subject)'}`);
  } else if (document.sourceConversationId) {
    details.push('Linked email');
  } else {
    details.push('Internal document');
  }
  return details.join(' · ');
}

function updateReplyAttachmentSummary() {
  const selectedDocuments = document.querySelectorAll('.reply-document:checked').length;
  const selectedFiles = $('#work-reply-files')?.files?.length || 0;
  const total = selectedDocuments + selectedFiles;
  $('#work-reply-attachments').textContent = total
    ? `${total} document(s) will be attached to this reply.`
    : 'Select documents in the sidebar to attach them to this reply.';
}

function renderInternalNotes(notes) {
  const root = $('#work-detail-notes');
  if (!notes.length) {
    root.innerHTML = '<div class="empty">No internal notes</div>';
    return;
  }
  root.replaceChildren(...notes.map(note => {
    const item = documentNode('article', 'internal-note');
    item.append(
      documentNode('p', '', note.content),
      documentNode('small', '', `${note.authorUsername} · ${new Date(note.createdAt).toLocaleString()}`)
    );
    return item;
  }));
}

function documentNode(tag, className, text) {
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (text !== undefined) element.textContent = text;
  return element;
}

function formatBytes(size) {
  if (!Number.isFinite(size) || size < 1024) return `${size || 0} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / (1024 * 1024)).toFixed(1)} MB`;
}

async function performTransition(executionId, transitionId, button) {
  button.disabled = true;
  try {
    await api(`/api/v1/work-items/executions/${executionId}/transitions/${transitionId}`, {method:'POST'});
    if ($('#work-item-detail').open) $('#work-item-detail').close();
    notice('Work item activity completed.'); await loadMyWork();
  } catch (cause) { notice(cause.message, true); button.disabled = false; }
}

$('#work-detail-type-form').onsubmit = async event => {
  event.preventDefault();
  const button = event.submitter;
  button.disabled = true;
  $('#work-detail-type-error').classList.add('hidden');
  try {
    await api(`/api/v1/work-items/executions/${activeWorkItemId}/type`, {
      method: 'PUT',
      body: JSON.stringify({
        definitionId: $('#work-detail-type').value
      })
    });
    $('#work-item-detail').close();
    notice('Work item type updated. Workflow actions and access were recalculated.');
    await loadMyWork();
  } catch (cause) {
    formError('#work-detail-type-error', cause.message);
  } finally {
    button.disabled = false;
  }
};

$('#work-note-form').onsubmit = async event => {
  event.preventDefault();
  const button = event.submitter;
  button.disabled = true;
  $('#work-note-error').classList.add('hidden');
  try {
    await api(`/api/v1/work-items/executions/${activeWorkItemId}/notes`, {
      method: 'POST',
      body: JSON.stringify({content: $('#work-note-content').value})
    });
    const detail = await api(`/api/v1/work-items/executions/${activeWorkItemId}`);
    renderInternalNotes(detail.internalNotes || []);
    $('#work-note-content').value = '';
    notice('Internal note added.');
  } catch (cause) {
    formError('#work-note-error', cause.message);
  } finally {
    button.disabled = false;
  }
};

$('#work-document-form').onsubmit = async event => {
  event.preventDefault();
  const button = event.submitter;
  const file = $('#work-document-file').files[0];
  if (!file) return;
  button.disabled = true;
  $('#work-document-error').classList.add('hidden');
  try {
    await uploadWorkItemDocument(activeWorkItemId, file);
    const detail = await api(`/api/v1/work-items/executions/${activeWorkItemId}`);
    renderWorkItemDocuments(
      activeWorkItemId,
      detail.documents || [],
      detail.communications || []);
    $('#work-document-file').value = '';
    notice('Internal document uploaded.');
  } catch (cause) {
    formError('#work-document-error', cause.message);
  } finally {
    button.disabled = false;
  }
};

async function uploadWorkItemDocument(executionId, file) {
  const body = new FormData();
  body.append('file', file, file.name);
  const response = await fetch(`/api/v1/work-items/executions/${executionId}/documents`, {
    method: 'POST',
    credentials: 'same-origin',
    body
  });
  if (!response.ok) {
    let error = {};
    try { error = await response.json(); } catch (_) {}
    throw new Error(error.error || `Upload failed (${response.status})`);
  }
  return response.json();
}

document.querySelectorAll('.editor-toolbar [data-command]').forEach(button => {
  button.onclick = () => {
    const command = button.dataset.command;
    let value = null;
    if (command === 'createLink') {
      value = window.prompt('Enter the link URL');
      if (!value) return;
      try {
        const url = new URL(value, window.location.origin);
        if (!['http:', 'https:', 'mailto:'].includes(url.protocol)) {
          throw new Error();
        }
        value = url.href;
      } catch (_) {
        formError('#work-reply-error', 'Use a valid HTTP, HTTPS, or mailto link.');
        return;
      }
    }
    $('#work-reply-editor').focus();
    document.execCommand(command, false, value);
  };
});

$('#send-work-reply').onclick = async event => {
  const button = event.currentTarget;
  const editor = $('#work-reply-editor');
  if (!editor.textContent.trim() && !editor.querySelector('img')) {
    formError('#work-reply-error', 'Write a reply before sending.');
    return;
  }
  button.disabled = true;
  $('#work-reply-error').classList.add('hidden');
  try {
    const selectedDocumentIds = [...document.querySelectorAll('.reply-document:checked')]
      .map(input => input.value);
    const newFiles = [...$('#work-reply-files').files];
    if (selectedDocumentIds.length + newFiles.length > 20) {
      throw new Error('At most 20 documents can be attached to one reply.');
    }
    const uploaded = await Promise.all(
      newFiles.map(file => uploadWorkItemDocument(activeWorkItemId, file)));
    await api(`/api/v1/work-item-replies/${activeWorkItemId}`, {
      method: 'POST',
      body: JSON.stringify({
        requestId: crypto.randomUUID(),
        htmlBody: editor.innerHTML,
        documentIds: [...new Set([
          ...selectedDocumentIds,
          ...uploaded.map(document => document.id)
        ])]
      })
    });
    editor.innerHTML = '';
    $('#work-reply-files').value = '';
    const detail = await api(`/api/v1/work-items/executions/${activeWorkItemId}`);
    renderCommunications(
      detail.communications || [],
      detail.documents || [],
      activeWorkItemId);
    renderWorkItemDocuments(
      activeWorkItemId,
      detail.documents || [],
      detail.communications || []);
    notice('Reply sent and recorded in the conversation.');
  } catch (cause) {
    formError('#work-reply-error', cause.message);
  } finally {
    button.disabled = false;
  }
};

$('#work-reply-files').onchange = updateReplyAttachmentSummary;

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
  const tenantId = currentUser.tenantId;
  await loadEffectiveWorkItems(tenantId);
  $('#work-account-item').value = account?.workItemId || availableWorkItems[0]?.id || '';
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
      const displayName = [user.firstName, user.lastName].filter(Boolean).join(' ') || user.username;
      const avatar = document.createElement('span'); avatar.className = 'mini-avatar'; avatar.textContent = initials(displayName);
      const copy = document.createElement('div');
      const name = document.createElement('h4'); name.textContent = displayName;
      const company = document.createElement('p'); company.textContent = `${user.username} · ${user.companyCode}`;
      copy.append(name, company); identity.append(avatar, copy);
      const actions = document.createElement('div'); actions.className = 'user-actions';
      if (user.mustChangePassword) { const force = document.createElement('span'); force.className = 'force'; force.textContent = 'PASSWORD CHANGE DUE'; actions.append(force); }
      const role = document.createElement('span'); role.className = 'user-role'; role.textContent = user.role.replaceAll('_', ' '); actions.append(role);
      const status = document.createElement('span');
      status.className = `tenant-status${user.active ? ' enabled' : ''}`;
      status.textContent = user.active ? 'ACTIVE' : 'INACTIVE';
      actions.append(status);
      if (canEditUser(user)) {
        const edit = document.createElement('button');
        edit.className = 'tenant-edit';
        edit.textContent = 'Edit';
        edit.onclick = () => openUserEditForm(user);
        actions.append(edit);
      }
      if (canReset(user)) {
        const reset = document.createElement('button'); reset.className = 'reset'; reset.textContent = 'Reset password';
        reset.onclick = () => resetPassword(user); actions.append(reset);
      }
      row.append(identity, actions); return row;
    }));
  } catch (cause) { notice(cause.message, true); }
}

function canEditUser(user) {
  if (user.id === currentUser.id) return true;
  return currentUser.role === 'GLOBAL_ADMIN'
    || !['GLOBAL_ADMIN', 'ADMIN'].includes(user.role);
}

function canReset(user) {
  if (user.id === currentUser.id) return false;
  return currentUser.role === 'GLOBAL_ADMIN' || !['GLOBAL_ADMIN', 'ADMIN'].includes(user.role);
}

function openUserEditForm(user) {
  const ownAccount = user.id === currentUser.id;
  const roles = ownAccount
    ? [user.role]
    : currentUser.role === 'GLOBAL_ADMIN'
      ? ['GLOBAL_ADMIN', 'ADMIN', 'PROCESSOR', 'BASE_USER']
      : ['PROCESSOR', 'BASE_USER'];
  $('#edit-role').replaceChildren(...roles.map(
    role => new Option(role.replaceAll('_', ' '), role)));
  $('#edit-user-id').value = user.id;
  $('#edit-company-code').value = user.companyCode;
  $('#edit-username').value = user.username;
  $('#edit-first-name').value = user.firstName;
  $('#edit-last-name').value = user.lastName;
  $('#edit-role').value = user.role;
  $('#edit-role').disabled = ownAccount;
  $('#edit-user-active').checked = user.active;
  $('#edit-user-active').disabled = ownAccount;
  $('#edit-user-error').classList.add('hidden');
  $('#create-user-form').classList.add('hidden');
  $('#edit-user-form').classList.remove('hidden');
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
    await api('/api/v1/users', {method:'POST', body:JSON.stringify({
      companyCode:$('#new-company-code').value,
      username:$('#new-username').value,
      firstName:$('#new-first-name').value,
      lastName:$('#new-last-name').value,
      role:$('#new-role').value,
      temporaryPassword:$('#new-temporary-password').value
    })});
    event.target.reset(); configureCreateForm(); event.target.classList.add('hidden'); notice('User created with a required first-login password change.');
    await Promise.all([loadUsers(), loadAssignmentContext()]);
  } catch (cause) { formError('#create-error', cause.message); }
  finally { button.disabled = false; }
};

$('#edit-user-form').onsubmit = async event => {
  event.preventDefault();
  const button = event.submitter;
  button.disabled = true;
  $('#edit-user-error').classList.add('hidden');
  const userId = $('#edit-user-id').value;
  try {
    const updated = await api(`/api/v1/users/${userId}`, {
      method: 'PUT',
      body: JSON.stringify({
        username: $('#edit-username').value,
        firstName: $('#edit-first-name').value,
        lastName: $('#edit-last-name').value,
        role: $('#edit-role').value,
        active: $('#edit-user-active').checked
      })
    });
    if (updated.id === currentUser.id) {
      currentUser = {...currentUser, ...updated};
      $('#profile-username').textContent = currentUser.username;
      $('#avatar').textContent = initials(
        [currentUser.firstName, currentUser.lastName].filter(Boolean).join(' ')
          || currentUser.username);
    }
    event.target.classList.add('hidden');
    notice(`User ${updated.username} updated.`);
    await Promise.all([loadUsers(), loadAssignmentContext()]);
  } catch (cause) {
    formError('#edit-user-error', cause.message);
  } finally {
    button.disabled = false;
  }
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
    await Promise.all([
      loadWorkItemDefinitions(),
      loadWorkAccounts(),
      loadMyWorkTypeOptions()
    ]);
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
    tenantId: currentUser.tenantId,
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

$('#show-create').onclick = () => {
  $('#edit-user-form').classList.add('hidden');
  $('#create-user-form').classList.remove('hidden');
};
$('#cancel-create').onclick = () => $('#create-user-form').classList.add('hidden');
$('#cancel-edit-user').onclick = () => $('#edit-user-form').classList.add('hidden');
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
$('#my-tasks-tab').onclick = () => setWorkQueueScope('MY');
$('#other-tasks-tab').onclick = () => setWorkQueueScope('OTHER');
$('#navigate-work-items').onclick = () => {
  showWorkspaceScreen('work-items');
  loadMyWork();
};
$('#navigate-administration').onclick = () => showWorkspaceScreen('administration');
$('#my-work-filters').onsubmit = event => { event.preventDefault(); myWorkPage = 0; loadMyWork(); };
$('#clear-my-work-filters').onclick = () => {
  $('#my-work-filters').reset();
  myWorkPage = 0;
  loadMyWork();
};
$('#my-work-previous').onclick = () => { if (myWorkPage > 0) { myWorkPage--; loadMyWork(); } };
$('#my-work-next').onclick = () => { myWorkPage++; loadMyWork(); };

function setWorkQueueScope(scope) {
  workQueueScope = scope;
  myWorkPage = 0;
  const myTasks = scope === 'MY';
  $('#my-tasks-tab').classList.toggle('active', myTasks);
  $('#my-tasks-tab').setAttribute('aria-selected', String(myTasks));
  $('#other-tasks-tab').classList.toggle('active', !myTasks);
  $('#other-tasks-tab').setAttribute('aria-selected', String(!myTasks));
  loadMyWork();
}
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

const connectButton = document.querySelector('#connect');
const clearButton = document.querySelector('#clear');
const empty = document.querySelector('#empty');
const result = document.querySelector('#result');
const error = document.querySelector('#error');
let oauthPopup;

async function beginAuthorization() {
  setLoading(true);
  showEmpty();
  try {
    const response = await fetch('/api/v1/gmail/authorize', {method: 'POST'});
    if (!response.ok) throw new Error(await readError(response));
    const authorization = await response.json();
    oauthPopup = window.open(authorization.authorizationUrl, 'casiq-google-oauth', 'popup,width=560,height=720');
    if (!oauthPopup) throw new Error('The OAuth popup was blocked. Allow popups for this site and try again.');
    oauthPopup.focus();
  } catch (cause) {
    showError(cause.message);
    setLoading(false);
  }
}

window.addEventListener('message', event => {
  if (event.origin !== window.location.origin || event.data?.type !== 'casiq-google-oauth') return;
  setLoading(false);
  if (event.data.error) showError(event.data.error);
  else showTokens(event.data.tokens);
});

function showTokens(tokens) {
  empty.classList.add('hidden'); error.classList.add('hidden'); result.classList.remove('hidden');
  document.querySelector('#access-token').textContent = tokens.accessToken || 'Not returned';
  document.querySelector('#refresh-token').textContent = tokens.refreshToken || 'Not returned by Google';
  document.querySelector('#token-type').textContent = tokens.tokenType || '—';
  document.querySelector('#expires-at').textContent = tokens.expiresAt ? new Date(tokens.expiresAt).toLocaleString() : '—';
  document.querySelector('#scope').textContent = tokens.scope || '—';
  clearButton.disabled = false;
}

function showError(message) {
  empty.classList.add('hidden'); result.classList.add('hidden'); error.classList.remove('hidden');
  document.querySelector('#error-message').textContent = message;
  clearButton.disabled = false;
}

function showEmpty() {
  result.classList.add('hidden'); error.classList.add('hidden'); empty.classList.remove('hidden');
  clearButton.disabled = true;
}

function setLoading(loading) {
  connectButton.disabled = loading;
  connectButton.innerHTML = loading ? 'Waiting for Google…' : 'Continue with Google <span>↗</span>';
}

async function readError(response) {
  try { const body = await response.json(); return body.details || body.error || `Request failed (${response.status})`; }
  catch (_) { return `Request failed (${response.status})`; }
}

document.querySelectorAll('[data-copy]').forEach(button => button.addEventListener('click', async () => {
  const value = document.querySelector(`#${button.dataset.copy}`).textContent;
  await navigator.clipboard.writeText(value);
  const label = button.textContent; button.textContent = 'Copied';
  setTimeout(() => button.textContent = label, 1200);
}));

connectButton.addEventListener('click', beginAuthorization);
clearButton.addEventListener('click', () => { showEmpty(); setLoading(false); });

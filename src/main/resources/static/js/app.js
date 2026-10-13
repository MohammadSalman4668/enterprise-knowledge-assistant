// App shell: sign-in, hash router, navigation and the model-mode banner.
// Framework choice: plain ES modules - no build step, nothing hidden, every line explainable.
import { api, ApiError, basicToken, session } from './api.js';
import { h, mount, errorBox, withBusy } from './ui.js';
import { renderAsk } from './views/ask.js';
import { renderSearch } from './views/search.js';
import { renderDocuments, renderDocumentDetail } from './views/documents.js';
import { renderSource } from './views/source.js';
import { renderAnalytics } from './views/analytics.js';

const app = document.getElementById('app');
import { state } from './state.js';

const DEMO_USERS = [
  ['employee', 'Employee', 'public + internal'],
  ['engineer', 'Engineer', '+ confidential runbooks'],
  ['hr', 'HR', '+ restricted HR data'],
  ['admin', 'Admin', 'everything, approvals'],
];

function renderLogin(error) {
  document.getElementById('topbar').hidden = true;
  document.getElementById('mode-banner').hidden = true;
  let username = 'employee';
  const pw = h('input', { type: 'password', id: 'password', autocomplete: 'current-password', required: true });
  const buttons = DEMO_USERS.map(([u, label, hint]) => h('button', {
    type: 'button', class: u === username ? 'selected' : '',
    onclick: (e) => {
      username = u;
      buttons.forEach((b) => b.classList.remove('selected'));
      e.currentTarget.classList.add('selected');
    },
  }, h('strong', {}, label), h('span', { class: 'small muted' }, hint)));
  const submit = h('button', { class: 'primary', type: 'submit' }, 'Sign in');
  const errSlot = h('div', {}, error ? errorBox(error) : null);
  const form = h('form', {
    class: 'card login',
    onsubmit: async (e) => {
      e.preventDefault();
      const tokenValue = basicToken(username, pw.value);
      await withBusy(submit, 'Signing in…', async () => {
        try {
          const me = await api('/api/knowledge/me', { auth: tokenValue });
          session.set({ token: tokenValue, username: me.username });
          await boot();
        } catch (err) {
          mount(errSlot, errorBox(err.status === 401
            ? new ApiError(401, { code: 'UNAUTHENTICATED', message: 'Wrong password for this demo user.' }) : err));
        }
      });
    },
  },
  h('h1', {}, 'Knowledge Assistant'),
  h('p', { class: 'muted' }, 'Sign in as one of the synthetic demo users to see role-based access in action.'),
  h('div', { class: 'demo-users' }, buttons),
  h('div', { class: 'field' }, h('label', { for: 'password' }, 'Demo password'), pw,
    h('div', { class: 'small muted' }, 'Configured as ekg.demo.password in local.properties or EKG_DEMO_PASSWORD; if unset, a generated one is printed in the server log.')),
  errSlot, submit);
  mount(app, form);
  pw.focus();
}

function renderChrome() {
  const { me, status } = state;
  document.getElementById('topbar').hidden = false;
  document.querySelectorAll('[data-admin]').forEach((el) => { el.hidden = !me.admin; });
  mount(document.getElementById('user-box'),
    h('span', {}, me.displayName),
    h('span', { class: 'badge info' }, me.role.toLowerCase()),
    h('button', { class: 'small', onclick: () => { session.clear(); state.me = null; renderLogin(); } }, 'Sign out'));
  const banner = document.getElementById('mode-banner');
  banner.hidden = false;
  banner.className = `mode-banner ${status.simulated ? '' : 'live'}`;
  banner.textContent = status.simulated
    ? '⚠ SIMULATED AGENT - no AI model is called. Answers are extracted verbatim from evidence by deterministic Java code.'
    : `✓ Live model: ${status.model}. Permissions, evidence checks and citation validation are enforced in Java.`;
}

const routes = [
  [/^#\/ask$/, () => renderAsk(app)],
  [/^#\/search$/, () => renderSearch(app)],
  [/^#\/documents$/, () => renderDocuments(app)],
  [/^#\/documents\/(\d+)$/, (m) => renderDocumentDetail(app, Number(m[1]))],
  [/^#\/source\/(\d+)$/, (m, params) => renderSource(app, Number(m[1]), params)],
  [/^#\/analytics$/, () => renderAnalytics(app)],
];

function route() {
  if (!state.me) return;
  const [path, query = ''] = (location.hash || '#/ask').split('?');
  const params = new URLSearchParams(query);
  document.querySelectorAll('#nav a').forEach((a) => {
    a.classList.toggle('active', path.startsWith(`#/${a.dataset.route}`));
  });
  for (const [re, render] of routes) {
    const m = path.match(re);
    if (m) {
      render(m, params);
      app.focus({ preventScroll: true });
      return;
    }
  }
  location.hash = '#/ask';
}

async function boot() {
  if (!session.get()) {
    renderLogin();
    return;
  }
  try {
    const [me, status] = await Promise.all([api('/api/knowledge/me'), api('/api/knowledge/agent/status')]);
    state.me = me;
    state.status = status;
    renderChrome();
    route();
  } catch (err) {
    session.clear();
    renderLogin(err.status === 401 ? null : err);
  }
}

window.addEventListener('hashchange', route);
window.addEventListener('ekg:unauthorized', () => {
  session.clear();
  state.me = null;
  renderLogin(new ApiError(401, { code: 'UNAUTHENTICATED', message: 'Your session has ended. Please sign in again.' }));
});
boot();

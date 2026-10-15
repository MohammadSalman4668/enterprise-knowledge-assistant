// App shell: sign-in, URL router (History API, clean paths like /ask and /login), navigation
// and the model-mode banner.
// Framework choice: plain ES modules - no build step, nothing hidden, every line explainable.
import { api, ApiError, basicToken, session } from './api.js';
import { h, mount, errorBox, withBusy } from './ui.js';
import { renderAsk } from './views/ask.js';
import { renderSearch } from './views/search.js';
import { renderDocuments, renderDocumentDetail } from './views/documents.js';
import { renderSource } from './views/source.js';
import { renderAnalytics } from './views/analytics.js';
import { renderSignup } from './views/signup.js';
import { renderApprovals } from './views/approvals.js';
import { renderProfile } from './views/profile.js';

const app = document.getElementById('app');
import { state } from './state.js';

function renderLogin(error) {
  document.getElementById('topbar').hidden = true;
  document.getElementById('mode-banner').hidden = true;
  const email = h('input', { type: 'email', id: 'email', autocomplete: 'username', required: true, maxlength: 254 });
  const pw = h('input', { type: 'password', id: 'password', autocomplete: 'current-password', required: true });
  const submit = h('button', { class: 'primary', type: 'submit' }, 'Sign in');
  const errSlot = h('div', {}, error ? errorBox(error) : null);
  const form = h('form', {
    class: 'card login',
    onsubmit: async (e) => {
      e.preventDefault();
      const tokenValue = basicToken(email.value.trim().toLowerCase(), pw.value);
      await withBusy(submit, 'Signing in…', async () => {
        try {
          const me = await api('/api/knowledge/me', { auth: tokenValue });
          session.set({ token: tokenValue, username: me.email });
          history.replaceState(null, '', takeReturnPath());
          await boot();
        } catch (err) {
          // Pending / rejected accounts get their own message from the server; anything else is a wrong email or password.
          if (err.code === 'ACCOUNT_PENDING') {
            mount(errSlot, h('div', { class: 'callout warning', role: 'alert' }, `⏳ ${err.message}`));
          } else if (err.code === 'ACCOUNT_REJECTED') {
            mount(errSlot, h('div', { class: 'callout critical', role: 'alert' }, `✕ ${err.message}`));
          } else if (err.status === 401) {
            mount(errSlot, h('div', { class: 'callout critical', role: 'alert' }, 'Incorrect email or password.'));
          } else {
            mount(errSlot, errorBox(err));
          }
        }
      });
    },
  },
  h('h1', {}, 'Knowledge Assistant'),
  h('p', { class: 'muted' }, 'Sign in with your work email.'),
  h('div', { class: 'field' }, h('label', { for: 'email' }, 'Email'), email),
  h('div', { class: 'field' }, h('label', { for: 'password' }, 'Password'), pw),
  errSlot,
  h('div', { class: 'row' }, submit, h('span', { class: 'spacer' }),
    h('span', { class: 'small muted' }, 'No account? ', h('a', { href: '/signup' }, 'Sign up'))));
  mount(app, form);
  email.focus();
}

function renderChrome() {
  const { me, status } = state;
  document.getElementById('topbar').hidden = false;
  document.querySelectorAll('[data-admin]').forEach((el) => { el.hidden = !me.admin; });
  document.querySelectorAll('[data-approver]').forEach((el) => { el.hidden = !me.canApprove; });
  mount(document.getElementById('user-box'),
    h('a', { href: '/profile', class: 'profile-link', title: 'Your profile' }, me.displayName),
    h('span', { class: 'badge info' }, me.role.toLowerCase()),
    h('button', { class: 'small', onclick: () => signOut() }, 'Sign out'));
  const banner = document.getElementById('mode-banner');
  banner.hidden = false;
  banner.className = `mode-banner ${status.simulated ? '' : 'live'}`;
  banner.textContent = status.simulated
    ? '⚠ SIMULATED AGENT - no AI model is called. Answers are extracted verbatim from evidence by deterministic Java code.'
    : `✓ Live model: ${status.model}. Permissions, evidence checks and citation validation are enforced in Java.`;
}

const routes = [
  [/^\/ask$/, () => renderAsk(app)],
  [/^\/search$/, () => renderSearch(app)],
  [/^\/documents$/, () => renderDocuments(app)],
  [/^\/documents\/(\d+)$/, (m) => renderDocumentDetail(app, Number(m[1]))],
  [/^\/source\/(\d+)$/, (m, params) => renderSource(app, Number(m[1]), params)],
  [/^\/analytics$/, () => renderAnalytics(app)],
  [/^\/approvals$/, () => renderApprovals(app)],
  [/^\/profile$/, () => renderProfile(app)],
];
const PUBLIC_PATHS = ['/login', '/signup'];
const isAppPath = (path) => PUBLIC_PATHS.includes(path) || routes.some(([re]) => re.test(path));

/** Changes the URL without reloading and renders the matching screen. */
function navigate(url, { replace = false } = {}) {
  if (replace) history.replaceState(null, '', url); else history.pushState(null, '', url);
  route();
}

function route() {
  if (!state.me) {
    renderSignedOut();
    return;
  }
  const path = location.pathname;
  if (path === '/' || PUBLIC_PATHS.includes(path) || !routes.some(([re]) => re.test(path))) {
    navigate('/ask', { replace: true });
    return;
  }
  const params = new URLSearchParams(location.search);
  document.querySelectorAll('#nav a').forEach((a) => {
    a.classList.toggle('active', path.startsWith(`/${a.dataset.route}`));
  });
  for (const [re, render] of routes) {
    const m = path.match(re);
    if (m) {
      render(m, params);
      app.focus({ preventScroll: true });
      return;
    }
  }
}

const RETURN_KEY = 'ekg.return-path';

/** Shows /login, remembering the page that was asked for so sign-in can return there. */
function renderSignedOut(error) {
  if (location.pathname === '/signup') {
    document.getElementById('topbar').hidden = true;
    document.getElementById('mode-banner').hidden = true;
    renderSignup(app);
    return;
  }
  const wanted = location.pathname + location.search;
  if (location.pathname !== '/login') {
    try {
      if (isAppPath(location.pathname)) sessionStorage.setItem(RETURN_KEY, wanted);
    } catch { /* storage unavailable: sign-in returns to /ask */ }
    history.replaceState(null, '', '/login');
  }
  renderLogin(error ?? takeNotice());
}

function takeReturnPath() {
  try {
    const path = sessionStorage.getItem(RETURN_KEY);
    sessionStorage.removeItem(RETURN_KEY);
    return path && path.startsWith('/') && !path.startsWith('//') ? path : '/ask';
  } catch {
    return '/ask';
  }
}

async function boot() {
  // Old bookmarks such as /#/documents become /documents.
  if (location.hash.startsWith('#/')) history.replaceState(null, '', location.hash.slice(1));
  if (!session.get()) {
    renderSignedOut();
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
    state.me = null;
    renderSignedOut(err.status === 401 ? null : err);
  }
}

const NOTICE_KEY = 'ekg.signin-notice';

/**
 * Ends the session with a full page load of /login, so no screen keeps the previous user's
 * data (last question/answer, filters…). The next user starts on the Ask tab.
 */
function signOut(notice) {
  session.clear();
  try {
    if (notice) sessionStorage.setItem(NOTICE_KEY, notice);
    sessionStorage.removeItem(RETURN_KEY);
  } catch { /* storage unavailable: the notice is simply not shown */ }
  location.replace('/login');
}

function takeNotice() {
  try {
    const notice = sessionStorage.getItem(NOTICE_KEY);
    sessionStorage.removeItem(NOTICE_KEY);
    return notice ? new ApiError(401, { code: 'UNAUTHENTICATED', message: notice }) : null;
  } catch {
    return null;
  }
}

// In-app links switch screens without a page reload; new-tab clicks, downloads and API links behave normally.
document.addEventListener('click', (e) => {
  const a = e.target.closest('a[href]');
  if (!a || e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey
    || a.target || a.hasAttribute('download')) return;
  const url = new URL(a.href, location.href);
  if (url.origin !== location.origin || !isAppPath(url.pathname)) return;
  e.preventDefault();
  if (url.pathname + url.search !== location.pathname + location.search) navigate(url.pathname + url.search);
});
window.addEventListener('popstate', route);
window.addEventListener('ekg:unauthorized', () => signOut('Your session has ended. Please sign in again.'));
boot();

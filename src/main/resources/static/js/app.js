// App shell: sign-in, URL router (History API, clean paths like /ask and /login), navigation
// and the model-mode banner.
// Framework choice: plain ES modules - no build step, nothing hidden, every line explainable.
import { api, ApiError, basicToken, session } from './api.js';
import { h, mount, errorBox, withBusy, icon, avatar, callout } from './ui.js';
import { authShell } from './views/auth-shell.js';
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

const layout = document.getElementById('layout');

/** Sign-in and sign-up pages use the full window, without the sidebar. */
function setSignedOutLayout(signedOut) {
  layout.classList.toggle('signed-out', signedOut);
  setNavOpen(false);
}

function renderLogin(error) {
  setSignedOutLayout(true);
  const email = h('input', { type: 'email', id: 'email', autocomplete: 'username', required: true, maxlength: 254, placeholder: 'name@company.com' });
  const pw = h('input', { type: 'password', id: 'password', autocomplete: 'current-password', required: true });
  const submit = h('button', { class: 'primary lg block', type: 'submit' }, 'Sign in');
  const errSlot = h('div', {}, error ? errorBox(error) : null);
  const form = h('form', {
    class: 'auth-form',
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
            mount(errSlot, callout('warning', err.message));
          } else if (err.code === 'ACCOUNT_REJECTED') {
            mount(errSlot, callout('critical', err.message));
          } else if (err.status === 401) {
            mount(errSlot, callout('critical', 'Incorrect email or password.'));
          } else {
            mount(errSlot, errorBox(err));
          }
        }
      });
    },
  },
  h('h1', {}, 'Sign in'),
  h('p', {}, 'Use your work email to continue.'),
  h('div', { class: 'field' }, h('label', { for: 'email' }, 'Email'), email),
  h('div', { class: 'field' }, h('label', { for: 'password' }, 'Password'), pw),
  errSlot,
  h('div', { class: 'form-actions' }, submit),
  h('p', { class: 'auth-foot' }, 'No account? ', h('a', { href: '/signup' }, 'Request access')));
  mount(app, authShell(form));
  email.focus();
}

const ROLE_NAME = { EMPLOYEE: 'Employee', HR: 'HR', ADMIN: 'Admin' };

function renderChrome() {
  const { me, status } = state;
  setSignedOutLayout(false);
  document.querySelectorAll('[data-admin]').forEach((el) => { el.hidden = !me.admin; });
  document.querySelectorAll('[data-approver]').forEach((el) => { el.hidden = !me.canApprove; });
  document.querySelectorAll('#nav a[data-icon]').forEach((a) => {
    if (!a.querySelector('svg')) a.prepend(icon(a.dataset.icon));
  });
  mount(document.getElementById('user-box'),
    h('a', { href: '/profile', class: 'profile-link', title: 'Your profile' },
      avatar(me.displayName, 'sm'),
      h('span', { class: 'user-meta' }, h('strong', {}, me.displayName), h('span', {}, ROLE_NAME[me.role] || me.role))),
    h('button', { class: 'ghost icon-only small', title: 'Sign out', 'aria-label': 'Sign out', onclick: () => signOut() }, icon('logout')));
  const banner = document.getElementById('mode-banner');
  banner.hidden = false;
  banner.title = status.simulated
    ? 'No AI model is called. Answers are extracted verbatim from evidence by deterministic Java code.'
    : 'Permissions, evidence checks and citation validation are enforced in Java.';
  mount(banner,
    h('span', { class: `status-dot ${status.simulated ? 'sim' : 'live'}`, 'aria-hidden': 'true' }),
    h('span', {},
      h('strong', {}, status.simulated ? 'Simulated agent' : 'Live model'),
      status.simulated ? 'Extractive answers, no AI model' : status.model));
}

// ---------- Mobile navigation (sidebar becomes an off-canvas panel below 960px) ----------
const navToggle = document.getElementById('nav-toggle');
navToggle.append(icon('menu', 18));
function setNavOpen(open) {
  layout.classList.toggle('nav-open', open);
  navToggle.setAttribute('aria-expanded', String(open));
}
navToggle.addEventListener('click', () => setNavOpen(!layout.classList.contains('nav-open')));
document.getElementById('scrim').addEventListener('click', () => setNavOpen(false));
document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && layout.classList.contains('nav-open')) setNavOpen(false); });

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
    const active = path.startsWith(`/${a.dataset.route}`);
    a.classList.toggle('active', active);
    if (active) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
  });
  setNavOpen(false);
  window.scrollTo(0, 0);
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
    setSignedOutLayout(true);
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

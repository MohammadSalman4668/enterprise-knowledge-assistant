// DOM helpers. Everything user- or document-provided is inserted as text nodes
// (never innerHTML), so document content cannot inject markup into the page.

export function h(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === undefined || v === null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k === 'dataset') Object.assign(el.dataset, v);
    else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
    else if (v === true) el.setAttribute(k, '');
    else el.setAttribute(k, v);
  }
  append(el, children);
  return el;
}

function append(el, children) {
  for (const c of children.flat(Infinity)) {
    if (c === undefined || c === null || c === false) continue;
    el.appendChild(c instanceof Node ? c : document.createTextNode(String(c)));
  }
}

export function mount(target, ...children) {
  target.replaceChildren();
  append(target, children);
}

// ---------- Icons: a small fixed set of 24px stroke paths, built with createElementNS ----------
const ICONS = {
  sparkle: 'M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9L12 3z M19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8L19 15z',
  search: 'M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14z M20 20l-4-4',
  file: 'M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8l-5-5z M14 3v5h5 M9 13h6 M9 17h4',
  userCheck: 'M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z M2 21v-1a6 6 0 0 1 6-6h2a6 6 0 0 1 4 1.5 M16 18l2 2 4-4',
  chart: 'M4 20V10 M10 20V4 M16 20v-7 M22 20H2',
  user: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8z M4 21v-1a7 7 0 0 1 7-7h2a7 7 0 0 1 7 7v1',
  logout: 'M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4 M16 17l5-5-5-5 M21 12H9',
  menu: 'M4 6h16 M4 12h16 M4 18h16',
  x: 'M6 6l12 12 M18 6L6 18',
  check: 'M5 12l5 5 9-10',
  checkCircle: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z M8 12l3 3 5-6',
  alert: 'M12 3l10 18H2L12 3z M12 10v4 M12 17.5v.01',
  alertCircle: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z M12 8v5 M12 16.5v.01',
  info: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z M12 11v5 M12 7.5v.01',
  clock: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z M12 7v5l3 2',
  upload: 'M12 16V4 M7 9l5-5 5 5 M4 16v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3',
  arrowLeft: 'M19 12H5 M11 18l-6-6 6-6',
  arrowRight: 'M5 12h14 M13 6l6 6-6 6',
  arrowUp: 'M12 19V5 M6 11l6-6 6 6',
  external: 'M14 4h6v6 M20 4l-9 9 M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5',
  refresh: 'M20 11a8 8 0 0 0-14.9-3.9L4 9 M4 4v5h5 M4 13a8 8 0 0 0 14.9 3.9L20 15 M20 20v-5h-5',
  archive: 'M3 4h18v4H3z M5 8v11a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V8 M10 12h4',
  edit: 'M4 20h4L19 9l-4-4L4 16v4z M13.5 6.5l4 4',
  filter: 'M3 5h18 M6 12h12 M10 19h4',
  shield: 'M12 3l8 3v6c0 4.5-3.4 8.3-8 9-4.6-.7-8-4.5-8-9V6l8-3z M9 12l2 2 4-4',
  layers: 'M12 3l9 5-9 5-9-5 9-5z M3 13l9 5 9-5',
  book: 'M4 5a2 2 0 0 1 2-2h14v16H6a2 2 0 0 0-2 2V5z M4 19a2 2 0 0 0 2 2h14',
  inbox: 'M3 13h5l1 3h6l1-3h5 M5 5h14l2 8v6a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-6l2-8z',
  lock: 'M6 11h12v10H6z M8 11V7a4 4 0 0 1 8 0v4',
};

export function icon(name, size = 16) {
  const NS = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(NS, 'svg');
  for (const [k, v] of Object.entries({
    viewBox: '0 0 24 24', width: size, height: size, fill: 'none', stroke: 'currentColor',
    'stroke-width': 1.8, 'stroke-linecap': 'round', 'stroke-linejoin': 'round', 'aria-hidden': 'true', class: 'icon',
  })) svg.setAttribute(k, v);
  const path = document.createElementNS(NS, 'path');
  path.setAttribute('d', ICONS[name] || ICONS.info);
  svg.appendChild(path);
  return svg;
}

// ---------- Feedback: toasts, loading, errors, empty states ----------
export function toast(message, kind = 'info') {
  const el = h('div', { class: `toast ${kind === 'error' ? 'error' : ''}`, role: kind === 'error' ? 'alert' : 'status' },
    icon(kind === 'error' ? 'alertCircle' : 'checkCircle'), h('div', {}, message));
  document.getElementById('toasts').appendChild(el);
  setTimeout(() => el.remove(), 4500);
}

export const loading = (text = 'Loading…') => h('div', { class: 'loading', role: 'status' }, h('span', { class: 'spinner' }), text);

/** Placeholder lines shown while a list or panel loads. */
export const skeleton = (lines = 4, label = 'Loading…') =>
  h('div', { class: 'skeleton', role: 'status', 'aria-label': label }, Array.from({ length: lines }, () => h('span')));

const CALLOUT_ICON = { info: 'info', good: 'checkCircle', warning: 'clock', serious: 'alert', critical: 'alertCircle' };

/** Alert box with an icon; `kind` is one of info, good, warning, serious, critical. */
export function callout(kind, ...children) {
  return h('div', { class: `callout ${kind}`, role: kind === 'critical' || kind === 'serious' ? 'alert' : 'status' },
    icon(CALLOUT_ICON[kind] || 'info'), h('div', { class: 'callout-body' }, children));
}

export function errorBox(err) {
  const lines = (err.fieldErrors || []).map((f) => h('div', {}, `${f.field}: ${f.message}`));
  return callout('critical',
    h('div', {}, err.message || String(err), err.code ? h('span', { class: 'error-code' }, err.code) : null),
    lines);
}

export const empty = (text) => emptyState(text);

export function emptyState(title, text, iconName = 'inbox') {
  return h('div', { class: 'empty' }, icon(iconName, 28), h('strong', {}, title), text ? h('div', { class: 'small' }, text) : null);
}

// ---------- Layout pieces ----------
/** Page title block: optional back link, title, description, meta line and actions. */
export function pageHeader({ title, description, actions, back, meta } = {}) {
  return h('header', { class: 'page-header' },
    h('div', {},
      back ? h('a', { class: 'back-link', href: back.href, onclick: back.onclick }, icon('arrowLeft', 14), back.label) : null,
      h('h1', {}, title),
      description ? h('p', {}, description) : null,
      meta ? h('div', { class: 'meta-line' }, meta) : null),
    actions ? h('div', { class: 'page-actions' }, actions) : null);
}

export function initials(name = '') {
  const parts = String(name).split(/\s+/).map((p) => p.replace(/[^\p{L}\p{N}]/gu, '')).filter(Boolean);
  return ((parts[0]?.[0] || '?') + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase();
}

export const avatar = (name, size = '') => h('span', { class: `avatar ${size}`, 'aria-hidden': 'true' }, initials(name));

/**
 * Accessible tab strip. items: [{ label, count?, render: () => Node }]; panels render on first open.
 */
export function tabs(items, { label = 'Sections' } = {}) {
  const uid = `tabs-${Math.random().toString(36).slice(2, 8)}`;
  const panels = items.map((_, i) => h('div', { class: 'tab-panel', role: 'tabpanel', id: `${uid}-p${i}`, 'aria-labelledby': `${uid}-t${i}`, tabindex: 0, hidden: i !== 0 }));
  const buttons = items.map((it, i) => h('button', {
    type: 'button', class: 'tab', role: 'tab', id: `${uid}-t${i}`, 'aria-controls': `${uid}-p${i}`,
    'aria-selected': String(i === 0), tabindex: i === 0 ? 0 : -1, onclick: () => select(i),
  }, it.label, it.count !== undefined ? h('span', { class: 'count' }, it.count) : null));
  const rendered = new Set();
  function select(i, focus = false) {
    buttons.forEach((b, j) => { b.setAttribute('aria-selected', String(i === j)); b.tabIndex = i === j ? 0 : -1; });
    panels.forEach((p, j) => { p.hidden = i !== j; });
    if (!rendered.has(i)) { mount(panels[i], items[i].render()); rendered.add(i); }
    if (focus) buttons[i].focus();
  }
  const strip = h('div', { class: 'tabs', role: 'tablist', 'aria-label': label,
    onkeydown: (e) => {
      const cur = buttons.findIndex((b) => b.getAttribute('aria-selected') === 'true');
      if (e.key === 'ArrowRight') select((cur + 1) % buttons.length, true);
      else if (e.key === 'ArrowLeft') select((cur - 1 + buttons.length) % buttons.length, true);
    } }, buttons);
  select(0);
  return h('div', {}, strip, panels);
}

/**
 * Styled replacement for window.confirm(): resolves true when the user confirms, false on cancel/Escape.
 */
export function confirmDialog({ title, message, confirmLabel = 'Confirm', danger = false }) {
  return new Promise((resolve) => {
    const ok = h('button', { class: danger ? 'danger-solid' : 'primary', type: 'button', value: 'ok' }, confirmLabel);
    const cancel = h('button', { type: 'button' }, 'Cancel');
    const dlg = h('dialog', { 'aria-labelledby': 'confirm-title' },
      h('div', { class: 'dialog-body' }, h('h2', { id: 'confirm-title' }, title), h('p', {}, message)),
      h('div', { class: 'dialog-actions' }, cancel, ok));
    let result = false;
    ok.addEventListener('click', () => { result = true; dlg.close(); });
    cancel.addEventListener('click', () => dlg.close());
    dlg.addEventListener('click', (e) => { if (e.target === dlg) dlg.close(); });
    dlg.addEventListener('close', () => { dlg.remove(); resolve(result); });
    document.body.appendChild(dlg);
    dlg.showModal();
    cancel.focus();
  });
}

/** Side panel (modal) for secondary forms. Returns { open, close, el }. */
export function drawer(title, ...body) {
  const close = h('button', { class: 'ghost icon-only', type: 'button', 'aria-label': 'Close' }, icon('x'));
  const dlg = h('dialog', { class: 'drawer', 'aria-label': title },
    h('div', { class: 'drawer-head' }, h('h2', {}, title), close),
    h('div', { class: 'drawer-body' }, body));
  close.addEventListener('click', () => dlg.close());
  dlg.addEventListener('click', (e) => { if (e.target === dlg) dlg.close(); });
  dlg.addEventListener('close', () => dlg.remove());
  return {
    el: dlg,
    open() { if (!dlg.isConnected) document.body.appendChild(dlg); dlg.showModal(); },
    close() { dlg.close(); },
  };
}

// ---------- Badges ----------
/** Status badge: always icon + label, never colour alone. */
export function badge(text, kind = '', glyph = '') {
  return h('span', { class: `badge ${kind}` }, glyph ? h('span', { class: 'glyph', 'aria-hidden': 'true' }, glyph) : null, text);
}

const OUTCOME = {
  ANSWERED: ['good', '✓', 'Answered'],
  PARTIAL: ['warning', '◐', 'Partial answer'],
  CONFLICT: ['serious', '⚠', 'Conflicting sources'],
  ABSTAINED: ['', '⦸', 'Abstained: insufficient evidence'],
};
export const outcomeBadge = (o) => badge(OUTCOME[o]?.[2] || o, OUTCOME[o]?.[0] || '', OUTCOME[o]?.[1] || '');

const STATUS = {
  ACTIVE: ['good', '●'], PENDING_APPROVAL: ['warning', '◷'], NEEDS_REINDEX: ['serious', '↻'],
  REJECTED: ['critical', '✕'], ARCHIVED: ['', '▣'],
};
export const statusBadge = (s) => badge(s.replace('_', ' ').toLowerCase(), STATUS[s]?.[0] || '', STATUS[s]?.[1] || '');

const LEVEL = { PUBLIC: '', INTERNAL: 'info', CONFIDENTIAL: 'warning', RESTRICTED: 'critical' };
export const levelBadge = (l) => badge(l.toLowerCase(), LEVEL[l] ?? '', l === 'RESTRICTED' || l === 'CONFIDENTIAL' ? '🔒' : '');

export function fmtDate(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}

/** Highlights words whose start matches one of the (stemmed) terms. */
export function highlight(text, terms) {
  const clean = (terms || []).map((t) => t.replace(/[^\p{L}\p{N}]/gu, '')).filter((t) => t.length >= 2);
  if (!clean.length) return document.createTextNode(text);
  const re = new RegExp(`\\b(${clean.join('|')})[\\p{L}\\p{N}]*`, 'giu');
  const frag = document.createDocumentFragment();
  let last = 0;
  for (const m of text.matchAll(re)) {
    frag.append(text.slice(last, m.index), h('mark', {}, m[0]));
    last = m.index + m[0].length;
  }
  frag.append(text.slice(last));
  return frag;
}

export async function withBusy(button, label, fn) {
  const original = [...button.childNodes];
  button.disabled = true;
  button.replaceChildren(h('span', { class: 'spinner' }), label);
  try {
    return await fn();
  } finally {
    button.disabled = false;
    button.replaceChildren(...original);
  }
}

export const CATEGORIES = ['HR', 'Finance', 'IT', 'Engineering', 'General'];
export const LEVELS = ['PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED'];
export const STATUSES = ['PENDING_APPROVAL', 'ACTIVE', 'NEEDS_REINDEX', 'REJECTED', 'ARCHIVED'];

export function categoryList(id) {
  return h('datalist', { id }, CATEGORIES.map((c) => h('option', { value: c })));
}

export function select(name, options, { value = '', blank } = {}) {
  return h('select', { name, id: name },
    blank !== undefined ? h('option', { value: '' }, blank) : null,
    options.map((o) => {
      const [v, label] = Array.isArray(o) ? o : [o, o];
      return h('option', { value: v, selected: v === value }, label);
    }));
}

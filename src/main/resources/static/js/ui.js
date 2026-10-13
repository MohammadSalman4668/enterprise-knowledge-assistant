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

export function toast(message, kind = 'info') {
  const el = h('div', { class: `toast ${kind === 'error' ? 'error' : ''}` }, message);
  document.getElementById('toasts').appendChild(el);
  setTimeout(() => el.remove(), 4500);
}

export const loading = (text = 'Loading…') => h('div', { class: 'loading', role: 'status' }, h('span', { class: 'spinner' }), text);

export function errorBox(err) {
  const lines = (err.fieldErrors || []).map((f) => h('div', {}, `${f.field}: ${f.message}`));
  return h('div', { class: 'error-box', role: 'alert' },
    h('strong', {}, err.code ? `${err.code}: ` : ''), err.message || String(err), lines);
}

export const empty = (text) => h('div', { class: 'empty' }, text);

/** Status badge: always icon + label, never colour alone. */
export function badge(text, kind = '', icon = '') {
  return h('span', { class: `badge ${kind}` }, icon ? h('span', { 'aria-hidden': 'true' }, icon) : null, text);
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

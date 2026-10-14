// Document management: upload, filter, lifecycle actions (approve / reject / revise / re-index / archive)
// and the document detail page. Buttons only appear when the role may use them; the server re-checks.
import { api, qs } from '../api.js';
import {
  h, mount, loading, errorBox, empty, badge, levelBadge, statusBadge, fmtDate, withBusy, toast,
  categoryList, select, LEVELS, STATUSES,
} from '../ui.js';
import { state } from '../state.js';

const filters = { category: '', status: '', accessLevel: '', page: 0 };

export function renderDocuments(app) {
  const tableSlot = h('div', {});
  mount(app,
    h('div', { class: 'page-head' }, h('div', {}, h('h1', {}, 'Documents'),
      h('p', { class: 'muted' }, 'Uploads are indexed immediately but only become searchable after an admin approves them.'))),
    state.me.canContribute ? uploadCard(() => loadTable(tableSlot)) : null,
    h('div', { class: 'card' }, filterBar(() => loadTable(tableSlot)), tableSlot));
  loadTable(tableSlot);
}

function uploadCard(onDone) {
  const file = h('input', { type: 'file', id: 'up-file', accept: '.txt,.md,.pdf', required: true });
  const title = h('input', { id: 'up-title', maxlength: 200, required: true });
  const category = h('input', { id: 'up-category', list: 'up-cat', maxlength: 64, required: true });
  const level = select('up-level', LEVELS.filter((l) => state.me.readableLevels.includes(l)), { value: 'INTERNAL' });
  const status = h('div', {});
  const submit = h('button', { class: 'primary', type: 'submit' }, 'Upload');
  file.addEventListener('change', () => {
    if (!title.value && file.files[0]) title.value = file.files[0].name.replace(/\.[^.]+$/, '').replace(/[-_]+/g, ' ');
  });
  const form = h('form', {
    onsubmit: async (e) => {
      e.preventDefault();
      if (!file.files[0]) {
        mount(status, errorBox({ code: 'VALIDATION_FAILED', message: 'Choose a .txt, .md or .pdf file.' }));
        return;
      }
      const data = new FormData();
      data.append('file', file.files[0]);
      data.append('title', title.value.trim());
      data.append('category', category.value.trim());
      data.append('accessLevel', level.value);
      await withBusy(submit, 'Uploading…', async () => {
        try {
          const doc = await api('/api/knowledge/documents', { method: 'POST', form: data });
          mount(status, h('div', { class: 'callout info' }, `✓ “${doc.title}” uploaded as version ${doc.version} - status `, statusBadge(doc.status), '. An admin must approve it before it is searchable.'));
          form.reset();
          onDone();
        } catch (err) {
          mount(status, errorBox(err));
        }
      });
    },
  },
  h('div', { class: 'fields' },
    h('div', { class: 'field' }, h('label', { for: 'up-file' }, 'File (.txt, .md, .pdf · max 5 MB)'), file),
    h('div', { class: 'field' }, h('label', { for: 'up-title' }, 'Title'), title),
    h('div', { class: 'field' }, h('label', { for: 'up-category' }, 'Category'), category, categoryList('up-cat')),
    h('div', { class: 'field' }, h('label', { for: 'up-level' }, 'Access level'), level)),
  status,
  h('div', { class: 'row' }, h('div', { class: 'spacer' }), submit));
  return h('details', { class: 'card', open: true }, h('summary', {}, 'Upload a document'), form);
}

function filterBar(onChange) {
  const category = h('input', { id: 'f-category', list: 'f-cat', placeholder: 'Any', value: filters.category });
  const status = select('f-status', STATUSES, { value: filters.status, blank: 'Any status' });
  const level = select('f-level', LEVELS.filter((l) => state.me.readableLevels.includes(l)), { value: filters.accessLevel, blank: 'Any level' });
  const apply = () => {
    Object.assign(filters, { category: category.value.trim(), status: status.value, accessLevel: level.value, page: 0 });
    onChange();
  };
  [status, level].forEach((el) => el.addEventListener('change', apply));
  category.addEventListener('change', apply);
  return h('div', { class: 'fields' },
    h('div', { class: 'field' }, h('label', { for: 'f-category' }, 'Category'), category, categoryList('f-cat')),
    h('div', { class: 'field' }, h('label', { for: 'f-status' }, 'Status'), status),
    h('div', { class: 'field' }, h('label', { for: 'f-level' }, 'Access level'), level));
}

async function loadTable(slot) {
  mount(slot, loading());
  try {
    const page = await api(`/api/knowledge/documents${qs({ ...filters, size: 20 })}`);
    mount(slot, renderTable(page, slot));
  } catch (err) {
    mount(slot, errorBox(err));
  }
}

function renderTable(page, slot) {
  if (!page.content.length) return empty('No documents match these filters.');
  const reload = () => loadTable(slot);
  return h('div', {},
    h('div', { class: 'table-wrap' }, h('table', {},
      h('thead', {}, h('tr', {}, ['Title', 'Category', 'Access', 'Version', 'Status', 'Owner', 'Updated', 'Actions'].map((c) => h('th', {}, c)))),
      h('tbody', {}, page.content.map((d) => h('tr', {},
        h('td', {}, h('a', { href: `/documents/${d.id}` }, d.title), h('div', { class: 'small muted' }, d.fileName)),
        h('td', {}, d.category), h('td', {}, levelBadge(d.accessLevel)), h('td', { class: 'num' }, `v${d.version}`),
        h('td', {}, statusBadge(d.status)), h('td', {}, d.owner), h('td', { class: 'small' }, fmtDate(d.updatedAt)),
        h('td', {}, actions(d, reload))))))),
    h('div', { class: 'row', style: 'margin-top:10px' },
      h('span', { class: 'small muted' }, `Page ${page.page + 1} of ${Math.max(page.totalPages, 1)} · ${page.totalElements} documents`),
      h('span', { class: 'spacer' }),
      h('button', { class: 'small', disabled: page.page === 0, onclick: () => { filters.page -= 1; reload(); } }, '‹ Prev'),
      h('button', { class: 'small', disabled: page.page + 1 >= page.totalPages, onclick: () => { filters.page += 1; reload(); } }, 'Next ›')));
}

function actions(d, reload) {
  const { me } = state;
  const btns = [];
  const act = (label, fn, cls = '') => h('button', {
    class: `small ${cls}`,
    onclick: async (e) => {
      const btn = e.currentTarget;
      await withBusy(btn, '…', async () => {
        try {
          const msg = await fn();
          if (msg) toast(msg);
          reload();
        } catch (err) {
          toast(`${err.code}: ${err.message}`, 'error');
        }
      });
    },
  }, label);
  const live = d.status === 'ACTIVE' || d.status === 'NEEDS_REINDEX';
  if (me.admin && d.status === 'PENDING_APPROVAL') {
    btns.push(act('Approve', async () => { await api(`/api/knowledge/documents/${d.id}/approve`, { method: 'POST' }); return `Approved “${d.title}” - now searchable`; }));
    btns.push(act('Reject', async () => { await api(`/api/knowledge/documents/${d.id}/reject`, { method: 'POST' }); return `Rejected “${d.title}”`; }, 'danger'));
  }
  if (live && me.canContribute && (me.admin || d.owner === me.username)) {
    btns.push(revisionButton(d, reload));
  }
  if (me.admin && live) {
    btns.push(act('Re-index', async () => {
      if (!confirm(`Re-index “${d.title}”? A new version is created and v${d.version} chunks become obsolete.`)) return null;
      const r = await api(`/api/knowledge/documents/${d.id}/reindex${qs({ expectedVersion: d.version })}`, { method: 'PUT' });
      return `Re-indexed - now version ${r.version}`;
    }));
  }
  if (me.admin && d.status !== 'ARCHIVED') {
    btns.push(act('Archive', async () => {
      if (!confirm(`Archive “${d.title}”? It will disappear from search and answers.`)) return null;
      await api(`/api/knowledge/documents/${d.id}/archive`, { method: 'POST' });
      return 'Archived';
    }, 'danger'));
  }
  return btns.length ? h('div', { class: 'actions' }, btns) : h('span', { class: 'small muted' }, '-');
}

function revisionButton(d, reload) {
  const input = h('input', { type: 'file', accept: '.txt,.md,.pdf', hidden: true });
  const button = h('button', { class: 'small', onclick: () => input.click() }, 'Submit revision');
  input.addEventListener('change', async () => {
    if (!input.files[0]) return;
    const data = new FormData();
    data.append('file', input.files[0]);
    await withBusy(button, 'Uploading…', async () => {
      try {
        await api(`/api/knowledge/documents/${d.id}/revisions`, { method: 'POST', form: data });
        toast('Revision stored - document now awaits re-indexing (current version still served)');
        reload();
      } catch (err) {
        toast(`${err.code}: ${err.message}`, 'error');
      }
    });
    input.value = '';
  });
  return h('span', {}, button, input);
}

export async function renderDocumentDetail(app, id) {
  mount(app, loading());
  try {
    const detail = await api(`/api/knowledge/documents/${id}`);
    const d = detail.document;
    mount(app,
      h('div', { class: 'page-head' },
        h('div', {}, h('a', { href: '/documents', class: 'small' }, '‹ All documents'), h('h1', {}, d.title),
          h('div', { class: 'row' }, badge(d.category), levelBadge(d.accessLevel), statusBadge(d.status),
            h('span', { class: 'small muted' }, `version ${d.version} · owner ${d.owner} · ${d.fileName} · updated ${fmtDate(d.updatedAt)}`)))),
      detail.pendingRevision ? h('div', { class: 'callout serious' }, `↻ Revision v${detail.pendingRevision} is waiting to be re-indexed. Search and answers still use v${d.version}.`) : null,
      h('div', { class: 'grid grid-2' },
        h('div', { class: 'card' }, h('h2', {}, `Current chunks (${detail.chunkCount})`),
          detail.chunks.length ? h('ul', { class: 'citations' }, detail.chunks.map((c) => h('li', {},
            h('a', { href: `/source/${c.chunkId}` }, `Chunk ${c.chunkIndex + 1}`),
            c.sectionRef ? h('span', { class: 'muted small' }, ` · § ${c.sectionRef}`) : null,
            c.pageNumber ? h('span', { class: 'muted small' }, ` · p. ${c.pageNumber}`) : null,
            h('div', { class: 'small' }, c.preview)))) : empty('No chunks.')),
        h('div', { class: 'card' }, h('h2', {}, 'Version history'),
          h('div', { class: 'table-wrap' }, h('table', {},
            h('thead', {}, h('tr', {}, ['Version', 'State', 'File', 'By', 'When'].map((c) => h('th', {}, c)))),
            h('tbody', {}, detail.versions.map((v) => h('tr', {},
              h('td', {}, `v${v.version}`),
              h('td', {}, v.status === 'CURRENT' ? badge('current', 'good', '●') : v.status === 'PENDING' ? badge('pending', 'warning', '◷') : badge('superseded', '', '▣')),
              h('td', { class: 'small' }, v.fileName), h('td', {}, v.createdBy), h('td', { class: 'small' }, fmtDate(v.createdAt))))))))));
  } catch (err) {
    mount(app, h('div', { class: 'card' }, h('a', { href: '/documents' }, '‹ All documents'), errorBox(err)));
  }
}

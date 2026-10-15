// Document management: upload, filter, lifecycle actions (approve / reject / revise / re-index / archive)
// and the document detail page. Buttons only appear when the role may use them; the server re-checks.
import { api, qs } from '../api.js';
import {
  h, mount, errorBox, badge, levelBadge, statusBadge, fmtDate, withBusy, toast,
  categoryList, select, LEVELS, STATUSES,
  icon, pageHeader, callout, skeleton, emptyState, drawer, confirmDialog,
} from '../ui.js';
import { state } from '../state.js';

const filters = { category: '', status: '', accessLevel: '', page: 0 };

export function renderDocuments(app) {
  const tableSlot = h('div', {});
  const upload = state.me.canContribute ? uploadDrawer(() => loadTable(tableSlot)) : null;
  mount(app,
    pageHeader({
      title: 'Documents',
      description: 'HR uploads become searchable after an admin approves them; admin uploads are live immediately.',
      actions: upload ? h('button', { class: 'primary', type: 'button', onclick: () => upload.open() }, icon('upload', 15), 'Upload document') : null,
    }),
    h('div', { class: 'card card-flush' }, filterBar(() => loadTable(tableSlot)), tableSlot));
  loadTable(tableSlot);
}

function uploadDrawer(onDone) {
  const file = h('input', { type: 'file', id: 'up-file', accept: '.txt,.md,.pdf', required: true });
  const title = h('input', { id: 'up-title', maxlength: 200, required: true });
  const category = h('input', { id: 'up-category', list: 'up-cat', maxlength: 64, required: true, placeholder: 'e.g. HR' });
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
          mount(status, callout('good', h('div', {}, `“${doc.title}” uploaded as version ${doc.version} - status `, statusBadge(doc.status),
            doc.status === 'ACTIVE' ? '. It is searchable now.' : '. An admin must approve it before it is searchable.')));
          form.reset();
          onDone();
        } catch (err) {
          mount(status, errorBox(err));
        }
      });
    },
  },
  h('div', { class: 'field' }, h('label', { for: 'up-file' }, 'File'), file, h('div', { class: 'hint' }, '.txt, .md or .pdf · max 5 MB')),
  h('div', { class: 'field' }, h('label', { for: 'up-title' }, 'Title'), title),
  h('div', { class: 'field' }, h('label', { for: 'up-category' }, 'Category'), category, categoryList('up-cat')),
  h('div', { class: 'field' }, h('label', { for: 'up-level' }, 'Access level'), level,
    h('div', { class: 'hint' }, state.me.admin ? 'Admin uploads are live immediately.' : 'An admin approves the document before it becomes searchable.')),
  status,
  h('div', { class: 'form-actions' }, submit));
  return drawer('Upload a document', form);
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
  return h('div', { class: 'toolbar' },
    h('div', { class: 'field' }, h('label', { for: 'f-category' }, 'Category'), category, categoryList('f-cat')),
    h('div', { class: 'field' }, h('label', { for: 'f-status' }, 'Status'), status),
    h('div', { class: 'field' }, h('label', { for: 'f-level' }, 'Access level'), level));
}

async function loadTable(slot) {
  mount(slot, h('div', { class: 'card-pad' }, skeleton(6, 'Loading documents…')));
  try {
    const page = await api(`/api/knowledge/documents${qs({ ...filters, size: 20 })}`);
    mount(slot, renderTable(page, slot));
  } catch (err) {
    mount(slot, h('div', { class: 'card-pad' }, errorBox(err)));
  }
}

function renderTable(page, slot) {
  if (!page.content.length) return emptyState('No documents', 'No documents match these filters.', 'file');
  const reload = () => loadTable(slot);
  return h('div', {},
    h('div', { class: 'table-wrap' }, h('table', {},
      h('thead', {}, h('tr', {}, ['Title', 'Category', 'Access', 'Version', 'Status', 'Owner', 'Updated', 'Actions']
        .map((c) => h('th', { class: c === 'Version' ? 'num' : '' }, c)))),
      h('tbody', {}, page.content.map((d) => h('tr', {},
        h('td', {}, h('a', { class: 'cell-title', href: `/documents/${d.id}` }, d.title), h('div', { class: 'cell-sub' }, d.fileName)),
        h('td', {}, d.category), h('td', {}, levelBadge(d.accessLevel)), h('td', { class: 'num' }, `v${d.version}`),
        h('td', {}, statusBadge(d.status)), h('td', { class: 'small' }, d.owner), h('td', { class: 'small muted' }, fmtDate(d.updatedAt)),
        h('td', {}, actions(d, reload))))))),
    h('div', { class: 'table-foot' },
      h('span', { class: 'small muted' }, `Page ${page.page + 1} of ${Math.max(page.totalPages, 1)} · ${page.totalElements} documents`),
      h('span', { class: 'spacer' }),
      h('button', { class: 'small', disabled: page.page === 0, onclick: () => { filters.page -= 1; reload(); } }, '‹ Prev'),
      h('button', { class: 'small', disabled: page.page + 1 >= page.totalPages, onclick: () => { filters.page += 1; reload(); } }, 'Next ›')));
}

function actions(d, reload) {
  const { me } = state;
  const btns = [];
  // `ask` (optional) shows a styled confirmation first; cancelling does nothing.
  const act = (label, fn, cls = '', ask = null) => {
    const btn = h('button', {
      class: `small ${cls}`,
      onclick: async () => {
        if (ask && !await confirmDialog(ask)) return;
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
    return btn;
  };
  const live = d.status === 'ACTIVE' || d.status === 'NEEDS_REINDEX';
  if (me.admin && d.status === 'PENDING_APPROVAL') {
    btns.push(act('Approve', async () => { await api(`/api/knowledge/documents/${d.id}/approve`, { method: 'POST' }); return `Approved “${d.title}” - now searchable`; }, 'primary'));
    btns.push(act('Reject', async () => { await api(`/api/knowledge/documents/${d.id}/reject`, { method: 'POST' }); return `Rejected “${d.title}”`; }, 'danger'));
  }
  if (live && me.canContribute && (me.admin || d.owner === me.email)) {
    btns.push(revisionButton(d, reload));
  }
  if (me.admin && live) {
    btns.push(act('Re-index', async () => {
      const r = await api(`/api/knowledge/documents/${d.id}/reindex${qs({ expectedVersion: d.version })}`, { method: 'PUT' });
      return `Re-indexed - now version ${r.version}`;
    }, '', {
      title: 'Re-index document?',
      message: `Re-index “${d.title}”? A new version is created and v${d.version} chunks become obsolete.`,
      confirmLabel: 'Re-index',
    }));
  }
  if (me.admin && d.status !== 'ARCHIVED') {
    btns.push(act('Archive', async () => {
      await api(`/api/knowledge/documents/${d.id}/archive`, { method: 'POST' });
      return 'Archived';
    }, 'danger', {
      title: 'Archive document?',
      message: `Archive “${d.title}”? It will disappear from search and answers.`,
      confirmLabel: 'Archive',
      danger: true,
    }));
  }
  return btns.length ? h('div', { class: 'actions' }, btns) : h('span', { class: 'small subtle' }, '-');
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

const allDocs = { href: '/documents', label: 'All documents' };

export async function renderDocumentDetail(app, id) {
  mount(app, pageHeader({ title: 'Loading document…', back: allDocs }), h('div', { class: 'card' }, skeleton(6)));
  try {
    const detail = await api(`/api/knowledge/documents/${id}`);
    const d = detail.document;
    mount(app,
      pageHeader({
        back: allDocs,
        title: d.title,
        meta: [badge(d.category), levelBadge(d.accessLevel), statusBadge(d.status),
          h('span', {}, `version ${d.version} · owner ${d.owner} · ${d.fileName} · updated ${fmtDate(d.updatedAt)}`)],
      }),
      detail.pendingRevision ? callout('serious', `Revision v${detail.pendingRevision} is waiting to be re-indexed. Search and answers still use v${d.version}.`) : null,
      h('div', { class: 'grid grid-2' },
        h('section', { class: 'card' },
          h('div', { class: 'card-header' }, h('h2', {}, 'Current chunks'), badge(String(detail.chunkCount))),
          detail.chunks.length ? h('ul', { class: 'sources' }, detail.chunks.map((c) => h('li', { class: 'source-row' },
            h('span', { class: 'source-text' },
              h('a', { class: 'source-title', href: `/source/${c.chunkId}` }, `Chunk ${c.chunkIndex + 1}`,
                c.sectionRef ? h('span', { class: 'subtle' }, ` · § ${c.sectionRef}`) : null,
                c.pageNumber ? h('span', { class: 'subtle' }, ` · p. ${c.pageNumber}`) : null),
              h('span', { class: 'source-loc' }, c.preview))))) : emptyState('No chunks')),
        h('section', { class: 'card' },
          h('div', { class: 'card-header' }, h('h2', {}, 'Version history')),
          h('div', { class: 'table-wrap' }, h('table', {},
            h('thead', {}, h('tr', {}, ['Version', 'State', 'File', 'By', 'When'].map((c) => h('th', {}, c)))),
            h('tbody', {}, detail.versions.map((v) => h('tr', {},
              h('td', {}, `v${v.version}`),
              h('td', {}, v.status === 'CURRENT' ? badge('current', 'good', '●') : v.status === 'PENDING' ? badge('pending', 'warning', '◷') : badge('superseded', '', '▣')),
              h('td', { class: 'small' }, v.fileName), h('td', { class: 'small' }, v.createdBy), h('td', { class: 'small muted' }, fmtDate(v.createdAt))))))))));
  } catch (err) {
    mount(app, pageHeader({ title: 'Document', back: allDocs }), errorBox(err));
  }
}

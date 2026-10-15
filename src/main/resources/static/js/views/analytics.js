// Analytics (admin): the five SQL reports plus summary tiles.
// Charts are single-series magnitude bars (one hue, no legend needed: the title names the series),
// each with hover tooltips and a table view so values never depend on reading colour or length alone.
import { api } from '../api.js';
import { h, mount, errorBox, empty, fmtDate, select, pageHeader, skeleton } from '../ui.js';
import { state } from '../state.js';

export async function renderAnalytics(app) {
  if (!state.me.admin) {
    mount(app, pageHeader({ title: 'Analytics' }), errorBox({ code: 'FORBIDDEN', message: 'Analytics are available to admins only.' }));
    return;
  }
  mount(app, pageHeader({ title: 'Search & feedback analytics', description: 'Loading analytics…' }),
    h('div', { class: 'tiles' }, Array.from({ length: 4 }, () => h('div', { class: 'tile' }, skeleton(2)))));
  try {
    const [summary, byCatStatus, queue, searched, feedback] = await Promise.all([
      api('/api/knowledge/analytics/summary'),
      api('/api/knowledge/analytics/documents-by-category-status'),
      api('/api/knowledge/analytics/reindex-queue'),
      api('/api/knowledge/analytics/search-categories'),
      api('/api/knowledge/analytics/feedback-by-document'),
    ]);
    const roleSlot = h('div', {});
    mount(app,
      pageHeader({ title: 'Search & feedback analytics', description: 'Live from the database. The same SQL is in docs/sql/reports.sql.' }),
      tiles(summary),
      h('div', { class: 'grid grid-2' },
        chartCard('Most searched categories', 'Searches (search screen + agent tool calls)', searched,
          { label: (r) => r.category, value: (r) => Number(r.search_count),
            tip: (r) => `${r.search_count} searches · ${r.agent_searches} by agent · ${r.zero_result_searches} with no results`,
            columns: [['Category', 'category'], ['Searches', 'search_count'], ['By agent', 'agent_searches'], ['Zero results', 'zero_result_searches']] }),
        chartCard('Average rating by document', 'Mean feedback rating (1-5)', feedback,
          { label: (r) => r.title, value: (r) => Number(r.average_rating), max: 5, fmt: (v) => v.toFixed(2),
            tip: (r) => `${r.title}: ${r.average_rating} avg from ${r.feedback_count} rating(s), ${r.low_ratings} low`,
            columns: [['Document', 'title'], ['Ratings', 'feedback_count'], ['Average', 'average_rating'], ['Low (≤2)', 'low_ratings']] }),
        chartCard('Documents by category and status', 'Document count', byCatStatus,
          { label: (r) => `${r.category} · ${r.status.replace('_', ' ').toLowerCase()}`, value: (r) => Number(r.document_count),
            tip: (r) => `${r.category} / ${r.status}: ${r.document_count}`,
            columns: [['Category', 'category'], ['Status', 'status'], ['Documents', 'document_count']] }),
        chartCard('Answer outcomes', 'Agent runs', summary.outcomes || [],
          { label: (r) => r.outcome.toLowerCase(), value: (r) => Number(r.run_count), tip: (r) => `${r.outcome}: ${r.run_count}`,
            columns: [['Outcome', 'outcome'], ['Runs', 'run_count']] })),
      h('div', { class: 'card' }, h('div', { class: 'card-header' }, h('h2', {}, 'Documents awaiting re-indexing')),
        queue.length ? table(queue, [['Document', 'title'], ['Category', 'category'], ['Status', 'status'],
          ['Current', 'current_version'], ['Pending', 'pending_version'], ['Submitted by', 'submitted_by'], ['Submitted', 'submitted_at', fmtDate]])
          : empty('Nothing is waiting for re-indexing.')),
      h('div', { class: 'card' }, h('div', { class: 'card-header' }, h('h2', {}, 'Documents accessible to role'),
        roleSelect(roleSlot)), roleSlot));
    loadRole(roleSlot, 'EMPLOYEE');
  } catch (err) {
    mount(app, pageHeader({ title: 'Search & feedback analytics' }), errorBox(err));
  }
}

function tiles(s) {
  const tile = (label, value) => h('div', { class: 'tile' }, h('div', { class: 'label' }, label), h('div', { class: 'value' }, value ?? '-'));
  const abstainRate = s.questions ? `${Math.round((100 * s.abstained) / s.questions)}%` : '-';
  return h('div', { class: 'tiles' },
    tile('Searchable documents', s.searchable_documents),
    tile('Pending approval', s.pending_approval),
    tile('Needs re-index', s.needs_reindex),
    tile('Searches', s.searches),
    tile('Questions asked', s.questions),
    tile('Abstain rate', abstainRate),
    tile(`Avg rating (${s.feedback_count ?? 0} rating${s.feedback_count === 1 ? '' : 's'})`,
      s.feedback_count ? `${Number(s.average_rating).toFixed(2)} / 5` : '-'));
}

function chartCard(title, valueName, rows, opts) {
  const body = h('div', {});
  let showTable = false;
  const toggle = h('button', { class: 'small', 'aria-pressed': 'false' }, 'Table');
  const draw = () => {
    toggle.textContent = showTable ? 'Chart' : 'Table';
    toggle.setAttribute('aria-pressed', String(showTable));
    mount(body, !rows.length ? empty('No data yet.') : showTable ? table(rows, opts.columns) : bars(rows, valueName, opts));
  };
  toggle.addEventListener('click', () => { showTable = !showTable; draw(); });
  draw();
  return h('div', { class: 'card' }, h('div', { class: 'card-header' }, h('div', {}, h('h3', {}, title), h('div', { class: 'small subtle' }, valueName)), toggle), body);
}

function bars(rows, valueName, { label, value, max, fmt = (v) => String(v), tip }) {
  const top = max ?? Math.max(...rows.map(value), 1);
  const tooltip = document.getElementById('tooltip');
  const show = (e, text) => {
    tooltip.textContent = text;
    tooltip.hidden = false;
    tooltip.style.left = `${Math.min(e.clientX + 12, window.innerWidth - 270)}px`;
    tooltip.style.top = `${e.clientY + 12}px`;
  };
  const hide = () => { tooltip.hidden = true; };
  return h('div', { class: 'bars', role: 'list', 'aria-label': valueName },
    rows.map((r) => {
      const v = value(r);
      const text = tip ? tip(r) : `${label(r)}: ${fmt(v)}`;
      return h('div', { class: 'bar-row', role: 'listitem', 'aria-label': text },
        h('div', { class: 'bar-label', title: label(r) }, label(r)),
        h('div', { class: 'bar-track baseline' },
          h('div', { class: 'bar', style: `width:${Math.max(0, Math.min(100, (100 * v) / top))}%` }),
          h('div', { class: 'bar-hit', onmousemove: (e) => show(e, text), onmouseleave: hide })),
        h('div', { class: 'bar-value' }, fmt(v)));
    }));
}

function table(rows, columns) {
  return h('div', { class: 'table-wrap' }, h('table', {},
    h('thead', {}, h('tr', {}, columns.map(([c, key]) => h('th', { class: typeof rows[0]?.[key] === 'number' ? 'num' : '' }, c)))),
    h('tbody', {}, rows.map((r) => h('tr', {}, columns.map(([, key, f]) => h('td', { class: typeof r[key] === 'number' ? 'num' : '' },
      f ? f(r[key]) : (r[key] ?? '-'))))))));
}

function roleSelect(slot) {
  const sel = select('role-select', ['EMPLOYEE', 'HR', 'ADMIN'], { value: 'EMPLOYEE' });
  sel.setAttribute('aria-label', 'Role');
  sel.style.width = 'auto';
  sel.addEventListener('change', () => loadRole(slot, sel.value));
  return sel;
}

async function loadRole(slot, role) {
  mount(slot, skeleton(4));
  try {
    const rows = await api(`/api/knowledge/analytics/accessible-documents?role=${role}`);
    mount(slot, rows.length ? table(rows, [['Document', 'title'], ['Category', 'category'], ['Access', 'access_level'],
      ['Status', 'status'], ['Version', 'current_version']]) : empty('No documents.'));
  } catch (err) {
    mount(slot, errorBox(err));
  }
}

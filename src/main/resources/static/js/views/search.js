// Search screen: keyword search with metadata filters; results respect the caller's access level.
import { api } from '../api.js';
import {
  h, mount, errorBox, badge, levelBadge, highlight, withBusy, categoryList, select, LEVELS,
  icon, pageHeader, callout, skeleton, emptyState,
} from '../ui.js';
import { state } from '../state.js';

export function renderSearch(app) {
  const query = h('input', { id: 'q', type: 'search', maxlength: 500, 'aria-label': 'Search terms', placeholder: 'e.g. carry over leave, VPN token, rollback' });
  const category = h('input', { id: 'category', list: 'cat-list', placeholder: 'Any', maxlength: 64 });
  const level = select('level', state.me.readableLevels.length ? LEVELS.filter((l) => state.me.readableLevels.includes(l)) : LEVELS, { blank: 'Any I can read' });
  const limit = select('limit', ['5', '10', '20'], { value: '10' });
  const submit = h('button', { class: 'primary', type: 'submit' }, 'Search');
  const results = h('div', {});

  const form = h('form', {
    class: 'card',
    role: 'search',
    onsubmit: async (e) => {
      e.preventDefault();
      if (!query.value.trim()) {
        mount(results, h('div', { class: 'results-head' }), errorBox({ code: 'VALIDATION_FAILED', message: 'Enter something to search for.' }));
        return;
      }
      mount(results, h('div', { class: 'results-head' }, h('span', { class: 'loading' }, h('span', { class: 'spinner' }), 'Searching…')),
        h('div', { class: 'card' }, skeleton(5)));
      await withBusy(submit, 'Searching…', async () => {
        try {
          const res = await api('/api/knowledge/search', {
            method: 'POST',
            body: { query: query.value.trim(), category: category.value.trim() || null, accessLevel: level.value || null, limit: Number(limit.value) },
          });
          mount(results, renderResults(res));
        } catch (err) {
          mount(results, h('div', { class: 'results-head' }), errorBox(err));
        }
      });
    },
  },
  h('div', { class: 'search-bar' },
    h('div', { class: 'input-icon' }, icon('search', 16), query),
    submit),
  h('div', { class: 'filters' },
    h('div', { class: 'field' }, h('label', { for: 'category' }, 'Category'), category, categoryList('cat-list')),
    h('div', { class: 'field' }, h('label', { for: 'level' }, 'Access level'), level),
    h('div', { class: 'field' }, h('label', { for: 'limit' }, 'Max results'), limit)));

  mount(app,
    pageHeader({ title: 'Search', description: 'Full-text search over current, approved documents you can access.' }),
    form, results);
  query.focus();
}

/** Why a result matched: shared words, similar meaning, or both. */
function matchBadge(r) {
  const how = r.matchedBy || 'KEYWORD';
  const sim = r.similarity != null ? ` ${r.similarity.toFixed(2)}` : '';
  if (how.includes('KEYWORD') && how.includes('SEMANTIC')) return badge(`keyword + meaning${sim}`, 'good', '≈');
  if (how.includes('SEMANTIC')) return badge(`meaning${sim}`, 'info', '≈');
  return badge('keyword', '', '⌕');
}

function renderResults(res) {
  const hl = (r) => r.matchedTerms.join(',');
  return h('section', { 'aria-label': 'Search results' },
    h('div', { class: 'results-head' },
      h('h2', {}, `${res.total} result${res.total === 1 ? '' : 's'}`),
      badge(`retrieval: ${res.retrievalMode.toLowerCase()}`),
      res.fallbackUsed ? badge('fallback used', 'serious', '↯') : null),
    res.warnings.length ? callout('warning', res.warnings.map((w) => h('div', {}, w))) : null,
    h('div', { class: 'card card-flush' },
      res.results.length ? res.results.map((r) => h('div', { class: 'result' },
        h('a', { class: 'title', href: `/source/${r.chunkId}?hl=${encodeURIComponent(hl(r))}` }, r.title),
        h('div', { class: 'meta' },
          badge(r.category), levelBadge(r.accessLevel), matchBadge(r),
          h('span', { class: 'small subtle' }, `v${r.version} · ${r.location} · score ${r.score}`)),
        h('div', { class: 'snippet' }, highlight(r.snippet, r.matchedTerms))))
        : emptyState('No matching passages', 'Nothing in the documents you can access matches. Try other words or remove filters.', 'search')));
}

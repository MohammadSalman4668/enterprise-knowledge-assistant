// Search screen: keyword search with metadata filters; results respect the caller's access level.
import { api } from '../api.js';
import {
  h, mount, loading, errorBox, empty, badge, levelBadge, highlight, withBusy, categoryList, select, LEVELS,
} from '../ui.js';
import { state } from '../state.js';

export function renderSearch(app) {
  const query = h('input', { id: 'q', type: 'search', maxlength: 500, placeholder: 'e.g. carry over leave, VPN token, rollback' });
  const category = h('input', { id: 'category', list: 'cat-list', placeholder: 'Any', maxlength: 64 });
  const level = select('level', state.me.readableLevels.length ? LEVELS.filter((l) => state.me.readableLevels.includes(l)) : LEVELS, { blank: 'Any I can read' });
  const limit = select('limit', ['5', '10', '20'], { value: '10' });
  const submit = h('button', { class: 'primary', type: 'submit' }, 'Search');
  const results = h('div', {});

  const form = h('form', {
    class: 'card',
    onsubmit: async (e) => {
      e.preventDefault();
      if (!query.value.trim()) {
        mount(results, errorBox({ code: 'VALIDATION_FAILED', message: 'Enter something to search for.' }));
        return;
      }
      mount(results, h('div', { class: 'card' }, loading('Searching…')));
      await withBusy(submit, 'Searching…', async () => {
        try {
          const res = await api('/api/knowledge/search', {
            method: 'POST',
            body: { query: query.value.trim(), category: category.value.trim() || null, accessLevel: level.value || null, limit: Number(limit.value) },
          });
          mount(results, renderResults(res));
        } catch (err) {
          mount(results, h('div', { class: 'card' }, errorBox(err)));
        }
      });
    },
  },
  h('div', { class: 'field' }, h('label', { for: 'q' }, 'Search terms'), query),
  h('div', { class: 'fields' },
    h('div', { class: 'field' }, h('label', { for: 'category' }, 'Category'), category, categoryList('cat-list')),
    h('div', { class: 'field' }, h('label', { for: 'level' }, 'Access level'), level),
    h('div', { class: 'field' }, h('label', { for: 'limit' }, 'Max results'), limit)),
  h('div', { class: 'row' }, h('div', { class: 'spacer' }), submit));

  mount(app,
    h('div', { class: 'page-head' }, h('div', {}, h('h1', {}, 'Search'),
      h('p', { class: 'muted' }, 'Full-text search over current, approved documents you can access.'))),
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
  return h('div', { class: 'card' },
    h('div', { class: 'row' },
      h('strong', {}, `${res.total} result${res.total === 1 ? '' : 's'}`),
      badge(`retrieval: ${res.retrievalMode.toLowerCase()}`),
      res.fallbackUsed ? badge('fallback used', 'serious', '↯') : null),
    res.warnings.length ? h('div', { class: 'callout warning' }, res.warnings.map((w) => h('div', {}, `⚠ ${w}`))) : null,
    res.results.length ? h('div', {}, res.results.map((r) => h('div', { class: 'result' },
      h('a', { class: 'title', href: `/source/${r.chunkId}?hl=${encodeURIComponent(hl(r))}` }, r.title),
      h('div', { class: 'meta' },
        badge(r.category), levelBadge(r.accessLevel), matchBadge(r),
        h('span', { class: 'small muted' }, `v${r.version} · ${r.location} · score ${r.score}`)),
      h('div', {}, highlight(r.snippet, r.matchedTerms)))))
      : empty('No matching passages in documents you can access. Try other words or remove filters.'));
}

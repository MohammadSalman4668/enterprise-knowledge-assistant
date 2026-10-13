// Source preview: the exact passage behind a citation or search hit, with matched terms highlighted.
// Obsolete passages (from a superseded version) stay viewable but are clearly labelled.
import { api } from '../api.js';
import { h, mount, loading, errorBox, badge, levelBadge, highlight } from '../ui.js';

export async function renderSource(app, chunkId, params) {
  mount(app, loading('Loading source passage…'));
  const terms = (params.get('hl') || '').split(',').filter(Boolean);
  const ref = params.get('ref');
  try {
    const c = await api(`/api/knowledge/chunks/${chunkId}`);
    mount(app,
      h('div', { class: 'page-head' }, h('div', {},
        h('button', { type: 'button', class: 'small', onclick: () => history.back() }, '‹ Back'),
        h('h1', {}, ref ? `${ref} · ${c.documentTitle}` : c.documentTitle),
        h('div', { class: 'row' }, badge(c.category), levelBadge(c.accessLevel),
          h('span', { class: 'small muted' }, `version ${c.version}`,
            c.sectionRef ? ` · § ${c.sectionRef}` : '', c.pageNumber ? ` · page ${c.pageNumber}` : '',
            ` · chunk ${c.chunkIndex + 1}`)))),
      c.current
        ? h('div', { class: 'callout info' }, `✓ Current: this passage is from the live version (v${c.documentCurrentVersion}).`)
        : h('div', { class: 'callout critical', role: 'alert' },
          `⚠ Obsolete: this passage is from version ${c.version}, but the document is now at version ${c.documentCurrentVersion}. `
          + 'It is kept only so past answers stay inspectable and is no longer used for search or answers.'),
      h('div', { class: 'card' },
        h('div', { class: 'content' }, highlight(c.content, terms)),
        h('p', { class: 'small muted', style: 'margin-top:12px' },
          h('a', { href: `#/documents/${c.documentId}` }, 'Open document details and version history ›'))));
  } catch (err) {
    mount(app, h('div', { class: 'card' }, errorBox(err),
      h('p', { class: 'muted small' }, 'Passages you are not allowed to read are reported as not found.')));
  }
}

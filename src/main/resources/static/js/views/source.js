// Source preview: the exact passage behind a citation or search hit, with matched terms highlighted.
// Obsolete passages (from a superseded version) stay viewable but are clearly labelled.
import { api } from '../api.js';
import { h, mount, errorBox, badge, levelBadge, highlight, pageHeader, callout, skeleton, icon } from '../ui.js';

const back = { href: '#', label: 'Back', onclick: (e) => { e.preventDefault(); history.back(); } };

export async function renderSource(app, chunkId, params) {
  mount(app, pageHeader({ title: 'Loading source passage…', back }), h('div', { class: 'card reader' }, skeleton(8, 'Loading source passage…')));
  const terms = (params.get('hl') || '').split(',').filter(Boolean);
  const ref = params.get('ref');
  try {
    const c = await api(`/api/knowledge/chunks/${chunkId}`);
    mount(app,
      pageHeader({
        back,
        title: ref ? `${ref} · ${c.documentTitle}` : c.documentTitle,
        meta: [badge(c.category), levelBadge(c.accessLevel),
          h('span', {}, `version ${c.version}`,
            c.sectionRef ? ` · § ${c.sectionRef}` : '', c.pageNumber ? ` · page ${c.pageNumber}` : '',
            ` · chunk ${c.chunkIndex + 1}`)],
        actions: h('a', { class: 'btn', href: `/documents/${c.documentId}` }, 'Document details', icon('arrowRight', 14)),
      }),
      h('div', { class: 'reader' },
        c.current
          ? callout('good', `Current: this passage is from the live version (v${c.documentCurrentVersion}).`)
          : callout('critical',
            h('strong', {}, 'Obsolete passage'),
            `This passage is from version ${c.version}, but the document is now at version ${c.documentCurrentVersion}. `
            + 'It is kept only so past answers stay inspectable and is no longer used for search or answers.'),
        h('article', { class: 'card' },
          h('div', { class: 'doc-text' }, highlight(c.content, terms))),
        h('p', { class: 'small muted reader-foot' },
          h('a', { href: `/documents/${c.documentId}` }, 'Open document details and version history ›'))));
  } catch (err) {
    mount(app, pageHeader({ title: 'Source passage', back }), errorBox(err),
      h('p', { class: 'muted small' }, 'Passages you are not allowed to read are reported as not found.'));
  }
}

// Two-column frame shared by the sign-in and sign-up pages: product summary on the left, form on the right.
import { h, icon } from '../ui.js';

const brandMark = () => h('span', { class: 'brand-mark', 'aria-hidden': 'true' }, icon('book', 16));

export function authShell(...card) {
  return h('div', { class: 'auth' },
    h('aside', { class: 'auth-aside' },
      h('div', {},
        h('div', { class: 'brand' }, brandMark(), h('span', {}, 'Knowledge Assistant')),
        h('h2', {}, 'Answers from your company’s own documents.'),
        h('ul', { class: 'auth-points' },
          h('li', {}, icon('sparkle', 18), h('div', {}, h('strong', {}, 'Cited answers'),
            'Every statement links back to the passage it came from.')),
          h('li', {}, icon('shield', 18), h('div', {}, h('strong', {}, 'Permission aware'),
            'You only see documents your role is allowed to read.')),
          h('li', {}, icon('layers', 18), h('div', {}, h('strong', {}, 'Always current'),
            'Archived and outdated versions are never used.')))),
      h('footer', {}, 'Enterprise Knowledge Assistant')),
    h('main', { class: 'auth-main' },
      h('div', { class: 'auth-card' },
        h('div', { class: 'brand auth-mobile-brand' }, brandMark(), h('span', {}, 'Knowledge Assistant')),
        card)));
}

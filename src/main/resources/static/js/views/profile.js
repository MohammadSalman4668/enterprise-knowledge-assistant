// Profile: who you are, your role and what it lets you do, and who approved your account.
import { api } from '../api.js';
import { h, mount, loading, errorBox, badge, levelBadge, fmtDate } from '../ui.js';

const ROLE_ABILITIES = {
  EMPLOYEE: 'Ask questions, search, and rate answers.',
  HR: 'Everything an Employee can do, plus upload documents (admin approval needed) and approve Employee sign-ups.',
  ADMIN: 'Full access: all documents, uploads go live immediately, approve documents and HR/Admin sign-ups, analytics.',
};

export async function renderProfile(app) {
  mount(app, loading());
  try {
    const me = await api('/api/knowledge/me');
    const approved = me.approvedBy === 'system'
      ? 'Built-in administrator account'
      : me.approvedBy ? `${me.approvedByName || me.approvedBy} on ${fmtDate(me.approvedAt)}` : '-';
    mount(app,
      h('div', { class: 'page-head' }, h('div', {}, h('h1', {}, 'Profile'))),
      h('div', { class: 'card profile' },
        h('dl', {},
          h('dt', {}, 'Name'), h('dd', {}, me.displayName),
          h('dt', {}, 'Email'), h('dd', {}, me.email),
          h('dt', {}, 'Role'), h('dd', {}, badge(me.role.toLowerCase(), 'info')),
          h('dt', {}, 'Account status'), h('dd', {}, badge(me.status.toLowerCase(), me.status === 'APPROVED' ? 'good' : 'warning')),
          h('dt', {}, 'Approved by'), h('dd', {}, approved),
          h('dt', {}, 'Member since'), h('dd', {}, fmtDate(me.memberSince)),
          h('dt', {}, 'Can read'), h('dd', { class: 'row' }, me.readableLevels.map((l) => levelBadge(l))),
          h('dt', {}, 'What you can do'), h('dd', {}, ROLE_ABILITIES[me.role] || ''))));
  } catch (err) {
    mount(app, h('div', { class: 'card' }, errorBox(err)));
  }
}

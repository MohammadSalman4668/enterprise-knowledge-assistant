// Profile: who you are, your role and what it lets you do, and who approved your account.
import { api } from '../api.js';
import { h, mount, errorBox, badge, levelBadge, fmtDate, pageHeader, skeleton, avatar } from '../ui.js';

const ROLE_ABILITIES = {
  EMPLOYEE: 'Ask questions, search, and rate answers.',
  HR: 'Everything an Employee can do, plus upload documents (admin approval needed) and approve Employee sign-ups.',
  ADMIN: 'Full access: all documents, uploads go live immediately, approve documents and HR/Admin sign-ups, analytics.',
};

export async function renderProfile(app) {
  mount(app, pageHeader({ title: 'Profile' }), h('div', { class: 'card' }, skeleton(6)));
  try {
    const me = await api('/api/knowledge/me');
    const approved = me.approvedBy === 'system'
      ? 'Built-in administrator account'
      : me.approvedBy ? `${me.approvedByName || me.approvedBy} on ${fmtDate(me.approvedAt)}` : '-';
    mount(app,
      pageHeader({ title: 'Profile', description: 'Your account, role and what it allows you to do.' }),
      h('div', { class: 'card' },
        h('div', { class: 'profile-hero' },
          avatar(me.displayName, 'lg'),
          h('div', {},
            h('h2', {}, me.displayName),
            h('div', { class: 'muted' }, me.email),
            h('div', { class: 'row' },
              badge(me.role.toLowerCase(), 'info'),
              badge(me.status.toLowerCase(), me.status === 'APPROVED' ? 'good' : 'warning', me.status === 'APPROVED' ? '●' : '◷'))))),
      h('div', { class: 'card profile' },
        h('h3', { class: 'section-title' }, 'Account details'),
        h('dl', { class: 'details' },
          h('dt', {}, 'Name'), h('dd', {}, me.displayName),
          h('dt', {}, 'Email'), h('dd', {}, me.email),
          h('dt', {}, 'Role'), h('dd', {}, badge(me.role.toLowerCase(), 'info')),
          h('dt', {}, 'Account status'), h('dd', {}, badge(me.status.toLowerCase(), me.status === 'APPROVED' ? 'good' : 'warning')),
          h('dt', {}, 'Approved by'), h('dd', {}, approved),
          h('dt', {}, 'Member since'), h('dd', {}, fmtDate(me.memberSince)),
          h('dt', {}, 'Can read'), h('dd', { class: 'row' }, me.readableLevels.map((l) => levelBadge(l))),
          h('dt', {}, 'What you can do'), h('dd', {}, ROLE_ABILITIES[me.role] || ''))));
  } catch (err) {
    mount(app, pageHeader({ title: 'Profile' }), errorBox(err));
  }
}

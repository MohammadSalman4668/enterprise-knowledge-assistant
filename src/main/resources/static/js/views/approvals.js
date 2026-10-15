// Approvals: HR decides Employee sign-ups; Admins decide HR and Admin sign-ups. The server enforces the same rule.
import { api } from '../api.js';
import {
  h, mount, errorBox, badge, fmtDate, withBusy, toast, pageHeader, skeleton, emptyState, avatar, confirmDialog, icon,
} from '../ui.js';
import { state } from '../state.js';

export async function renderApprovals(app) {
  const listSlot = h('div', {});
  const who = state.me.role === 'HR' ? 'Employee sign-up requests' : 'HR and Admin sign-up requests';
  mount(app,
    pageHeader({ title: 'Approvals', description: `${who} waiting for your decision. Approved users can sign in immediately.` }),
    h('div', { class: 'card card-flush' }, listSlot));
  await load(listSlot);
}

async function load(slot) {
  mount(slot, h('div', { class: 'card-pad' }, skeleton(4)));
  try {
    const pending = await api('/api/users/pending');
    mount(slot, pending.length ? table(pending, slot) : emptyState('You’re all caught up', 'No pending requests.', 'userCheck'));
  } catch (err) {
    mount(slot, h('div', { class: 'card-pad' }, errorBox(err)));
  }
}

function table(rows, slot) {
  return h('div', { class: 'table-wrap' }, h('table', {},
    h('thead', {}, h('tr', {}, ['Name', 'Requested role', 'Requested', 'Decision'].map((c) => h('th', {}, c)))),
    h('tbody', {}, rows.map((r) => h('tr', {},
      h('td', {}, h('div', { class: 'cell-person' }, avatar(r.name, 'sm'),
        h('div', {}, h('div', { class: 'cell-title' }, r.name), h('div', { class: 'cell-sub' }, r.email)))),
      h('td', {}, badge(r.role.toLowerCase(), 'info')),
      h('td', { class: 'small muted' }, fmtDate(r.requestedAt)),
      h('td', {}, h('div', { class: 'actions' },
        decideButton(r, true, slot),
        decideButton(r, false, slot))))))));
}

function decideButton(r, approve, slot) {
  const button = h('button', {
    class: `small ${approve ? 'primary' : 'danger'}`,
    onclick: async () => {
      if (!approve && !await confirmDialog({
        title: 'Reject sign-up request?',
        message: `Reject the sign-up request from ${r.name} (${r.email})?`,
        confirmLabel: 'Reject',
        danger: true,
      })) return;
      await withBusy(button, '…', async () => {
        try {
          await api(`/api/users/${r.id}/${approve ? 'approve' : 'reject'}`, { method: 'POST' });
          toast(`${approve ? 'Approved' : 'Rejected'} ${r.name}`);
        } catch (err) {
          toast(`${err.code}: ${err.message}`, 'error');
        }
      });
      load(slot);
    },
  }, approve ? icon('check', 14) : null, approve ? 'Approve' : 'Reject');
  return button;
}

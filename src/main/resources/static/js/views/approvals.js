// Approvals: HR decides Employee sign-ups; Admins decide HR and Admin sign-ups. The server enforces the same rule.
import { api } from '../api.js';
import { h, mount, loading, errorBox, empty, badge, fmtDate, withBusy, toast } from '../ui.js';
import { state } from '../state.js';

export async function renderApprovals(app) {
  const listSlot = h('div', {});
  const who = state.me.role === 'HR' ? 'Employee sign-up requests' : 'HR and Admin sign-up requests';
  mount(app,
    h('div', { class: 'page-head' }, h('div', {}, h('h1', {}, 'Approvals'),
      h('p', { class: 'muted' }, `${who} waiting for your decision. Approved users can sign in immediately.`))),
    h('div', { class: 'card' }, listSlot));
  await load(listSlot);
}

async function load(slot) {
  mount(slot, loading());
  try {
    const pending = await api('/api/users/pending');
    mount(slot, pending.length ? table(pending, slot) : empty('No pending requests.'));
  } catch (err) {
    mount(slot, errorBox(err));
  }
}

function table(rows, slot) {
  return h('div', { class: 'table-wrap' }, h('table', {},
    h('thead', {}, h('tr', {}, ['Name', 'Email', 'Requested role', 'Requested', 'Decision'].map((c) => h('th', {}, c)))),
    h('tbody', {}, rows.map((r) => h('tr', {},
      h('td', {}, r.name),
      h('td', {}, r.email),
      h('td', {}, badge(r.role.toLowerCase(), 'info')),
      h('td', { class: 'small' }, fmtDate(r.requestedAt)),
      h('td', {}, h('div', { class: 'actions' },
        decideButton(r, true, slot),
        decideButton(r, false, slot))))))));
}

function decideButton(r, approve, slot) {
  return h('button', {
    class: `small ${approve ? 'primary' : 'danger'}`,
    onclick: async (e) => {
      if (!approve && !confirm(`Reject the sign-up request from ${r.name} (${r.email})?`)) return;
      await withBusy(e.currentTarget, '…', async () => {
        try {
          await api(`/api/users/${r.id}/${approve ? 'approve' : 'reject'}`, { method: 'POST' });
          toast(`${approve ? 'Approved' : 'Rejected'} ${r.name}`);
        } catch (err) {
          toast(`${err.code}: ${err.message}`, 'error');
        }
      });
      load(slot);
    },
  }, approve ? 'Approve' : 'Reject');
}

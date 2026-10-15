// Sign-up: creates a PENDING account. Employee requests are approved by HR; HR and Admin requests by an Admin.
import { api } from '../api.js';
import { h, mount, errorBox, withBusy, icon } from '../ui.js';
import { authShell } from './auth-shell.js';

const ROLE_LABEL = { EMPLOYEE: 'Employee', HR: 'HR', ADMIN: 'Admin' };

const ROLES = [
  ['EMPLOYEE', 'Employee', 'approved by HR'],
  ['HR', 'HR', 'approved by an Admin'],
  ['ADMIN', 'Admin', 'approved by an Admin'],
];

export function renderSignup(app) {
  const name = h('input', { id: 'su-name', required: true, maxlength: 100, autocomplete: 'name' });
  const email = h('input', { id: 'su-email', type: 'email', required: true, maxlength: 254, autocomplete: 'email', placeholder: 'name@company.com' });
  const pw = h('input', { id: 'su-password', type: 'password', required: true, minlength: 8, maxlength: 72, autocomplete: 'new-password' });
  const pw2 = h('input', { id: 'su-password2', type: 'password', required: true, autocomplete: 'new-password' });
  const role = h('select', { id: 'su-role' }, ROLES.map(([v, label, who]) => h('option', { value: v }, `${label} (${who})`)));
  const status = h('div', {});
  const submit = h('button', { class: 'primary lg block', type: 'submit' }, 'Request account');

  const form = h('form', {
    class: 'auth-form',
    onsubmit: async (e) => {
      e.preventDefault();
      if (pw.value !== pw2.value) {
        mount(status, errorBox({ code: 'VALIDATION_FAILED', message: 'The two passwords do not match.' }));
        return;
      }
      await withBusy(submit, 'Sending request…', async () => {
        try {
          const created = await api('/api/auth/signup', {
            method: 'POST',
            body: { name: name.value.trim(), email: email.value.trim(), password: pw.value, role: role.value },
          });
          mount(app, authShell(h('div', { class: 'auth-done' },
            h('span', { class: 'done-mark', 'aria-hidden': 'true' }, icon('check', 20)),
            h('h1', {}, 'Request sent'),
            h('p', {}, `Thanks, ${created.name}. Your ${ROLE_LABEL[created.role] || created.role} account (${created.email}) is waiting for approval by `,
              h('strong', {}, created.approvedBy), '.'),
            h('p', { class: 'muted' }, 'You can sign in as soon as it has been approved.'),
            h('div', { class: 'form-actions' }, h('a', { href: '/login', class: 'btn primary lg' }, 'Go to sign in')))));
        } catch (err) {
          mount(status, errorBox(err));
        }
      });
    },
  },
  h('h1', {}, 'Create an account'),
  h('p', {}, 'Your request must be approved before you can sign in.'),
  h('div', { class: 'field' }, h('label', { for: 'su-name' }, 'Full name'), name),
  h('div', { class: 'field' }, h('label', { for: 'su-email' }, 'Work email'), email),
  h('div', { class: 'field' }, h('label', { for: 'su-role' }, 'I am signing up as'), role),
  h('div', { class: 'field' }, h('label', { for: 'su-password' }, 'Password'), pw, h('div', { class: 'hint' }, 'At least 8 characters.')),
  h('div', { class: 'field' }, h('label', { for: 'su-password2' }, 'Repeat password'), pw2),
  status,
  h('div', { class: 'form-actions' }, submit),
  h('p', { class: 'auth-foot' }, 'Already have an account? ', h('a', { href: '/login' }, 'Sign in')));
  mount(app, authShell(form));
  name.focus();
}

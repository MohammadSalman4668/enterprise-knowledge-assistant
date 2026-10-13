// Thin fetch wrapper: adds the Basic auth header, parses the uniform ErrorResponse,
// and turns network failures into a readable error. All API calls go through here.

const KEY = 'ekg.auth';

export const session = {
  get() {
    try { return JSON.parse(sessionStorage.getItem(KEY)); } catch { return null; }
  },
  set(value) {
    try { sessionStorage.setItem(KEY, JSON.stringify(value)); } catch { /* private mode: keep in memory only */ }
    memory = value;
  },
  clear() {
    try { sessionStorage.removeItem(KEY); } catch { /* ignore */ }
    memory = null;
  },
};
let memory = null;

function token() {
  return (session.get() || memory)?.token;
}

export function basicToken(username, password) {
  const bytes = new TextEncoder().encode(`${username}:${password}`);
  let bin = '';
  bytes.forEach((b) => { bin += String.fromCharCode(b); });
  return btoa(bin);
}

export class ApiError extends Error {
  constructor(status, body) {
    super(body?.message || `Request failed (${status})`);
    this.status = status;
    this.code = body?.code || 'ERROR';
    this.fieldErrors = body?.fieldErrors || [];
  }
}

export async function api(path, { method = 'GET', body, form, auth } = {}) {
  const headers = { Accept: 'application/json' };
  const t = auth || token();
  if (t) headers.Authorization = `Basic ${t}`;
  let payload;
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    payload = JSON.stringify(body);
  } else if (form) {
    payload = form; // the browser sets the multipart boundary
  }
  let res;
  try {
    res = await fetch(path, { method, headers, body: payload });
  } catch {
    throw new ApiError(0, { code: 'NETWORK_ERROR', message: 'Cannot reach the server. Is it running?' });
  }
  const text = await res.text();
  let data = null;
  if (text) {
    try { data = JSON.parse(text); } catch { data = { message: text }; }
  }
  if (!res.ok) {
    if (res.status === 401 && !auth) window.dispatchEvent(new CustomEvent('ekg:unauthorized'));
    throw new ApiError(res.status, data || {});
  }
  return data;
}

export const qs = (params) => {
  const p = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') p.set(k, v);
  });
  const s = p.toString();
  return s ? `?${s}` : '';
};

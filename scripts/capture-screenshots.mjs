// Regenerates docs/screenshots/*.png by driving headless Edge/Chrome over the DevTools protocol.
// No npm dependencies (Node 22+ has fetch and WebSocket built in).
//
// Usage (app running on BASE_URL, demo password in the environment, tests already run once):
//   PowerShell:  $env:EKG_DEMO_PASSWORD="..."; node scripts/capture-screenshots.mjs
//
// Env: BASE_URL (default http://localhost:8095), BROWSER_PATH, OUT_DIR (default docs/screenshots)
import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const BASE = process.env.BASE_URL || 'http://localhost:8095';
const PASSWORD = process.env.EKG_DEMO_PASSWORD;
const OUT = process.env.OUT_DIR || 'docs/screenshots';
const BROWSER = process.env.BROWSER_PATH || [
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
].find((p) => existsSync(p));
if (!PASSWORD) throw new Error('Set EKG_DEMO_PASSWORD');
if (!BROWSER) throw new Error('No Edge/Chrome found; set BROWSER_PATH');
mkdirSync(OUT, { recursive: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const PORT = 9333;
const browser = spawn(BROWSER, ['--headless=new', `--remote-debugging-port=${PORT}`,
  `--user-data-dir=${mkdtempSync(join(tmpdir(), 'ekg-shots-'))}`, '--hide-scrollbars', '--no-first-run', 'about:blank'],
{ stdio: 'ignore' });

let targets;
for (let i = 0; i < 50 && !targets; i++) {
  try { targets = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json(); } catch { await sleep(200); }
}
const ws = new WebSocket(targets.find((t) => t.type === 'page').webSocketDebuggerUrl);
await new Promise((r) => { ws.onopen = r; });
let seq = 0;
const pending = new Map();
ws.onmessage = (e) => {
  const m = JSON.parse(e.data);
  if (m.id && pending.has(m.id)) {
    const { res, rej } = pending.get(m.id);
    pending.delete(m.id);
    if (m.error) rej(new Error(m.error.message)); else res(m.result);
  }
};
const send = (method, params = {}) => new Promise((res, rej) => {
  const id = ++seq;
  pending.set(id, { res, rej });
  ws.send(JSON.stringify({ id, method, params }));
});
const evaluate = async (expression) => {
  const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || 'evaluate failed');
  return r.result.value;
};
await send('Page.enable');
await send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });

let loads = 0;
async function go(hash) {
  // A different query string forces a full load, so the SPA re-reads its session.
  await send('Page.navigate', { url: `${BASE}/?load=${++loads}${hash}` });
  await sleep(900);
}
async function waitFor(selector, timeout = 8000) {
  const ok = await evaluate(`(async () => { const t = Date.now(); while (Date.now() - t < ${timeout}) {
    if (document.querySelector(${JSON.stringify(selector)})) return true; await new Promise(r => setTimeout(r, 100)); } return false; })()`);
  if (!ok) throw new Error(`Timed out waiting for ${selector}`);
}
async function signIn(user) {
  await go('');
  await evaluate(`sessionStorage.setItem('ekg.auth', JSON.stringify({ token: btoa(${JSON.stringify(`${user}:${PASSWORD}`)}), username: '${user}' })); true`);
}
const signOut = () => evaluate(`sessionStorage.clear(); true`);
async function apiCall(path, method = 'GET', body) {
  return evaluate(`(async () => { const a = JSON.parse(sessionStorage.getItem('ekg.auth'));
    const r = await fetch(${JSON.stringify(path)}, { method: '${method}', headers: { Authorization: 'Basic ' + a.token,
      ${body ? "'Content-Type': 'application/json'" : ''} }, ${body ? `body: ${JSON.stringify(JSON.stringify(body))}` : ''} });
    return { status: r.status, body: await r.json().catch(() => null) }; })()`);
}
async function shot(name, { full = true } = {}) {
  await sleep(250);
  let clip;
  if (full) {
    const m = await send('Page.getLayoutMetrics');
    clip = { x: 0, y: 0, width: 1280, height: Math.min(Math.ceil(m.cssContentSize.height), 4000), scale: 1 };
  }
  const r = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: full, clip });
  writeFileSync(join(OUT, name), Buffer.from(r.data, 'base64'));
  console.log('saved', name);
}
async function ask(question, { open = [] } = {}) {
  await go('#/ask');
  await waitFor('#question');
  await evaluate(`document.querySelector('#question').value = ${JSON.stringify(question)};
    document.querySelector('form button[type=submit]').click(); true`);
  await waitFor('#answer .answer-text');
  await evaluate(`[...document.querySelectorAll('#answer details')].forEach(d => {
    const t = d.querySelector('summary').textContent; d.open = ${JSON.stringify(open)}.some(o => t.startsWith(o)) || !!d.querySelector('.stars'); }); true`);
}

try {
  // ---- Signed out
  await go('');
  await signOut();
  await go('');
  await waitFor('.login');
  await shot('01-sign-in.png', { full: false });

  // ---- Employee
  await signIn('employee');
  await ask('How many annual leave days do I get and how do I reset my VPN token?');
  await shot('02-ask-answer-with-citations.png');
  await evaluate(`[...document.querySelectorAll('#answer details')].forEach(d => d.open = true); true`);
  await shot('03-agent-evidence-and-trace.png');
  await ask('What is the hotel cap per night?');
  await shot('04-conflicting-sources.png');
  await ask('What are the salary bands for L5 engineers?', { open: ['Goals'] });
  await shot('05-restricted-question-abstains.png');
  await ask('How many days per week can I work remotely?', { open: ['Evidence'] });
  await shot('06-prompt-injection-neutralised.png');
  await ask('How do I reset my VPN token?');
  await evaluate(`(async () => { const f = document.querySelector('#answer form'); f.querySelector('input[value="5"]').click();
    f.querySelector('textarea').value = 'Correct, and the citation opened the right section.';
    f.querySelector('button[type=submit]').click(); await new Promise(r => setTimeout(r, 800)); return true; })()`);
  await shot('07-feedback-saved.png');

  await go('#/search');
  await waitFor('#q');
  await evaluate(`document.querySelector('#q').value = 'carry over unused leave days'; document.querySelector('#category').value = 'HR';
    document.querySelector('form button[type=submit]').click(); true`);
  await waitFor('.result');
  await shot('08-search-with-filters.png');
  await evaluate(`document.querySelector('.result a.title').click(); true`);
  await waitFor('.content');
  await shot('09-source-preview.png', { full: false });

  // ---- Lifecycle: engineer uploads (pending) → admin queue → approve → revision → re-index → obsolete chunk
  await signIn('engineer');
  const upload = await evaluate(`(async () => { const a = JSON.parse(sessionStorage.getItem('ekg.auth')); const fd = new FormData();
    fd.append('file', new Blob([${JSON.stringify(readFileSync('docs/samples/parking-policy.md', 'utf8'))}], { type: 'text/markdown' }), 'parking-policy.md');
    fd.append('title', 'Parking Policy'); fd.append('category', 'Facilities'); fd.append('accessLevel', 'INTERNAL');
    const r = await fetch('/api/knowledge/documents', { method: 'POST', headers: { Authorization: 'Basic ' + a.token }, body: fd });
    return { status: r.status, body: await r.json() }; })()`);
  console.log('upload:', upload.status, upload.body.code || upload.body.status);

  await signIn('admin');
  const docs = await apiCall('/api/knowledge/documents?category=Facilities&size=5');
  const parking = docs.body.content.find((d) => d.title === 'Parking Policy');
  await go('#/documents');
  await waitFor('tbody tr');
  await evaluate(`document.querySelector('details.card').open = false; true`);
  await shot('10-documents-approval-queue.png', { full: false });
  if (parking.status === 'PENDING_APPROVAL') await apiCall(`/api/knowledge/documents/${parking.id}/approve`, 'POST');

  const before = await apiCall('/api/knowledge/search', 'POST', { query: 'visitor parking hours', category: 'Facilities', limit: 1 });
  const oldChunk = before.body.results[0]?.chunkId;
  await signIn('engineer');
  await evaluate(`(async () => { const a = JSON.parse(sessionStorage.getItem('ekg.auth')); const fd = new FormData();
    fd.append('file', new Blob([${JSON.stringify(readFileSync('docs/samples/parking-policy-v2.md', 'utf8'))}], { type: 'text/markdown' }), 'parking-policy-v2.md');
    await fetch('/api/knowledge/documents/${parking.id}/revisions', { method: 'POST', headers: { Authorization: 'Basic ' + a.token }, body: fd }); return true; })()`);
  await signIn('admin');
  await go('#/analytics');
  await waitFor('.bars');
  await shot('11-analytics.png');
  const current = await apiCall(`/api/knowledge/documents/${parking.id}`);
  await apiCall(`/api/knowledge/documents/${parking.id}/reindex?expectedVersion=${current.body.document.version}`, 'PUT');
  await go(`#/documents/${parking.id}`);
  await waitFor('.citations');
  await shot('12-document-versions-after-reindex.png', { full: false });
  if (oldChunk) {
    await go(`#/source/${oldChunk}?hl=visitor,park,hour`);
    await waitFor('.content');
    await shot('13-obsolete-version-flagged.png', { full: false });
  }

  // ---- Test results page built from Surefire XML + the TC-05-12 evaluation report
  const dir = 'target/surefire-reports';
  const rows = existsSync(dir) ? readdirSync(dir).filter((f) => f.startsWith('TEST-') && f.endsWith('.xml')).map((f) => {
    const x = readFileSync(join(dir, f), 'utf8');
    const attr = (n) => Number((x.match(new RegExp(`<testsuite[^>]*\\s${n}="([\\d.]+)"`)) || [])[1] || 0);
    return { name: f.slice(5, -4).replace('com.salman.ekg.', ''), tests: attr('tests'), failures: attr('failures'), errors: attr('errors'), time: attr('time') };
  }).filter((r) => r.tests > 0) : [];
  const total = rows.reduce((a, r) => ({ tests: a.tests + r.tests, bad: a.bad + r.failures + r.errors }), { tests: 0, bad: 0 });
  const evalMd = existsSync('target/eval-report.md') ? readFileSync('target/eval-report.md', 'utf8') : '';
  const evalRows = evalMd.split('\n').filter((l) => l.startsWith('| Q')).map((l) => l.split('|').slice(1, -1).map((c) => c.trim()));
  const esc = (s) => String(s).replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));
  const html = `<html><body style="font:14px system-ui;margin:24px;color:#0b0b0b;background:#fcfcfb">
    <h2 style="margin:0 0 4px">Test results: ${total.tests} tests, ${total.bad} failures</h2>
    <p style="color:#52514e;margin:0 0 12px">mvnw test (JUnit 5 + Mockito unit tests, MockMvc + PostgreSQL integration tests)</p>
    <table style="border-collapse:collapse;width:100%">${['Suite', 'Tests', 'Failures', 'Errors', 'Time (s)'].map((h) => `<th style="text-align:left;border-bottom:1px solid #ddd;padding:5px">${h}</th>`).join('')}
    ${rows.map((r) => `<tr>${[esc(r.name), r.tests, r.failures, r.errors, r.time.toFixed(2)].map((c) => `<td style="border-bottom:1px solid #eee;padding:5px">${c}</td>`).join('')}</tr>`).join('')}</table>
    <h3 style="margin:20px 0 6px">TC-05-12 answer-quality evaluation</h3>
    <table style="border-collapse:collapse;width:100%">${['ID', 'Role', 'Question', 'Expected', 'Actual', 'Fact support', 'Cit. recall', 'Cit. precision'].map((h) => `<th style="text-align:left;border-bottom:1px solid #ddd;padding:5px">${h}</th>`).join('')}
    ${evalRows.map((r) => `<tr>${r.map((c) => `<td style="border-bottom:1px solid #eee;padding:5px">${esc(c)}</td>`).join('')}</tr>`).join('')}</table></body></html>`;
  await send('Page.navigate', { url: 'about:blank' });
  await sleep(300);
  const { frameTree } = await send('Page.getFrameTree');
  await send('Page.setDocumentContent', { frameId: frameTree.frame.id, html });
  await shot('14-test-results.png');
} finally {
  ws.close();
  browser.kill();
}

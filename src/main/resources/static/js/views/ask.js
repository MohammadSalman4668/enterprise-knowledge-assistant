// Ask screen: question → agent run → answer with citations, evidence, trace and feedback form.
import { api } from '../api.js';
import {
  h, mount, loading, errorBox, outcomeBadge, badge, levelBadge, withBusy, toast, categoryList,
} from '../ui.js';

const EXAMPLES = [
  'How many days of annual leave do I get?',
  'How many annual leave days do I get and how do I reset my VPN token?',
];

// Keeps the signed-in user's last answer when they open a cited source and come back.
// Signing out reloads the page, so it never carries over to another user.
let lastResult = null;

export function renderAsk(app) {
  const question = h('textarea', { id: 'question', maxlength: 1000, required: true, placeholder: 'Ask a question about policies, runbooks or FAQs…' });
  const category = h('input', { id: 'category', list: 'cat-list', placeholder: 'Any', maxlength: 64 });
  const submit = h('button', { class: 'primary', type: 'submit' }, 'Ask');
  const result = h('div', { id: 'answer' });

  const form = h('form', {
    class: 'card',
    onsubmit: async (e) => {
      e.preventDefault();
      if (!question.value.trim()) {
        mount(result, errorBox({ code: 'VALIDATION_FAILED', message: 'Please enter a question.' }));
        return;
      }
      mount(result, h('div', { class: 'card' }, loading('Researching: planning → searching → reading evidence → validating citations…')));
      await withBusy(submit, 'Researching…', async () => {
        try {
          lastResult = await api('/api/knowledge/ask', {
            method: 'POST', body: { question: question.value.trim(), category: category.value.trim() || null },
          });
          mount(result, renderAnswer(lastResult));
        } catch (err) {
          mount(result, h('div', { class: 'card' }, errorBox(err)));
        }
      });
    },
  },
  h('div', { class: 'field' }, h('label', { for: 'question' }, 'Your question'), question),
  h('div', { class: 'row' },
    h('div', { class: 'field', style: 'min-width:180px' }, h('label', { for: 'category' }, 'Limit to category (optional)'), category, categoryList('cat-list')),
    h('div', { class: 'spacer' }), submit),
  h('div', { class: 'small muted' }, 'Try:'),
  h('div', { class: 'chips' }, EXAMPLES.map((q) => h('button', {
    type: 'button', class: 'chip', onclick: () => { question.value = q; question.focus(); },
  }, q))));

  // Enter asks (same as clicking Ask); Shift+Enter inserts a new line.
  question.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
      e.preventDefault();
      if (!submit.disabled) form.requestSubmit();
    }
  });

  mount(app,
    h('div', { class: 'page-head' }, h('div', {}, h('h1', {}, 'Ask the knowledge base'),
      h('p', { class: 'muted' }, 'Answers are grounded in documents you are allowed to read, with citations you can open.'))),
    form, result);
  if (lastResult) {
    question.value = lastResult.question;
    mount(result, renderAnswer(lastResult));
  }
}

function terms(res) {
  return [...new Set(res.subQuestions.flatMap((s) => s.keyTerms))].join(',');
}

function sourceLink(res, ref) {
  const ev = res.evidence.find((e) => e.ref === ref);
  return ev ? `/source/${ev.chunkId}?hl=${encodeURIComponent(terms(res))}&ref=${ref}` : null;
}

/** Renders answer text, turning [E1] markers into links to the cited passage. */
function answerText(res) {
  const out = h('div', { class: 'answer-text' });
  const parts = res.answer.split(/(\[E\d+\])/g);
  for (const part of parts) {
    const m = part.match(/^\[(E\d+)\]$/);
    if (m) {
      const href = sourceLink(res, m[1]);
      out.append(href ? h('a', { class: 'cite', href, title: 'Open source passage' }, m[1]) : part);
    } else {
      out.append(part);
    }
  }
  return out;
}

export function renderAnswer(res) {
  const card = h('div', { class: 'card' },
    h('div', { class: 'row' },
      outcomeBadge(res.outcome),
      res.mode === 'SIMULATED' ? badge('Simulated agent', 'warning', '⚙') : badge(`Live · ${res.model}`, 'good', '✦'),
      res.degraded ? badge('Degraded: fallback used', 'serious', '↯') : null,
      badge(`retrieval: ${res.retrievalMode.toLowerCase()}`),
      h('span', { class: 'spacer' }),
      h('span', { class: 'small muted' }, `${res.toolCalls} tool calls · ${res.latencyMs} ms · answer #${res.answerId}`)),
    answerText(res),
    res.warnings.length ? h('div', { class: 'callout warning' }, res.warnings.map((w) => h('div', {}, `⚠ ${w}`))) : null,
    res.conflicts.length ? h('div', { class: 'callout serious' },
      h('strong', {}, 'Conflicting evidence'),
      res.conflicts.map((c) => h('div', {}, `${c.quantity}: `,
        c.facts.map((f, i) => [i ? ' vs ' : '', `${f.documentTitle} says ${f.value} `,
          h('a', { class: 'cite', href: sourceLink(res, f.ref) }, f.ref)])))) : null,
    res.citations.length ? h('div', {},
      h('h3', {}, 'Sources'),
      h('ul', { class: 'citations' }, res.citations.map((c) => h('li', {},
        h('a', { class: 'cite', href: sourceLink(res, c.ref) }, c.ref), ' ',
        h('a', { href: sourceLink(res, c.ref) }, c.title), ' ',
        h('span', { class: 'muted small' }, `· v${c.version} · ${c.location}`))))) : null,
    feedbackForm(res),
    evidencePanel(res),
    gatePanel(res),
    tracePanel(res));
  return card;
}

function feedbackForm(res) {
  let rating = 0;
  const stars = [1, 2, 3, 4, 5].map((n) => {
    const id = `star-${res.answerId}-${n}`;
    const label = h('label', { for: id, title: `${n} star${n > 1 ? 's' : ''}` }, '★');
    const input = h('input', {
      type: 'radio', name: 'rating', id, value: n,
      onchange: () => {
        rating = n;
        stars.forEach(([, l], i) => l.classList.toggle('on', i < n));
      },
    });
    return [input, label];
  });
  const docs = [...new Map(res.citations.map((c) => [c.documentId, c.title])).entries()];
  const docSelect = h('select', { id: `fb-doc-${res.answerId}` },
    h('option', { value: '' }, docs.length ? 'Whole answer (first cited document)' : 'Whole answer'),
    docs.map(([id, title]) => h('option', { value: id }, title)));
  const comment = h('textarea', { id: `fb-comment-${res.answerId}`, maxlength: 1000, placeholder: 'What was good or wrong? (optional)' });
  const status = h('div', {});
  const submit = h('button', { type: 'submit', class: 'primary' }, 'Send feedback');
  const form = h('form', {
    onsubmit: async (e) => {
      e.preventDefault();
      if (!rating) {
        mount(status, errorBox({ code: 'VALIDATION_FAILED', message: 'Choose a rating from 1 to 5 stars.' }));
        return;
      }
      let saved = null;
      await withBusy(submit, 'Sending…', async () => {
        try {
          saved = await api('/api/knowledge/feedback', {
            method: 'POST',
            body: { answerId: res.answerId, documentId: docSelect.value ? Number(docSelect.value) : null, rating, comment: comment.value },
          });
        } catch (err) {
          mount(status, errorBox(err));
        }
      });
      // Disable only after withBusy has restored the button, otherwise it would be re-enabled.
      if (saved) {
        mount(status, h('div', { class: 'callout info' }, `✓ Thanks! Feedback #${saved.id} saved${saved.documentTitle ? ` for “${saved.documentTitle}”` : ''}.`));
        form.querySelectorAll('input,select,textarea,button').forEach((el) => { el.disabled = true; });
        toast('Feedback saved');
      }
    },
  },
  h('details', { open: true },
    h('summary', {}, 'Rate this answer'),
    h('div', { class: 'stars', role: 'radiogroup', 'aria-label': 'Rating' }, stars),
    h('div', { class: 'fields' },
      h('div', { class: 'field' }, h('label', { for: docSelect.id }, 'About document'), docSelect),
      h('div', { class: 'field' }, h('label', { for: comment.id }, 'Comment'), comment)),
    status, submit));
  return form;
}

function evidencePanel(res) {
  return h('details', {},
    h('summary', {}, `Evidence the agent read (${res.evidence.length})`),
    res.evidence.length ? res.evidence.map((e) => h('div', { class: 'evidence' },
      h('div', { class: 'row' },
        h('a', { class: 'cite', href: sourceLink(res, e.ref) }, e.ref),
        h('strong', {}, e.title), levelBadge(e.accessLevel),
        h('span', { class: 'small muted' }, `v${e.version} · ${e.location} · score ${e.score}`),
        e.cited ? badge('cited', 'good', '✓') : badge('not cited'),
        e.injectionFlags.length ? badge(`injection neutralised: ${e.injectionFlags.join(', ')}`, 'critical', '⛨') : null),
      h('pre', {}, e.text)))
      : h('p', { class: 'muted' }, 'No passages were read: nothing relevant was found among the documents you can access.'));
}

function gatePanel(res) {
  return h('details', {},
    h('summary', {}, 'Goals and evidence check'),
    h('div', { class: 'table-wrap' }, h('table', {},
      h('thead', {}, h('tr', {}, h('th', {}, '#'), h('th', {}, 'Sub-question'), h('th', {}, 'Key terms'),
        h('th', { class: 'num' }, 'Word coverage'), h('th', { class: 'num' }, 'Meaning similarity'), h('th', {}, 'Supported by'))),
      h('tbody', {}, res.subQuestions.map((s) => h('tr', {},
        h('td', {}, s.index + 1), h('td', {}, s.text), h('td', { class: 'mono' }, s.keyTerms.join(', ')),
        h('td', { class: 'num' }, s.bestCoverage),
        h('td', { class: 'num' }, s.bestSimilarity == null ? '-' : s.bestSimilarity),
        h('td', {}, s.supported ? s.supportingRefs.join(', ') : badge('insufficient', 'warning', '!'))))))),
    res.validationIssues.length
      ? h('div', { class: 'callout warning' }, h('strong', {}, 'Validation corrections'), res.validationIssues.map((i) => h('div', { class: 'small' }, i)))
      : h('p', { class: 'small muted' }, '✓ Every sentence is cited and supported by its evidence.'));
}

function tracePanel(res) {
  return h('details', {},
    h('summary', {}, `Agent trace (${res.trace.length} steps)`),
    h('div', { class: 'table-wrap' }, h('table', {},
      h('thead', {}, h('tr', {}, h('th', {}, 'Step'), h('th', {}, 'Phase'), h('th', {}, 'Tool / input'),
        h('th', {}, 'Status'), h('th', {}, 'Detail'), h('th', { class: 'num' }, 'ms'))),
      h('tbody', {}, res.trace.map((t) => h('tr', {},
        h('td', {}, t.step), h('td', {}, t.phase),
        h('td', { class: 'mono' }, t.tool ? `${t.tool}(${JSON.stringify(t.input)})` : (Object.keys(t.input || {}).length ? JSON.stringify(t.input) : '')),
        h('td', {}, t.status === 'OK' ? badge('ok', 'good', '✓')
          : t.status === 'DENIED' ? badge('denied', 'critical', '✕') : badge(t.status.toLowerCase(), 'warning', '!')),
        h('td', { class: 'small' }, t.detail), h('td', { class: 'num' }, t.durationMs)))))));
}

#!/usr/bin/env node
// Measure the brief's remaining success criterion: a 20-page upload answered
// with citations in under 90 seconds.
//
// The probe drives the real API of a running stack, so it measures what a user
// waits for: PDF transfer, server-side extraction/embedding, retrieval and the
// model call. It registers a throwaway account (live-<8hex>@example.com, which
// scripts/purge-test-accounts.sh also recognises), uploads a generated PDF,
// polls until the document is READY, asks one question whose answer is planted
// in the text, checks the stored answer carries citations, prints a timing
// breakdown, writes a JSON report, and deletes the account again.
//
//   node scripts/measure-ask-latency.mjs              # measure, 20 pages, 90 s budget
//   node scripts/measure-ask-latency.mjs --keep       # leave the account for inspection
//   node scripts/measure-ask-latency.mjs --pages 5 --budget-ms 45000
//
// This spends one model call and needs the stack running
// (docker compose up -d, http://localhost:8080). Exit code 0 = criterion met,
// 1 = budget missed, missing citations or an error.
//
// Options:
//   --base <url>        stack origin (default http://localhost:8080)
//   --pages <n>         PDF length (default 20)
//   --budget-ms <n>     end-to-end deadline (default 90000)
//   --timeout-ms <n>    per-stage deadline for processing and answering
//   --json <path>       report path (default backend/target/perf/ask-latency.json)
//   --keep              keep the probe account instead of deleting it
//   -h, --help

import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomBytes } from 'node:crypto';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

const args = process.argv.slice(2);
if (args.includes('-h') || args.includes('--help')) {
  const text = await readFile(fileURLToPath(import.meta.url), 'utf8');
  const help = [];
  for (const line of text.split('\n').slice(1)) {
    if (!line.startsWith('//')) break;
    help.push(line.replace(/^\/\/ ?/, ''));
  }
  console.log(help.join('\n'));
  process.exit(0);
}

function option(name, fallback) {
  const index = args.indexOf(`--${name}`);
  if (index === -1) return fallback;
  const value = args[index + 1];
  if (value === undefined || value.startsWith('--')) {
    throw new Error(`--${name} needs a value`);
  }
  return value;
}

const base = option('base', 'http://localhost:8080').replace(/\/$/, '');
const pages = Number(option('pages', '20'));
const budgetMs = Number(option('budget-ms', '90000'));
const timeoutMs = Number(option('timeout-ms', '180000'));
const reportPath = resolve(root, option('json', 'backend/target/perf/ask-latency.json'));
const reportDir = dirname(reportPath);
const probePdfPath = join(reportDir, `probe-${pages}-pages.pdf`);
const keepAccount = args.includes('--keep');

if (!Number.isInteger(pages) || pages < 1 || pages > 200) {
  console.error('--pages must be an integer between 1 and 200');
  process.exit(2);
}

const PLANTED_FACT = 'The Meridian facility emitted 4,812 tonnes of carbon dioxide in 2025.';
const QUESTION = 'How many tonnes of carbon dioxide did the Meridian facility emit in 2025?';
const REGIONS = ['Northwind', 'Harbour Point', 'Ashfield', 'Cedar Vale', 'Kestrel Bay', 'Lowmoor'];
const UNITS = ['throughput', 'energy draw', 'water use', 'maintenance hours', 'staffing', 'waste diverted'];

/**
 * Roughly a printed page of prose (about 350 words), so the probe measures a
 * realistic document rather than 20 nearly empty pages. Deterministic: the
 * same run length always produces the same text.
 */
function pageParagraphs(pageIndex) {
  const paragraphs = [];
  for (let paragraph = 0; paragraph < 6; paragraph += 1) {
    const region = REGIONS[(pageIndex + paragraph) % REGIONS.length];
    const unit = UNITS[(pageIndex * 2 + paragraph) % UNITS.length];
    paragraphs.push(
      `Section ${pageIndex + 1}.${paragraph + 1} records ${unit} at the ${region} site ` +
        `for the ${2018 + ((pageIndex + paragraph) % 7)} reporting year. ` +
        `Site managers confirmed the readings against the previous audit and ` +
        `reconciled every variance above two percent before the draft was circulated. ` +
        `The method note explains how meters were calibrated, which shifts were sampled, ` +
        `and why the measured figures differ from the planning assumptions recorded earlier.`,
    );
  }
  return paragraphs;
}

/** Build a structurally valid PDF with one text block per page. */
function buildPdf(pageTexts) {
  const escape = (text) =>
    text.replace(/\\/g, '\\\\').replace(/\(/g, '\\(').replace(/\)/g, '\\)');
  const fontObject = 3 + 2 * pageTexts.length;
  const objects = [];
  const kids = pageTexts.map((_, index) => `${3 + 2 * index} 0 R`).join(' ');
  objects[0] = '<< /Type /Catalog /Pages 2 0 R >>';
  objects[1] = `<< /Type /Pages /Kids [${kids}] /Count ${pageTexts.length} >>`;
  pageTexts.forEach((text, index) => {
    const pageObject = 3 + 2 * index;
    objects[pageObject - 1] =
      '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] ' +
      `/Resources << /Font << /F1 ${fontObject} 0 R >> >> /Contents ${pageObject + 1} 0 R >>`;
    const stream = `BT /F1 11 Tf 54 730 Td (${escape(text)}) Tj ET`;
    objects[pageObject] =
      `<< /Length ${Buffer.byteLength(stream, 'latin1')} >>\nstream\n${stream}\nendstream`;
  });
  objects[fontObject - 1] = '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>';

  const chunks = [];
  let offset = 0;
  const push = (value) => {
    const bytes = Buffer.from(value, 'latin1');
    chunks.push(bytes);
    offset += bytes.length;
  };
  push('%PDF-1.4\n%\xE2\xE3\xCF\xD3\n');
  const offsets = [];
  objects.forEach((object, index) => {
    offsets.push(offset);
    push(`${index + 1} 0 obj\n${object}\nendobj\n`);
  });
  const xrefOffset = offset;
  let xref = `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  for (const objectOffset of offsets) {
    xref += `${String(objectOffset).padStart(10, '0')} 00000 n \n`;
  }
  push(xref);
  push(`trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xrefOffset}\n%%EOF\n`);
  return Buffer.concat(chunks);
}

function probeText(pageIndex) {
  const paragraphs = pageParagraphs(pageIndex);
  // The fact must exist for short runs too, so clamp the first page down.
  const planted = pageIndex === Math.min(3, pages - 1) || pageIndex === pages - 2;
  if (planted) paragraphs.splice(3, 0, PLANTED_FACT);
  return [`Northwind operational review, page ${pageIndex + 1} of ${pages}.`, ...paragraphs].join(' ');
}

// --- HTTP with a minimal cookie jar, mirroring the browser's CSRF contract ---

const cookies = new Map();
let csrfToken = null;
let csrfHeader = 'X-XSRF-TOKEN';

function storeCookies(response) {
  const list = response.headers.getSetCookie?.() ?? [];
  for (const cookie of list) {
    const pair = cookie.split(';')[0];
    const separator = pair.indexOf('=');
    if (separator > 0) cookies.set(pair.slice(0, separator), pair.slice(separator + 1));
  }
}

function cookieHeader() {
  return [...cookies].map(([name, value]) => `${name}=${value}`).join('; ');
}

async function fetchCsrf() {
  const response = await fetch(`${base}/api/v1/auth/csrf`, {
    headers: { ...(cookies.size ? { Cookie: cookieHeader() } : {}) },
  });
  storeCookies(response);
  if (!response.ok) throw new Error(`GET /auth/csrf returned ${response.status}`);
  const body = await response.json();
  csrfToken = body.token;
  csrfHeader = body.headerName || csrfHeader;
}

async function api(path, { method = 'GET', body, raw, accept } = {}) {
  const mutating = method !== 'GET';
  if (mutating && !csrfToken) await fetchCsrf();

  const send = () => {
    const headers = { ...(cookies.size ? { Cookie: cookieHeader() } : {}) };
    if (accept) headers.Accept = accept;
    if (body !== undefined && !raw) headers['Content-Type'] = 'application/json';
    if (mutating && csrfToken) headers[csrfHeader] = csrfToken;
    return fetch(`${base}${path}`, {
      method,
      headers,
      body: raw ?? (body === undefined ? undefined : JSON.stringify(body)),
    });
  };

  let response = await send();
  if (mutating && response.status === 403) {
    csrfToken = null;
    await fetchCsrf();
    response = await send();
  }
  storeCookies(response);

  if (response.status === 204) return null;
  const text = await response.text();
  const parsed = text ? JSON.parse(text) : null;
  if (!response.ok) {
    const message = parsed?.message ?? text.slice(0, 200);
    throw new Error(`${method} ${path} returned ${response.status}: ${message}`);
  }
  return parsed;
}

const sleep = (ms) => new Promise((resolveSleep) => setTimeout(resolveSleep, ms));
const seconds = (ms) => `${(ms / 1000).toFixed(1)} s`;

async function waitFor(check, { deadline, interval = 500, what }) {
  for (;;) {
    const result = await check();
    if (result) return result;
    if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}`);
    await sleep(interval);
  }
}

const email = `live-${randomBytes(4).toString('hex')}@example.com`;
const password = `Probe-${randomBytes(6).toString('hex')}`;
const filename = `probe-${pages}-pages.pdf`;
let accountCreated = false;

try {
  await fetchCsrf();

  const pageTexts = Array.from({ length: pages }, (_, index) => probeText(index));
  const pdf = buildPdf(pageTexts);
  const textChars = pageTexts.reduce((total, text) => total + text.length, 0);

  const registered = await api('/api/v1/auth/register', {
    method: 'POST',
    body: { email, password, displayName: 'Latency probe' },
  });
  if (!registered?.authenticated) {
    throw new Error('registration did not create a signed-in account (is the account already registered?)');
  }
  accountCreated = true;

  await mkdir(reportDir, { recursive: true });
  await writeFile(probePdfPath, pdf);

  const uploadStarted = Date.now();
  const form = new FormData();
  form.append('file', new Blob([pdf], { type: 'application/pdf' }), filename);
  const uploaded = await api('/api/v1/documents', { method: 'POST', raw: form });
  const uploadMs = Date.now() - uploadStarted;

  const doc = await waitFor(
    async () => {
      const current = await api(`/api/v1/documents/${uploaded.id}`);
      if (current.status === 'FAILED') {
        throw new Error(`processing failed: ${current.errorMessage ?? current.errorCode ?? 'unknown error'}`);
      }
      return current.status === 'READY' ? current : null;
    },
    { deadline: Date.now() + timeoutMs, what: 'document to become READY' },
  );
  const processingMs = Date.now() - uploadStarted - uploadMs;

  const conversation = await api('/api/v1/conversations', {
    method: 'POST',
    body: { title: `Latency probe ${new Date().toISOString()}`, documentIds: [doc.id] },
  });

  const answerStarted = Date.now();
  const message = await api(`/api/v1/conversations/${conversation.id}/messages`, {
    method: 'POST',
    body: { question: QUESTION, documentIds: [] },
  });
  const answerMs = Date.now() - answerStarted;
  const totalMs = Date.now() - uploadStarted;

  const citations = message.citations ?? [];
  const citesInText = /\[S\d+\]/.test(message.content ?? '');

  const failures = [];
  if (totalMs > budgetMs) failures.push(`total ${seconds(totalMs)} exceeds budget ${seconds(budgetMs)}`);
  if (doc.pageCount !== pages) failures.push(`document has ${doc.pageCount} pages, expected ${pages}`);
  if (message.status !== 'COMPLETED') failures.push(`answer status is ${message.status}`);
  if (citations.length === 0) failures.push('answer has no citations');
  if (!citesInText) failures.push('answer text contains no [S#] citation markers');
  if (message.evidenceStatus === 'INSUFFICIENT') failures.push('model reported insufficient evidence');

  const report = {
    measuredAt: new Date().toISOString(),
    base,
    pages,
    budgetMs,
    passed: failures.length === 0,
    failures,
    timingsMs: { upload: uploadMs, processing: processingMs, answer: answerMs, total: totalMs },
    document: {
      id: doc.id,
      filename: doc.originalFilename,
      pageCount: doc.pageCount,
      chunkCount: doc.chunkCount,
      textChars,
      charsPerPage: Math.round(textChars / pages),
      status: doc.status,
    },
    answer: {
      status: message.status,
      evidenceStatus: message.evidenceStatus,
      model: message.model,
      latencyMs: message.latencyMs,
      citations: citations.map((citation) => ({
        index: citation.citationIndex,
        page: citation.pageNumber,
        document: citation.documentName,
      })),
      preview: (message.content ?? '').slice(0, 240),
    },
  };

  console.log('VerityAI ask latency probe');
  console.log(
    `  document   ${filename} — ${doc.pageCount} pages, ${doc.chunkCount ?? '?'} chunks, ` +
      `${textChars} chars (${Math.round(textChars / pages)} per page)`,
  );
  console.log(`  citations  ${citations.length}${citations.length ? ` (pages ${citations.map((c) => c.pageNumber).join(', ')})` : ''}`);
  console.log(`  budget     ${seconds(budgetMs)}`);
  console.log('  ----');
  console.log(`  upload     ${seconds(uploadMs)}`);
  console.log(`  processing ${seconds(processingMs)}`);
  console.log(`  answer     ${seconds(answerMs)}`);
  console.log(`  total      ${seconds(totalMs)}  ${report.passed ? 'PASS' : 'FAIL'}`);
  if (failures.length) failures.forEach((failure) => console.log(`  failure    ${failure}`));
  console.log(`  answer     ${(message.content ?? '').replace(/\s+/g, ' ').slice(0, 200)}`);

  await writeFile(reportPath, `${JSON.stringify(report, null, 2)}\n`);
  console.log(`  report     ${reportPath}`);

  if (failures.length) process.exitCode = 1;
} catch (error) {
  console.error(`probe failed: ${error.message}`);
  console.error('If the answer step failed, check the stack has LLM_API_KEY/GROQ_API_KEY configured.');
  if (error.message.includes('429') && error.message.includes('register')) {
    console.error(
      'Registration is capped per IP (RATE_LIMIT_REGISTER_PER_HOUR_IP, 10/hour by default), ' +
        'so repeated runs need to wait for the window or raise that setting for local runs.',
    );
  }
  process.exitCode = 1;
} finally {
  if (accountCreated && !keepAccount) {
    try {
      await api('/api/v1/users/me', { method: 'DELETE', body: { password } });
      console.log('  account    deleted');
    } catch (error) {
      console.error(`  account    could not be deleted (${error.message}); run ./scripts/purge-test-accounts.sh --apply --purge-files`);
    }
  } else if (accountCreated) {
    console.log(`  account    kept: ${email}`);
  }
}

#!/usr/bin/env node
/**
 * Minimal SMTP sink + HTTP mailbox used by the Playwright E2E suite.
 *
 * The backend is pointed at 127.0.0.1:1025 with STARTTLS/AUTH disabled. Every
 * captured message is parsed (headers, quoted-printable/base64, single-part or
 * multipart) and exposed over a tiny HTTP API on 127.0.0.1:1026 so tests can
 * retrieve the password-reset link without a real mailbox:
 *
 *   GET    /health              -> { ok, messages }
 *   GET    /messages            -> captured messages (newest last)
 *   GET    /messages?to=a@b.c   -> filter by envelope recipient
 *   GET    /messages/latest     -> newest message (optional ?to=)
 *   DELETE /messages            -> clear the mailbox
 *
 * The process is intentionally dependency-free so it starts instantly in CI.
 */
import { randomUUID } from 'node:crypto';
import { createServer as createHttpServer } from 'node:http';
import { createServer as createTcpServer } from 'node:net';

const SMTP_PORT = Number(process.env.E2E_SMTP_PORT ?? 1025);
const HTTP_PORT = Number(process.env.E2E_MAIL_API_PORT ?? 1026);
const HOST = '127.0.0.1';

/** @typedef {{ id: string, from: string, to: string[], subject: string, text: string, html: string, raw: string, receivedAt: string }} CapturedMessage */

/** @type {CapturedMessage[]} */
const messages = [];

function log(message) {
  console.log(`[smtp-sink] ${message}`);
}

function extractAddress(value) {
  const match = value.match(/<([^>]*)>/);
  return (match ? match[1] : value).trim();
}

function splitHeaders(raw) {
  const normalized = raw.replace(/\r\n/g, '\n');
  const separator = normalized.indexOf('\n\n');
  const headerBlock = separator >= 0 ? normalized.slice(0, separator) : normalized;
  const body = separator >= 0 ? normalized.slice(separator + 2) : '';
  /** @type {Record<string, string>} */
  const headers = {};
  let lastKey = '';
  for (const line of headerBlock.split('\n')) {
    if (/^[ \t]/.test(line) && lastKey) {
      headers[lastKey] += ` ${line.trim()}`;
      continue;
    }
    const colon = line.indexOf(':');
    if (colon <= 0) continue;
    lastKey = line.slice(0, colon).trim().toLowerCase();
    headers[lastKey] = line.slice(colon + 1).trim();
  }
  return { headers, body };
}

function decodeQuotedPrintable(value) {
  const bytes = [];
  for (let i = 0; i < value.length; i += 1) {
    const char = value[i];
    if (char === '=') {
      const next = value[i + 1];
      const after = value[i + 2];
      if (next === '\n') {
        i += 1;
        continue;
      }
      if (next === '\r' && after === '\n') {
        i += 2;
        continue;
      }
      const hex = value.slice(i + 1, i + 3);
      if (/^[0-9A-Fa-f]{2}$/.test(hex)) {
        bytes.push(Number.parseInt(hex, 16));
        i += 2;
        continue;
      }
    }
    bytes.push(char.charCodeAt(0) & 0xff);
  }
  return Buffer.from(bytes).toString('utf8');
}

function decodeTransfer(body, encoding) {
  const normalized = (encoding ?? '').toLowerCase();
  if (normalized.includes('base64')) {
    return Buffer.from(body.replace(/\s+/g, ''), 'base64').toString('utf8');
  }
  if (normalized.includes('quoted-printable')) {
    return decodeQuotedPrintable(body);
  }
  return body;
}

function splitMultipart(body, boundary) {
  const parts = body.split(`--${boundary}`);
  const result = [];
  for (let i = 1; i < parts.length; i += 1) {
    let part = parts[i];
    if (part.startsWith('--')) break;
    part = part.replace(/^\n/, '').replace(/\n$/, '');
    if (part.trim()) result.push(part);
  }
  return result;
}

function parseAddressList(value) {
  return value
    .split(',')
    .map((entry) => extractAddress(entry))
    .filter(Boolean);
}

/**
 * Walks a MIME tree and collects the plain-text and HTML bodies.
 *
 * Mail clients routinely nest parts (`multipart/mixed` wrapping a
 * `multipart/alternative`, for example), so a single level is not enough.
 *
 * @param {Record<string, string>} headers
 * @param {string} body
 * @param {{ text: string, html: string }} acc
 */
function collectBodies(headers, body, acc) {
  // Keep the raw value: MIME boundaries are case-sensitive, so only the media
  // type comparison may be case-insensitive.
  const rawContentType = headers['content-type'] ?? 'text/plain';
  const contentType = rawContentType.toLowerCase();

  if (contentType.startsWith('multipart/')) {
    const boundaryMatch = rawContentType.match(/boundary="?([^";]+)"?/i);
    if (!boundaryMatch) return;
    for (const part of splitMultipart(body, boundaryMatch[1])) {
      const parsed = splitHeaders(part);
      collectBodies(parsed.headers, parsed.body, acc);
    }
    return;
  }

  const decoded = decodeTransfer(body, headers['content-transfer-encoding'] ?? '');
  if (contentType.startsWith('text/html')) acc.html += decoded;
  else if (contentType.startsWith('text/plain')) acc.text += decoded;
}

/**
 * @param {string} raw
 * @param {string} envelopeFrom
 * @param {string[]} envelopeTo
 * @returns {CapturedMessage}
 */
function parseMessage(raw, envelopeFrom, envelopeTo) {
  const { headers, body } = splitHeaders(raw);
  const bodies = { text: '', html: '' };
  collectBodies(headers, body, bodies);

  return {
    id: randomUUID(),
    from: envelopeFrom || headers['from'] || '',
    to: envelopeTo.length > 0 ? envelopeTo : parseAddressList(headers['to'] ?? ''),
    subject: headers['subject'] ?? '',
    text: bodies.text,
    html: bodies.html,
    raw,
    receivedAt: new Date().toISOString(),
  };
}

const smtpServer = createTcpServer((socket) => {
  socket.setEncoding('utf8');
  socket.write('220 verityai-e2e-smtp ESMTP\r\n');

  let buffer = '';
  let inData = false;
  /** @type {string[]} */
  let dataLines = [];
  let envelopeFrom = '';
  /** @type {string[]} */
  let recipients = [];

  const reply = (line) => socket.write(`${line}\r\n`);

  const resetEnvelope = () => {
    envelopeFrom = '';
    recipients = [];
  };

  const handleLine = (line) => {
    if (inData) {
      if (line === '.') {
        inData = false;
        const message = parseMessage(dataLines.join('\r\n'), envelopeFrom, recipients);
        messages.push(message);
        log(
          `captured "${message.subject || '(no subject)'}" to ${message.to.join(', ') || '(unknown)'}`,
        );
        dataLines = [];
        resetEnvelope();
        reply('250 2.0.0 Message accepted');
      } else {
        dataLines.push(line.startsWith('..') ? line.slice(1) : line);
        if (dataLines.length > 50_000) socket.destroy();
      }
      return;
    }

    const upper = line.toUpperCase();
    if (upper.startsWith('EHLO')) {
      reply('250-verityai-e2e-smtp');
      reply('250-8BITMIME');
      reply('250 SIZE 52428800');
    } else if (upper.startsWith('HELO')) {
      reply('250 verityai-e2e-smtp');
    } else if (upper.startsWith('MAIL FROM:')) {
      envelopeFrom = extractAddress(line.slice(10));
      reply('250 2.1.0 Sender OK');
    } else if (upper.startsWith('RCPT TO:')) {
      recipients.push(extractAddress(line.slice(8)));
      reply('250 2.1.5 Recipient OK');
    } else if (upper === 'DATA') {
      if (recipients.length === 0) {
        reply('503 5.5.1 Need RCPT first');
        return;
      }
      inData = true;
      dataLines = [];
      reply('354 End data with <CR><LF>.<CR><LF>');
    } else if (upper === 'RSET') {
      inData = false;
      dataLines = [];
      resetEnvelope();
      reply('250 2.0.0 OK');
    } else if (upper === 'NOOP') {
      reply('250 2.0.0 OK');
    } else if (upper === 'QUIT') {
      reply('221 2.0.0 Bye');
      socket.end();
    } else {
      reply('250 2.0.0 OK');
    }
  };

  socket.on('data', (chunk) => {
    buffer += chunk;
    let index = buffer.indexOf('\n');
    while (index >= 0) {
      let line = buffer.slice(0, index);
      buffer = buffer.slice(index + 1);
      if (line.endsWith('\r')) line = line.slice(0, -1);
      handleLine(line);
      index = buffer.indexOf('\n');
    }
  });
  socket.on('error', () => socket.destroy());
});

const httpServer = createHttpServer((request, response) => {
  const url = new URL(request.url ?? '/', `http://${HOST}:${HTTP_PORT}`);
  const send = (status, body) => {
    const payload = JSON.stringify(body);
    response.writeHead(status, {
      'content-type': 'application/json; charset=utf-8',
      'content-length': Buffer.byteLength(payload),
    });
    response.end(payload);
  };

  if (request.method === 'GET' && url.pathname === '/health') {
    send(200, { ok: true, messages: messages.length });
    return;
  }

  if (request.method === 'GET' && url.pathname === '/messages') {
    const to = url.searchParams.get('to')?.toLowerCase();
    const subject = url.searchParams.get('subject')?.toLowerCase();
    let list = messages;
    if (to) list = list.filter((message) => message.to.some((value) => value.toLowerCase() === to));
    if (subject) {
      list = list.filter((message) => message.subject.toLowerCase().includes(subject));
    }
    send(200, list);
    return;
  }

  if (request.method === 'GET' && url.pathname === '/messages/latest') {
    const to = url.searchParams.get('to')?.toLowerCase();
    const list = to
      ? messages.filter((message) => message.to.some((value) => value.toLowerCase() === to))
      : messages;
    if (list.length === 0) {
      send(404, { error: 'no messages' });
      return;
    }
    send(200, list[list.length - 1]);
    return;
  }

  if (request.method === 'DELETE' && url.pathname === '/messages') {
    messages.length = 0;
    send(200, { ok: true });
    return;
  }

  send(404, { error: 'not found' });
});

smtpServer.listen(SMTP_PORT, HOST, () => {
  log(`SMTP listening on ${HOST}:${SMTP_PORT}`);
});
httpServer.listen(HTTP_PORT, HOST, () => {
  log(`mailbox API listening on http://${HOST}:${HTTP_PORT}`);
});

function shutdown() {
  smtpServer.close();
  httpServer.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 1000).unref();
}

process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);

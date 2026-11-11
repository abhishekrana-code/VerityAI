const MAIL_API_URL = process.env.E2E_MAIL_API_URL ?? 'http://127.0.0.1:1026';

export interface CapturedMessage {
  id: string;
  from: string;
  to: string[];
  subject: string;
  text: string;
  html: string;
  raw: string;
  receivedAt: string;
}

export async function clearMailbox(): Promise<void> {
  const response = await fetch(`${MAIL_API_URL}/messages`, { method: 'DELETE' });
  if (!response.ok) {
    throw new Error(`Could not clear the E2E mailbox (HTTP ${response.status})`);
  }
}

/**
 * Polls the local SMTP sink until a message for `to` with the expected subject
 * arrives. Mail is sent after the reset transaction commits, so a short poll is
 * required even though the HTTP request has already returned.
 */
export async function waitForMessage(
  to: string,
  subject: string,
  timeoutMs = 20_000,
): Promise<CapturedMessage> {
  const deadline = Date.now() + timeoutMs;
  let lastError = '';

  while (Date.now() < deadline) {
    try {
      const response = await fetch(`${MAIL_API_URL}/messages?to=${encodeURIComponent(to)}`);
      if (response.ok) {
        const messages = (await response.json()) as CapturedMessage[];
        const match = messages.filter((message) => message.subject.includes(subject)).at(-1);
        if (match) return match;
      } else {
        lastError = `HTTP ${response.status}`;
      }
    } catch (error) {
      lastError = error instanceof Error ? error.message : String(error);
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }

  throw new Error(
    `Timed out waiting for "${subject}" addressed to ${to}${lastError ? ` (${lastError})` : ''}`,
  );
}

/** Extracts the raw reset token from the link in the captured email. */
export function extractResetToken(message: CapturedMessage): string {
  const haystack = `${message.text}\n${message.html}`;
  const match = haystack.match(/reset-password\?token=([A-Za-z0-9_-]+)/);
  if (!match) {
    throw new Error(
      `No reset-password link found in the email body:\n${message.text}\n--- raw ---\n${message.raw}`,
    );
  }
  return match[1];
}

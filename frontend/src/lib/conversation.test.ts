import { describe, expect, it } from 'vitest';
import { DEFAULT_CONVERSATION_TITLE, deriveConversationTitle } from './conversation';

describe('deriveConversationTitle', () => {
  it('uses a short question verbatim', () => {
    expect(deriveConversationTitle('What are the main findings?')).toBe(
      'What are the main findings?',
    );
  });

  it('collapses whitespace and trims', () => {
    expect(deriveConversationTitle('  What   changed\nin 2024? ')).toBe('What changed in 2024?');
  });

  it('falls back to a default for an empty question', () => {
    expect(deriveConversationTitle('   ')).toBe(DEFAULT_CONVERSATION_TITLE);
  });

  it('truncates long questions on a word boundary', () => {
    const title = deriveConversationTitle(
      'Compare the reported revenue growth across every regional market described in the annual report',
    );
    expect(title.length).toBeLessThanOrEqual(61);
    expect(title.endsWith('…')).toBe(true);
    expect(title).not.toContain('regiona…');
    expect(title.startsWith('Compare the reported revenue growth')).toBe(true);
  });

  it('respects a custom maximum length', () => {
    expect(deriveConversationTitle('Ask about quarterly-results.pdf', 20)).toBe('Ask about…');
  });
});

import { describe, expect, it } from 'vitest';
import { formatFileSize, pluralize } from './utils';

describe('pluralize', () => {
  it('uses the singular form for exactly one', () => {
    expect(pluralize(1, 'document')).toBe('1 document');
    expect(pluralize(1, 'page')).toBe('1 page');
  });

  it('pluralizes everything else', () => {
    expect(pluralize(0, 'page')).toBe('0 pages');
    expect(pluralize(2, 'document')).toBe('2 documents');
    expect(pluralize(1200, 'message')).toBe('1200 messages');
  });

  it('accepts an irregular plural', () => {
    expect(pluralize(1, 'entity', 'entities')).toBe('1 entity');
    expect(pluralize(3, 'entity', 'entities')).toBe('3 entities');
  });
});

describe('formatFileSize', () => {
  it('shows bytes below one kilobyte instead of 0.0 MB', () => {
    expect(formatFileSize(817)).toBe('817 B');
    expect(formatFileSize(0)).toBe('0 B');
  });

  it('shows kilobytes with one decimal under 10 KB', () => {
    expect(formatFileSize(1536)).toBe('1.5 KB');
  });

  it('shows megabytes', () => {
    expect(formatFileSize(3.25 * 1024 * 1024)).toBe('3.3 MB');
    expect(formatFileSize(12.4 * 1024 * 1024)).toBe('12 MB');
  });

  it('returns a dash for invalid sizes', () => {
    expect(formatFileSize(-1)).toBe('—');
    expect(formatFileSize(Number.NaN)).toBe('—');
  });
});

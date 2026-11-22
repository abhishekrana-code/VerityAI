import { describe, expect, it } from 'vitest';
import type { ClaimResult } from '@/lib/api';
import { SUPPORT_LABELS, isQuickCheck, supportLabel, trustLine } from './verification';

function claim(index: number, citationIndexes: number[], hasInvalidCitation = false): ClaimResult {
  return {
    claimIndex: index,
    sentenceIndex: index,
    claimText: `Claim ${index}`,
    supportClass: null,
    explanation: null,
    citationIndexes,
    hasInvalidCitation,
  };
}

describe('trustLine', () => {
  it('reports the cited share and the gaps', () => {
    const claims = [claim(0, [0]), claim(1, [1]), claim(2, [2]), claim(3, [3]), claim(4, [])];
    expect(trustLine(claims)).toBe('4 of 5 claims cite a source · 1 has no citation');
  });

  it('says when every claim cites a source', () => {
    expect(trustLine([claim(0, [0]), claim(1, [1]), claim(2, [2])])).toBe(
      'All 3 claims cite a source',
    );
  });

  it('says when no claim cites a source', () => {
    expect(trustLine([claim(0, []), claim(1, []), claim(2, [])])).toBe(
      'None of 3 claims cite a source',
    );
  });

  it('never mentions support or verification, only citations', () => {
    const line = trustLine([claim(0, [0]), claim(1, [])]);
    expect(line.toLowerCase()).not.toContain('verified');
    expect(line.toLowerCase()).not.toContain('supported');
  });

  it('flags a citation that does not match the sources', () => {
    const line = trustLine([claim(0, [0], true), claim(1, [1])]);
    expect(line).toContain('a citation does not match the sources');
  });

  it('handles an answer with no claims', () => {
    expect(trustLine([])).toBe('No checkable claims in this answer.');
  });
});

describe('supportLabel', () => {
  it('uses plain language instead of the API enum names', () => {
    expect(supportLabel('SUPPORTED')).toBe('Backed by the document');
    expect(supportLabel('PARTIALLY_SUPPORTED')).toBe('Partly backed');
    expect(supportLabel('UNSUPPORTED')).toBe('Not found in the document');
    expect(supportLabel('CONFLICTING')).toBe('Contradicts the document');
  });

  it('does not claim support for an unassessed claim', () => {
    expect(supportLabel(null)).toBe('Not assessed yet');
    expect(supportLabel('SOMETHING_NEW')).toBe('Not assessed yet');
  });

  it('never labels a class as verified', () => {
    for (const label of Object.values(SUPPORT_LABELS)) {
      expect(label.toLowerCase()).not.toContain('verified');
    }
  });
});

describe('isQuickCheck', () => {
  it('is true only for the deterministic layer', () => {
    expect(isQuickCheck({ mode: 'QUICK' } as never)).toBe(true);
    expect(isQuickCheck({ mode: 'DEEP' } as never)).toBe(false);
    expect(isQuickCheck(null)).toBe(false);
  });
});

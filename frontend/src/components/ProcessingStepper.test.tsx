import { describe, expect, it } from 'vitest';
import { processingStepIndex } from '@/lib/processing';

describe('processingStepIndex', () => {
  it('starts at Uploaded', () => {
    expect(processingStepIndex('UPLOADED', 'UPLOADED')).toBe(0);
    expect(processingStepIndex('PROCESSING', 'QUEUED')).toBe(0);
  });

  it('advances through Reading and Indexing', () => {
    expect(processingStepIndex('PROCESSING', 'EXTRACTING')).toBe(1);
    expect(processingStepIndex('PROCESSING', 'CHUNKING')).toBe(2);
    expect(processingStepIndex('PROCESSING', 'INDEXING')).toBe(2);
  });

  it('finishes at Ready', () => {
    expect(processingStepIndex('READY', 'COMPLETED')).toBe(3);
    expect(processingStepIndex('READY', null)).toBe(3);
  });

  it('treats PROCESSING without a stage as past upload', () => {
    expect(processingStepIndex('PROCESSING', null)).toBe(1);
  });

  it('keeps the failed step visible', () => {
    expect(processingStepIndex('FAILED', 'EXTRACTING')).toBe(1);
    expect(processingStepIndex('FAILED', null)).toBe(0);
  });
});

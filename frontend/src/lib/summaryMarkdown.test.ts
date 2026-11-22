import { describe, expect, it } from 'vitest';
import type { SummarySection } from '@/lib/api';
import { summaryToMarkdown } from './summaryMarkdown';

function section(kind: string, content: Record<string, unknown>): SummarySection {
  return {
    id: kind,
    kind: kind as SummarySection['kind'],
    source: 'GENERATED',
    content,
    model: 'test-model',
    generatedAt: '2026-01-01T00:00:00Z',
  };
}

describe('summaryToMarkdown', () => {
  it('writes each section under its plain-language heading', () => {
    const markdown = summaryToMarkdown({ originalFilename: 'report.pdf', pageCount: 3 }, [
      section('EXECUTIVE_SUMMARY', { summary: 'Revenue grew 14 percent.' }),
      section('KEY_FINDINGS', { findings: [{ text: 'Costs fell' }, { text: '  ' }] }),
      section('TOPICS', { topics: ['finance', 'growth'] }),
    ]);

    expect(markdown).toContain('# report.pdf');
    expect(markdown).toContain('— 3 pages');
    expect(markdown).toContain('## Executive summary\n\nRevenue grew 14 percent.');
    expect(markdown).toContain('## Key findings\n\n- Costs fell');
    expect(markdown).toContain('## Topics\n\n- finance\n- growth');
    expect(markdown).not.toContain('undefined');
  });

  it('labels entity types and skips empty values', () => {
    const markdown = summaryToMarkdown({ originalFilename: 'a.pdf' }, [
      section('ENTITIES', { entities: [{ name: 'Alpha Corp', type: 'ORGANIZATION' }, {}] }),
    ]);
    expect(markdown).toContain('- Alpha Corp (organization)');
    expect(markdown.match(/- /g)).toHaveLength(1);
  });

  it('says so when there is nothing to export', () => {
    const markdown = summaryToMarkdown({ originalFilename: 'empty.pdf' }, []);
    expect(markdown).toContain('_No summary content to export yet._');
  });
});

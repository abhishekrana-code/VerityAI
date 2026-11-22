import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import { api, type ReportItem, type ReportListResponse } from '@/lib/api';
import { renderWithProviders } from '@/test/renderWithProviders';
import { ReportsPage } from './ReportsPage';

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    api: { get: vi.fn(), post: vi.fn(), delete: vi.fn() },
  };
});

const get = vi.mocked(api.get);

const DEFERRED_MESSAGE =
  'Summary generation was deferred because the background queue is full. ' +
  'It will retry automatically on the next server start.';

function report(overrides: Partial<ReportItem>): ReportItem {
  return {
    documentId: 'doc-1',
    documentName: 'alpha-annual-report.pdf',
    pageCount: 1,
    updatedAt: new Date().toISOString(),
    summaryStatus: 'READY',
    summaryError: null,
    sectionCount: 6,
    model: 'openai/gpt-oss-20b',
    generatedAt: new Date().toISOString(),
    executiveSummary: '',
    keyFindings: [],
    entityCount: 0,
    dateStatCount: 0,
    topicCount: 0,
    questionCount: 0,
    ...overrides,
  };
}

function mockReports(items: ReportItem[]) {
  const response: ReportListResponse = {
    content: items,
    page: 0,
    size: 8,
    totalElements: items.length,
    totalPages: 1,
    first: true,
    last: true,
  };
  get.mockResolvedValue(response);
}

beforeEach(() => {
  vi.clearAllMocks();
});

function cardFor(name: string): HTMLElement {
  const card = screen.getByText(name).closest('li');
  if (!card) throw new Error(`No report card found for ${name}`);
  return card;
}

describe('ReportsPage report status', () => {
  it('labels a deferred summary as Deferred, with the reason and no spinner', async () => {
    mockReports([
      report({
        documentId: 'doc-1',
        documentName: 'alpha-annual-report.pdf',
        summaryStatus: 'PENDING',
        summaryError: DEFERRED_MESSAGE,
      }),
      report({
        documentId: 'doc-2',
        documentName: 'beta-holdings-overview.pdf',
        summaryStatus: 'PENDING',
        summaryError: null,
      }),
    ]);
    renderWithProviders(<ReportsPage />);

    expect(await screen.findByText('alpha-annual-report.pdf')).toBeInTheDocument();

    const deferredCard = cardFor('alpha-annual-report.pdf');
    expect(within(deferredCard).getByText('Deferred')).toBeInTheDocument();
    expect(within(deferredCard).queryByText('Generating')).not.toBeInTheDocument();
    expect(within(deferredCard).queryByText('Ready')).not.toBeInTheDocument();
    expect(within(deferredCard).getByText(DEFERRED_MESSAGE)).toBeInTheDocument();
    expect(deferredCard.querySelector('.animate-spin')).toBeNull();

    // A PENDING summary without a reason is still a run in progress.
    const generatingCard = cardFor('beta-holdings-overview.pdf');
    expect(within(generatingCard).getByText('Generating')).toBeInTheDocument();
    expect(generatingCard.querySelector('.animate-spin')).not.toBeNull();
  });
});

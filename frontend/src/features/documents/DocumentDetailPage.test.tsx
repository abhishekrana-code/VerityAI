import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { api, type Document, type DocumentSummariesResponse } from '@/lib/api';
import { renderWithProviders } from '@/test/renderWithProviders';
import { DocumentDetailPage } from './DocumentDetailPage';

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    api: { get: vi.fn(), post: vi.fn(), delete: vi.fn() },
  };
});

const get = vi.mocked(api.get);
const post = vi.mocked(api.post);

const DEFERRED_MESSAGE =
  'Summary generation was deferred because the background queue is full. ' +
  'It will retry automatically on the next server start.';

const doc: Document = {
  id: 'doc-1',
  originalFilename: 'alpha-annual-report.pdf',
  mimeType: 'application/pdf',
  sizeBytes: 2048,
  pageCount: 1,
  status: 'READY',
  sha256: 'abc123',
  errorCode: null,
  errorMessage: null,
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
};

const deferredSummaries: DocumentSummariesResponse = {
  documentId: 'doc-1',
  summaryStatus: 'PENDING',
  summaryError: DEFERRED_MESSAGE,
  summaries: [],
};

function mockDocument(summaries: DocumentSummariesResponse) {
  get.mockImplementation((endpoint: string) => {
    if (endpoint.endsWith('/processing')) {
      return Promise.resolve({
        documentId: 'doc-1',
        currentStage: 'COMPLETED',
        currentAttempt: 1,
        events: [],
      });
    }
    if (endpoint.endsWith('/summaries')) return Promise.resolve(summaries);
    if (endpoint === '/documents/doc-1') return Promise.resolve(doc);
    return Promise.reject(new Error(`unexpected GET ${endpoint}`));
  });
}

beforeEach(() => {
  vi.clearAllMocks();
  post.mockResolvedValue(deferredSummaries);
});

function renderPage() {
  return renderWithProviders(<DocumentDetailPage />, {
    route: '/dashboard/documents/doc-1',
    path: '/dashboard/documents/:id',
  });
}

describe('DocumentDetailPage summary tab', () => {
  it('shows a deferred summary with its reason and an enabled retry, not a spinner', async () => {
    mockDocument(deferredSummaries);
    renderPage();

    expect(await screen.findByText('Summary deferred')).toBeInTheDocument();

    expect(
      screen.getByText('Summary deferred — it will retry automatically'),
    ).toBeInTheDocument();
    expect(screen.getByText(DEFERRED_MESSAGE)).toBeInTheDocument();

    const retry = screen.getByRole('button', { name: /retry summary/i });
    expect(retry).toBeEnabled();
    // A deferred job is idle, so it must not look like a run in progress.
    expect(screen.queryByText(/Generating summary/)).not.toBeInTheDocument();
    expect(screen.queryByText('Regenerate')).not.toBeInTheDocument();
  });

  it('retries a deferred summary instead of asking for a fresh generation', async () => {
    mockDocument(deferredSummaries);
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: /retry summary/i }));

    expect(post).toHaveBeenCalledWith('/documents/doc-1/summaries/regenerate');
  });

  it('treats PENDING without a reason as generating and blocks the button', async () => {
    mockDocument({ documentId: 'doc-1', summaryStatus: 'PENDING', summaryError: null, summaries: [] });
    renderPage();

    expect(await screen.findByText('Generating summary…')).toBeInTheDocument();
    expect(screen.queryByText('Summary deferred')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /regenerate/i })).toBeDisabled();
  });
});

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import {
  api,
  type Document,
  type KnowledgeGraphResponse,
} from '@/lib/api';
import { renderWithProviders } from '@/test/renderWithProviders';
import { KnowledgeGraphPage } from './KnowledgeGraphPage';

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    api: { get: vi.fn(), post: vi.fn(), delete: vi.fn() },
  };
});

const get = vi.mocked(api.get);

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

const GRAPH_ERROR = 'Knowledge graph build failed: evidence extraction timed out after 30s';

// The backend keeps the last successful graph when a rebuild fails, so FAILED
// arrives with nodes intact.
const failedGraph: KnowledgeGraphResponse = {
  documentId: 'doc-1',
  documentName: 'alpha-annual-report.pdf',
  scope: 'DOCUMENT',
  status: 'FAILED',
  error: GRAPH_ERROR,
  generatedAt: new Date(Date.now() - 40 * 60 * 1000).toISOString(),
  entityCount: 2,
  relationCount: 1,
  documentCount: 1,
  returnedEntities: 2,
  truncated: false,
  nodes: [
    {
      id: 'n1',
      name: 'Alpha Corp',
      type: 'ORGANIZATION',
      mentionCount: 4,
      documentId: 'doc-1',
      documentName: 'alpha-annual-report.pdf',
      firstPage: 1,
      evidenceExcerpt: null,
    },
    {
      id: 'n2',
      name: 'Berlin',
      type: 'LOCATION',
      mentionCount: 2,
      documentId: 'doc-1',
      documentName: 'alpha-annual-report.pdf',
      firstPage: 1,
      evidenceExcerpt: null,
    },
  ],
  edges: [
    {
      id: 'e1',
      source: 'n1',
      target: 'n2',
      relationType: 'CO_OCCURS',
      weight: 2,
      documentId: 'doc-1',
      firstPage: 1,
      evidenceExcerpt: null,
    },
  ],
};

beforeEach(() => {
  vi.clearAllMocks();
  get.mockImplementation((endpoint: string) => {
    if (endpoint.startsWith('/documents')) return Promise.resolve({ content: [doc] });
    if (endpoint.startsWith('/knowledge-graph')) return Promise.resolve(failedGraph);
    return Promise.reject(new Error(`unexpected GET ${endpoint}`));
  });
});

describe('KnowledgeGraphPage failed rebuild', () => {
  it('warns that the rebuild failed, shows the error, and keeps the last graph visible', async () => {
    renderWithProviders(<KnowledgeGraphPage />);

    const notice = (await screen.findByText(/Showing the last successful graph/)).closest('p');
    expect(notice).not.toBeNull();
    expect(notice!.textContent).toBe(
      `Rebuild failed. ${GRAPH_ERROR}. Showing the last successful graph — rebuild to try again.`,
    );

    // The retained graph is still rendered rather than being hidden by the warning.
    expect(
      screen.getByRole('img', { name: 'Knowledge graph with 2 entities and 1 co-occurrence links' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /rebuild graphs/i })).toBeEnabled();
  });
});

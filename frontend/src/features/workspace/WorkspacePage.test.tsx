import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { api, type Document } from '@/lib/api';
import { renderWithProviders } from '@/test/renderWithProviders';
import { WorkspacePage } from './WorkspacePage';

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    api: { get: vi.fn(), post: vi.fn(), delete: vi.fn(), uploadWithProgress: vi.fn() },
  };
});

const get = vi.mocked(api.get);

function document(status: Document['status']): Document {
  return {
    id: `doc-${status}`,
    originalFilename: 'annual-report.pdf',
    mimeType: 'application/pdf',
    sizeBytes: 2048,
    pageCount: 1,
    status,
    sha256: 'abc123',
    errorCode: null,
    errorMessage: null,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  };
}

function page(content: Document[]) {
  return {
    content,
    page: 0,
    size: content.length,
    totalElements: content.length,
    totalPages: 1,
    first: true,
    last: true,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('WorkspacePage composer', () => {
  it('lists the processing document and its status instead of the upload hint', async () => {
    get.mockImplementation((endpoint: string) => {
      if (endpoint.startsWith('/conversations')) return Promise.resolve(page([]));
      if (endpoint.includes('status=READY')) return Promise.resolve(page([]));
      if (endpoint.startsWith('/documents?size=10')) return Promise.resolve(page([document('PROCESSING')]));
      return Promise.reject(new Error(`unexpected GET ${endpoint}`));
    });

    renderWithProviders(<WorkspacePage />);

    expect(await screen.findByText(/annual-report\.pdf is processing/i)).toBeInTheDocument();
    expect(screen.queryByText(/Upload a PDF to ask your first question/)).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /All ready documents \(0\)/ }));

    expect(screen.getByRole('checkbox', { name: 'annual-report.pdf (Processing)' })).toBeDisabled();
    expect(screen.queryByText('No ready documents yet — upload a PDF first.')).not.toBeInTheDocument();
  });
});

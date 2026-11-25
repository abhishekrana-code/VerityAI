import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { api, type Document } from '@/lib/api';
import { useReadyDocuments } from './useReadyDocuments';

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return { ...actual, api: { get: vi.fn() } };
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

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

afterEach(() => {
  vi.useRealTimers();
  vi.clearAllMocks();
});

describe('useReadyDocuments', () => {
  it('refreshes the ready list once a processing document finishes', async () => {
    vi.useFakeTimers();
    let processing = true;

    get.mockImplementation((endpoint: string) => {
      if (endpoint.includes('status=READY')) {
        return Promise.resolve(page(processing ? [] : [document('READY')]));
      }
      return Promise.resolve(page(processing ? [document('PROCESSING')] : [document('READY')]));
    });

    const { result } = renderHook(() => useReadyDocuments(), { wrapper });

    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    // The upload is still processing, so the composer starts empty.
    expect(result.current.data).toEqual([]);
    expect(get).toHaveBeenCalledWith('/documents?size=100&status=READY');

    processing = false;
    // Let the pending scan notice the empty queue and refresh the ready list.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3000);
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });

    expect(result.current.data).toHaveLength(1);
    expect(result.current.data?.[0].originalFilename).toBe('annual-report.pdf');
  });
});

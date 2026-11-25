import { useEffect, useRef } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type Document } from '@/lib/api';

export const READY_DOCUMENTS_QUERY_KEY = ['documents', 'ready'] as const;

/** How often to look again while a PDF is still being processed. */
const POLL_MS = 2500;
/** Uploads land at the top of this newest-first window. */
const RECENT_WINDOW = 10;

function isProcessing(doc: Document) {
  return doc.status === 'UPLOADED' || doc.status === 'PROCESSING';
}

/**
 * Ready documents for the Ask composer.
 *
 * Uploading invalidates `['documents']` while the PDF is still processing, so
 * that refetch can cache an empty ready list for the whole stale window (five
 * minutes). Watch the newest documents and poll the ready list until the last
 * one leaves the queue, so the composer unlocks without a reload.
 */
export function useReadyDocuments() {
  const queryClient = useQueryClient();

  const { data: pendingCount = 0 } = useQuery({
    queryKey: ['documents', 'pending-scan'],
    queryFn: async () => {
      const recent = await api.get<{ content: Document[] }>(`/documents?size=${RECENT_WINDOW}`);
      return recent.content.filter(isProcessing).length;
    },
    refetchInterval: (query) => ((query.state.data ?? 0) > 0 ? POLL_MS : false),
  });

  const ready = useQuery({
    queryKey: READY_DOCUMENTS_QUERY_KEY,
    queryFn: () => api.get<{ content: Document[] }>('/documents?size=100&status=READY'),
    select: (data) => data.content,
    refetchInterval: pendingCount > 0 ? POLL_MS : false,
  });

  // The last poll can land just before processing finishes; refresh once more
  // when the queue empties so a finished document is never left out.
  const wasPending = useRef(false);
  useEffect(() => {
    if (wasPending.current && pendingCount === 0) {
      void queryClient.invalidateQueries({ queryKey: READY_DOCUMENTS_QUERY_KEY });
    }
    wasPending.current = pendingCount > 0;
  }, [pendingCount, queryClient]);

  return ready;
}

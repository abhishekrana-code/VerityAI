import { useEffect } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type Document } from '@/lib/api';

const READY_DOCUMENTS_QUERY_KEY = ['documents', 'ready'];
const PENDING_SCAN_QUERY_KEY = ['documents', 'pending-scan'];

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
 * minutes). The scan below polls the newest documents while any is pending and
 * refreshes the ready list whenever that queue changes — including when it
 * empties — so the composer unlocks without a reload.
 */
export function useReadyDocuments() {
  const queryClient = useQueryClient();
  type DocumentPage = { content: Document[] };

  const { data: pendingCount = 0 } = useQuery({
    queryKey: PENDING_SCAN_QUERY_KEY,
    queryFn: async () => {
      const recent = await api.get<DocumentPage>(`/documents?size=${RECENT_WINDOW}`);
      return recent.content.filter(isProcessing).length;
    },
    refetchInterval: (query) => ((query.state.data ?? 0) > 0 ? POLL_MS : false),
  });

  const ready = useQuery({
    queryKey: READY_DOCUMENTS_QUERY_KEY,
    queryFn: () => api.get<DocumentPage>('/documents?size=100&status=READY'),
    select: (data) => data.content,
  });

  // The queue is the only thing that changes this list, so each step it takes
  // (including reaching zero) is exactly when a refetch can show something new.
  useEffect(() => {
    void queryClient.invalidateQueries({ queryKey: READY_DOCUMENTS_QUERY_KEY });
  }, [pendingCount, queryClient]);

  return ready;
}

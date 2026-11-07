'use client';

import { useEffect, useState } from 'react';
import { FileText, ChevronLeft, ChevronRight, X, Loader2, AlertCircle } from 'lucide-react';

/**
 * Minimal document shape needed to preview a PDF. `Document` from the API
 * satisfies it; callers that only know an id/filename (e.g. citations) can
 * pass a partial target and the modal fetches the page count itself.
 */
export interface PdfPreviewTarget {
  id: string;
  originalFilename: string;
  pageCount?: number | null;
}

/**
 * Full-screen PDF preview with page navigation. Shared by the document
 * detail page and the research workspace (citation "open source" jumps).
 */
export function PdfPreviewModal({
  doc,
  initialPage = 1,
  onClose,
}: {
  doc: PdfPreviewTarget;
  initialPage?: number;
  onClose: () => void;
}) {
  const [objectUrl, setObjectUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [page, setPage] = useState(initialPage);
  const [pageCount, setPageCount] = useState<number | null>(doc.pageCount ?? null);
  const [unavailable, setUnavailable] = useState(false);

  useEffect(() => {
    let cancelled = false;
    let url: string | null = null;
    setLoading(true);
    setLoadError(null);
    fetch(`${import.meta.env.VITE_API_BASE_URL || '/api/v1'}/documents/${doc.id}/file`, {
      credentials: 'include',
    })
      .then(async (res) => {
        if (!res.ok) throw new Error(res.status === 404 ? 'The stored file is no longer on disk.' : `Preview failed (${res.status})`);
        const blob = await res.blob();
        if (cancelled) return;
        url = URL.createObjectURL(blob);
        setObjectUrl(url);
        setLoading(false);
      })
      .catch((e: Error) => {
        if (cancelled) return;
        setLoadError(e.message || 'Could not load the PDF');
        setLoading(false);
      });
    return () => {
      cancelled = true;
      if (url) URL.revokeObjectURL(url);
    };
  }, [doc.id]);

  // When the caller only knows id + filename (citation jumps), fetch the page
  // count so navigation controls work. Best-effort: preview still opens without it.
  useEffect(() => {
    if (doc.pageCount != null) return;
    let cancelled = false;
    fetch(`${import.meta.env.VITE_API_BASE_URL || '/api/v1'}/documents/${doc.id}`, {
      credentials: 'include',
    })
      .then((res) => (res.ok ? res.json() : null))
      .then((data: { pageCount?: number } | null) => {
        if (!cancelled && data && typeof data.pageCount === 'number') {
          setPageCount(data.pageCount);
        }
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [doc.id, doc.pageCount]);

  const totalPages = pageCount ?? 0;

  // Clamp an out-of-range initial page once the page count is known.
  useEffect(() => {
    if (totalPages > 0) {
      setPage((p) => Math.min(Math.max(1, p), totalPages));
    }
  }, [totalPages]);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-2 sm:p-6"
      role="dialog"
      aria-modal="true"
      aria-label={`Preview ${doc.originalFilename}`}
    >
      <div className="absolute inset-0 bg-black/70 backdrop-blur-sm" onClick={onClose} />
      <div className="relative w-full max-w-4xl h-[90vh] flex flex-col rounded-xl border border-border bg-card shadow-2xl overflow-hidden">
        <div className="flex items-center justify-between gap-3 border-b px-4 py-3">
          <div className="flex items-center gap-2 min-w-0">
            <FileText className="h-4 w-4 text-muted-foreground shrink-0" />
            <span className="text-sm font-medium truncate">{doc.originalFilename}</span>
            <span className="text-xs text-muted-foreground shrink-0 hidden sm:inline">
              {totalPages ? `${totalPages} pages` : ''}
            </span>
          </div>
          <div className="flex items-center gap-2">
            {objectUrl && totalPages > 1 && (
              <div className="flex items-center gap-1">
                <button
                  onClick={() => setPage((p) => Math.max(1, p - 1))}
                  disabled={page <= 1}
                  aria-label="Previous page"
                  className="p-1.5 rounded-lg hover:bg-accent disabled:opacity-40"
                >
                  <ChevronLeft className="h-4 w-4" />
                </button>
                <span className="text-xs text-muted-foreground min-w-[64px] text-center">
                  {page} / {totalPages}
                </span>
                <button
                  onClick={() => setPage((p) => Math.min(totalPages, p + 1))}
                  disabled={page >= totalPages}
                  aria-label="Next page"
                  className="p-1.5 rounded-lg hover:bg-accent disabled:opacity-40"
                >
                  <ChevronRight className="h-4 w-4" />
                </button>
              </div>
            )}
            <button onClick={onClose} aria-label="Close preview" className="p-1.5 rounded-lg hover:bg-accent">
              <X className="h-4 w-4" />
            </button>
          </div>
        </div>

        <div className="flex-1 overflow-auto bg-muted/30">
          {loading ? (
            <div className="h-full flex flex-col items-center justify-center gap-3">
              <Loader2 className="h-8 w-8 animate-spin text-primary" />
              <p className="text-sm text-muted-foreground">Loading PDF…</p>
            </div>
          ) : loadError ? (
            <div className="h-full flex flex-col items-center justify-center p-8 text-center">
              <AlertCircle className="h-10 w-10 text-destructive/60 mb-3" />
              <p className="text-sm font-medium text-destructive mb-1">Preview unavailable</p>
              <p className="text-sm text-muted-foreground">{loadError}</p>
              <button
                onClick={onClose}
                className="mt-4 rounded-lg border border-border px-4 py-2 text-sm hover:bg-accent transition-colors"
              >
                Close
              </button>
            </div>
          ) : objectUrl ? (
            unavailable ? (
              <div className="h-full flex flex-col items-center justify-center p-8 text-center">
                <FileText className="h-10 w-10 text-muted-foreground/50 mb-3" />
                <p className="text-sm text-muted-foreground mb-1">
                  Your browser can't display this PDF inline.
                </p>
                <a
                  href={objectUrl}
                  download={doc.originalFilename}
                  className="mt-2 rounded-lg bg-primary text-primary-foreground px-4 py-2 text-sm font-medium hover:bg-primary/90 transition-colors"
                >
                  Download PDF
                </a>
              </div>
            ) : (
              <iframe
                src={`${objectUrl}#page=${page}&toolbar=0&view=FitH`}
                title={`Preview of ${doc.originalFilename}`}
                className="h-full w-full border-0"
                onError={() => setUnavailable(true)}
              />
            )
          ) : null}
        </div>
      </div>
    </div>
  );
}

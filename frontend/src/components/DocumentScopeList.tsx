import { FileText } from 'lucide-react';
import type { Document } from '@/lib/api';
import { pendingStatusLabel } from '@/hooks/useReadyDocuments';
import { pluralize } from '@/lib/utils';

interface DocumentScopeListProps {
  /** Documents that can be selected as evidence. */
  ready: Document[];
  /** Documents still being processed; shown but not selectable. */
  processing: Document[];
  selectedIds: string[];
  onToggle: (id: string) => void;
  /** The lists have not resolved yet, so "no documents" would be a guess. */
  loading: boolean;
  /** The ready list failed to load; also not the same as having none. */
  error: boolean;
  onRetry: () => void;
  /** Show page counts, as the Search picker does. */
  showPageCount?: boolean;
}

/**
 * The document picker shared by Ask and Search: every ready document is a
 * checkbox, and anything still processing is listed in place with its status
 * instead of an empty hint. Both pages render the same rows so the two lists
 * cannot drift apart.
 */
export function DocumentScopeList({
  ready,
  processing,
  selectedIds,
  onToggle,
  loading,
  error,
  onRetry,
  showPageCount = false,
}: DocumentScopeListProps) {
  if (loading) {
    return (
      <p className="px-2 py-1 text-sm text-muted-foreground">Loading documents…</p>
    );
  }

  if (error) {
    return (
      <div className="space-y-1 px-2 py-1">
        <p className="text-sm text-muted-foreground">Couldn’t load your documents.</p>
        <button
          type="button"
          onClick={onRetry}
          className="text-xs font-medium text-primary hover:underline"
        >
          Retry
        </button>
      </div>
    );
  }

  if (ready.length === 0 && processing.length === 0) {
    return (
      <p className="px-2 py-1 text-sm text-muted-foreground">
        No ready documents yet — upload a PDF first.
      </p>
    );
  }

  return (
    <ul className="space-y-1">
      {ready.map((document) => (
        <li key={document.id}>
          <label className="flex cursor-pointer items-center gap-2 rounded-lg px-2 py-1.5 text-sm hover:bg-accent/70">
            <input
              type="checkbox"
              checked={selectedIds.includes(document.id)}
              onChange={() => onToggle(document.id)}
              className="rounded border-input"
            />
            <FileText className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
            <span className="min-w-0 flex-1 truncate">{document.originalFilename}</span>
            {showPageCount && document.pageCount != null && (
              <span className="text-[11px] text-muted-foreground">
                {pluralize(document.pageCount, 'page')}
              </span>
            )}
          </label>
        </li>
      ))}
      {processing.map((document) => {
        const status = pendingStatusLabel(document.status);
        return (
          <li key={document.id}>
            <div className="flex items-center gap-2 rounded-lg px-2 py-1.5 text-sm text-muted-foreground">
              <input
                type="checkbox"
                disabled
                aria-label={`${document.originalFilename} (${status})`}
                className="rounded border-input"
              />
              <FileText className="h-4 w-4 shrink-0" aria-hidden="true" />
              <span className="min-w-0 flex-1 truncate">{document.originalFilename}</span>
              <span className="rounded-full bg-muted px-2 py-0.5 text-[11px]">{status}</span>
            </div>
          </li>
        );
      })}
    </ul>
  );
}

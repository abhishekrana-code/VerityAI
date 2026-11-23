import { AlertCircle } from 'lucide-react';
import { cn } from '@/lib/utils';

/**
 * Marks a document whose stored PDF is gone. Its extracted text, summaries and
 * citations still work, so this reads as a preview limitation rather than a
 * failed document. Shared by the library card/table and the detail header so
 * the wording and look cannot drift apart.
 */
export function PdfUnavailableBadge({ className }: { className?: string }) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded-full border border-border bg-muted px-2 py-0.5 text-[11px] font-medium text-muted-foreground',
        className
      )}
      title="The original PDF is no longer stored on the server. Extracted text, summaries and citations still work."
    >
      <AlertCircle className="h-3 w-3" aria-hidden="true" /> PDF unavailable
    </span>
  );
}

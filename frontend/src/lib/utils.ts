import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/**
 * PENDING with a visible reason is a deferred job (its queued work was dropped)
 * that can be retried now; PENDING without one is genuine work in progress.
 */
export function isDeferredStatus(
  status?: string | null,
  error?: string | null,
): boolean {
  return status === 'PENDING' && !!error;
}

/** Counted noun with correct singular/plural, e.g. "1 document", "2 documents". */
export function pluralize(count: number, singular: string, plural?: string): string {
  return `${count} ${count === 1 ? singular : (plural ?? `${singular}s`)}`;
}

/** Human-readable size: "817 B", "12.4 KB", "3.2 MB". Avoids "0.0 MB" for small files. */
export function formatFileSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return '—';
  if (bytes < 1024) return `${Math.round(bytes)} B`;
  const kb = bytes / 1024;
  if (kb < 1024) return `${kb < 10 ? kb.toFixed(1) : Math.round(kb)} KB`;
  const mb = kb / 1024;
  return `${mb < 10 ? mb.toFixed(1) : Math.round(mb)} MB`;
}
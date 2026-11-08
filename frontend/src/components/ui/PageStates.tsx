import type { LucideIcon } from 'lucide-react';
import { AlertCircle, ChevronLeft, ChevronRight, Loader2, RefreshCw } from 'lucide-react';
import type { ReactNode } from 'react';

/**
 * Shared page states so every Phase 3 screen has the same honest loading,
 * error and empty treatment instead of ad-hoc placeholders.
 */

export function PageLoading({ label = 'Loading…' }: { label?: string }) {
  return (
    <div className="flex min-h-[280px] flex-col items-center justify-center gap-3 rounded-2xl border border-border/70 bg-card/50 p-8 text-center">
      <Loader2 className="h-6 w-6 animate-spin text-primary" aria-hidden="true" />
      <p className="text-sm text-muted-foreground" role="status">{label}</p>
    </div>
  );
}

export function PageError({
  title = 'Something went wrong',
  message,
  onRetry,
}: {
  title?: string;
  message?: string;
  onRetry?: () => void;
}) {
  return (
    <div className="flex min-h-[240px] flex-col items-center justify-center gap-3 rounded-2xl border border-destructive/30 bg-destructive/5 p-8 text-center">
      <AlertCircle className="h-7 w-7 text-destructive" aria-hidden="true" />
      <h3 className="text-sm font-semibold">{title}</h3>
      {message && <p className="max-w-md text-sm text-muted-foreground">{message}</p>}
      {onRetry && (
        <button
          onClick={onRetry}
          className="mt-1 inline-flex items-center gap-2 rounded-lg border border-border bg-card px-3 py-2 text-xs font-semibold transition hover:bg-accent"
        >
          <RefreshCw className="h-3.5 w-3.5" aria-hidden="true" />
          Try again
        </button>
      )}
    </div>
  );
}

export function PageEmpty({
  icon: Icon,
  title,
  description,
  action,
}: {
  icon: LucideIcon;
  title: string;
  description: string;
  action?: ReactNode;
}) {
  return (
    <div className="flex min-h-[280px] flex-col items-center justify-center gap-3 rounded-2xl border border-dashed border-border/80 bg-card/40 p-8 text-center">
      <div className="flex h-12 w-12 items-center justify-center rounded-2xl border border-border/70 bg-muted/50 text-muted-foreground">
        <Icon className="h-6 w-6" aria-hidden="true" />
      </div>
      <h3 className="text-sm font-semibold">{title}</h3>
      <p className="max-w-md text-sm text-muted-foreground">{description}</p>
      {action}
    </div>
  );
}

export function StatCard({
  label,
  value,
  hint,
  accent = 'text-foreground',
}: {
  label: string;
  value: string | number;
  hint?: string;
  accent?: string;
}) {
  return (
    <div className="rounded-2xl border border-border/80 bg-card/70 p-4 shadow-sm">
      <p className="eyebrow">{label}</p>
      <p className={`mt-2 text-2xl font-bold tabular-nums ${accent}`}>{value}</p>
      {hint && <p className="mt-1 text-[11px] text-muted-foreground">{hint}</p>}
    </div>
  );
}

export function Pagination({
  page,
  totalPages,
  onChange,
  label = 'Pagination',
}: {
  page: number;
  totalPages: number;
  onChange: (page: number) => void;
  label?: string;
}) {
  if (totalPages <= 1) {
    return null;
  }
  return (
    <nav
      className="flex items-center justify-between gap-3 rounded-xl border border-border/70 bg-card/50 px-3 py-2 text-xs"
      aria-label={label}
    >
      <button
        type="button"
        onClick={() => onChange(page - 1)}
        disabled={page <= 0}
        className="inline-flex items-center gap-1 rounded-lg px-2.5 py-1.5 font-semibold text-muted-foreground transition hover:bg-accent hover:text-foreground disabled:cursor-not-allowed disabled:opacity-40"
      >
        <ChevronLeft className="h-3.5 w-3.5" aria-hidden="true" />
        Previous
      </button>
      <span className="tabular-nums text-muted-foreground">
        Page {page + 1} of {totalPages}
      </span>
      <button
        type="button"
        onClick={() => onChange(page + 1)}
        disabled={page >= totalPages - 1}
        className="inline-flex items-center gap-1 rounded-lg px-2.5 py-1.5 font-semibold text-muted-foreground transition hover:bg-accent hover:text-foreground disabled:cursor-not-allowed disabled:opacity-40"
      >
        Next
        <ChevronRight className="h-3.5 w-3.5" aria-hidden="true" />
      </button>
    </nav>
  );
}

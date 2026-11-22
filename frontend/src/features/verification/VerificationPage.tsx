'use client';

import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  api,
  type ClaimVerificationResponse,
  type SupportClass,
  type VerificationListItem,
  type VerificationListResponse,
  type VerificationStatsResponse,
} from '@/lib/api';
import { errorToast } from '@/lib/errors';
import { SUPPORT_STYLES, supportLabel } from '@/lib/verification';
import { cn } from '@/lib/utils';
import {
  PageEmpty,
  PageError,
  PageLoading,
  Pagination,
  StatCard,
} from '@/components/ui/PageStates';
import { ToastProvider, ToastViewport } from '@/components/ui/toast';
import { useToast } from '@/hooks/useToast';
import { ContradictionsPanel } from '@/features/verification/ContradictionsPanel';
import { formatDistanceToNow } from 'date-fns';
import {
  AlertTriangle,
  ArrowUpRight,
  CheckCircle2,
  Loader2,
  MessageSquare,
  ShieldCheck,
  ShieldQuestion,
} from 'lucide-react';

const PAGE_SIZE = 10;

const FILTERS: Array<{ value: string; label: string }> = [
  { value: '', label: 'All answers' },
  { value: 'ATTENTION', label: 'Needs attention' },
  { value: 'COMPLETED', label: 'Checked' },
  { value: 'PARTIAL', label: 'Partly checked' },
  { value: 'UNVERIFIED', label: 'Not checked' },
];

const STATUS_STYLES: Record<string, { label: string; className: string }> = {
  NOT_REQUESTED: { label: 'Not checked', className: 'border-border/80 bg-muted/60 text-muted-foreground' },
  PARTIAL: { label: 'Partly checked', className: 'border-amber-400/25 bg-amber-500/10 text-amber-700 dark:text-amber-300' },
  COMPLETED: { label: 'Checked', className: 'border-emerald-400/25 bg-emerald-500/10 text-emerald-700 dark:text-emerald-300' },
  FAILED: { label: 'Check did not run', className: 'border-rose-400/25 bg-rose-500/10 text-rose-700 dark:text-rose-300' },
};

export function VerificationPage() {
  const queryClient = useQueryClient();
  const { toast } = useToast();
  const [view, setView] = useState<'verification' | 'contradictions'>('verification');
  const [page, setPage] = useState(0);
  const [filter, setFilter] = useState('');

  const statsQuery = useQuery({
    queryKey: ['verifications', 'stats'],
    queryFn: () => api.get<VerificationStatsResponse>('/verifications/stats'),
    enabled: view === 'verification',
  });

  const listQuery = useQuery({
    queryKey: ['verifications', 'list', page, filter],
    queryFn: () => {
      const params = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
      if (filter) params.set('status', filter);
      return api.get<VerificationListResponse>(`/verifications?${params.toString()}`);
    },
    enabled: view === 'verification',
    placeholderData: (previous) => previous,
  });

  const verify = useMutation<ClaimVerificationResponse, Error, VerificationListItem>({
    mutationFn: (item) =>
      api.post<ClaimVerificationResponse>(
        `/conversations/${item.conversationId}/messages/${item.messageId}/verify`
      ),
    onSuccess: (result) => {
      queryClient.invalidateQueries({ queryKey: ['verifications'] });
      if (result.status === 'FAILED') {
        toast({
          title: 'Deep check did not run',
          description: result.error ?? 'The model could not assess this answer. Nothing was stored.',
          variant: 'destructive',
        });
      } else {
        const assessed = result.claims.filter((claim) => claim.supportClass != null).length;
        toast({
          title: result.status === 'PARTIAL' ? 'Deep check partly completed' : 'Deep check finished',
          description: `${assessed} of ${result.claims.length} analyzed claim(s) assessed${
            result.coverage != null ? ` · ${Math.round(result.coverage * 100)}% cite a source` : ''
          }.`,
        });
      }
    },
    onError: (error) => toast(errorToast(error, 'Deep check failed')),
  });

  const stats = statsQuery.data;
  const list = listQuery.data;
  const items = list?.content ?? [];

  const changeFilter = (value: string) => {
    setFilter(value);
    setPage(0);
  };

  return (
    <div className="space-y-6">
      <section className="page-hero">
        <p className="eyebrow">Checks</p>
        <h1 className="mt-2 text-2xl font-extrabold tracking-tight sm:text-3xl">See what each answer actually cites</h1>
        <p className="mt-2 max-w-2xl text-sm leading-7 text-muted-foreground">
          Every answer gets a free citation check: how many of its claims point at a source. A deep
          check adds the AI&apos;s assessment of whether the cited text backs each claim. Those
          assessments are not probabilities — open the conversation to read the excerpts yourself.
        </p>
      </section>

      <div
        className="flex flex-wrap items-center gap-1 border-b border-border/70"
        role="tablist"
        aria-label="Verification views"
      >
        <button
          type="button"
          role="tab"
          aria-selected={view === 'verification'}
          onClick={() => setView('verification')}
          className={cn(
            'inline-flex items-center gap-2 border-b-2 px-3 py-2.5 text-sm font-semibold transition',
            view === 'verification'
              ? 'border-primary text-foreground'
              : 'border-transparent text-muted-foreground hover:text-foreground'
          )}
        >
          <ShieldCheck className="h-4 w-4" aria-hidden="true" />
          Answers
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={view === 'contradictions'}
          onClick={() => setView('contradictions')}
          className={cn(
            'inline-flex items-center gap-2 border-b-2 px-3 py-2.5 text-sm font-semibold transition',
            view === 'contradictions'
              ? 'border-primary text-foreground'
              : 'border-transparent text-muted-foreground hover:text-foreground'
          )}
        >
          <AlertTriangle className="h-4 w-4" aria-hidden="true" />
          Conflicts
          <span className="rounded-full border border-border/80 bg-muted/60 px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">
            Beta
          </span>
        </button>
      </div>

      {view === 'verification' && (
        <>
      {statsQuery.isLoading && <PageLoading label="Loading verification totals…" />}

      {statsQuery.isError && !statsQuery.isLoading && (
        <PageError
          title="Could not load verification totals"
          message={statsQuery.error instanceof Error ? statsQuery.error.message : undefined}
          onRetry={() => statsQuery.refetch()}
        />
      )}

      {stats && (
        <section className="grid grid-cols-2 gap-3 lg:grid-cols-5">
          <StatCard label="Answers" value={stats.total} hint="assistant responses" />
          <StatCard label="Checked" value={stats.verified} hint="at least one stored check" accent="text-emerald-700 dark:text-emerald-300" />
          <StatCard label="Deep check done" value={stats.completed} hint="every analyzed claim assessed" accent="text-primary" />
          <StatCard label="Partly checked" value={stats.partial} hint="some claims unassessed" accent="text-amber-700 dark:text-amber-300" />
          <StatCard label="Not checked" value={stats.unverified} hint="no stored check" accent="text-muted-foreground" />
        </section>
      )}

      <div className="flex flex-wrap items-center gap-2" role="tablist" aria-label="Verification status filter">
        {FILTERS.map((option) => (
          <button
            key={option.value || 'all'}
            type="button"
            role="tab"
            aria-selected={filter === option.value}
            onClick={() => changeFilter(option.value)}
            className={cn(
              'rounded-full border px-3.5 py-1.5 text-xs font-semibold transition',
              filter === option.value
                ? 'border-primary/30 bg-primary/15 text-primary'
                : 'border-border/80 bg-card/60 text-muted-foreground hover:bg-accent hover:text-foreground'
            )}
          >
            {option.label}
          </button>
        ))}
      </div>

      {listQuery.isLoading && <PageLoading label="Loading answers…" />}

      {listQuery.isError && !listQuery.isLoading && (
        <PageError
          title="Could not load answers"
          message={listQuery.error instanceof Error ? listQuery.error.message : undefined}
          onRetry={() => listQuery.refetch()}
        />
      )}

      {list && !listQuery.isLoading && !listQuery.isError && (
        <>
          {items.length === 0 ? (
            <PageEmpty
              icon={ShieldQuestion}
              title={filter ? 'No answers match this filter' : 'No answers checked yet'}
              description={
                filter
                  ? 'Try another filter, or clear it to see every assistant answer.'
                  : 'Run a deep check from any answer in Ask. Every answer also gets a free citation check automatically, and the results appear here.'
              }
              action={
                <Link
                  to="/dashboard/workspace"
                  className="mt-1 inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-semibold text-primary-foreground hover:bg-primary/90"
                >
                  <MessageSquare className="h-4 w-4" aria-hidden="true" />
                  Open research workspace
                </Link>
              }
            />
          ) : (
            <ul className="space-y-3">
              {items.map((item) => (
                <VerificationCard
                  key={item.messageId}
                  item={item}
                  verifying={verify.isPending && verify.variables?.messageId === item.messageId}
                  onVerify={() => verify.mutate(item)}
                />
              ))}
            </ul>
          )}

          <Pagination
            page={list.page}
            totalPages={list.totalPages}
            onChange={setPage}
            label="Verification pages"
          />
        </>
      )}

      <p className="rounded-xl border border-border/70 bg-muted/30 px-4 py-3 text-[11px] leading-5 text-muted-foreground">
        <strong className="text-foreground/80">What the numbers mean:</strong> coverage is the
        deterministic fraction of analyzed claims that cite at least one valid source. Support
        badges come from one bounded model call and describe grounding in the cited excerpts only.
        A failed check stores nothing and leaves the answer unchanged.
      </p>
        </>
      )}

      {view === 'contradictions' && <ContradictionsPanel />}

      <ToastProvider>
        <ToastViewport />
      </ToastProvider>
    </div>
  );
}

function VerificationCard({
  item,
  verifying,
  onVerify,
}: {
  item: VerificationListItem;
  verifying: boolean;
  onVerify: () => void;
}) {
  const status = STATUS_STYLES[item.status] ?? STATUS_STYLES.NOT_REQUESTED;
  const hasStoredResult = item.status !== 'NOT_REQUESTED';

  return (
    <li className="rounded-2xl border border-border/80 bg-card/70 p-5 shadow-sm transition hover:border-emerald-400/25">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <span className={cn('inline-flex rounded-full border px-2.5 py-0.5 text-[11px] font-semibold', status.className)}>
              {status.label}
            </span>
            {item.conversationTitle && (
              <Link
                to={`/dashboard/workspace/${item.conversationId}`}
                className="inline-flex min-w-0 items-center gap-1.5 text-sm font-semibold hover:text-primary"
              >
                <MessageSquare className="h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
                <span className="truncate">{item.conversationTitle}</span>
              </Link>
            )}
            <span className="text-[11px] text-muted-foreground">
              {formatDistanceToNow(new Date(item.createdAt), { addSuffix: true })}
            </span>
          </div>
          <p className="mt-3 max-w-3xl text-sm leading-7 text-foreground/90">{item.contentExcerpt}</p>
        </div>

        <div className="flex shrink-0 flex-wrap items-center gap-2">
          <Link
            to={`/dashboard/workspace/${item.conversationId}`}
            className="inline-flex items-center gap-1.5 rounded-lg border border-border bg-background/70 px-3 py-2 text-xs font-semibold text-muted-foreground transition hover:bg-accent hover:text-foreground"
          >
            Open
            <ArrowUpRight className="h-3.5 w-3.5" aria-hidden="true" />
          </Link>
          <button
            type="button"
            onClick={onVerify}
            disabled={verifying}
            className="inline-flex items-center gap-1.5 rounded-lg bg-primary px-3 py-2 text-xs font-semibold text-primary-foreground transition hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {verifying ? (
              <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden="true" />
            ) : (
              <ShieldCheck className="h-3.5 w-3.5" aria-hidden="true" />
            )}
            {verifying ? 'Checking…' : hasStoredResult ? 'Re-run deep check' : 'Deep check'}
          </button>
        </div>
      </div>

      <div className="mt-4 flex flex-wrap items-center gap-x-5 gap-y-2 border-t border-border/60 pt-3 text-[11px] text-muted-foreground">
        <span>
          coverage{' '}
          <strong className="text-foreground/90 tabular-nums">
            {item.coverage != null ? `${Math.round(item.coverage * 100)}%` : '—'}
          </strong>
        </span>
        <span>
          claims analyzed{' '}
          <strong className="text-foreground/90 tabular-nums">
            {item.analyzedClaims}/{item.totalClaims}
          </strong>
        </span>
        <span>
          classified{' '}
          <strong className="text-foreground/90 tabular-nums">{item.classifiedClaims}</strong>
        </span>
        <span>
          citations <strong className="text-foreground/90 tabular-nums">{item.citationCount}</strong>
        </span>
        {item.invalidReferenceCount > 0 && (
          <span className="inline-flex items-center gap-1 text-rose-700 dark:text-rose-300">
            <AlertTriangle className="h-3 w-3" aria-hidden="true" />
            {item.invalidReferenceCount} invalid reference{item.invalidReferenceCount === 1 ? '' : 's'}
          </span>
        )}
        {item.verificationModel && <span>model {item.verificationModel}</span>}
        {item.verifiedAt && (
          <span>checked {formatDistanceToNow(new Date(item.verifiedAt), { addSuffix: true })}</span>
        )}
      </div>

      {hasStoredResult && (
        <div className="mt-3 flex flex-wrap items-center gap-2">
          {(Object.keys(SUPPORT_STYLES) as SupportClass[]).map((supportClass) => {
            const style = SUPPORT_STYLES[supportClass];
            const count = item.supportCounts[supportClass] ?? 0;
            return (
              <span
                key={supportClass}
                className={cn(
                  'inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-[10px] font-semibold',
                  count > 0 ? style.className : 'border-border/60 bg-muted/30 text-muted-foreground/60'
                )}
              >
                {supportClass === 'SUPPORTED' && <CheckCircle2 className="h-3 w-3" aria-hidden="true" />}
                {supportLabel(supportClass)}
                <span className="tabular-nums">{count}</span>
              </span>
            );
          })}
        </div>
      )}
    </li>
  );
}

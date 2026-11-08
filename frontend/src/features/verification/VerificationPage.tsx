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
  { value: 'VERIFIED', label: 'Verified' },
  { value: 'COMPLETED', label: 'Completed' },
  { value: 'PARTIAL', label: 'Partial' },
  { value: 'UNVERIFIED', label: 'Not verified' },
];

const SUPPORT_STYLES: Record<SupportClass, { label: string; className: string }> = {
  SUPPORTED: { label: 'Supported', className: 'border-emerald-400/25 bg-emerald-500/10 text-emerald-300' },
  PARTIALLY_SUPPORTED: { label: 'Partially supported', className: 'border-amber-400/25 bg-amber-500/10 text-amber-300' },
  UNSUPPORTED: { label: 'Unsupported', className: 'border-rose-400/25 bg-rose-500/10 text-rose-300' },
  CONFLICTING: { label: 'Conflicting', className: 'border-fuchsia-400/25 bg-fuchsia-500/10 text-fuchsia-300' },
};

const STATUS_STYLES: Record<string, { label: string; className: string }> = {
  NOT_REQUESTED: { label: 'Not verified', className: 'border-border/80 bg-muted/60 text-muted-foreground' },
  PARTIAL: { label: 'Partial', className: 'border-amber-400/25 bg-amber-500/10 text-amber-300' },
  COMPLETED: { label: 'Verified', className: 'border-emerald-400/25 bg-emerald-500/10 text-emerald-300' },
  FAILED: { label: 'Failed', className: 'border-rose-400/25 bg-rose-500/10 text-rose-300' },
};

export function VerificationPage() {
  const queryClient = useQueryClient();
  const { toast } = useToast();
  const [page, setPage] = useState(0);
  const [filter, setFilter] = useState('');

  const statsQuery = useQuery({
    queryKey: ['verifications', 'stats'],
    queryFn: () => api.get<VerificationStatsResponse>('/verifications/stats'),
  });

  const listQuery = useQuery({
    queryKey: ['verifications', 'list', page, filter],
    queryFn: () => {
      const params = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
      if (filter) params.set('status', filter);
      return api.get<VerificationListResponse>(`/verifications?${params.toString()}`);
    },
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
          title: 'Verification failed',
          description: result.error ?? 'The model could not classify this answer. Nothing was stored.',
          variant: 'destructive',
        });
      } else {
        const classified = result.claims.filter((claim) => claim.supportClass != null).length;
        toast({
          title: result.status === 'PARTIAL' ? 'Verification partially completed' : 'Verification complete',
          description: `${classified} of ${result.claims.length} analyzed claim(s) classified${
            result.coverage != null ? ` · coverage ${Math.round(result.coverage * 100)}%` : ''
          }.`,
        });
      }
    },
    onError: (error) => toast(errorToast(error, 'Verification failed')),
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
      <section className="relative isolate overflow-hidden rounded-[26px] border border-emerald-400/15 bg-gradient-to-br from-emerald-500/[0.12] via-card to-indigo-500/[0.08] p-6 sm:p-8">
        <div className="pointer-events-none absolute -right-16 -top-24 -z-10 h-64 w-64 rounded-full bg-emerald-500/10 blur-[90px]" />
        <p className="eyebrow">Verification</p>
        <h1 className="mt-2 text-2xl font-extrabold tracking-tight sm:text-3xl">Check every claim against its evidence</h1>
        <p className="mt-2 max-w-2xl text-sm leading-7 text-muted-foreground">
          Each assistant answer is broken into claims and assessed against the passages it cites.
          Support classes are AI grounding assessments, not calibrated probabilities — open the
          conversation to read the source excerpts yourself.
        </p>
      </section>

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
          <StatCard label="Verified" value={stats.verified} hint="at least one stored claim" accent="text-emerald-300" />
          <StatCard label="Completed" value={stats.completed} hint="every analyzed claim classified" accent="text-indigo-300" />
          <StatCard label="Partial" value={stats.partial} hint="some claims unclassified" accent="text-amber-300" />
          <StatCard label="Not verified" value={stats.unverified} hint="no stored verification" accent="text-muted-foreground" />
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
                ? 'border-indigo-400/30 bg-indigo-500/15 text-indigo-200'
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
              title={filter ? 'No answers match this filter' : 'No answers to verify yet'}
              description={
                filter
                  ? 'Try another status filter, or clear it to see every assistant answer.'
                  : 'Ask a question in the research workspace. Answers there can be verified claim by claim, and the results appear here.'
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
        classes come from one bounded model call and describe grounding in the cited excerpts only.
        A failed verification stores nothing and leaves the answer unverified.
      </p>

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
                className="inline-flex min-w-0 items-center gap-1.5 text-sm font-semibold hover:text-indigo-300"
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
            {verifying ? 'Verifying…' : hasStoredResult ? 'Re-run verification' : 'Verify claims'}
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
          <span className="inline-flex items-center gap-1 text-rose-300">
            <AlertTriangle className="h-3 w-3" aria-hidden="true" />
            {item.invalidReferenceCount} invalid reference{item.invalidReferenceCount === 1 ? '' : 's'}
          </span>
        )}
        {item.verificationModel && <span>model {item.verificationModel}</span>}
        {item.verifiedAt && (
          <span>verified {formatDistanceToNow(new Date(item.verifiedAt), { addSuffix: true })}</span>
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
                {style.label}
                <span className="tabular-nums">{count}</span>
              </span>
            );
          })}
        </div>
      )}
    </li>
  );
}

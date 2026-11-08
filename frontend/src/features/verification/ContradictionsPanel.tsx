'use client';

import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  api,
  type ContradictionDetectionResponse,
  type ContradictionItem,
  type ContradictionListResponse,
  type ContradictionSeverity,
  type ContradictionStatsResponse,
  type ContradictionType,
  type DocumentListResponse,
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
import { useToast } from '@/hooks/useToast';
import { formatDistanceToNow } from 'date-fns';
import {
  AlertTriangle,
  ArrowUpRight,
  FileText,
  Loader2,
  ScanSearch,
  ShieldAlert,
} from 'lucide-react';

const PAGE_SIZE = 10;

const TYPE_LABELS: Record<ContradictionType, string> = {
  NUMERIC_CONFLICT: 'Numeric',
  TEMPORAL_CONFLICT: 'Temporal',
  ATTRIBUTE_CONFLICT: 'Attribute',
  CAUSAL_CONFLICT: 'Causal',
  POLARITY_CONFLICT: 'Polarity',
};

const TYPE_FILTERS: Array<{ value: '' | ContradictionType; label: string }> = [
  { value: '', label: 'All classes' },
  { value: 'NUMERIC_CONFLICT', label: 'Numeric' },
  { value: 'TEMPORAL_CONFLICT', label: 'Temporal' },
  { value: 'ATTRIBUTE_CONFLICT', label: 'Attribute' },
  { value: 'CAUSAL_CONFLICT', label: 'Causal' },
  { value: 'POLARITY_CONFLICT', label: 'Polarity' },
];

const SEVERITY_STYLES: Record<ContradictionSeverity, { label: string; className: string }> = {
  HIGH: { label: 'High impact', className: 'border-rose-400/25 bg-rose-500/10 text-rose-300' },
  MEDIUM: { label: 'Medium impact', className: 'border-amber-400/25 bg-amber-500/10 text-amber-300' },
  LOW: { label: 'Low impact', className: 'border-sky-400/25 bg-sky-500/10 text-sky-300' },
};

/**
 * Contradictions tab (Feature 8). Detection is explicit because it spends one
 * bounded model call; reading stored findings never calls a model. Every card
 * shows both claims with their document/page citations so the user can judge
 * the finding instead of trusting a label.
 */
export function ContradictionsPanel() {
  const queryClient = useQueryClient();
  const { toast } = useToast();
  const [page, setPage] = useState(0);
  const [typeFilter, setTypeFilter] = useState<'' | ContradictionType>('');
  const [scopeDocumentId, setScopeDocumentId] = useState('');

  const documentsQuery = useQuery({
    queryKey: ['documents', 'contradiction-scope'],
    queryFn: () =>
      api.get<DocumentListResponse>('/documents?size=100&status=READY&sort=createdAt,desc'),
    staleTime: 30_000,
  });

  const statsQuery = useQuery({
    queryKey: ['contradictions', 'stats'],
    queryFn: () => api.get<ContradictionStatsResponse>('/contradictions/stats'),
  });

  const listQuery = useQuery({
    queryKey: ['contradictions', 'list', page, typeFilter, scopeDocumentId],
    queryFn: () => {
      const params = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
      if (typeFilter) params.set('type', typeFilter);
      if (scopeDocumentId) params.set('documentId', scopeDocumentId);
      return api.get<ContradictionListResponse>(`/contradictions?${params.toString()}`);
    },
    placeholderData: (previous) => previous,
  });

  const detect = useMutation<ContradictionDetectionResponse, Error, void>({
    mutationFn: () =>
      api.post<ContradictionDetectionResponse>(
        '/contradictions/detect',
        scopeDocumentId ? { documentIds: [scopeDocumentId] } : {}
      ),
    onSuccess: (result) => {
      queryClient.invalidateQueries({ queryKey: ['contradictions'] });
      if (result.status === 'FAILED') {
        toast({
          title: 'Contradiction detection failed',
          description: result.error ?? 'Nothing was stored. Earlier findings were kept.',
          variant: 'destructive',
        });
      } else if (result.status === 'SKIPPED') {
        toast({
          title: 'Nothing to scan',
          description: result.error ?? 'Upload and process documents first.',
        });
      } else {
        toast({
          title: result.stored > 0 ? 'Contradictions detected' : 'No contradictions found',
          description:
            result.candidatePairs === 0
              ? `No comparable claim pairs were found across ${result.analyzedDocuments} document(s).`
              : `${result.stored} finding(s) stored from ${result.candidatePairs} candidate pair(s) across ${result.analyzedDocuments} document(s).`,
        });
      }
    },
    onError: (error) => toast(errorToast(error, 'Contradiction detection failed')),
  });

  const stats = statsQuery.data;
  const list = listQuery.data;
  const items = list?.content ?? [];
  const documents = documentsQuery.data?.content ?? [];
  const activeScopeName = documents.find((document) => document.id === scopeDocumentId)?.originalFilename;

  const changeType = (value: '' | ContradictionType) => {
    setTypeFilter(value);
    setPage(0);
  };

  const changeScope = (value: string) => {
    setScopeDocumentId(value);
    setPage(0);
  };

  return (
    <div className="space-y-5">
      <section className="rounded-2xl border border-border/80 bg-card/60 p-4 sm:p-5">
        <div className="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
          <div className="flex flex-col gap-2 sm:flex-row sm:items-end">
            <div>
              <label htmlFor="contradiction-scope" className="eyebrow">
                Scope
              </label>
              <select
                id="contradiction-scope"
                value={scopeDocumentId}
                onChange={(event) => changeScope(event.target.value)}
                className="mt-1.5 w-full min-w-[240px] rounded-lg border border-border bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-indigo-400/40 sm:w-auto"
              >
                <option value="">Entire library</option>
                {documents.map((document) => (
                  <option key={document.id} value={document.id}>
                    {document.originalFilename}
                  </option>
                ))}
              </select>
            </div>
            <button
              type="button"
              onClick={() => detect.mutate()}
              disabled={detect.isPending}
              aria-busy={detect.isPending}
              className="inline-flex items-center justify-center gap-2 rounded-lg bg-primary px-4 py-2.5 text-sm font-semibold text-primary-foreground transition hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-50"
            >
              {detect.isPending ? (
                <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
              ) : (
                <ScanSearch className="h-4 w-4" aria-hidden="true" />
              )}
              {detect.isPending
                ? 'Scanning claims…'
                : activeScopeName
                  ? 'Scan this document'
                  : 'Scan library'}
            </button>
          </div>

          <p className="max-w-xl text-[11px] leading-5 text-muted-foreground">
            Each scan compares bounded claim pairs that share a subject. Different scopes,
            complementary facts, different time windows and paraphrases are deliberately
            <strong className="text-foreground/80"> not </strong>
            reported as contradictions.
          </p>
        </div>

        {documentsQuery.isError && (
          <p className="mt-3 text-[11px] text-amber-300">
            Document list unavailable — library scope still works.
          </p>
        )}
      </section>

      {statsQuery.isLoading && <PageLoading label="Loading contradiction totals…" />}

      {statsQuery.isError && !statsQuery.isLoading && (
        <PageError
          title="Could not load contradiction totals"
          message={statsQuery.error instanceof Error ? statsQuery.error.message : undefined}
          onRetry={() => statsQuery.refetch()}
        />
      )}

      {stats && (
        <section className="grid grid-cols-2 gap-3 lg:grid-cols-4">
          <StatCard label="Findings" value={stats.total} hint="stored contradiction pairs" />
          <StatCard
            label="High impact"
            value={stats.bySeverity.HIGH}
            hint="material to a decision"
            accent="text-rose-300"
          />
          <StatCard
            label="Medium impact"
            value={stats.bySeverity.MEDIUM}
            hint="worth reviewing"
            accent="text-amber-300"
          />
          <StatCard
            label="Low impact"
            value={stats.bySeverity.LOW}
            hint="minor discrepancy"
            accent="text-sky-300"
          />
        </section>
      )}

      {stats?.lastDetectedAt && (
        <p className="text-[11px] text-muted-foreground">
          Newest stored finding{' '}
          {formatDistanceToNow(new Date(stats.lastDetectedAt), { addSuffix: true })}
        </p>
      )}

      {detect.data && <DetectionResult result={detect.data} />}

      <div
        className="flex flex-wrap items-center gap-2"
        role="tablist"
        aria-label="Contradiction class filter"
      >
        {TYPE_FILTERS.map((option) => (
          <button
            key={option.value || 'all'}
            type="button"
            role="tab"
            aria-selected={typeFilter === option.value}
            onClick={() => changeType(option.value)}
            className={cn(
              'rounded-full border px-3.5 py-1.5 text-xs font-semibold transition',
              typeFilter === option.value
                ? 'border-indigo-400/30 bg-indigo-500/15 text-indigo-200'
                : 'border-border/80 bg-card/60 text-muted-foreground hover:bg-accent hover:text-foreground'
            )}
          >
            {option.label}
          </button>
        ))}
      </div>

      {listQuery.isLoading && <PageLoading label="Loading contradictions…" />}

      {listQuery.isError && !listQuery.isLoading && (
        <PageError
          title="Could not load contradictions"
          message={listQuery.error instanceof Error ? listQuery.error.message : undefined}
          onRetry={() => listQuery.refetch()}
        />
      )}

      {list && !listQuery.isLoading && !listQuery.isError && (
        <>
          {items.length === 0 ? (
            <PageEmpty
              icon={ScanSearch}
              title={
                typeFilter || scopeDocumentId
                  ? 'No findings match this view'
                  : 'No contradictions detected yet'
              }
              description={
                typeFilter || scopeDocumentId
                  ? 'Try another class or scope, or run a new scan for the current selection.'
                  : 'Run a scan to compare claims that share a subject across your processed documents. A clean result is a real result — nothing is invented when no conflict exists.'
              }
              action={
                <button
                  type="button"
                  onClick={() => detect.mutate()}
                  disabled={detect.isPending}
                  className="mt-1 inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-semibold text-primary-foreground hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  <ScanSearch className="h-4 w-4" aria-hidden="true" />
                  Run detection
                </button>
              }
            />
          ) : (
            <ul className="space-y-3" aria-label="Detected contradictions">
              {items.map((item) => (
                <ContradictionCard key={item.id} item={item} />
              ))}
            </ul>
          )}

          <Pagination
            page={list.page}
            totalPages={list.totalPages}
            onChange={setPage}
            label="Contradiction pages"
          />
        </>
      )}

      <p className="rounded-xl border border-border/70 bg-muted/30 px-4 py-3 text-[11px] leading-5 text-muted-foreground">
        <strong className="text-foreground/80">What severity means:</strong> LOW, MEDIUM and HIGH
        describe the potential impact of a conflict on a decision — never the confidence or
        probability of detection. Every finding stores both claims and their document/page
        citations, so read the passages before acting. A failed scan stores nothing and leaves
        earlier findings untouched.
      </p>
    </div>
  );
}

function DetectionResult({ result }: { result: ContradictionDetectionResponse }) {
  const failed = result.status === 'FAILED';
  const skipped = result.status === 'SKIPPED';
  return (
    <div
      role="status"
      className={cn(
        'rounded-xl border px-4 py-3 text-[11px] leading-5',
        failed
          ? 'border-destructive/30 bg-destructive/5 text-rose-200'
          : skipped
            ? 'border-amber-400/25 bg-amber-500/5 text-amber-200'
            : 'border-emerald-400/20 bg-emerald-500/5 text-emerald-200'
      )}
    >
      <p className="font-semibold">
        {failed ? 'Scan failed' : skipped ? 'Scan skipped' : 'Scan completed'}
      </p>
      {failed || skipped ? (
        <p>{result.error ?? 'Nothing was stored.'}</p>
      ) : (
        <p>
          {result.analyzedDocuments} document(s), {result.analyzedClaims} claim(s),{' '}
          {result.candidatePairs} candidate pair(s), {result.stored} stored finding(s).
          {result.model ? ` Model: ${result.model}.` : ''}
        </p>
      )}
    </div>
  );
}

function ContradictionCard({ item }: { item: ContradictionItem }) {
  const severity = SEVERITY_STYLES[item.severity] ?? SEVERITY_STYLES.MEDIUM;
  return (
    <li className="rounded-2xl border border-border/80 bg-card/70 p-5 shadow-sm transition hover:border-fuchsia-400/25">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex flex-wrap items-center gap-2">
          <span
            className={cn(
              'inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-[11px] font-semibold',
              severity.className
            )}
          >
            {item.severity === 'HIGH' ? (
              <ShieldAlert className="h-3 w-3" aria-hidden="true" />
            ) : (
              <AlertTriangle className="h-3 w-3" aria-hidden="true" />
            )}
            {severity.label}
          </span>
          <span className="inline-flex rounded-full border border-violet-400/25 bg-violet-500/10 px-2.5 py-0.5 text-[11px] font-semibold text-violet-200">
            {TYPE_LABELS[item.contradictionType] ?? item.contradictionType}
          </span>
          {item.subjectEntity && (
            <span className="inline-flex rounded-full border border-border/80 bg-muted/50 px-2.5 py-0.5 text-[11px] text-muted-foreground">
              subject: <strong className="ml-1 text-foreground/80">{item.subjectEntity}</strong>
            </span>
          )}
        </div>
        <span className="text-[11px] text-muted-foreground">
          detected {formatDistanceToNow(new Date(item.detectedAt), { addSuffix: true })}
        </span>
      </div>

      {item.explanation && (
        <p className="mt-3 max-w-4xl text-sm leading-7 text-foreground/90">{item.explanation}</p>
      )}

      <div className="mt-4 grid gap-3 md:grid-cols-2">
        <ClaimBlock
          label="Claim A"
          claim={item.claimA}
          documentId={item.documentAId}
          documentName={item.documentAName}
          page={item.pageA}
        />
        <ClaimBlock
          label="Claim B"
          claim={item.claimB}
          documentId={item.documentBId}
          documentName={item.documentBName}
          page={item.pageB}
        />
      </div>

      <div className="mt-3 flex flex-wrap items-center gap-x-5 gap-y-2 border-t border-border/60 pt-3 text-[11px] text-muted-foreground">
        {item.model && <span>model {item.model}</span>}
        <span>severity is impact, not probability</span>
      </div>
    </li>
  );
}

function ClaimBlock({
  label,
  claim,
  documentId,
  documentName,
  page,
}: {
  label: string;
  claim: string;
  documentId: string;
  documentName: string;
  page: number | null;
}) {
  return (
    <div className="rounded-xl border border-border/70 bg-background/40 p-3.5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="eyebrow">{label}</p>
        <Link
          to={`/dashboard/documents/${documentId}`}
          className="inline-flex min-w-0 items-center gap-1.5 text-[11px] font-semibold text-indigo-300 hover:text-indigo-200"
        >
          <FileText className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
          <span className="max-w-[220px] truncate">{documentName}</span>
          <span className="shrink-0 text-muted-foreground">p. {page ?? '?'}</span>
          <ArrowUpRight className="h-3 w-3 shrink-0" aria-hidden="true" />
        </Link>
      </div>
      <blockquote className="mt-2 border-l-2 border-border pl-3 text-sm leading-7 text-foreground/90">
        {claim}
      </blockquote>
    </div>
  );
}

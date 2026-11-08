'use client';

import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { api, type ReportItem, type ReportListResponse } from '@/lib/api';
import { cn } from '@/lib/utils';
import {
  PageEmpty,
  PageError,
  PageLoading,
  Pagination,
} from '@/components/ui/PageStates';
import { formatDistanceToNow } from 'date-fns';
import {
  ArrowUpRight,
  CalendarClock,
  FileBarChart,
  FileText,
  Lightbulb,
  ListChecks,
  Loader2,
  Search,
  Tags,
} from 'lucide-react';

const PAGE_SIZE = 8;

const STATUS_FILTERS: Array<{ value: string; label: string }> = [
  { value: '', label: 'All reports' },
  { value: 'READY', label: 'Ready' },
  { value: 'PENDING', label: 'Generating' },
  { value: 'FAILED', label: 'Failed' },
  { value: 'NONE', label: 'Not generated' },
];

const STATUS_STYLES: Record<string, string> = {
  READY: 'border-emerald-400/25 bg-emerald-500/10 text-emerald-300',
  PENDING: 'border-indigo-400/25 bg-indigo-500/10 text-indigo-300',
  FAILED: 'border-rose-400/25 bg-rose-500/10 text-rose-300',
  NONE: 'border-border/80 bg-muted/60 text-muted-foreground',
};

export function ReportsPage() {
  const [page, setPage] = useState(0);
  const [queryInput, setQueryInput] = useState('');
  const [query, setQuery] = useState('');
  const [status, setStatus] = useState('');

  const reportsQuery = useQuery({
    queryKey: ['reports', page, query, status],
    queryFn: () => {
      const params = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
      if (query) params.set('q', query);
      if (status) params.set('summaryStatus', status);
      return api.get<ReportListResponse>(`/reports?${params.toString()}`);
    },
    placeholderData: (previous) => previous,
  });

  const reports = reportsQuery.data;
  const items = reports?.content ?? [];

  const submitSearch = (event: React.FormEvent) => {
    event.preventDefault();
    setQuery(queryInput.trim());
    setPage(0);
  };

  const changeStatus = (value: string) => {
    setStatus(value);
    setPage(0);
  };

  return (
    <div className="space-y-6">
      <section className="relative isolate overflow-hidden rounded-[26px] border border-indigo-400/15 bg-gradient-to-br from-indigo-500/[0.14] via-card to-cyan-500/[0.08] p-6 sm:p-8">
        <div className="pointer-events-none absolute -right-16 -top-24 -z-10 h-64 w-64 rounded-full bg-cyan-500/10 blur-[90px]" />
        <p className="eyebrow">Reports</p>
        <h1 className="mt-2 text-2xl font-extrabold tracking-tight sm:text-3xl">Structured briefs from your documents</h1>
        <p className="mt-2 max-w-2xl text-sm leading-7 text-muted-foreground">
          Executive summaries, key findings, entities, dates and suggested questions generated from
          each processed document. Every finding is labelled as a fact stated in the document or an
          interpretation of it.
        </p>
      </section>

      <section className="flex flex-col gap-3 rounded-2xl border border-border/80 bg-card/60 p-3 lg:flex-row lg:items-center">
        <form onSubmit={submitSearch} className="flex min-w-0 flex-1 items-center gap-2">
          <div className="relative min-w-0 flex-1">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
            <input
              type="search"
              value={queryInput}
              onChange={(event) => setQueryInput(event.target.value)}
              placeholder="Filter reports by document name…"
              aria-label="Filter reports by document name"
              className="h-10 w-full rounded-xl border border-border/70 bg-background/70 pl-9 pr-3 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/25"
            />
          </div>
          <button type="submit" className="rounded-lg border border-border bg-card px-3 py-2 text-xs font-semibold hover:bg-accent">
            Filter
          </button>
        </form>

        <div className="flex flex-wrap items-center gap-2" role="tablist" aria-label="Report status filter">
          {STATUS_FILTERS.map((option) => (
            <button
              key={option.value || 'all'}
              type="button"
              role="tab"
              aria-selected={status === option.value}
              onClick={() => changeStatus(option.value)}
              className={cn(
                'rounded-full border px-3 py-1.5 text-xs font-semibold transition',
                status === option.value
                  ? 'border-indigo-400/30 bg-indigo-500/15 text-indigo-200'
                  : 'border-border/80 bg-card/60 text-muted-foreground hover:bg-accent hover:text-foreground'
              )}
            >
              {option.label}
            </button>
          ))}
        </div>
      </section>

      {reportsQuery.isLoading && <PageLoading label="Loading reports…" />}

      {reportsQuery.isError && !reportsQuery.isLoading && (
        <PageError
          title="Could not load reports"
          message={reportsQuery.error instanceof Error ? reportsQuery.error.message : undefined}
          onRetry={() => reportsQuery.refetch()}
        />
      )}

      {reports && !reportsQuery.isLoading && !reportsQuery.isError && (
        <>
          {items.length === 0 ? (
            <PageEmpty
              icon={FileBarChart}
              title={query || status ? 'No reports match these filters' : 'No reports yet'}
              description={
                query || status
                  ? 'Clear the search or status filter to see every processed document.'
                  : 'Reports are generated after a document finishes processing. Upload a PDF and its summary will appear here when it is ready.'
              }
              action={
                <Link
                  to="/dashboard/documents"
                  className="mt-1 inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-semibold text-primary-foreground hover:bg-primary/90"
                >
                  <FileText className="h-4 w-4" aria-hidden="true" />
                  Go to documents
                </Link>
              }
            />
          ) : (
            <ul className="space-y-4">
              {items.map((report) => (
                <ReportCard key={report.documentId} report={report} />
              ))}
            </ul>
          )}

          <Pagination
            page={reports.page}
            totalPages={reports.totalPages}
            onChange={setPage}
            label="Report pages"
          />
        </>
      )}

      <p className="rounded-xl border border-border/70 bg-muted/30 px-4 py-3 text-[11px] leading-5 text-muted-foreground">
        <strong className="text-foreground/80">Reading the labels:</strong> “Fact” means the
        statement is grounded in the document text. “Interpretation” is the model’s reading and may
        be wrong. Regenerating a report replaces its stored sections atomically.
      </p>
    </div>
  );
}

function ReportCard({ report }: { report: ReportItem }) {
  const statusClass = STATUS_STYLES[report.summaryStatus] ?? STATUS_STYLES.NONE;

  return (
    <li className="rounded-2xl border border-border/80 bg-card/70 p-5 shadow-sm transition hover:border-indigo-400/25">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <Link
              to={`/dashboard/documents/${report.documentId}`}
              className="inline-flex min-w-0 items-center gap-1.5 text-base font-bold hover:text-indigo-300"
            >
              <FileText className="h-4 w-4 shrink-0 text-indigo-300" aria-hidden="true" />
              <span className="truncate">{report.documentName}</span>
            </Link>
            <span className={cn('inline-flex rounded-full border px-2.5 py-0.5 text-[10px] font-semibold', statusClass)}>
              {report.summaryStatus === 'READY'
                ? 'Ready'
                : report.summaryStatus === 'PENDING'
                  ? 'Generating'
                  : report.summaryStatus === 'FAILED'
                    ? 'Failed'
                    : 'Not generated'}
            </span>
          </div>
          <p className="mt-1 text-[11px] text-muted-foreground">
            {report.pageCount != null ? `${report.pageCount} pages · ` : ''}
            updated {formatDistanceToNow(new Date(report.updatedAt), { addSuffix: true })}
            {report.model ? ` · ${report.model}` : ''}
          </p>
        </div>

        <Link
          to={`/dashboard/documents/${report.documentId}`}
          className="inline-flex shrink-0 items-center gap-1.5 rounded-lg border border-border bg-background/70 px-3 py-2 text-xs font-semibold text-muted-foreground transition hover:bg-accent hover:text-foreground"
        >
          Open document
          <ArrowUpRight className="h-3.5 w-3.5" aria-hidden="true" />
        </Link>
      </div>

      {report.summaryStatus === 'FAILED' && report.summaryError && (
        <p className="mt-3 rounded-xl border border-rose-400/25 bg-rose-500/10 px-3 py-2 text-xs text-rose-200">
          {report.summaryError}
        </p>
      )}

      {report.summaryStatus === 'PENDING' && (
        <p className="mt-3 inline-flex items-center gap-2 rounded-xl border border-indigo-400/20 bg-indigo-500/5 px-3 py-2 text-xs text-indigo-200">
          <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden="true" />
          Summaries are being generated. This page will show them once ready.
        </p>
      )}

      {report.executiveSummary ? (
        <div className="mt-4 rounded-xl border border-border/60 bg-background/40 p-4">
          <p className="eyebrow">Executive summary</p>
          <p className="mt-2 text-sm leading-7 text-foreground/90">{report.executiveSummary}</p>
        </div>
      ) : report.summaryStatus === 'READY' ? (
        <p className="mt-4 rounded-xl border border-border/60 bg-background/40 px-4 py-3 text-xs text-muted-foreground">
          No executive summary section was stored for this document.
        </p>
      ) : null}

      {report.keyFindings.length > 0 && (
        <div className="mt-4">
          <p className="flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">
            <ListChecks className="h-3.5 w-3.5" aria-hidden="true" />
            Key findings
          </p>
          <ul className="mt-2 space-y-2">
            {report.keyFindings.map((finding, index) => (
              <li key={index} className="flex items-start gap-2 text-sm leading-6 text-foreground/90">
                <span
                  className={cn(
                    'mt-0.5 inline-flex shrink-0 items-center gap-1 rounded-full border px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-wide',
                    finding.type === 'FACT'
                      ? 'border-emerald-400/25 bg-emerald-500/10 text-emerald-300'
                      : 'border-amber-400/25 bg-amber-500/10 text-amber-300'
                  )}
                >
                  {finding.type === 'FACT' ? <FileText className="h-2.5 w-2.5" aria-hidden="true" /> : <Lightbulb className="h-2.5 w-2.5" aria-hidden="true" />}
                  {finding.type === 'FACT' ? 'Fact' : 'Interpretation'}
                </span>
                <span>{finding.text}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className="mt-4 flex flex-wrap items-center gap-x-5 gap-y-2 border-t border-border/60 pt-3 text-[11px] text-muted-foreground">
        <span className="inline-flex items-center gap-1.5">
          <Tags className="h-3 w-3" aria-hidden="true" />
          {report.entityCount} entities
        </span>
        <span className="inline-flex items-center gap-1.5">
          <CalendarClock className="h-3 w-3" aria-hidden="true" />
          {report.dateStatCount} dates/stats
        </span>
        <span>{report.topicCount} topics</span>
        <span>{report.questionCount} suggested questions</span>
        <span>{report.sectionCount} sections</span>
        {report.generatedAt && (
          <span>generated {formatDistanceToNow(new Date(report.generatedAt), { addSuffix: true })}</span>
        )}
      </div>
    </li>
  );
}

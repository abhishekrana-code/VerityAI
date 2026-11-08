'use client';

import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  api,
  type Conversation,
  type ConversationListResponse,
  type HistoryItem,
  type HistoryListResponse,
  type ProcessingStage,
} from '@/lib/api';
import { cn } from '@/lib/utils';
import {
  PageEmpty,
  PageError,
  PageLoading,
  Pagination,
} from '@/components/ui/PageStates';
import { formatDistanceToNow } from 'date-fns';
import {
  AlertTriangle,
  ArrowUpRight,
  CheckCircle2,
  Clock,
  FileText,
  History,
  Loader2,
  MessageSquare,
  Upload,
} from 'lucide-react';

const PROCESSING_PAGE_SIZE = 25;
const SESSION_PAGE_SIZE = 15;

type Tab = 'processing' | 'sessions';

const STAGE_STYLES: Record<ProcessingStage, { label: string; className: string; icon: typeof Clock }> = {
  UPLOADED: { label: 'Uploaded', className: 'border-slate-400/25 bg-slate-500/10 text-slate-300', icon: Upload },
  QUEUED: { label: 'Queued', className: 'border-sky-400/25 bg-sky-500/10 text-sky-300', icon: Clock },
  EXTRACTING: { label: 'Extracting', className: 'border-indigo-400/25 bg-indigo-500/10 text-indigo-300', icon: Loader2 },
  CHUNKING: { label: 'Chunking', className: 'border-violet-400/25 bg-violet-500/10 text-violet-300', icon: Loader2 },
  INDEXING: { label: 'Indexing', className: 'border-cyan-400/25 bg-cyan-500/10 text-cyan-300', icon: Loader2 },
  COMPLETED: { label: 'Completed', className: 'border-emerald-400/25 bg-emerald-500/10 text-emerald-300', icon: CheckCircle2 },
  FAILED: { label: 'Failed', className: 'border-rose-400/25 bg-rose-500/10 text-rose-300', icon: AlertTriangle },
};

export function HistoryPage() {
  const [tab, setTab] = useState<Tab>('processing');
  const [processingPage, setProcessingPage] = useState(0);
  const [sessionPage, setSessionPage] = useState(0);

  const processingQuery = useQuery({
    queryKey: ['history', 'processing', processingPage],
    queryFn: () =>
      api.get<HistoryListResponse>(
        `/history/processing?page=${processingPage}&size=${PROCESSING_PAGE_SIZE}`
      ),
    enabled: tab === 'processing',
    placeholderData: (previous) => previous,
  });

  const sessionsQuery = useQuery({
    queryKey: ['history', 'sessions', sessionPage],
    queryFn: () =>
      api.get<ConversationListResponse>(
        `/conversations?page=${sessionPage}&size=${SESSION_PAGE_SIZE}`
      ),
    enabled: tab === 'sessions',
    placeholderData: (previous) => previous,
  });

  const processing = processingQuery.data;
  const sessions = sessionsQuery.data;

  return (
    <div className="space-y-6">
      <section className="relative isolate overflow-hidden rounded-[26px] border border-slate-400/15 bg-gradient-to-br from-slate-500/[0.12] via-card to-indigo-500/[0.08] p-6 sm:p-8">
        <div className="pointer-events-none absolute -right-16 -top-24 -z-10 h-64 w-64 rounded-full bg-slate-400/10 blur-[90px]" />
        <p className="eyebrow">History</p>
        <h1 className="mt-2 text-2xl font-extrabold tracking-tight sm:text-3xl">Everything that happened, in order</h1>
        <p className="mt-2 max-w-2xl text-sm leading-7 text-muted-foreground">
          Persisted document-processing events and your research sessions. Nothing here is
          synthesized — each row is a stored stage transition or a saved conversation.
        </p>
      </section>

      <div className="inline-flex rounded-xl border border-border/80 bg-card/60 p-1" role="tablist" aria-label="History type">
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'processing'}
          onClick={() => setTab('processing')}
          className={cn(
            'inline-flex items-center gap-2 rounded-lg px-4 py-2 text-xs font-semibold transition',
            tab === 'processing' ? 'bg-indigo-500/15 text-indigo-200' : 'text-muted-foreground hover:text-foreground'
          )}
        >
          <History className="h-3.5 w-3.5" aria-hidden="true" />
          Processing events
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'sessions'}
          onClick={() => setTab('sessions')}
          className={cn(
            'inline-flex items-center gap-2 rounded-lg px-4 py-2 text-xs font-semibold transition',
            tab === 'sessions' ? 'bg-indigo-500/15 text-indigo-200' : 'text-muted-foreground hover:text-foreground'
          )}
        >
          <MessageSquare className="h-3.5 w-3.5" aria-hidden="true" />
          Research sessions
        </button>
      </div>

      {tab === 'processing' && (
        <>
          {processingQuery.isLoading && <PageLoading label="Loading processing history…" />}

          {processingQuery.isError && !processingQuery.isLoading && (
            <PageError
              title="Could not load processing history"
              message={processingQuery.error instanceof Error ? processingQuery.error.message : undefined}
              onRetry={() => processingQuery.refetch()}
            />
          )}

          {processing && !processingQuery.isLoading && !processingQuery.isError && (
            <>
              {processing.content.length === 0 ? (
                <PageEmpty
                  icon={History}
                  title="No processing events yet"
                  description="Upload a PDF and its stage transitions — queued, extracting, chunking, indexing, completed or failed — will be recorded here."
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
                <ol className="space-y-2">
                  {processing.content.map((event) => (
                    <ProcessingEventRow key={event.id} event={event} />
                  ))}
                </ol>
              )}

              <Pagination
                page={processing.page}
                totalPages={processing.totalPages}
                onChange={setProcessingPage}
                label="Processing history pages"
              />
            </>
          )}
        </>
      )}

      {tab === 'sessions' && (
        <>
          {sessionsQuery.isLoading && <PageLoading label="Loading research sessions…" />}

          {sessionsQuery.isError && !sessionsQuery.isLoading && (
            <PageError
              title="Could not load research sessions"
              message={sessionsQuery.error instanceof Error ? sessionsQuery.error.message : undefined}
              onRetry={() => sessionsQuery.refetch()}
            />
          )}

          {sessions && !sessionsQuery.isLoading && !sessionsQuery.isError && (
            <>
              {sessions.content.length === 0 ? (
                <PageEmpty
                  icon={MessageSquare}
                  title="No research sessions yet"
                  description="Start a conversation in the research workspace and it will be listed here with its message count and source documents."
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
                <ul className="grid gap-3 md:grid-cols-2">
                  {sessions.content.map((conversation) => (
                    <SessionCard key={conversation.id} conversation={conversation} />
                  ))}
                </ul>
              )}

              <Pagination
                page={sessions.page}
                totalPages={sessions.totalPages}
                onChange={setSessionPage}
                label="Research session pages"
              />
            </>
          )}
        </>
      )}
    </div>
  );
}

function ProcessingEventRow({ event }: { event: HistoryItem }) {
  const stage = STAGE_STYLES[event.stage] ?? STAGE_STYLES.QUEUED;
  const Icon = stage.icon;

  return (
    <li className="flex items-start gap-3 rounded-2xl border border-border/80 bg-card/70 px-4 py-3">
      <span className={cn('mt-0.5 inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-xl border', stage.className)}>
        <Icon className={cn('h-4 w-4', (event.stage === 'EXTRACTING' || event.stage === 'CHUNKING' || event.stage === 'INDEXING') && 'animate-spin')} aria-hidden="true" />
      </span>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className={cn('inline-flex rounded-full border px-2 py-0.5 text-[10px] font-semibold', stage.className)}>
            {stage.label}
          </span>
          <Link
            to={`/dashboard/documents/${event.documentId}`}
            className="inline-flex min-w-0 items-center gap-1.5 text-sm font-semibold hover:text-indigo-300"
          >
            <FileText className="h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
            <span className="truncate">{event.documentName}</span>
          </Link>
          <span className="rounded-md bg-muted px-1.5 py-0.5 text-[10px] text-muted-foreground">
            attempt {event.attempt}
          </span>
          <span className="ml-auto text-[11px] text-muted-foreground">
            {formatDistanceToNow(new Date(event.createdAt), { addSuffix: true })}
          </span>
        </div>
        {event.errorMessage && (
          <p className="mt-2 rounded-lg border border-rose-400/20 bg-rose-500/5 px-2.5 py-1.5 text-[11px] text-rose-200">
            {event.errorCode ? `${event.errorCode}: ` : ''}
            {event.errorMessage}
          </p>
        )}
      </div>
    </li>
  );
}

function SessionCard({ conversation }: { conversation: Conversation }) {
  return (
    <li className="rounded-2xl border border-border/80 bg-card/70 p-4 transition hover:border-indigo-400/25">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <Link
            to={`/dashboard/workspace/${conversation.id}`}
            className="line-clamp-2 text-sm font-bold hover:text-indigo-300"
          >
            {conversation.title}
          </Link>
          <p className="mt-1 text-[11px] text-muted-foreground">
            {conversation.messageCount} message{conversation.messageCount === 1 ? '' : 's'} · updated{' '}
            {formatDistanceToNow(new Date(conversation.updatedAt), { addSuffix: true })}
          </p>
        </div>
        <Link
          to={`/dashboard/workspace/${conversation.id}`}
          className="inline-flex shrink-0 items-center gap-1 rounded-lg border border-border bg-background/70 px-2.5 py-1.5 text-[11px] font-semibold text-muted-foreground transition hover:bg-accent hover:text-foreground"
          aria-label={`Open session ${conversation.title}`}
        >
          Open
          <ArrowUpRight className="h-3 w-3" aria-hidden="true" />
        </Link>
      </div>
      {conversation.documents.length > 0 && (
        <div className="mt-3 flex flex-wrap gap-1.5">
          {conversation.documents.slice(0, 4).map((document) => (
            <span
              key={document.id}
              className="inline-flex max-w-[180px] items-center gap-1 rounded-md bg-muted/60 px-2 py-0.5 text-[10px] text-muted-foreground"
            >
              <FileText className="h-2.5 w-2.5 shrink-0" aria-hidden="true" />
              <span className="truncate">{document.originalFilename}</span>
            </span>
          ))}
          {conversation.documents.length > 4 && (
            <span className="text-[10px] text-muted-foreground">+{conversation.documents.length - 4} more</span>
          )}
        </div>
      )}
    </li>
  );
}

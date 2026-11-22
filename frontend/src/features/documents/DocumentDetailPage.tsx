'use client';

import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  api,
  type Document,
  type DocumentSummariesResponse,
  type ProcessingHistoryResponse,
  type ProcessingStage,
  type SummarySection,
  type Tag,
  type Collection,
  type Conversation,
} from '@/lib/api';
import {
  ArrowLeft, Loader2, RotateCcw, Trash2, FileText, Clock, CheckCircle, AlertCircle, MessageSquare,
  Sparkles, RefreshCw, Eye, X, Plus, Tag as TagIcon, Folder, ArrowUpRight, Download,
} from 'lucide-react';
import { cn, formatFileSize, isDeferredStatus, pluralize } from '@/lib/utils';
import { ProcessingStepper } from '@/components/ProcessingStepper';
import { askAboutDocument } from '@/lib/conversation';
import { summaryToMarkdown } from '@/lib/summaryMarkdown';
import { DocumentEntitiesTab } from './DocumentEntitiesTab';
import { formatDistanceToNow } from 'date-fns';
import { ToastProvider, ToastViewport } from '@/components/ui/toast';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { PdfPreviewModal } from '@/components/PdfPreviewModal';
import { useToast } from '@/hooks/useToast';

type Tab = 'summary' | 'entities' | 'processing' | 'details';

const SUMMARY_STATUS_LABELS: Record<string, string> = {
  NONE: 'not generated',
  PENDING: 'generating',
  READY: 'ready',
  FAILED: 'failed',
};

export function DocumentDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { toast } = useToast();
  const [tab, setTab] = useState<Tab>('summary');
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [previewOpen, setPreviewOpen] = useState(false);
  const [previewPage, setPreviewPage] = useState(1);

  const { data: doc, isLoading, error } = useQuery({
    queryKey: ['document', id],
    queryFn: () => api.get<Document>(`/documents/${id}`),
    enabled: !!id,
    refetchInterval: (query) => {
      const d = query.state.data;
      return (d?.status === 'PROCESSING' || d?.status === 'UPLOADED') ? 2000 : false;
    },
  });

  const { data: history } = useQuery({
    queryKey: ['document-processing', id],
    queryFn: () => api.get<ProcessingHistoryResponse>(`/documents/${id}/processing`),
    enabled: !!id,
    refetchInterval: (query) => {
      const stage = query.state.data?.currentStage;
      const pendingStages: ProcessingStage[] = ['UPLOADED', 'QUEUED', 'EXTRACTING', 'CHUNKING', 'INDEXING'];
      return stage && pendingStages.includes(stage) ? 2500 : false;
    },
  });

  const { data: summaries, isLoading: summariesLoading } = useQuery({
    queryKey: ['document-summaries', id],
    queryFn: () => api.get<DocumentSummariesResponse>(`/documents/${id}/summaries`),
    enabled: !!id && doc?.status === 'READY',
    // A deferred summary (PENDING with a reason) will not progress until the
    // next server start, so stop polling once that signal is present.
    refetchInterval: (query) =>
      query.state.data?.summaryStatus === 'PENDING' && !query.state.data?.summaryError ? 2000 : false,
  });

  const retryMutation = useMutation({
    mutationFn: () => api.post<void>(`/documents/${id}/retry`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['document', id] });
      queryClient.invalidateQueries({ queryKey: ['document-processing', id] });
      queryClient.invalidateQueries({ queryKey: ['document-summaries', id] });
      queryClient.invalidateQueries({ queryKey: ['documents'] });
      toast({ title: 'Retry started', description: 'Document is being reprocessed' });
      setTab('processing');
    },
    onError: (err: Error) => toast({ title: 'Retry failed', description: err.message, variant: 'destructive' }),
  });

  // Suggested questions start a research session seeded with that question and
  // this document, instead of leaving the user to retype it in the workspace.
  const startResearch = useMutation({
    mutationFn: (question: string) =>
      api.post<Conversation>('/conversations', {
        title: question.length > 80 ? `${question.slice(0, 79)}…` : question,
        documentIds: id ? [id] : [],
      }),
    onSuccess: (conversation, question) => {
      queryClient.invalidateQueries({ queryKey: ['conversations'] });
      navigate(`/dashboard/workspace/${conversation.id}?q=${encodeURIComponent(question)}`);
    },
    onError: (err: Error) =>
      toast({ title: 'Could not start research', description: err.message, variant: 'destructive' }),
  });

  // One click opens a conversation already scoped to this document, so a READY
  // document is immediately askable instead of being a dead end.
  const askAboutMutation = useMutation({
    mutationFn: (doc: Document) => askAboutDocument(doc.id, doc.originalFilename),
    onSuccess: (conversation) => {
      queryClient.invalidateQueries({ queryKey: ['conversations'] });
      navigate(`/dashboard/workspace/${conversation.id}`);
    },
    onError: (err: Error) =>
      toast({ title: 'Could not start a conversation', description: err.message, variant: 'destructive' }),
  });

  const deleteMutation = useMutation({
    mutationFn: () => api.delete<void>(`/documents/${id}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['documents'] });
      toast({ title: 'Document deleted' });
      navigate('/dashboard/documents');
    },
    onError: () => toast({ title: 'Delete failed', variant: 'destructive' }),
  });

  const regenerateMutation = useMutation({
    mutationFn: () => api.post<DocumentSummariesResponse>(`/documents/${id}/summaries/regenerate`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['document-summaries', id] });
      queryClient.invalidateQueries({ queryKey: ['document', id] });
      toast({ title: 'Regenerating summary', description: 'This usually takes under a minute' });
    },
    onError: (err: Error) => toast({ title: 'Could not regenerate', description: err.message, variant: 'destructive' }),
  });

  if (isLoading) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <Loader2 className="h-8 w-8 animate-spin text-primary" aria-label="Loading document" />
      </div>
    );
  }

  if (error || !doc) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <div className="text-center">
          <AlertCircle className="h-12 w-12 mx-auto text-destructive/50 mb-4" />
          <h2 className="text-xl font-semibold mb-2">Document not found</h2>
          <p className="text-muted-foreground text-sm mb-4">
            It may have been deleted, or the link is no longer valid.
          </p>
          <button onClick={() => navigate('/dashboard/documents')} className="text-primary hover:underline">
            Back to Documents
          </button>
        </div>
      </div>
    );
  }

  // The export is built in the browser from the stored sections, so it is
  // instant and costs no model call.
  const exportSummary = () => {
    if (!summaries?.summaries.length) return;
    const markdown = summaryToMarkdown(doc, summaries.summaries);
    const url = URL.createObjectURL(new Blob([markdown], { type: 'text/markdown;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = `${doc.originalFilename.replace(/\.pdf$/i, '')}-summary.md`;
    link.click();
    URL.revokeObjectURL(url);
  };

  const tabs: Array<{ key: Tab; label: string }> = [
    { key: 'summary', label: 'Summary' },
    { key: 'entities', label: 'Entities' },
    { key: 'processing', label: 'Processing' },
    { key: 'details', label: 'Details' },
  ];

  return (
    <div className="max-w-4xl mx-auto space-y-6">
      {/* Header */}
      <div className="flex items-start gap-4">
        <button
          onClick={() => navigate('/dashboard/documents')}
          aria-label="Back to documents"
          className="p-2 rounded-lg hover:bg-accent mt-0.5"
        >
          <ArrowLeft className="h-5 w-5" />
        </button>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-3">
            <h1 className="text-2xl font-bold truncate">{doc.originalFilename}</h1>
            <ProcessingStepper status={doc.status} stage={doc.processingStage} />
          </div>
          <p className="text-muted-foreground text-sm">
            {doc.pageCount ? `${pluralize(doc.pageCount, 'page')} • ` : ''}
            {formatFileSize(doc.sizeBytes)} •
            uploaded {formatDistanceToNow(new Date(doc.createdAt), { addSuffix: true })}
            {doc.status === 'READY' && (
              <> • Summary: {SUMMARY_STATUS_LABELS[doc.summaryStatus ?? 'NONE'] ?? 'not generated'}</>
            )}
          </p>
        </div>
        <div className="flex flex-wrap items-center justify-end gap-2">
          {doc.status === 'READY' && (
            <button
              onClick={() => askAboutMutation.mutate(doc)}
              disabled={askAboutMutation.isPending}
              className="inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-50"
            >
              {askAboutMutation.isPending
                ? <Loader2 className="h-4 w-4 animate-spin" />
                : <MessageSquare className="h-4 w-4" />}
              Ask about this document
            </button>
          )}
          {doc.status === 'READY' && !!summaries?.summaries.length && (
            <button
              onClick={exportSummary}
              className="inline-flex items-center gap-2 rounded-lg border border-border px-3 py-2 text-sm font-medium hover:bg-accent transition-colors"
            >
              <Download className="h-4 w-4" /> Export summary
            </button>
          )}
          <button
            onClick={() => setPreviewOpen(true)}
            className="inline-flex items-center gap-2 rounded-lg border border-border px-3 py-2 text-sm font-medium hover:bg-accent transition-colors"
          >
            <Eye className="h-4 w-4" /> Preview
          </button>
          <button
            onClick={() => setConfirmDelete(true)}
            aria-label="Delete document"
            className="inline-flex items-center gap-2 rounded-lg border border-border px-3 py-2 text-sm font-medium text-destructive hover:bg-destructive/10 transition-colors"
          >
            <Trash2 className="h-4 w-4" />
          </button>
        </div>
      </div>

      {/* Tabs */}
      <div className="border-b border-border" role="tablist" aria-label="Document sections">
        <div className="flex gap-1">
          {tabs.map((t) => (
            <button
              key={t.key}
              role="tab"
              aria-selected={tab === t.key}
              onClick={() => setTab(t.key)}
              className={cn(
                'px-4 py-2.5 text-sm font-medium rounded-t-lg transition-colors relative',
                tab === t.key
                  ? 'text-foreground'
                  : 'text-muted-foreground hover:text-foreground'
              )}
            >
              {t.label}
              {tab === t.key && (
                <span className="absolute left-2 right-2 -bottom-px h-0.5 bg-primary rounded-full" />
              )}
            </button>
          ))}
        </div>
      </div>

      {/* Tab content */}
      {tab === 'summary' && (
        <SummaryTab
          doc={doc}
          summaries={summaries}
          isLoading={summariesLoading}
          isRegenerating={regenerateMutation.isPending}
          onRegenerate={() => regenerateMutation.mutate()}
          onAskQuestion={(question) => startResearch.mutate(question)}
          asking={startResearch.isPending}
        />
      )}

      {tab === 'entities' && (
        <DocumentEntitiesTab
          documentId={doc.id}
          onOpenPage={(page) => {
            setPreviewPage(page);
            setPreviewOpen(true);
          }}
        />
      )}

      {tab === 'processing' && (
        <ProcessingTab
          doc={doc}
          history={history}
          isRetrying={retryMutation.isPending}
          onRetry={() => retryMutation.mutate()}
        />
      )}

      {tab === 'details' && <DetailsTab doc={doc} />}

      {/* Delete confirmation */}
      <ConfirmDialog
        open={confirmDelete}
        title="Delete document?"
        description={`Deleting "${doc.originalFilename}" removes the file, its extracted text, processing history and AI summaries. This cannot be undone.`}
        confirmLabel="Delete"
        loading={deleteMutation.isPending}
        onConfirm={() => deleteMutation.mutate()}
        onCancel={() => setConfirmDelete(false)}
      />

      {/* PDF preview */}
      {previewOpen && (
        <PdfPreviewModal doc={doc} initialPage={previewPage} onClose={() => setPreviewOpen(false)} />
      )}

      <ToastProvider>
        <ToastViewport />
      </ToastProvider>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Summary tab
// ---------------------------------------------------------------------------

function SummaryTab({
  doc, summaries, isLoading, isRegenerating, onRegenerate, onAskQuestion, asking,
}: {
  doc: Document;
  summaries?: DocumentSummariesResponse;
  isLoading: boolean;
  isRegenerating: boolean;
  onRegenerate: () => void;
  onAskQuestion: (question: string) => void;
  asking: boolean;
}) {
  if (doc.status !== 'READY') {
    return (
      <div className="rounded-xl border border-dashed p-10 text-center">
        <FileText className="h-12 w-12 mx-auto text-muted-foreground/50 mb-3" />
        <h3 className="font-semibold mb-1">Summary not available yet</h3>
        <p className="text-sm text-muted-foreground">
          AI summaries are generated once the document has finished processing.
        </p>
      </div>
    );
  }

  const status = summaries?.summaryStatus ?? 'NONE';
  // PENDING with a reason is a deferred job: idle until the next start, so it
  // must be retryable now. PENDING without one is a run genuinely in flight.
  const deferred = isDeferredStatus(status, summaries?.summaryError);
  const generating = status === 'PENDING' && !deferred;
  const retryBlocked = isRegenerating || generating;

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <Sparkles className="h-4 w-4 text-primary" />
          {status === 'PENDING' && (deferred ? 'Summary deferred' : 'Generating summary…')}
          {status === 'READY' && summaries?.summaries.length
            ? `Generated ${formatDistanceToNow(new Date(summaries.summaries[0].generatedAt), { addSuffix: true })}`
            : null}
          {status === 'NONE' && 'No summary generated yet'}
          {status === 'FAILED' && <span className="text-destructive">Summary generation failed</span>}
        </div>
        <button
          onClick={onRegenerate}
          disabled={retryBlocked}
          className="inline-flex items-center gap-2 rounded-lg border border-border px-3 py-2 text-sm font-medium hover:bg-accent transition-colors disabled:opacity-50"
        >
          {retryBlocked
            ? <Loader2 className="h-4 w-4 animate-spin" />
            : <RefreshCw className="h-4 w-4" />}
          {deferred ? 'Retry summary' : status === 'NONE' ? 'Generate summary' : 'Regenerate'}
        </button>
      </div>

      {status === 'FAILED' && (
        <div className="rounded-xl border border-destructive/30 bg-destructive/5 p-4">
          <div className="flex items-center gap-2 text-destructive font-medium text-sm mb-1">
            <AlertCircle className="h-4 w-4" /> Summary could not be generated
          </div>
          <p className="text-sm text-muted-foreground">{summaries?.summaryError ?? 'Unknown error'}</p>
        </div>
      )}

      {deferred && (
        <div className="rounded-xl border border-amber-400/30 bg-amber-500/10 p-4">
          <div className="flex items-center gap-2 text-sm font-medium text-amber-700 dark:text-amber-200">
            <Clock className="h-4 w-4" /> Summary deferred — it will retry automatically
          </div>
          <p className="mt-1 text-sm text-muted-foreground">{summaries.summaryError}</p>
        </div>
      )}

      {generating && (
        <div className="rounded-xl border p-6 space-y-3" aria-live="polite">
          <div className="flex items-center gap-2 text-sm">
            <Loader2 className="h-4 w-4 animate-spin text-primary" />
            Reading the document and writing the summary…
          </div>
          <div className="h-3 bg-muted rounded w-full animate-pulse" />
          <div className="h-3 bg-muted rounded w-5/6 animate-pulse" />
          <div className="h-3 bg-muted rounded w-2/3 animate-pulse" />
        </div>
      )}

      {isLoading && status !== 'PENDING' && (
        <div className="rounded-xl border p-6 space-y-3">
          <div className="h-4 bg-muted rounded w-1/3 animate-pulse" />
          <div className="h-3 bg-muted rounded w-full animate-pulse" />
          <div className="h-3 bg-muted rounded w-4/5 animate-pulse" />
        </div>
      )}

      {status === 'NONE' && !isLoading && (
        <div className="rounded-xl border border-dashed p-8 text-center">
          <Sparkles className="h-10 w-10 mx-auto text-muted-foreground/50 mb-3" />
          <p className="text-sm text-muted-foreground">
            Generate an AI summary to get key findings, entities and suggested questions
            grounded in this document.
          </p>
        </div>
      )}

      {status === 'READY' && summaries?.summaries.map((section) => (
        <SummarySectionCard
          key={section.id}
          section={section}
          onAskQuestion={onAskQuestion}
          asking={asking}
        />
      ))}
    </div>
  );
}

function SummarySectionCard({
  section,
  onAskQuestion,
  asking = false,
}: {
  section: SummarySection;
  onAskQuestion?: (question: string) => void;
  asking?: boolean;
}) {
  type Content = {
    summary?: string;
    findings?: Array<{ text: string; type?: string }>;
    entities?: Array<{ name: string; type?: string }>;
    items?: Array<{ text: string }>;
    topics?: string[];
    questions?: string[];
  };
  const content = section.content as Content;

  const meta: Record<string, { title: string; icon: typeof Sparkles }> = {
    EXECUTIVE_SUMMARY: { title: 'Executive summary', icon: Sparkles },
    KEY_FINDINGS: { title: 'Key findings', icon: CheckCircle },
    ENTITIES: { title: 'Entities', icon: TagIcon },
    DATES_AND_STATS: { title: 'Dates & statistics', icon: Clock },
    TOPICS: { title: 'Topics', icon: Folder },
    SUGGESTED_QUESTIONS: { title: 'Questions this document can answer', icon: FileText },
  };
  const m = meta[section.kind] ?? { title: section.kind, icon: FileText };
  const Icon = m.icon;

  return (
    <div className="rounded-xl border p-5">
      <div className="flex items-center justify-between gap-3 mb-3">
        <h3 className="font-semibold flex items-center gap-2">
          <Icon className="h-4 w-4 text-primary" /> {m.title}
        </h3>
        <span className={cn(
          'rounded-full px-2 py-0.5 text-[11px] font-medium',
          section.source === 'EXTRACTED'
            ? 'bg-green-500/15 text-green-600 dark:text-green-400'
            : 'bg-primary/15 text-primary dark:text-primary'
        )} title={section.source === 'EXTRACTED'
          ? 'Taken verbatim from the document text'
          : 'Written by the AI based on the document text'}>
          {section.source === 'EXTRACTED' ? 'Extracted from document' : 'AI-written'}
        </span>
      </div>

      {section.kind === 'EXECUTIVE_SUMMARY' && (
        <p className="text-sm leading-relaxed text-foreground/90 whitespace-pre-wrap">
          {String(content.summary ?? '')}
        </p>
      )}

      {section.kind === 'KEY_FINDINGS' && (
        <div className="space-y-3">
          <p className="text-xs text-muted-foreground">
            Facts are stated directly in the document; interpretations are inferences from it.
          </p>
          <ul className="space-y-2.5">
            {(content.findings ?? []).map((f: { text: string; type?: string }, i: number) => (
              <li key={i} className="flex items-start gap-2.5 text-sm">
                <span className={cn(
                  'mt-0.5 shrink-0 rounded px-1.5 py-0.5 text-[10px] font-semibold tracking-wide',
                  f.type === 'INTERPRETATION'
                    ? 'bg-amber-500/15 text-amber-600 dark:text-amber-400'
                    : 'bg-blue-500/15 text-blue-600 dark:text-blue-400'
                )}>
                  {f.type === 'INTERPRETATION' ? 'INTERPRETATION' : 'FACT'}
                </span>
                <span className="text-foreground/90">{f.text}</span>
              </li>
            ))}
            {(content.findings ?? []).length === 0 && (
              <li className="text-sm text-muted-foreground">No findings extracted.</li>
            )}
          </ul>
        </div>
      )}

      {section.kind === 'ENTITIES' && (
        <div className="flex flex-wrap gap-2">
          {(content.entities ?? []).map((e: { name: string; type?: string }, i: number) => (
            <span key={i} className="inline-flex items-center gap-1.5 rounded-lg border border-border px-2.5 py-1 text-sm">
              <span className="font-medium">{e.name}</span>
              {e.type && <span className="text-[11px] text-muted-foreground">{e.type}</span>}
            </span>
          ))}
          {(content.entities ?? []).length === 0 && (
            <p className="text-sm text-muted-foreground">No entities found in the text.</p>
          )}
        </div>
      )}

      {section.kind === 'DATES_AND_STATS' && (
        <ul className="space-y-2">
          {(content.items ?? []).map((it: { text: string }, i: number) => (
            <li key={i} className="text-sm text-foreground/90 flex gap-2">
              <span className="text-primary mt-1.5 h-1.5 w-1.5 rounded-full bg-primary shrink-0" />
              {it.text}
            </li>
          ))}
          {(content.items ?? []).length === 0 && (
            <li className="text-sm text-muted-foreground">No dates or statistics found in the text.</li>
          )}
        </ul>
      )}

      {section.kind === 'TOPICS' && (
        <div className="flex flex-wrap gap-2">
          {(content.topics ?? []).map((t: string, i: number) => (
            <span key={i} className="rounded-full bg-accent px-3 py-1 text-sm">{t}</span>
          ))}
          {(content.topics ?? []).length === 0 && (
            <p className="text-sm text-muted-foreground">No clear topics identified.</p>
          )}
        </div>
      )}

      {section.kind === 'SUGGESTED_QUESTIONS' && (
        <ul className="space-y-2">
          {(content.questions ?? []).map((q: string, i: number) => (
            <li key={i}>
              <button
                type="button"
                onClick={() => onAskQuestion?.(q)}
                disabled={!onAskQuestion || asking}
                title="Start a research session with this question"
                className="group flex w-full items-start gap-2 rounded-lg px-2 py-1.5 text-left text-sm text-foreground/90 transition hover:bg-accent/60 disabled:opacity-60"
              >
                <span className="shrink-0 font-medium text-primary">Q{i + 1}.</span>
                <span className="min-w-0 flex-1">{q}</span>
                <ArrowUpRight
                  className="mt-0.5 h-3.5 w-3.5 shrink-0 text-muted-foreground opacity-0 transition group-hover:opacity-100"
                  aria-hidden="true"
                />
              </button>
            </li>
          ))}
          {(content.questions ?? []).length === 0 && (
            <li className="text-sm text-muted-foreground">No suggested questions.</li>
          )}
        </ul>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Processing tab
// ---------------------------------------------------------------------------

const STAGE_ORDER: ProcessingStage[] = [
  'UPLOADED', 'QUEUED', 'EXTRACTING', 'CHUNKING', 'INDEXING', 'COMPLETED',
];

const STAGE_LABELS: Record<string, string> = {
  UPLOADED: 'Uploaded',
  QUEUED: 'Queued',
  EXTRACTING: 'Extracting text',
  CHUNKING: 'Chunking',
  INDEXING: 'Indexing',
  COMPLETED: 'Completed',
  FAILED: 'Failed',
};

function ProcessingTab({
  doc, history, isRetrying, onRetry,
}: {
  doc: Document;
  history?: ProcessingHistoryResponse;
  isRetrying: boolean;
  onRetry: () => void;
}) {
  const pending = doc.status === 'UPLOADED' || doc.status === 'PROCESSING';

  // Build the checklist from the current attempt's recorded stages
  const currentAttempt = history?.currentAttempt ?? 1;
  const attemptEvents = (history?.events ?? []).filter((e) => e.attempt === currentAttempt);
  const reached = new Set(attemptEvents.map((e) => e.stage));
  const failure = attemptEvents.find((e) => e.stage === 'FAILED');

  return (
    <div className="space-y-6">
      {/* Stage checklist for the current attempt */}
      <div className="rounded-xl border p-5">
        <div className="flex items-center justify-between mb-4">
          <h3 className="font-semibold">
            Attempt {currentAttempt}
            {pending && <span className="ml-2 text-sm font-normal text-muted-foreground">in progress…</span>}
          </h3>
          <span className="text-xs text-muted-foreground">
            {history ? 'Persistent history — survives restarts' : 'Loading history…'}
          </span>
        </div>

        {!history ? (
          <div className="space-y-3">
            {[...Array(5)].map((_, i) => <div key={i} className="h-8 bg-muted rounded animate-pulse" />)}
          </div>
        ) : (
          <ol className="space-y-1">
            {STAGE_ORDER.map((stage, idx) => {
              const done = reached.has(stage);
              const isCurrent = !done && idx === STAGE_ORDER.findIndex(
                (s) => !reached.has(s) && !reached.has('FAILED')
              );
              const failedHere = failure !== undefined && stage === 'COMPLETED';
              return (
                <li key={stage} className="flex items-center gap-3 py-1.5" aria-current={isCurrent ? 'step' : undefined}>
                  <span className={cn(
                    'flex h-6 w-6 items-center justify-center rounded-full border-2 shrink-0',
                    done && !failedHere && 'bg-green-500/15 border-green-500 text-green-500',
                    isCurrent && 'border-primary text-primary',
                    failedHere && 'bg-destructive/15 border-destructive text-destructive',
                    !done && !isCurrent && !failedHere && 'border-muted text-muted-foreground/50'
                  )}>
                    {done && !failedHere && <CheckCircle className="h-3.5 w-3.5" />}
                    {failedHere && <AlertCircle className="h-3.5 w-3.5" />}
                    {isCurrent && !done && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
                    {!done && !isCurrent && !failedHere && <span className="h-1.5 w-1.5 rounded-full bg-current" />}
                  </span>
                  <span className={cn(
                    'text-sm',
                    (done || isCurrent) ? 'text-foreground' : 'text-muted-foreground/70'
                  )}>
                    {failedHere ? 'Failed' : STAGE_LABELS[stage]}
                  </span>
                </li>
              );
            })}
          </ol>
        )}

        {failure && (
          <div className="mt-4 rounded-lg bg-destructive/10 border border-destructive/20 p-3 space-y-1">
            <p className="text-sm font-medium text-destructive">
              {STAGE_LABELS.FAILED}
              {failure.errorCode && <span className="ml-2 font-mono text-xs">({failure.errorCode})</span>}
            </p>
            {failure.errorMessage && (
              <p className="text-sm text-foreground/90">{failure.errorMessage}</p>
            )}
          </div>
        )}
      </div>

      {/* Event log (all attempts, newest first) */}
      <div className="rounded-xl border p-5">
        <h3 className="font-semibold mb-4">Event history</h3>
        {!history || history.events.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            No processing events recorded yet — they appear as soon as processing starts.
          </p>
        ) : (
          <div className="space-y-0">
            {history.events.map((event) => (
              <div key={event.id} className="flex items-start gap-3 py-2.5 border-b border-border/50 last:border-0">
                <span className={cn(
                  'mt-1 h-2 w-2 rounded-full shrink-0',
                  event.stage === 'FAILED' ? 'bg-destructive'
                    : event.stage === 'COMPLETED' ? 'bg-green-500'
                    : 'bg-primary'
                )} />
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2 text-sm">
                    <span className="font-medium">{STAGE_LABELS[event.stage] ?? event.stage}</span>
                    <span className="rounded-full bg-muted px-2 py-0.5 text-[11px] text-muted-foreground">
                      attempt {event.attempt}
                    </span>
                    {event.errorCode && (
                      <span className="font-mono text-[11px] text-destructive">{event.errorCode}</span>
                    )}
                  </div>
                  {event.errorMessage && (
                    <p className="text-xs text-muted-foreground mt-0.5 break-words">{event.errorMessage}</p>
                  )}
                </div>
                <span className="text-xs text-muted-foreground whitespace-nowrap">
                  {new Date(event.createdAt).toLocaleString()}
                </span>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Retry */}
      <div className="rounded-xl border p-5 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h3 className="font-semibold">Reprocess</h3>
          <p className="text-sm text-muted-foreground">
            Re-runs extraction, chunking and indexing from the original file.
            AI summaries regenerate afterwards.
          </p>
        </div>
        <button
          onClick={onRetry}
          disabled={isRetrying || pending}
          className={cn(
            'inline-flex items-center gap-2 rounded-lg px-4 py-2 text-sm font-medium transition-colors',
            pending
              ? 'bg-muted text-muted-foreground cursor-not-allowed'
              : 'bg-primary text-primary-foreground hover:bg-primary/90',
            isRetrying && 'opacity-70'
          )}
        >
          {isRetrying && <Loader2 className="h-4 w-4 animate-spin" />}
          <RotateCcw className="h-4 w-4" />
          {doc.status === 'FAILED' ? 'Retry processing' : 'Reprocess document'}
        </button>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Details tab
// ---------------------------------------------------------------------------

function DetailsTab({ doc }: { doc: Document }) {
  const queryClient = useQueryClient();
  const { toast } = useToast();
  const [tagPickerOpen, setTagPickerOpen] = useState(false);
  const [collectionPickerOpen, setCollectionPickerOpen] = useState(false);

  const { data: tags } = useQuery({
    queryKey: ['tags'],
    queryFn: () => api.get<Tag[]>('/tags'),
  });

  const { data: collections } = useQuery({
    queryKey: ['collections'],
    queryFn: () => api.get<Collection[]>('/collections'),
  });

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['document', doc.id] });
    queryClient.invalidateQueries({ queryKey: ['documents'] });
  };

  const attachTagMutation = useMutation({
    mutationFn: (tagId: string) => api.post<Document>(`/documents/${doc.id}/tags/${tagId}`),
    onSuccess: () => { invalidate(); queryClient.invalidateQueries({ queryKey: ['tags'] }); setTagPickerOpen(false); },
    onError: (err: Error) => toast({ title: 'Could not add tag', description: err.message, variant: 'destructive' }),
  });

  const detachTagMutation = useMutation({
    mutationFn: (tagId: string) => api.delete<Document>(`/documents/${doc.id}/tags/${tagId}`),
    onSuccess: () => { invalidate(); queryClient.invalidateQueries({ queryKey: ['tags'] }); },
    onError: () => toast({ title: 'Could not remove tag', variant: 'destructive' }),
  });

  const addTagMutation = useMutation({
    mutationFn: (name: string) => api.post<Tag>('/tags', { name }),
    onSuccess: (tag) => attachTagMutation.mutate(tag.id),
    onError: (err: Error) => toast({ title: 'Could not create tag', description: err.message, variant: 'destructive' }),
  });

  const joinCollectionMutation = useMutation({
    mutationFn: (collectionId: string) =>
      api.post<Collection>(`/collections/${collectionId}/documents`, { documentIds: [doc.id] }),
    onSuccess: (_r, collectionId) => {
      invalidate();
      queryClient.invalidateQueries({ queryKey: ['collections'] });
      const name = collections?.find((c) => c.id === collectionId)?.name;
      toast({ title: `Added to ${name ?? 'collection'}` });
      setCollectionPickerOpen(false);
    },
    onError: (err: Error) => toast({ title: 'Could not add to collection', description: err.message, variant: 'destructive' }),
  });

  const leaveCollectionMutation = useMutation({
    mutationFn: (collectionId: string) =>
      api.delete<void>(`/collections/${collectionId}/documents/${doc.id}`),
    onSuccess: () => { invalidate(); queryClient.invalidateQueries({ queryKey: ['collections'] }); },
    onError: () => toast({ title: 'Could not remove from collection', variant: 'destructive' }),
  });

  const attachedIds = new Set(doc.tags?.map((t) => t.id));
  const memberIds = new Set(doc.collections?.map((c) => c.id));
  const availableTags = tags?.filter((t) => !attachedIds.has(t.id));
  const availableCollections = collections?.filter((c) => !memberIds.has(c.id));

  return (
    <div className="grid gap-6 lg:grid-cols-3">
      <div className="lg:col-span-2 space-y-6">
        {/* Metadata */}
        <div className="rounded-xl border p-6">
          <h3 className="font-semibold mb-4">Metadata</h3>
          <dl className="space-y-3 text-sm">
            <Row label="Filename" value={doc.originalFilename} mono />
            <Row label="Size" value={formatFileSize(doc.sizeBytes)} />
            <Row label="Pages" value={doc.pageCount != null ? String(doc.pageCount) : 'Unknown'} />
            <Row label="Chunks" value={doc.chunkCount != null ? String(doc.chunkCount) : '—'} />
            <Row label="MIME type" value={doc.mimeType} mono />
            <Row label="SHA-256" value={doc.sha256} mono truncate />
            <Row label="Uploaded" value={new Date(doc.createdAt).toLocaleString()} />
            <Row label="Last updated" value={new Date(doc.updatedAt).toLocaleString()} />
          </dl>
        </div>

        {/* Error detail (kept visible on Details too) */}
        {doc.errorMessage && (
          <div className="rounded-xl border border-destructive/30 bg-destructive/5 p-4 space-y-2">
            <div className="flex items-center gap-2 text-destructive">
              <AlertCircle className="h-5 w-5" />
              <span className="font-medium text-sm">
                {doc.errorCode === 'SCANNED_PDF_NO_TEXT' ? 'Scanned document detected' : 'Processing error'}
              </span>
            </div>
            <p className="text-sm text-foreground/90">{doc.errorMessage}</p>
            {doc.errorCode && <p className="text-xs text-muted-foreground">Error code: {doc.errorCode}</p>}
          </div>
        )}
      </div>

      {/* Tags & collections sidebar */}
      <div className="space-y-6">
        <div className="rounded-xl border p-5">
          <div className="flex items-center justify-between mb-3">
            <h3 className="font-semibold flex items-center gap-2"><TagIcon className="h-4 w-4" /> Tags</h3>
            <button
              onClick={() => setTagPickerOpen((v) => !v)}
              aria-label="Add tag"
              className="p-1.5 rounded-lg hover:bg-accent text-muted-foreground hover:text-foreground transition-colors"
            >
              <Plus className="h-4 w-4" />
            </button>
          </div>
          <div className="flex flex-wrap gap-2">
            {doc.tags?.map((t) => (
              <span key={t.id} className="inline-flex items-center gap-1.5 rounded-full bg-accent px-2.5 py-1 text-sm">
                <span className="h-2 w-2 rounded-full" style={{ backgroundColor: t.color || '#7c3aed' }} />
                {t.name}
                <button
                  onClick={() => detachTagMutation.mutate(t.id)}
                  aria-label={`Remove tag ${t.name}`}
                  className="text-muted-foreground hover:text-destructive transition-colors"
                >
                  <X className="h-3 w-3" />
                </button>
              </span>
            ))}
            {!doc.tags?.length && (
              <p className="text-sm text-muted-foreground">No tags yet.</p>
            )}
          </div>
          {tagPickerOpen && (
            <div className="mt-3 border-t pt-3 space-y-2">
              <input
                autoFocus
                placeholder="Create a tag and press Enter…"
                onKeyDown={(e) => {
                  if (e.key === 'Enter') {
                    const name = e.currentTarget.value.trim();
                    if (name) addTagMutation.mutate(name);
                  }
                }}
                disabled={addTagMutation.isPending}
                className="w-full h-9 rounded-lg border border-input bg-background px-3 text-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              />
              {availableTags && availableTags.length > 0 && (
                <div className="max-h-32 overflow-y-auto space-y-1">
                  {availableTags.map((t) => (
                    <button
                      key={t.id}
                      onClick={() => attachTagMutation.mutate(t.id)}
                      disabled={attachTagMutation.isPending}
                      className="w-full text-left rounded-lg px-2.5 py-1.5 text-sm hover:bg-accent transition-colors"
                    >
                      {t.name} <span className="text-muted-foreground">({t.documentCount})</span>
                    </button>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>

        <div className="rounded-xl border p-5">
          <div className="flex items-center justify-between mb-3">
            <h3 className="font-semibold flex items-center gap-2"><Folder className="h-4 w-4" /> Collections</h3>
            <button
              onClick={() => setCollectionPickerOpen((v) => !v)}
              aria-label="Add to collection"
              className="p-1.5 rounded-lg hover:bg-accent text-muted-foreground hover:text-foreground transition-colors"
            >
              <Plus className="h-4 w-4" />
            </button>
          </div>
          <div className="flex flex-wrap gap-2">
            {doc.collections?.map((c) => (
              <span key={c.id} className="inline-flex items-center gap-1.5 rounded-full bg-accent px-2.5 py-1 text-sm">
                {c.name}
                <button
                  onClick={() => leaveCollectionMutation.mutate(c.id)}
                  aria-label={`Remove from ${c.name}`}
                  className="text-muted-foreground hover:text-destructive transition-colors"
                >
                  <X className="h-3 w-3" />
                </button>
              </span>
            ))}
            {!doc.collections?.length && (
              <p className="text-sm text-muted-foreground">Not in any collection.</p>
            )}
          </div>
          {collectionPickerOpen && (
            <div className="mt-3 border-t pt-3">
              {availableCollections && availableCollections.length > 0 ? (
                <div className="max-h-32 overflow-y-auto space-y-1">
                  {availableCollections.map((c) => (
                    <button
                      key={c.id}
                      onClick={() => joinCollectionMutation.mutate(c.id)}
                      disabled={joinCollectionMutation.isPending}
                      className="w-full text-left rounded-lg px-2.5 py-1.5 text-sm hover:bg-accent transition-colors"
                    >
                      {c.name} <span className="text-muted-foreground">({c.documentCount})</span>
                    </button>
                  ))}
                </div>
              ) : (
                <p className="text-sm text-muted-foreground">
                  No other collections — create one from the Documents page.
                </p>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

function Row({ label, value, mono, truncate }: { label: string; value: string; mono?: boolean; truncate?: boolean }) {
  return (
    <div className="flex items-center justify-between gap-4">
      <dt className="text-muted-foreground shrink-0">{label}</dt>
      <dd className={cn(
        'text-right',
        mono && 'font-mono text-xs',
        truncate ? 'truncate max-w-[220px]' : 'truncate'
      )} title={value}>{value}</dd>
    </div>
  );
}

// ---------------------------------------------------------------------------
// PDF preview modal lives in @/components/PdfPreviewModal (shared with the
// research workspace for citation jumps).
// ---------------------------------------------------------------------------


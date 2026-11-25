'use client';

import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api, type Conversation, type ConversationListResponse, type Message } from '@/lib/api';
import { createConversation, deriveConversationTitle } from '@/lib/conversation';
import { errorToast } from '@/lib/errors';
import {
  Search, Loader2, MessageSquare, FileText, Sparkles, ChevronLeft, ChevronRight,
  Trash2, Send, Check, ChevronDown,
} from 'lucide-react';
import { pluralize } from '@/lib/utils';
import { formatDistanceToNow } from 'date-fns';
import { ToastProvider, ToastViewport } from '@/components/ui/toast';
import { useToast } from '@/hooks/useToast';
import { UploadDropzone } from '@/components/UploadDropzone';
import { pendingStatusLabel, useReadyDocuments } from '@/hooks/useReadyDocuments';

interface AskResult {
  conversation: Conversation;
  askFailed: boolean;
  error?: string;
}

export function WorkspacePage() {
  const queryClient = useQueryClient();
  const { toast } = useToast();
  const navigate = useNavigate();
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [question, setQuestion] = useState('');
  const [scopeIds, setScopeIds] = useState<string[]>([]);
  const [scopeOpen, setScopeOpen] = useState(false);

  const { data: conversations, isLoading } = useQuery({
    queryKey: ['conversations', page, search],
    queryFn: () => {
      const params = new URLSearchParams();
      params.set('page', page.toString());
      params.set('size', '20');
      if (search) params.set('q', search);
      return api.get<ConversationListResponse>(`/conversations?${params.toString()}`);
    },
    placeholderData: (prev) => prev,
  });

  const { documents, processing } = useReadyDocuments();

  /**
   * Asking creates the conversation implicitly, so the user never names one
   * first. If the answer request itself fails (rate limit, provider error) the
   * conversation is still opened with the question pre-filled rather than
   * discarding what they typed.
   */
  const askMutation = useMutation<AskResult, Error, { question: string; documentIds: string[] }>({
    mutationFn: async ({ question: text, documentIds }) => {
      const conversation = await createConversation(deriveConversationTitle(text), documentIds);
      try {
        await api.post<Message>(`/conversations/${conversation.id}/messages`, {
          question: text,
          documentIds: [],
        });
        return { conversation, askFailed: false };
      } catch (error) {
        return {
          conversation,
          askFailed: true,
          error: error instanceof Error ? error.message : 'The question could not be sent.',
        };
      }
    },
    onSuccess: (result, variables) => {
      queryClient.invalidateQueries({ queryKey: ['conversations'] });
      const path = `/dashboard/workspace/${result.conversation.id}`;
      if (result.askFailed) {
        toast({
          title: 'Question not sent',
          description: result.error ?? 'Your question is waiting in the conversation.',
          variant: 'destructive',
        });
        navigate(`${path}?q=${encodeURIComponent(variables.question)}`);
      } else {
        navigate(path);
      }
    },
    onError: (error) => toast(errorToast(error, 'Could not start the conversation')),
  });

  const deleteConversationMutation = useMutation({
    mutationFn: (id: string) => api.delete<void>(`/conversations/${id}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['conversations'] }),
    onError: () => toast({ title: 'Failed to delete', variant: 'destructive' }),
  });

  const readyCount = documents?.length ?? 0;
  const selectedDocuments = documents?.filter((d) => scopeIds.includes(d.id)) ?? [];
  const canAsk = question.trim().length > 0 && readyCount > 0 && !askMutation.isPending;

  const submit = () => {
    const text = question.trim();
    if (!text || readyCount === 0 || askMutation.isPending) return;
    askMutation.mutate({ question: text, documentIds: scopeIds });
  };

  const toggleScope = (id: string) => {
    setScopeIds((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]));
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Ask</h1>
        <p className="text-muted-foreground">
          Ask a question about your documents, then check how well the answer is backed.
        </p>
      </div>

      {/* Composer: asking creates the conversation, no naming step */}
      <section className="surface-card space-y-3 p-5">
        <label htmlFor="ask-question" className="sr-only">
          Your question
        </label>
        <textarea
          id="ask-question"
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              submit();
            }
          }}
          rows={3}
          placeholder="What do these documents say about…"
          className="w-full resize-none rounded-xl border border-input bg-background px-4 py-3 text-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        />
        <div className="flex flex-wrap items-center gap-2">
          <div className="relative">
            <button
              type="button"
              onClick={() => setScopeOpen((open) => !open)}
              aria-expanded={scopeOpen}
              aria-haspopup="true"
              className="inline-flex items-center gap-2 rounded-full border border-border bg-card px-3 py-1.5 text-xs font-medium hover:bg-accent"
            >
              <FileText className="h-3.5 w-3.5 text-muted-foreground" aria-hidden="true" />
              {selectedDocuments.length === 0
                ? `All ready documents (${readyCount})`
                : `${pluralize(selectedDocuments.length, 'document')} selected`}
              <ChevronDown className="h-3.5 w-3.5 text-muted-foreground" aria-hidden="true" />
            </button>
            {scopeOpen && (
              <div
                role="dialog"
                aria-label="Choose document scope"
                className="absolute left-0 top-full z-30 mt-2 w-72 rounded-xl border border-border bg-background p-3 shadow-lg"
              >
                <p className="mb-2 text-xs text-muted-foreground">
                  Answers draw evidence only from these documents.
                </p>
                <div className="max-h-56 space-y-1 overflow-y-auto">
                  {readyCount === 0 && processing.length === 0 && (
                    <p className="py-1 text-sm text-muted-foreground">
                      No ready documents yet — upload a PDF first.
                    </p>
                  )}
                  {processing.map((doc) => (
                    <div
                      key={doc.id}
                      className="flex items-center gap-2 py-1 text-sm text-muted-foreground"
                    >
                      <input
                        type="checkbox"
                        disabled
                        aria-label={`${doc.originalFilename} (${pendingStatusLabel(doc.status)})`}
                        className="rounded border-input"
                      />
                      <span className="truncate">{doc.originalFilename}</span>
                      <span className="ml-auto shrink-0 rounded-full bg-muted px-2 py-0.5 text-[11px]">
                        {pendingStatusLabel(doc.status)}
                      </span>
                    </div>
                  ))}
                  {documents?.map((doc) => (
                    <label key={doc.id} className="flex cursor-pointer items-center gap-2 py-1 text-sm">
                      <input
                        type="checkbox"
                        checked={scopeIds.includes(doc.id)}
                        onChange={() => toggleScope(doc.id)}
                        className="rounded border-input"
                      />
                      <span className="truncate">{doc.originalFilename}</span>
                    </label>
                  ))}
                </div>
                <div className="mt-2 flex items-center justify-between border-t border-border/60 pt-2">
                  <button
                    type="button"
                    onClick={() => setScopeIds([])}
                    className="text-xs text-muted-foreground hover:text-foreground"
                  >
                    Use all documents
                  </button>
                  <button
                    type="button"
                    onClick={() => setScopeOpen(false)}
                    className="inline-flex items-center gap-1 text-xs font-medium text-primary"
                  >
                    <Check className="h-3 w-3" /> Done
                  </button>
                </div>
              </div>
            )}
          </div>
          <span className="text-xs text-muted-foreground">Press Enter to ask</span>
          <button
            type="button"
            onClick={submit}
            disabled={!canAsk}
            className="ml-auto inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {askMutation.isPending ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Send className="h-4 w-4" />
            )}
            Ask
          </button>
        </div>
        {readyCount === 0 &&
          (processing.length > 0 ? (
            <p className="text-sm text-muted-foreground">
              <Loader2 className="mr-1 inline h-4 w-4 animate-spin text-primary" aria-hidden="true" />
              {processing.length === 1
                ? `${processing[0].originalFilename} is ${pendingStatusLabel(processing[0].status).toLowerCase()} — asking unlocks when it is ready.`
                : `${pluralize(processing.length, 'document')} are processing — asking unlocks as they become ready.`}
            </p>
          ) : (
            <p className="text-sm text-muted-foreground">
              <Sparkles className="mr-1 inline h-4 w-4 text-primary" aria-hidden="true" />
              Upload a PDF to ask your first question.{' '}
              <Link to="/dashboard/documents" className="text-primary hover:underline">
                Go to the library
              </Link>
            </p>
          ))}
      </section>

      {/* Reuse the one upload surface so a PDF can be added without leaving Ask */}
      <details className="rounded-xl border border-border bg-card">
        <summary className="cursor-pointer px-4 py-3 text-sm font-medium">
          Add a document
        </summary>
        <div className="border-t border-border/70 p-3">
          <UploadDropzone compact bare />
        </div>
      </details>

      {/* Conversations */}
      <section className="space-y-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-lg font-bold">Conversations</h2>
          <div className="relative w-full max-w-xs">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <input
              type="search"
              placeholder="Search conversations…"
              value={search}
              onChange={(e) => {
                setSearch(e.target.value);
                setPage(0);
              }}
              className="h-10 w-full rounded-lg border border-input bg-background pl-10 pr-3 text-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            />
          </div>
        </div>

        {isLoading ? (
          <div className="space-y-3">
            {[...Array(3)].map((_, i) => (
              <ConversationSkeleton key={i} />
            ))}
          </div>
        ) : conversations?.content.length === 0 ? (
          <div className="rounded-xl border border-dashed p-10 text-center">
            <MessageSquare className="mx-auto mb-3 h-12 w-12 text-muted-foreground/50" />
            <h3 className="font-semibold mb-1">No conversations yet</h3>
            <p className="text-sm text-muted-foreground">
              Ask your first question above — it becomes a conversation automatically.
            </p>
          </div>
        ) : (
          <>
            <div className="space-y-3">
              {conversations!.content.map((conv) => (
                <ConversationItem
                  key={conv.id}
                  conv={conv}
                  onDelete={() => deleteConversationMutation.mutate(conv.id)}
                />
              ))}
            </div>

            <div className="flex items-center justify-center gap-2">
              <button
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                disabled={page === 0}
                className="flex h-10 w-10 items-center justify-center rounded-lg border border-input bg-background hover:bg-accent disabled:opacity-50"
                aria-label="Previous page"
              >
                <ChevronLeft className="h-5 w-5" />
              </button>
              <span className="text-sm text-muted-foreground">
                Page {page + 1} of {conversations!.totalPages || 1}
              </span>
              <button
                onClick={() => setPage((p) => Math.min((conversations!.totalPages || 1) - 1, p + 1))}
                disabled={page >= (conversations!.totalPages || 1) - 1}
                className="flex h-10 w-10 items-center justify-center rounded-lg border border-input bg-background hover:bg-accent disabled:opacity-50"
                aria-label="Next page"
              >
                <ChevronRight className="h-5 w-5" />
              </button>
            </div>
          </>
        )}
      </section>

      <ToastProvider>
        <ToastViewport />
      </ToastProvider>
    </div>
  );
}

function ConversationItem({ conv, onDelete }: { conv: Conversation; onDelete: () => void }) {
  return (
    <div className="group flex items-center gap-4 rounded-xl border p-4 transition-colors hover:border-primary/50 hover:bg-accent/50 focus-within:border-primary/50">
      <Link
        to={`/dashboard/workspace/${conv.id}`}
        className="flex min-w-0 flex-1 items-center gap-4 rounded-lg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
      >
        <MessageSquare className="flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary" />
        <div className="min-w-0 flex-1">
          <h3 className="truncate font-medium">{conv.title}</h3>
          <p className="text-xs text-muted-foreground">
            {pluralize(conv.messageCount, 'message')} • {formatDistanceToNow(new Date(conv.updatedAt), { addSuffix: true })}
          </p>
          {conv.documents.length > 0 && (
            <div className="mt-2 flex flex-wrap gap-1">
              {conv.documents.slice(0, 3).map((d) => (
                <span key={d.id} className="inline-flex items-center gap-1 rounded bg-muted px-2 py-0.5 text-xs text-muted-foreground">
                  <FileText className="h-3 w-3" />
                  {d.originalFilename}
                </span>
              ))}
              {conv.documents.length > 3 && (
                <span className="text-xs text-muted-foreground">+{conv.documents.length - 3} more</span>
              )}
            </div>
          )}
        </div>
      </Link>
      <div className="flex items-center gap-1 opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100">
        <button
          type="button"
          className="rounded-lg p-2 hover:bg-accent"
          title="Delete"
          aria-label={`Delete ${conv.title}`}
          onClick={onDelete}
        >
          <Trash2 className="h-4 w-4 text-muted-foreground" />
        </button>
      </div>
    </div>
  );
}

function ConversationSkeleton() {
  return (
    <div className="flex animate-pulse items-center gap-4 rounded-xl border p-4">
      <div className="h-10 w-10 flex-shrink-0 rounded-lg bg-muted" />
      <div className="flex-1 space-y-2">
        <div className="h-4 w-1/3 rounded bg-muted" />
        <div className="h-3 w-1/2 rounded bg-muted" />
      </div>
    </div>
  );
}


'use client';

import { useState, useEffect, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api, type Message, type MessageListResponse, type Conversation, type Document, type Citation, type ClaimVerificationResponse } from '@/lib/api';
import { errorToast } from '@/lib/errors';
import { ArrowLeft, Send, Loader2, Copy, Check, AlertCircle, FileText, MoreVertical, Sparkles, Plus, X, ShieldCheck } from 'lucide-react';
import { cn } from '@/lib/utils';
import { formatDistanceToNow } from 'date-fns';
import { Markdown } from '@/components/ui/Markdown';
import { PdfPreviewModal, type PdfPreviewTarget } from '@/components/PdfPreviewModal';
import { ToastProvider, ToastViewport } from '@/components/ui/toast';
import { useToast } from '@/hooks/useToast';

export function ConversationPage() {
  const { conversationId } = useParams<{ conversationId: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { toast } = useToast();
  const [question, setQuestion] = useState('');
  const [showSources, setShowSources] = useState<string | null>(null);
  const [showContextPicker, setShowContextPicker] = useState(false);
  const [pickerSelected, setPickerSelected] = useState<string[]>([]);
  const [preview, setPreview] = useState<{ doc: PdfPreviewTarget; page: number } | null>(null);
  const [verifications, setVerifications] = useState<Record<string, ClaimVerificationResponse>>({});
  const [verifyingId, setVerifyingId] = useState<string | null>(null);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const { data: conversation, isLoading: convLoading } = useQuery({
    queryKey: ['conversation', conversationId],
    queryFn: () => api.get<Conversation>(`/conversations/${conversationId}`),
    enabled: !!conversationId,
  });

  const { data: messagesData, isLoading: msgsLoading, isFetching } = useQuery({
    queryKey: ['messages', conversationId],
    queryFn: () => api.get<MessageListResponse>(`/conversations/${conversationId}/messages?size=100`),
    enabled: !!conversationId,
    placeholderData: (prev) => prev,
  });

  const askMutation = useMutation({
    mutationFn: (q: string) => api.post<Message>(`/conversations/${conversationId}/messages`, { question: q, documentIds: [] }),
    onMutate: async (newQuestion) => {
      await queryClient.cancelQueries({ queryKey: ['messages', conversationId] });
      const previousMessages = queryClient.getQueryData<MessageListResponse>(['messages', conversationId]);
      const optimisticMessage: Message = {
        id: `temp-${Date.now()}`,
        role: 'USER',
        content: newQuestion,
        status: 'COMPLETED',
        model: null,
        promptTokens: null,
        completionTokens: null,
        latencyMs: null,
        evidenceStatus: null,
        createdAt: new Date().toISOString(),
        citations: [],
      };
      queryClient.setQueryData<MessageListResponse>(['messages', conversationId], (old) => ({
        ...old!,
        content: [...old!.content, optimisticMessage],
      }));
      return { previousMessages };
    },
    onError: (err, _newQuestion, context) => {
      if (context?.previousMessages) {
        queryClient.setQueryData(['messages', conversationId], context.previousMessages);
      }
      toast(errorToast(err, 'Failed to send'));
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['messages', conversationId] });
      queryClient.invalidateQueries({ queryKey: ['conversation', conversationId] });
    },
    onSettled: () => {
      setQuestion('');
      textareaRef.current?.focus();
    },
  });

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  // Document context (Feature 5): add/remove documents at any time via the
  // existing PATCH /conversations/{id} endpoint (ownership-checked server-side).
  const { data: allDocuments, isLoading: docsLoading, error: docsError, refetch: refetchDocs } = useQuery({
    queryKey: ['documents', 'all'],
    queryFn: () => api.get<{ content: Document[] }>('/documents?size=100&status=READY'),
    select: (data) => data.content,
    enabled: showContextPicker,
  });

  const updateContextMutation = useMutation({
    mutationFn: (documentIds: string[]) =>
      api.patch<Conversation>(`/conversations/${conversationId}`, { documentIds }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['conversation', conversationId] });
      queryClient.invalidateQueries({ queryKey: ['conversations'] });
      toast({ title: 'Context updated' });
    },
    onError: (err) => toast(errorToast(err, 'Failed to update context')),
  });

  useEffect(() => {
    if (!showContextPicker) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setShowContextPicker(false);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [showContextPicker]);

  useEffect(() => {
    scrollToBottom();
  }, [messagesData?.content.length, isFetching]);

  if (convLoading) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <Loader2 className="h-8 w-8 animate-spin text-primary" />
      </div>
    );
  }

  if (!conversation) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <div className="text-center">
          <AlertCircle className="h-12 w-12 mx-auto text-destructive/50 mb-4" />
          <h2 className="text-xl font-semibold mb-2">Conversation not found</h2>
          <button onClick={() => navigate('/dashboard/workspace')} className="text-primary hover:underline">
            Back to Workspace
          </button>
        </div>
      </div>
    );
  }

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (question.trim() && !askMutation.isPending) {
      askMutation.mutate(question.trim());
    }
  };

  const openPicker = () => {
    setPickerSelected(conversation.documents.map((d) => d.id));
    setShowContextPicker(true);
  };

  const removeDocument = (id: string) => {
    updateContextMutation.mutate(
      conversation.documents.filter((d) => d.id !== id).map((d) => d.id)
    );
  };

  const openCitation = (cite: Citation) => {
    setPreview({
      doc: { id: cite.documentId, originalFilename: cite.documentName, pageCount: null },
      page: cite.pageNumber,
    });
  };

  /**
   * Loads verification for a message; runs it (POST) only when none is stored
   * yet, so a page refresh never re-pays for an already-verified answer.
   */
  const runVerification = async (messageId: string, force = false) => {
    setVerifyingId(messageId);
    try {
      let result: ClaimVerificationResponse;
      if (force) {
        result = await api.post<ClaimVerificationResponse>(
          `/conversations/${conversationId}/messages/${messageId}/verify`
        );
      } else {
        result = await api.get<ClaimVerificationResponse>(
          `/conversations/${conversationId}/messages/${messageId}/verify`
        );
        if (result.status === 'NOT_REQUESTED') {
          result = await api.post<ClaimVerificationResponse>(
            `/conversations/${conversationId}/messages/${messageId}/verify`
          );
        }
      }
      setVerifications((v) => ({ ...v, [messageId]: result }));
      if (result.status === 'FAILED') {
        toast({
          title: 'Verification failed',
          description: result.error ?? 'The model could not classify this answer.',
          variant: 'destructive',
        });
      }
    } catch (err) {
      toast(errorToast(err, 'Verification failed'));
    } finally {
      setVerifyingId(null);
    }
  };

  return (
    <div className="flex-1 flex flex-col max-w-4xl mx-auto w-full">
      {/* Header */}
      <div className="mb-6 flex items-center gap-4 border-b border-border/70 pb-4">
        <button onClick={() => navigate('/dashboard/workspace')} className="p-2 rounded-lg hover:bg-accent">
          <ArrowLeft className="h-5 w-5" />
        </button>
        <div className="flex-1 min-w-0">
          <h1 className="font-semibold truncate">{conversation.title}</h1>
          <p className="text-sm text-muted-foreground">
            {conversation.messageCount} messages • {conversation.documents.length} documents
          </p>
        </div>
        <button className="p-2 rounded-lg hover:bg-accent" title="More options">
          <MoreVertical className="h-5 w-5" />
        </button>
      </div>

      {/* Document context: which documents answers draw evidence from */}
      <div className="mb-4 flex flex-wrap items-center gap-2" role="group" aria-label="Document context">
        <span className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Context</span>
        {conversation.documents.length === 0 && (
          <span className="text-xs text-muted-foreground">
            No documents selected — answers may use any of your documents
          </span>
        )}
        {conversation.documents.map((doc) => (
          <span
            key={doc.id}
            className="inline-flex items-center gap-1.5 rounded-full border border-border/70 bg-card px-2.5 py-1 text-xs"
          >
            <FileText className="h-3 w-3 text-muted-foreground" aria-hidden />
            <span className="max-w-[180px] truncate">{doc.originalFilename}</span>
            <button
              type="button"
              onClick={() => removeDocument(doc.id)}
              disabled={updateContextMutation.isPending}
              aria-label={`Remove ${doc.originalFilename} from context`}
              className="rounded-full p-0.5 text-muted-foreground hover:text-foreground hover:bg-accent disabled:opacity-50"
            >
              <X className="h-3 w-3" />
            </button>
          </span>
        ))}
        <button
          type="button"
          onClick={openPicker}
          disabled={updateContextMutation.isPending}
          className="inline-flex items-center gap-1 rounded-full border border-dashed border-border px-2.5 py-1 text-xs text-muted-foreground transition-colors hover:border-primary/50 hover:text-foreground disabled:opacity-50"
        >
          <Plus className="h-3 w-3" aria-hidden />
          {conversation.documents.length === 0 ? 'Select documents' : 'Add'}
        </button>
      </div>

      {/* Messages */}
      <div className="flex-1 overflow-y-auto space-y-6 pb-4" role="log" aria-live="polite">
        {msgsLoading ? (
          <div className="flex items-center justify-center h-64">
            <Loader2 className="h-8 w-8 animate-spin text-primary" />
          </div>
        ) : messagesData?.content.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-64 text-center text-muted-foreground">
            <Sparkles className="h-16 w-16 mb-4 opacity-30" />
            <h3 className="text-lg font-medium mb-2">No messages yet</h3>
            <p>Ask a question about your documents to start researching</p>
          </div>
        ) : (
          <>
            {messagesData!.content.map((msg) => (
              <MessageBubble
                key={msg.id}
                message={msg}
                onCopy={() => {
                  navigator.clipboard.writeText(msg.content);
                  toast({ title: 'Copied to clipboard' });
                }}
                onToggleSources={() => setShowSources(showSources === msg.id ? null : msg.id)}
                showSources={showSources === msg.id}
                onOpenCitation={openCitation}
                verification={verifications[msg.id]}
                verifying={verifyingId === msg.id}
                onVerify={() => runVerification(msg.id)}
                onReverify={() => runVerification(msg.id, true)}
              />
            ))}
            <div ref={messagesEndRef} />
          </>
        )}

        {/* Typing indicator */}
        {askMutation.isPending && (
          <div className="flex items-center gap-2 text-muted-foreground animate-pulse">
            <Loader2 className="h-5 w-5" />
            <span>Generating answer...</span>
          </div>
        )}
      </div>

      {/* Input */}
      <form onSubmit={handleSubmit} className="sticky bottom-0 border-t border-border/70 bg-background/90 pt-4 backdrop-blur-xl">
        <div className="relative">
          <textarea
            ref={textareaRef}
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            placeholder="Ask a question about your documents..."
            rows={1}
            className="premium-input min-h-[56px] w-full resize-none rounded-2xl px-4 py-3 pr-14 text-sm placeholder:text-muted-foreground disabled:opacity-50"
            disabled={askMutation.isPending}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                handleSubmit(e);
              }
            }}
          />
          {question && (
            <button
              type="submit"
              disabled={askMutation.isPending || !question.trim()}
              className="absolute bottom-2 right-2 p-2 rounded-lg bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
              aria-label="Send"
            >
              <Send className="h-5 w-5" />
            </button>
          )}
        </div>
        <p className="text-xs text-muted-foreground mt-2 text-center">
          Press Enter to send • Shift+Enter for new line
        </p>
      </form>

      {/* Document context picker */}
      {showContextPicker && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
          onClick={() => setShowContextPicker(false)}
        >
          <div
            role="dialog"
            aria-modal="true"
            aria-labelledby="context-picker-title"
            className="w-full max-w-md rounded-xl border bg-background p-6"
            onClick={(e) => e.stopPropagation()}
          >
            <h2 id="context-picker-title" className="mb-1 text-xl font-semibold">
              Document context
            </h2>
            <p className="mb-4 text-sm text-muted-foreground">
              Choose which documents this conversation draws evidence from.
            </p>
            <div className="max-h-64 overflow-y-auto rounded-lg border p-3">
              {docsLoading ? (
                <div className="flex items-center justify-center py-6" role="status" aria-label="Loading documents">
                  <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
                </div>
              ) : docsError ? (
                <div className="py-4 text-center text-sm">
                  <p className="mb-2 text-destructive">Failed to load documents</p>
                  <button onClick={() => refetchDocs()} className="text-primary hover:underline">
                    Retry
                  </button>
                </div>
              ) : allDocuments?.length === 0 ? (
                <p className="py-2 text-sm text-muted-foreground">
                  No processed documents available. Upload a PDF first.
                </p>
              ) : (
                allDocuments!.map((doc) => (
                  <label key={doc.id} className="flex cursor-pointer items-center gap-2 py-1 text-sm">
                    <input
                      type="checkbox"
                      checked={pickerSelected.includes(doc.id)}
                      onChange={(e) =>
                        setPickerSelected(
                          e.target.checked
                            ? [...pickerSelected, doc.id]
                            : pickerSelected.filter((id) => id !== doc.id)
                        )
                      }
                      className="rounded border-input"
                    />
                    <FileText className="h-4 w-4 text-muted-foreground" aria-hidden />
                    <span className="truncate">{doc.originalFilename}</span>
                    {doc.pageCount != null && (
                      <span className="text-xs text-muted-foreground">({doc.pageCount} pages)</span>
                    )}
                  </label>
                ))
              )}
            </div>
            <div className="mt-4 flex justify-end gap-2">
              <button
                onClick={() => setShowContextPicker(false)}
                className="rounded-lg border border-input bg-background px-4 py-2 text-sm font-medium hover:bg-accent"
              >
                Cancel
              </button>
              <button
                onClick={() => {
                  updateContextMutation.mutate(pickerSelected);
                  setShowContextPicker(false);
                }}
                disabled={updateContextMutation.isPending}
                className="inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
              >
                {updateContextMutation.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
                Save
              </button>
            </div>
          </div>
        </div>
      )}

      {/* PDF viewer for citation jumps */}
      {preview && (
        <PdfPreviewModal
          doc={preview.doc}
          initialPage={preview.page}
          onClose={() => setPreview(null)}
        />
      )}

      <ToastProvider>
        <ToastViewport />
      </ToastProvider>
    </div>
  );
}

function MessageBubble({
  message,
  onCopy,
  onToggleSources,
  showSources,
  onOpenCitation,
  verification,
  verifying,
  onVerify,
  onReverify,
}: {
  message: Message;
  onCopy: () => void;
  onToggleSources: () => void;
  showSources: boolean;
  onOpenCitation: (cite: Citation) => void;
  verification?: ClaimVerificationResponse;
  verifying: boolean;
  onVerify: () => void;
  onReverify: () => void;
}) {
  const isUser = message.role === 'USER';

  return (
    <div className={cn('flex gap-3', isUser ? 'flex-row-reverse' : '')}>
      <div className={cn('flex-1 max-w-[85%]', isUser ? 'text-right' : '')}>
        <div className={cn(
          'inline-block rounded-2xl border px-4 py-3 shadow-sm',
          isUser ? 'border-indigo-300/15 bg-primary text-primary-foreground rounded-tr-sm' : 'border-border/70 bg-card rounded-tl-sm'
        )}>
          {isUser || message.citations.length === 0 ? (
            <Markdown>{message.content}</Markdown>
          ) : (
            <div className="text-sm">
              {renderWithCitations(message.content, message.citations, onOpenCitation)}
            </div>
          )}
          {message.evidenceStatus && message.evidenceStatus !== 'NONE' && (
            <div className={cn('mt-2 flex items-center gap-1.5 text-xs', message.evidenceStatus === 'SUFFICIENT' ? 'text-green-600' : 'text-yellow-600')}>
              <Check className="h-3.5 w-3.5" />
              <span>{message.evidenceStatus === 'SUFFICIENT' ? 'Sufficient evidence' : 'Insufficient evidence'}</span>
              {message.citations.length > 0 && (
                <button
                  onClick={onToggleSources}
                  className="ml-2 underline hover:no-underline"
                >
                  {showSources ? 'Hide' : 'Show'} {message.citations.length} source{message.citations.length > 1 ? 's' : ''}
                </button>
              )}
            </div>
          )}
          {showSources && message.citations.length > 0 && (
            <div className="mt-3 rounded-lg border bg-background p-3 text-sm">
              <div className="flex items-center justify-between mb-2">
                <span className="font-medium">Sources</span>
                <button onClick={onToggleSources} className="text-muted-foreground hover:text-foreground">×</button>
              </div>
              <p className="mb-2 text-xs text-muted-foreground">Click a source to open the PDF at that page.</p>
              <div className="space-y-2 max-h-64 overflow-y-auto">
                {message.citations.map((cite) => (
                  <div
                    key={cite.chunkId}
                    role="button"
                    tabIndex={0}
                    onClick={() => onOpenCitation(cite)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault();
                        onOpenCitation(cite);
                      }
                    }}
                    title={`Open ${cite.documentName} at page ${cite.pageNumber}`}
                    className="cursor-pointer rounded border p-2 bg-muted/50 transition-colors hover:border-primary/40 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                  >
                    <div className="flex items-center gap-2 text-xs text-muted-foreground mb-1">
                      <FileText className="h-3.5 w-3.5" />
                      <span>{cite.documentName}</span>
                      <span>•</span>
                      <span>Page {cite.pageNumber}</span>
                      <span>•</span>
                      <span>Relevance: {(cite.rank * 100).toFixed(0)}%</span>
                    </div>
                    <p className="text-sm line-clamp-2">{cite.excerpt}</p>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Claim verification (Feature 6) */}
          {!isUser && message.status === 'COMPLETED' && !verification && (
            <div className="mt-2">
              <button
                onClick={onVerify}
                disabled={verifying}
                className="inline-flex items-center gap-1.5 rounded-md border border-border px-2 py-1 text-xs text-muted-foreground transition-colors hover:border-primary/50 hover:text-foreground disabled:opacity-50"
              >
                {verifying ? <Loader2 className="h-3 w-3 animate-spin" /> : <ShieldCheck className="h-3 w-3" />}
                {verifying ? 'Verifying claims…' : 'Verify claims'}
              </button>
            </div>
          )}
          {!isUser && verifying && !verification && (
            <div className="mt-2 flex items-center gap-1.5 text-xs text-muted-foreground animate-pulse">
              <Loader2 className="h-3 w-3 animate-spin" />
              Checking claims against cited sources…
            </div>
          )}
          {verification && (
            <VerificationPanel
              verification={verification}
              citations={message.citations}
              onOpenCitation={onOpenCitation}
              onReverify={onReverify}
              verifying={verifying}
            />
          )}
        </div>
        <div className={cn('mt-1 flex items-center gap-1', isUser ? 'justify-end' : '')}>
          <span className="text-xs text-muted-foreground">{formatDistanceToNow(new Date(message.createdAt), { addSuffix: true })}</span>
          {!isUser && message.status === 'COMPLETED' && (
            <button onClick={onCopy} className="p-1.5 rounded hover:bg-accent" title="Copy">
              <Copy className="h-4 w-4" />
            </button>
          )}
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Citation chips: [S1] markers in the answer become clickable jumps to the PDF
// ---------------------------------------------------------------------------

function renderWithCitations(content: string, citations: Citation[], onOpen: (cite: Citation) => void) {
  const parts = content.split(/(\[S\d+\])/g);
  return parts.map((part, i) => {
    if (!part) return null;
    const match = /^\[S(\d+)\]$/.exec(part);
    if (!match) return <Markdown key={i}>{part}</Markdown>;
    const n = Number(match[1]);
    const cite = citations[n - 1];
    if (!cite) {
      return (
        <span
          key={i}
          title={`Source ${n} does not exist among this answer's sources`}
          className="mx-0.5 rounded bg-destructive/10 px-1 py-0.5 text-xs font-medium text-destructive line-through"
        >
          {part}
        </span>
      );
    }
    return (
      <button
        key={i}
        type="button"
        onClick={() => onOpen(cite)}
        title={`Open ${cite.documentName}, page ${cite.pageNumber}`}
        className="mx-0.5 rounded bg-primary/10 px-1.5 py-0.5 text-xs font-medium text-primary transition-colors hover:bg-primary/20 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
      >
        {part}
      </button>
    );
  });
}

// ---------------------------------------------------------------------------
// Verification panel
// ---------------------------------------------------------------------------

const SUPPORT_BADGES: Record<string, { label: string; className: string }> = {
  SUPPORTED: {
    label: 'Supported',
    className: 'bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-400',
  },
  PARTIALLY_SUPPORTED: {
    label: 'Partially supported',
    className: 'bg-amber-100 text-amber-800 dark:bg-amber-900/30 dark:text-amber-400',
  },
  UNSUPPORTED: {
    label: 'Unsupported',
    className: 'bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-400',
  },
  CONFLICTING: {
    label: 'Conflicting',
    className: 'bg-violet-100 text-violet-800 dark:bg-violet-900/30 dark:text-violet-400',
  },
};

const UNCLASSIFIED_BADGE = {
  label: 'Not classified',
  className: 'bg-muted text-muted-foreground',
};

function VerificationPanel({
  verification,
  citations,
  onOpenCitation,
  onReverify,
  verifying,
}: {
  verification: ClaimVerificationResponse;
  citations: Citation[];
  onOpenCitation: (cite: Citation) => void;
  onReverify: () => void;
  verifying: boolean;
}) {
  const citedClaims = verification.claims.filter((c) => c.citationIndexes.length > 0).length;
  const coveragePercent = verification.coverage != null ? Math.round(verification.coverage * 100) : null;

  return (
    <div className="mt-3 space-y-3 rounded-lg border bg-background p-3 text-sm">
      <div className="flex items-center justify-between">
        <span className="flex items-center gap-1.5 font-medium">
          <ShieldCheck className="h-4 w-4 text-primary" />
          Claim verification
        </span>
        <button
          onClick={onReverify}
          disabled={verifying}
          className="text-xs text-muted-foreground underline hover:text-foreground disabled:opacity-50"
        >
          {verifying ? 'Re-running…' : 'Re-run'}
        </button>
      </div>

      {/* Deterministic evidence coverage */}
      {coveragePercent != null && verification.claims.length > 0 && (
        <div>
          <div className="mb-1 flex justify-between text-xs text-muted-foreground">
            <span>Claims citing evidence</span>
            <span>{coveragePercent}%</span>
          </div>
          <div
            role="progressbar"
            aria-valuenow={coveragePercent}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-label="Share of claims that cite at least one source"
            className="h-2 overflow-hidden rounded-full bg-muted"
          >
            <div
              className={cn('h-full rounded-full transition-all', coveragePercent >= 70 ? 'bg-green-500' : coveragePercent >= 40 ? 'bg-amber-500' : 'bg-red-500')}
              style={{ width: `${coveragePercent}%` }}
            />
          </div>
          <p className="mt-1 text-xs text-muted-foreground">
            {citedClaims} of {verification.claims.length} analyzed claims cite at least one source
          </p>
        </div>
      )}

      {verification.status === 'PARTIAL' && (
        <p className="rounded border border-amber-300/40 bg-amber-50 p-2 text-xs text-amber-700 dark:bg-amber-900/20 dark:text-amber-400">
          Some claims could not be classified — they appear without a badge. Re-run to retry.
        </p>
      )}
      {verification.status === 'FAILED' && (
        <p className="rounded border border-destructive/30 bg-destructive/5 p-2 text-xs text-destructive">
          Verification failed: {verification.error ?? 'unknown error'}. Nothing was stored — try again.
        </p>
      )}

      {verification.invalidReferences.length > 0 && (
        <div className="rounded border border-destructive/30 bg-destructive/5 p-2">
          <p className="mb-1 text-xs font-medium text-destructive">
            Citation references that don't match this answer's sources:
          </p>
          <ul className="space-y-0.5 text-xs text-destructive">
            {verification.invalidReferences.map((inv, i) => (
              <li key={`${inv.claimIndex}-${inv.reference}-${i}`}>
                [S{inv.reference}] in “{inv.claimText.length > 90 ? inv.claimText.slice(0, 90) + '…' : inv.claimText}”
              </li>
            ))}
          </ul>
        </div>
      )}

      {verification.totalClaims > verification.claims.length && (
        <p className="text-xs text-muted-foreground">
          Analyzed the first {verification.claims.length} of {verification.totalClaims} claims.
        </p>
      )}

      {verification.claims.length > 0 && (
        <div className="max-h-72 space-y-2 overflow-y-auto">
          {verification.claims.map((claim) => {
            const badge = claim.supportClass ? SUPPORT_BADGES[claim.supportClass] ?? UNCLASSIFIED_BADGE : UNCLASSIFIED_BADGE;
            return (
              <div key={claim.claimIndex} className="rounded border bg-muted/40 p-2">
                <div className="flex items-start gap-2">
                  <span className={cn('shrink-0 rounded px-1.5 py-0.5 text-[11px] font-medium', badge.className)}>
                    {badge.label}
                  </span>
                  <p className="flex-1 text-sm">{claim.claimText}</p>
                </div>
                {claim.explanation && (
                  <p className="mt-1 pl-2 text-xs text-muted-foreground">{claim.explanation}</p>
                )}
                {claim.citationIndexes.length > 0 && (
                  <div className="mt-1.5 flex flex-wrap gap-1 pl-2">
                    {claim.citationIndexes.map((idx) => {
                      const cite = citations[idx];
                      return (
                        <button
                          key={idx}
                          type="button"
                          onClick={() => cite && onOpenCitation(cite)}
                          disabled={!cite}
                          title={cite ? `Open ${cite.documentName}, page ${cite.pageNumber}` : 'Source unavailable'}
                          className="rounded bg-primary/10 px-1.5 py-0.5 text-[11px] font-medium text-primary transition-colors hover:bg-primary/20 disabled:opacity-50"
                        >
                          S{idx + 1}
                        </button>
                      );
                    })}
                  </div>
                )}
                {claim.hasInvalidCitation && (
                  <p className="mt-1 pl-2 text-xs text-destructive">
                    Contains a citation that doesn't match the available sources.
                  </p>
                )}
              </div>
            );
          })}
        </div>
      )}

      <p className="text-[11px] leading-relaxed text-muted-foreground">
        Support badges are AI assessments of grounding in the cited sources — not calibrated
        probabilities. Coverage counts claims that cite at least one source.
      </p>
    </div>
  );
}
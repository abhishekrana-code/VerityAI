'use client';

import { useState, useEffect, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api, type Message, type MessageListResponse, type Conversation } from '@/lib/api';
import { ArrowLeft, Send, Loader2, Copy, Check, AlertCircle, FileText, ChevronDown, ChevronUp, MoreVertical, Sparkles } from 'lucide-react';
import { cn } from '@/lib/utils';
import { formatDistanceToNow } from 'date-fns';
import { Markdown } from '@/components/ui/Markdown';
import { ToastProvider, ToastViewport } from '@/components/ui/toast';
import { useToast } from '@/hooks/useToast';

export function ConversationPage() {
  const { conversationId } = useParams<{ conversationId: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { toast } = useToast();
  const [question, setQuestion] = useState('');
  const [showSources, setShowSources] = useState<string | null>(null);
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
    onError: (err, newQuestion, context) => {
      if (context?.previousMessages) {
        queryClient.setQueryData(['messages', conversationId], context.previousMessages);
      }
      toast({ title: 'Failed to send', description: err instanceof Error ? err.message : 'Unknown error', variant: 'destructive' });
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

  return (
    <div className="flex-1 flex flex-col max-w-4xl mx-auto w-full">
      {/* Header */}
      <div className="flex items-center gap-4 mb-6 pb-4 border-b">
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
      <form onSubmit={handleSubmit} className="border-t pt-4">
        <div className="relative">
          <textarea
            ref={textareaRef}
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            placeholder="Ask a question about your documents..."
            rows={1}
            className="w-full min-h-[56px] max-h-64 rounded-xl border border-input bg-background px-4 py-3 text-sm resize-none focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring placeholder:text-muted-foreground disabled:opacity-50"
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

      <ToastProvider>
        <ToastViewport />
      </ToastProvider>
    </div>
  );
}

function MessageBubble({ message, onCopy, onToggleSources, showSources }: { message: Message; onCopy: () => void; onToggleSources: () => void; showSources: boolean }) {
  const isUser = message.role === 'USER';

  return (
    <div className={cn('flex gap-3', isUser ? 'flex-row-reverse' : '')}>
      <div className={cn('flex-1 max-w-[85%]', isUser ? 'text-right' : '')}>
        <div className={cn(
          'inline-block rounded-2xl px-4 py-3',
          isUser ? 'bg-primary text-primary-foreground rounded-tr-sm' : 'bg-muted rounded-tl-sm'
        )}>
          <Markdown>{message.content}</Markdown>
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
              <div className="space-y-2 max-h-64 overflow-y-auto">
                {message.citations.map((cite) => (
                  <div key={cite.chunkId} className="rounded border p-2 bg-muted/50">
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
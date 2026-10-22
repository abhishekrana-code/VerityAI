'use client';

import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api, type Conversation, type ConversationListResponse, type Document } from '@/lib/api';
import { Plus, Search, Loader2, Send, MessageSquare, FileText, Sparkles, X, ChevronDown, MoreVertical, Trash2, Edit2 } from 'lucide-react';
import { cn } from '@/lib/utils';
import { formatDistanceToNow } from 'date-fns';
import { useDropzone } from 'react-dropzone';
import { ToastProvider, ToastViewport } from '@/components/ui/toast';
import { useToast } from '@/hooks/useToast';
import { useAuth } from '@/hooks/useAuth';

export function WorkspacePage() {
  const queryClient = useQueryClient();
  const { toast } = useToast();
  const { data: auth } = useAuth();
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [showNewConversation, setShowNewConversation] = useState(false);
  const [newConvTitle, setNewConvTitle] = useState('');
  const [selectedDocIds, setSelectedDocIds] = useState<string[]>([]);
  const [uploading, setUploading] = useState(false);

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

  const { data: documents } = useQuery({
    queryKey: ['documents', 'all'],
    queryFn: () => api.get<{ content: Document[] }>('/documents?size=100&status=READY'),
    select: (data) => data.content,
  });

  const createConversationMutation = useMutation({
    mutationFn: (data: { title: string; documentIds: string[] }) => api.post<Conversation>('/conversations', data),
    onSuccess: (newConv) => {
      queryClient.invalidateQueries({ queryKey: ['conversations'] });
      setShowNewConversation(false);
      setNewConvTitle('');
      setSelectedDocIds([]);
      toast({ title: 'Conversation created', description: 'Start researching now' });
    },
    onError: () => toast({ title: 'Failed to create conversation', variant: 'destructive' }),
  });

  const deleteConversationMutation = useMutation({
    mutationFn: (id: string) => api.delete<void>(`/conversations/${id}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['conversations'] }),
    onError: () => toast({ title: 'Failed to delete', variant: 'destructive' }),
  });

  const uploadMutation = useMutation({
    mutationFn: (file: File) => {
      const formData = new FormData();
      formData.append('file', file);
      return api.upload<{ id: string }>('/documents', formData);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['documents'] });
      toast({ title: 'Upload started' });
    },
    onError: () => toast({ title: 'Upload failed', variant: 'destructive' }),
    onSettled: () => setUploading(false),
  });

  const onDrop = (acceptedFiles: File[]) => {
    const file = acceptedFiles[0];
    if (file.type !== 'application/pdf') {
      toast({ title: 'Invalid file type', description: 'Only PDF files are supported', variant: 'destructive' });
      return;
    }
    if (file.size > 25 * 1024 * 1024) {
      toast({ title: 'File too large', description: 'Maximum file size is 25 MB', variant: 'destructive' });
      return;
    }
    setUploading(true);
    uploadMutation.mutate(file);
  };

  const { getRootProps, getInputProps, isDragActive } = useDropzone({
    onDrop,
    accept: { 'application/pdf': ['.pdf'] },
    maxSize: 25 * 1024 * 1024,
    disabled: uploading,
  });

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold">Workspace</h1>
          <p className="text-muted-foreground">Your research conversations</p>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={() => setShowNewConversation(true)}
            className="inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors"
          >
            <Plus className="h-4 w-4" />
            New Research
          </button>
          <div {...getRootProps()} className={cn(
            'relative flex items-center justify-center rounded-lg border-2 border-dashed p-3 transition-colors',
            isDragActive ? 'border-primary bg-primary/5' : 'border-border hover:border-primary/50'
          )}>
            <input {...getInputProps()} />
            <div className="flex flex-col items-center gap-1 text-center">
              <FileText className="h-6 w-6 text-muted-foreground" />
              <span className="text-xs text-muted-foreground">Drop PDF</span>
            </div>
          </div>
        </div>
      </div>

      {/* Search */}
      <div className="relative max-w-md">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
        <input
          type="search"
          placeholder="Search conversations..."
          value={search}
          onChange={(e) => { setSearch(e.target.value); setPage(0); }}
          className="w-full pl-10 h-10 rounded-lg border border-input bg-background px-3 text-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        />
      </div>

      {/* Conversation List */}
      {isLoading ? (
        <div className="space-y-3">
          {[...Array(5)].map((_, i) => (
            <ConversationSkeleton key={i} />
          ))}
        </div>
      ) : conversations?.content.length === 0 ? (
        <div className="rounded-xl border p-12 text-center">
          <MessageSquare className="h-16 w-16 mx-auto text-muted-foreground/50 mb-4" />
          <h3 className="font-semibold mb-2">No conversations yet</h3>
          <p className="text-muted-foreground mb-6">Start your first research session</p>
          <button
            onClick={() => setShowNewConversation(true)}
            className="inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
          >
            <Plus className="h-4 w-4" />
            New Research
          </button>
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

          {/* Pagination */}
          <div className="flex items-center justify-center gap-2">
            <button
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              disabled={page === 0}
              className="h-10 w-10 rounded-lg border border-input bg-background flex items-center justify-center hover:bg-accent disabled:opacity-50"
            >
              <ChevronLeft className="h-5 w-5" />
            </button>
            <span className="text-sm text-muted-foreground">
              Page {page + 1} of {conversations!.totalPages || 1}
            </span>
            <button
              onClick={() => setPage((p) => Math.min((conversations!.totalPages || 1) - 1, p + 1))}
              disabled={page >= (conversations!.totalPages || 1) - 1}
              className="h-10 w-10 rounded-lg border border-input bg-background flex items-center justify-center hover:bg-accent disabled:opacity-50"
            >
              <ChevronRight className="h-5 w-5" />
            </button>
          </div>
        </>
      )}

      {/* New Conversation Dialog */}
      {showNewConversation && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4" onClick={() => setShowNewConversation(false)}>
          <div className="bg-background rounded-xl border p-6 w-full max-w-md" onClick={(e) => e.stopPropagation()}>
            <h2 className="text-xl font-semibold mb-4">New Research Session</h2>
            <div className="space-y-4">
              <div>
                <label className="block text-sm font-medium mb-2">Title</label>
                <input
                  type="text"
                  value={newConvTitle}
                  onChange={(e) => setNewConvTitle(e.target.value)}
                  placeholder="What are you researching?"
                  className="w-full h-10 rounded-lg border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                  autoFocus
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-2">Documents (optional)</label>
                <div className="rounded-lg border p-3 max-h-48 overflow-y-auto">
                  {documents?.map((doc) => (
                    <label key={doc.id} className="flex items-center gap-2 text-sm cursor-pointer">
                      <input
                        type="checkbox"
                        checked={selectedDocIds.includes(doc.id)}
                        onChange={(e) => setSelectedDocIds(e.target.checked ? [...selectedDocIds, doc.id] : selectedDocIds.filter((id) => id !== doc.id))}
                        className="rounded border-input"
                      />
                      <FileText className="h-4 w-4 text-muted-foreground" />
                      <span className="truncate">{doc.originalFilename}</span>
                      {doc.pageCount && <span className="text-xs text-muted-foreground">({doc.pageCount} pages)</span>}
                    </label>
                  ))}
                  {documents?.length === 0 && (
                    <p className="text-sm text-muted-foreground">No processed documents available. Upload a PDF first.</p>
                  )}
                </div>
              </div>
              <div className="flex justify-end gap-2">
                <button
                  onClick={() => setShowNewConversation(false)}
                  className="rounded-lg border border-input bg-background px-4 py-2 text-sm font-medium hover:bg-accent"
                >
                  Cancel
                </button>
                <button
                  onClick={() => createConversationMutation.mutate({ title: newConvTitle || 'New Conversation', documentIds: selectedDocIds })}
                  disabled={createConversationMutation.isPending}
                  className="inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
                >
                  {createConversationMutation.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
                  <Sparkles className="h-4 w-4" />
                  Start Research
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      <ToastProvider>
        <ToastViewport />
      </ToastProvider>
    </div>
  );
}

function ConversationItem({ conv, onDelete }: { conv: Conversation; onDelete: () => void }) {
  return (
    <div className="flex items-center gap-4 rounded-xl border p-4 hover:border-primary/50 hover:bg-accent/50 transition-colors group">
      <MessageSquare className="h-10 w-10 rounded-lg bg-primary/10 text-primary flex-shrink-0 flex items-center justify-center" />
      <div className="flex-1 min-w-0">
        <h3 className="font-medium truncate">{conv.title}</h3>
        <p className="text-xs text-muted-foreground">
          {conv.messageCount} messages • {formatDistanceToNow(new Date(conv.updatedAt), { addSuffix: true })}
        </p>
        {conv.documents.length > 0 && (
          <div className="flex flex-wrap gap-1 mt-2">
            {conv.documents.slice(0, 3).map((d) => (
              <span key={d.id} className="inline-flex items-center gap-1 rounded px-2 py-0.5 text-xs text-muted-foreground bg-muted">
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
      <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
        <button className="p-2 rounded-lg hover:bg-accent" title="Delete" onClick={onDelete}>
          <Trash2 className="h-4 w-4 text-muted-foreground" />
        </button>
      </div>
    </div>
  );
}

function ConversationSkeleton() {
  return (
    <div className="animate-pulse flex items-center gap-4 rounded-xl border p-4">
      <div className="h-10 w-10 rounded-lg bg-muted flex-shrink-0" />
      <div className="flex-1 space-y-2">
        <div className="h-4 bg-muted rounded w-1/3" />
        <div className="h-3 bg-muted rounded w-1/2" />
        <div className="h-3 bg-muted rounded w-1/4" />
      </div>
    </div>
  );
}
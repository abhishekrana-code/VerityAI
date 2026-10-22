'use client';

import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api, type Document, type DocumentListResponse, type DocumentUploadResponse } from '@/lib/api';
import { Upload, FileText, Loader2, Search, Filter, MoreVertical, Trash2, RotateCcw, Eye, Download } from 'lucide-react';
import { cn } from '@/lib/utils';
import { formatDistanceToNow } from 'date-fns';
import { useDropzone } from 'react-dropzone';
import { Toast, ToastProvider, ToastViewport } from '@/components/ui/toast';
import { useToast } from '@/hooks/useToast';

const STATUS_OPTIONS = ['All', 'UPLOADED', 'PROCESSING', 'READY', 'FAILED'] as const;

export function DocumentsPage() {
  const queryClient = useQueryClient();
  const { toast } = useToast();
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState<typeof STATUS_OPTIONS[number]>('All');
  const [sort, setSort] = useState('createdAt,desc');
  const [uploading, setUploading] = useState(false);

  const { data, isLoading, error } = useQuery({
    queryKey: ['documents', page, size, search, statusFilter, sort],
    queryFn: () => {
      const params = new URLSearchParams();
      params.set('page', page.toString());
      params.set('size', size.toString());
      if (search) params.set('q', search);
      if (statusFilter !== 'All') params.set('status', statusFilter);
      params.set('sort', sort);
      return api.get<DocumentListResponse>(`/documents?${params.toString()}`);
    },
    placeholderData: (prev) => prev,
  });

  const uploadMutation = useMutation({
    mutationFn: (file: File) => {
      const formData = new FormData();
      formData.append('file', file);
      return api.upload<DocumentUploadResponse>('/documents', formData);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['documents'] });
      toast({ title: 'Upload started', description: 'Document is being processed' });
    },
    onError: (error: Error) => {
      toast({ title: 'Upload failed', description: error.message, variant: 'destructive' });
    },
    onSettled: () => setUploading(false),
  });

  const retryMutation = useMutation({
    mutationFn: (id: string) => api.post<void>(`/documents/${id}/retry`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['documents'] }),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => api.delete<void>(`/documents/${id}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['documents'] }),
    onError: () => toast({ title: 'Delete failed', variant: 'destructive' }),
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

  const getStatusBadge = (status: Document['status']) => {
    const variants = {
      UPLOADED: 'bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-400',
      PROCESSING: 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900/30 dark:text-yellow-400',
      READY: 'bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-400',
      FAILED: 'bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-400',
    };
    const icons = {
      UPLOADED: Clock,
      PROCESSING: Loader2,
      READY: CheckCircle,
      FAILED: AlertCircle,
    };
    const Icon = icons[status];
    return (
      <span className={cn('inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium', variants[status])}>
        <Icon className="h-3 w-3" />
        {status}
      </span>
    );
  };

  const handleDelete = (id: string, name: string) => {
    if (confirm(`Delete "${name}"? This cannot be undone.`)) {
      deleteMutation.mutate(id);
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold">Documents</h1>
          <p className="text-muted-foreground">Manage your research documents</p>
        </div>
        <div {...getRootProps()} className={cn(
          'relative flex items-center justify-center rounded-lg border-2 border-dashed p-6 transition-colors',
          isDragActive ? 'border-primary bg-primary/5' : 'border-border hover:border-primary/50'
        )}>
          <input {...getInputProps()} />
          <div className="flex flex-col items-center gap-2 text-center">
            <Upload className="h-8 w-8 text-muted-foreground" />
            <p className="text-sm font-medium">{isDragActive ? 'Drop PDF here' : 'Drag & drop PDF or click to upload'}</p>
            <p className="text-xs text-muted-foreground">Max 25 MB • PDF only</p>
            {uploading && <Loader2 className="h-4 w-4 animate-spin text-primary" />}
          </div>
        </div>
      </div>

      {/* Filters */}
      <div className="flex flex-wrap items-center gap-4">
        <div className="relative flex-1 max-w-xs">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
          <input
            type="search"
            placeholder="Search documents..."
            value={search}
            onChange={(e) => { setSearch(e.target.value); setPage(0); }}
            className="w-full pl-10 h-10 rounded-lg border border-input bg-background px-3 text-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          />
        </div>
        <select
          value={statusFilter}
          onChange={(e) => { setStatusFilter(e.target.value as typeof STATUS_OPTIONS[number]); setPage(0); }}
          className="h-10 rounded-lg border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          {STATUS_OPTIONS.map((s) => <option key={s} value={s}>{s}</option>)}
        </select>
        <select
          value={sort}
          onChange={(e) => { setSort(e.target.value); setPage(0); }}
          className="h-10 rounded-lg border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <option value="createdAt,desc">Newest first</option>
          <option value="createdAt,asc">Oldest first</option>
          <option value="originalFilename,asc">Name A-Z</option>
          <option value="originalFilename,desc">Name Z-A</option>
        </select>
      </div>

      {/* Document Grid */}
      {isLoading ? (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          {[...Array(8)].map((_, i) => (
            <DocumentSkeleton key={i} />
          ))}
        </div>
      ) : error ? (
        <div className="rounded-xl border p-8 text-center text-destructive">
          Failed to load documents. <button onClick={() => queryClient.invalidateQueries({ queryKey: ['documents'] })} className="ml-2 underline">Retry</button>
        </div>
      ) : data?.content.length === 0 ? (
        <div {...getRootProps()} className={cn(
          'relative rounded-xl border-2 border-dashed p-12 text-center transition-colors',
          isDragActive ? 'border-primary bg-primary/5' : 'border-border'
        )}>
          <input {...getInputProps()} />
          <FileText className="h-16 w-16 mx-auto text-muted-foreground/50 mb-4" />
          <h3 className="text-lg font-semibold mb-2">No documents found</h3>
          <p className="text-muted-foreground mb-4">Drag & drop a PDF or click to upload your first document</p>
        </div>
      ) : (
        <>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
            {data.content.map((doc) => (
              <DocumentCard
                key={doc.id}
                doc={doc}
                onRetry={() => retryMutation.mutate(doc.id)}
                onDelete={() => handleDelete(doc.id, doc.originalFilename)}
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
              Page {page + 1} of {data.totalPages || 1}
            </span>
            <button
              onClick={() => setPage((p) => Math.min((data.totalPages || 1) - 1, p + 1))}
              disabled={page >= (data.totalPages || 1) - 1}
              className="h-10 w-10 rounded-lg border border-input bg-background flex items-center justify-center hover:bg-accent disabled:opacity-50"
            >
              <ChevronRight className="h-5 w-5" />
            </button>
          </div>
        </>
      )}

      <ToastProvider>
        <ToastViewport />
      </ToastProvider>
    </div>
  );
}

function DocumentCard({ doc, onRetry, onDelete }: { doc: Document; onRetry: () => void; onDelete: () => void }) {
  const getStatusBadge = (status: Document['status']) => {
    const variants = {
      UPLOADED: 'bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-400',
      PROCESSING: 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900/30 dark:text-yellow-400',
      READY: 'bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-400',
      FAILED: 'bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-400',
    };
    const icons = {
      UPLOADED: Clock,
      PROCESSING: Loader2,
      READY: CheckCircle,
      FAILED: AlertCircle,
    };
    const Icon = icons[status];
    return (
      <span className={cn('inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium', variants[status])}>
        <Icon className="h-3 w-3" />
        {status}
      </span>
    );
  };

  return (
    <div className="rounded-xl border p-5 hover:border-primary/50 transition-colors group relative">
      <div className="flex items-start justify-between gap-4 mb-3">
        <FileText className="h-10 w-10 text-muted-foreground/50 group-hover:text-primary/50 transition-colors flex-shrink-0" />
        {getStatusBadge(doc.status)}
      </div>
      <h3 className="font-medium truncate mb-1" title={doc.originalFilename}>{doc.originalFilename}</h3>
      <p className="text-xs text-muted-foreground mb-3">
        {doc.pageCount ? `${doc.pageCount} pages • ` : ''}
        {formatDistanceToNow(new Date(doc.createdAt), { addSuffix: true })}
      </p>
      {doc.errorMessage && (
        <div className="text-xs text-destructive mb-3" title={doc.errorMessage}>
          Error: {doc.errorMessage}
        </div>
      )}
      <div className="flex items-center justify-end gap-2 pt-2 border-t">
        {doc.status === 'FAILED' && (
          <button
            onClick={onRetry}
            className="inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-medium text-muted-foreground hover:text-foreground hover:bg-accent transition-colors"
            title="Retry processing"
          >
            <RotateCcw className="h-3.5 w-3.5" />
            Retry
          </button>
        )}
        <button
          onClick={onDelete}
          className="inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-medium text-destructive hover:bg-destructive/10 transition-colors"
          title="Delete"
        >
          <Trash2 className="h-3.5 w-3.5" />
        </button>
      </div>
    </div>
  );
}

function DocumentSkeleton() {
  return (
    <div className="animate-pulse rounded-xl border p-5 space-y-3">
      <div className="flex items-start justify-between">
        <div className="h-10 w-10 rounded bg-muted" />
        <div className="h-6 w-20 rounded-full bg-muted" />
      </div>
      <div className="h-4 bg-muted rounded w-3/4" />
      <div className="h-3 bg-muted rounded w-1/2" />
      <div className="h-3 bg-muted rounded w-1/4" />
      <div className="h-8 w-full bg-muted rounded flex items-center justify-end gap-2 pt-2 border-t">
        <div className="h-8 w-20 rounded-lg bg-muted" />
        <div className="h-8 w-20 rounded-lg bg-muted" />
      </div>
    </div>
  );
}
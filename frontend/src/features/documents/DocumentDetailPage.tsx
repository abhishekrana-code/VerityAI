'use client';

import { useParams, useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api, type Document } from '@/lib/api';
import { ArrowLeft, Loader2, RotateCcw, Trash2, Download, Eye, FileText, Clock, CheckCircle, AlertCircle } from 'lucide-react';
import { cn } from '@/lib/utils';
import { formatDistanceToNow } from 'date-fns';
import { ToastProvider, ToastViewport } from '@/components/ui/toast';
import { useToast } from '@/hooks/useToast';

export function DocumentDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { toast } = useToast();

  const { data: doc, isLoading, error } = useQuery({
    queryKey: ['document', id],
    queryFn: () => api.get<Document>(`/documents/${id}`),
    enabled: !!id,
  });

  const retryMutation = useMutation({
    mutationFn: () => api.post<void>(`/documents/${id}/retry`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['document', id] });
      queryClient.invalidateQueries({ queryKey: ['documents'] });
      toast({ title: 'Retry started', description: 'Document is being reprocessed' });
    },
    onError: () => toast({ title: 'Retry failed', variant: 'destructive' }),
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

  if (isLoading) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <Loader2 className="h-8 w-8 animate-spin text-primary" />
      </div>
    );
  }

  if (error || !doc) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <div className="text-center">
          <AlertCircle className="h-12 w-12 mx-auto text-destructive/50 mb-4" />
          <h2 className="text-xl font-semibold mb-2">Document not found</h2>
          <button onClick={() => navigate('/dashboard/documents')} className="text-primary hover:underline">
            Back to Documents
          </button>
        </div>
      </div>
    );
  }

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
      <span className={cn('inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-sm font-medium', variants[status])}>
        <Icon className="h-4 w-4" />
        {status}
      </span>
    );
  };

  return (
    <div className="max-w-3xl mx-auto space-y-6">
      <div className="flex items-center gap-4">
        <button onClick={() => navigate('/dashboard/documents')} className="p-2 rounded-lg hover:bg-accent">
          <ArrowLeft className="h-5 w-5" />
        </button>
        <div>
          <h1 className="text-2xl font-bold truncate">{doc.originalFilename}</h1>
          <p className="text-muted-foreground">Document details and processing status</p>
        </div>
      </div>

      <div className="grid gap-6 lg:grid-cols-3">
        {/* Main Info */}
        <div className="lg:col-span-2 space-y-6">
          {/* Status */}
          <div className="rounded-xl border p-6">
            <div className="flex items-center justify-between mb-4">
              <h2 className="font-semibold">Processing Status</h2>
              {getStatusBadge(doc.status)}
            </div>
            <div className="space-y-3">
              <div className="flex items-center justify-between text-sm">
                <span className="text-muted-foreground">Filename</span>
                <span className="font-mono truncate max-w-[200px]">{doc.originalFilename}</span>
              </div>
              <div className="flex items-center justify-between text-sm">
                <span className="text-muted-foreground">Size</span>
                <span>{(doc.sizeBytes / 1024 / 1024).toFixed(2)} MB</span>
              </div>
              <div className="flex items-center justify-between text-sm">
                <span className="text-muted-foreground">Pages</span>
                <span>{doc.pageCount ?? 'Unknown'}</span>
              </div>
              <div className="flex items-center justify-between text-sm">
                <span className="text-muted-foreground">SHA-256</span>
                <span className="font-mono text-xs truncate max-w-[200px]">{doc.sha256}</span>
              </div>
              <div className="flex items-center justify-between text-sm">
                <span className="text-muted-foreground">Uploaded</span>
                <span>{formatDistanceToNow(new Date(doc.createdAt), { addSuffix: true })}</span>
              </div>
              {doc.updatedAt !== doc.createdAt && (
                <div className="flex items-center justify-between text-sm">
                  <span className="text-muted-foreground">Last updated</span>
                  <span>{formatDistanceToNow(new Date(doc.updatedAt), { addSuffix: true })}</span>
                </div>
              )}
            </div>
            {doc.errorMessage && (
              <div className="mt-4 p-4 rounded-lg bg-destructive/10 border border-destructive/20">
                <div className="flex items-center gap-2 text-destructive mb-2">
                  <AlertCircle className="h-5 w-5" />
                  <span className="font-medium">Processing Error</span>
                </div>
                <p className="text-sm text-destructive">{doc.errorMessage}</p>
                {doc.errorCode && (
                  <p className="mt-1 text-xs text-muted-foreground">Error code: {doc.errorCode}</p>
                )}
              </div>
            )}
          </div>

          {/* Actions */}
          <div className="rounded-xl border p-6 space-y-4">
            <h2 className="font-semibold">Actions</h2>
            <div className="flex flex-wrap gap-3">
              <button
                onClick={() => retryMutation.mutate()}
                disabled={retryMutation.isPending || doc.status !== 'FAILED'}
                className={cn(
                  'inline-flex items-center gap-2 rounded-lg px-4 py-2 text-sm font-medium transition-colors',
                  doc.status === 'FAILED'
                    ? 'bg-primary text-primary-foreground hover:bg-primary/90'
                    : 'bg-muted text-muted-foreground cursor-not-allowed'
                )}
              >
                {retryMutation.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
                <RotateCcw className="h-4 w-4" />
                Retry Processing
              </button>
              <button
                onClick={() => deleteMutation.mutate()}
                disabled={deleteMutation.isPending}
                className={cn(
                  'inline-flex items-center gap-2 rounded-lg px-4 py-2 text-sm font-medium text-destructive hover:bg-destructive/10 transition-colors',
                  deleteMutation.isPending && 'opacity-50 cursor-not-allowed'
                )}
              >
                {deleteMutation.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
                <Trash2 className="h-4 w-4" />
                Delete Document
              </button>
            </div>
          </div>
        </div>

        {/* Sidebar */}
        <div className="space-y-6">
          <div className="rounded-xl border p-6 text-center">
            <FileText className="h-16 w-16 mx-auto text-muted-foreground/50 mb-4" />
            <h3 className="font-semibold mb-1">{doc.originalFilename}</h3>
            <p className="text-sm text-muted-foreground">PDF Document</p>
            <div className="mt-4 flex items-center justify-center gap-4 text-sm text-muted-foreground">
              <span>{doc.pageCount ?? '?'} pages</span>
              <span>•</span>
              <span>{(doc.sizeBytes / 1024 / 1024).toFixed(1)} MB</span>
            </div>
          </div>

          <div className="rounded-xl border p-6">
            <h3 className="font-semibold mb-4">Technical Details</h3>
            <dl className="space-y-3 text-sm">
              <div className="flex justify-between">
                <dt className="text-muted-foreground">MIME Type</dt>
                <dd className="font-mono">{doc.mimeType}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-muted-foreground">SHA-256</dt>
                <dd className="font-mono truncate max-w-[150px]">{doc.sha256}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-muted-foreground">Created</dt>
                <dd>{new Date(doc.createdAt).toLocaleString()}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-muted-foreground">Updated</dt>
                <dd>{new Date(doc.updatedAt).toLocaleString()}</dd>
              </div>
            </dl>
          </div>
        </div>
      </div>

      <ToastProvider>
        <ToastViewport />
      </ToastProvider>
    </div>
  );
}
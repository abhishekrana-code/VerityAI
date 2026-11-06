'use client';

import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  api,
  type Document,
  type DocumentListResponse,
  type DocumentUploadResponse,
  type BulkDeleteResponse,
  type Collection,
  type Tag,
} from '@/lib/api';
import { errorToast } from '@/lib/errors';
import {
  Upload, FileText, Loader2, Search, Trash2, RotateCcw, ChevronLeft, ChevronRight,
  Clock, CheckCircle, AlertCircle, LayoutGrid, List, X, FolderPlus, Eye,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { formatDistanceToNow } from 'date-fns';
import { useDropzone } from 'react-dropzone';
import { ToastProvider, ToastViewport } from '@/components/ui/toast';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { useToast } from '@/hooks/useToast';

const STATUS_OPTIONS = ['All', 'UPLOADED', 'PROCESSING', 'READY', 'FAILED'] as const;

/** Sort keys map to the backend whitelist (name/date/size/status/…). */
const SORT_OPTIONS = [
  { value: 'date,desc', label: 'Newest first' },
  { value: 'date,asc', label: 'Oldest first' },
  { value: 'name,asc', label: 'Name A–Z' },
  { value: 'name,desc', label: 'Name Z–A' },
  { value: 'size,desc', label: 'Largest first' },
  { value: 'size,asc', label: 'Smallest first' },
  { value: 'status,asc', label: 'Status A–Z' },
] as const;

const MAX_FILE_SIZE = 25 * 1024 * 1024;

export function DocumentsPage() {
  const queryClient = useQueryClient();
  const { toast } = useToast();
  const [page, setPage] = useState(0);
  const [size] = useState(20);
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState<typeof STATUS_OPTIONS[number]>('All');
  const [tagFilter, setTagFilter] = useState('');
  const [collectionFilter, setCollectionFilter] = useState('');
  const [sort, setSort] = useState('date,desc');
  const [view, setView] = useState<'grid' | 'list'>('grid');
  const [uploadProgress, setUploadProgress] = useState<number | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [confirmDelete, setConfirmDelete] = useState<{ ids: string[]; label: string } | null>(null);
  const [addToCollectionOpen, setAddToCollectionOpen] = useState(false);
  const [newCollectionOpen, setNewCollectionOpen] = useState(false);
  const [newCollectionName, setNewCollectionName] = useState('');

  const hasFilters = search !== '' || statusFilter !== 'All' || tagFilter !== '' || collectionFilter !== '';

  const { data, isLoading, error } = useQuery({
    queryKey: ['documents', page, size, search, statusFilter, tagFilter, collectionFilter, sort],
    queryFn: () => {
      const params = new URLSearchParams();
      params.set('page', page.toString());
      params.set('size', size.toString());
      if (search) params.set('q', search);
      if (statusFilter !== 'All') params.set('status', statusFilter);
      if (tagFilter) params.set('tag', tagFilter);
      if (collectionFilter) params.set('collection', collectionFilter);
      params.set('sort', sort);
      return api.get<DocumentListResponse>(`/documents?${params.toString()}`);
    },
    placeholderData: (prev) => prev,
    refetchInterval: (query) => {
      const docs = query.state.data?.content;
      const hasPending = docs?.some((d) => d.status === 'PROCESSING' || d.status === 'UPLOADED');
      return hasPending ? 2500 : false;
    },
  });

  const { data: tags } = useQuery({
    queryKey: ['tags'],
    queryFn: () => api.get<Tag[]>('/tags'),
  });

  const { data: collections } = useQuery({
    queryKey: ['collections'],
    queryFn: () => api.get<Collection[]>('/collections'),
  });

  const uploadMutation = useMutation({
    mutationFn: (file: File) => {
      const formData = new FormData();
      formData.append('file', file);
      return api.uploadWithProgress<DocumentUploadResponse>(
        '/documents', formData, setUploadProgress
      );
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['documents'] });
      toast({ title: 'Upload started', description: 'Document is being processed' });
    },
    onError: (error: Error) => {
      toast(errorToast(error, 'Upload failed'));
    },
    onSettled: () => setUploadProgress(null),
  });

  const retryMutation = useMutation({
    mutationFn: (id: string) => api.post<void>(`/documents/${id}/retry`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['documents'] }),
    onError: (error: Error) => toast(errorToast(error, 'Retry failed')),
  });

  const deleteMutation = useMutation({
    mutationFn: (ids: string[]) =>
      ids.length === 1
        ? api.delete<void>(`/documents/${ids[0]}`).then(() => ({ deleted: 1, skippedIds: [] as string[] }))
        : api.post<BulkDeleteResponse>('/documents/bulk-delete', { documentIds: ids }),
    onSuccess: (result) => {
      queryClient.invalidateQueries({ queryKey: ['documents'] });
      queryClient.invalidateQueries({ queryKey: ['tags'] });
      queryClient.invalidateQueries({ queryKey: ['collections'] });
      setSelected(new Set());
      setConfirmDelete(null);
      if (result.skippedIds?.length) {
        toast({
          title: `${result.deleted} deleted, ${result.skippedIds.length} skipped`,
          description: 'Some documents could not be deleted — they may already be gone.',
          variant: 'destructive',
        });
      } else {
        toast({ title: `${result.deleted} document${result.deleted > 1 ? 's' : ''} deleted` });
      }
    },
    onError: (error: Error) => toast(errorToast(error, 'Delete failed')),
  });

  const addToCollectionMutation = useMutation({
    mutationFn: ({ collectionId, documentIds }: { collectionId: string; documentIds: string[] }) =>
      api.post<Collection>(`/collections/${collectionId}/documents`, { documentIds }),
    onSuccess: (_result, vars) => {
      queryClient.invalidateQueries({ queryKey: ['documents'] });
      queryClient.invalidateQueries({ queryKey: ['collections'] });
      const name = collections?.find((c) => c.id === vars.collectionId)?.name ?? 'collection';
      toast({ title: `Added ${vars.documentIds.length} document${vars.documentIds.length > 1 ? 's' : ''} to ${name}` });
      setAddToCollectionOpen(false);
      setSelected(new Set());
    },
    onError: (error: Error) => toast(errorToast(error, 'Could not add to collection')),
  });

  const createCollectionMutation = useMutation({
    mutationFn: (name: string) => api.post<Collection>('/collections', { name }),
    onSuccess: (collection) => {
      queryClient.invalidateQueries({ queryKey: ['collections'] });
      if (selected.size > 0) {
        addToCollectionMutation.mutate({
          collectionId: collection.id,
          documentIds: Array.from(selected),
        });
      }
      setNewCollectionOpen(false);
      setNewCollectionName('');
      setAddToCollectionOpen(false);
    },
    onError: (error: Error) => toast(errorToast(error, 'Could not create collection')),
  });

  const uploading = uploadProgress !== null || uploadMutation.isPending;

  const onDrop = (acceptedFiles: File[]) => {
    const file = acceptedFiles[0];
    if (!file) return;
    if (file.type !== 'application/pdf') {
      toast({ title: 'Invalid file type', description: 'Only PDF files are supported', variant: 'destructive' });
      return;
    }
    if (file.size > MAX_FILE_SIZE) {
      toast({ title: 'File too large', description: 'Maximum file size is 25 MB', variant: 'destructive' });
      return;
    }
    setUploadProgress(0);
    uploadMutation.mutate(file);
  };

  const { getRootProps, getInputProps, isDragActive } = useDropzone({
    onDrop,
    accept: { 'application/pdf': ['.pdf'] },
    maxSize: MAX_FILE_SIZE,
    disabled: uploading,
  });

  const docs = useMemo(() => data?.content ?? [], [data]);

  // Selection helpers (page-scoped select-all)
  const pageIds = useMemo(() => docs.map((d) => d.id), [docs]);
  const allPageSelected = pageIds.length > 0 && pageIds.every((id) => selected.has(id));
  const somePageSelected = pageIds.some((id) => selected.has(id));

  const toggleSelected = (id: string) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const toggleSelectPage = () => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (allPageSelected) pageIds.forEach((id) => next.delete(id));
      else pageIds.forEach((id) => next.add(id));
      return next;
    });
  };

  const clearFilters = () => {
    setSearch('');
    setStatusFilter('All');
    setTagFilter('');
    setCollectionFilter('');
    setPage(0);
  };

  // Drop selections that no longer exist after refetch/delete
  useEffect(() => {
    if (selected.size === 0) return;
    const known = new Set([
      ...pageIds,
      ...Array.from(selected), // keep ids not on this page (multi-page selection)
    ]);
    if (known.size === 0) setSelected(new Set());
  }, [pageIds, selected]);

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold">Documents</h1>
          <p className="text-muted-foreground">Manage your research documents</p>
        </div>
        <div className="flex items-center gap-3">
          {/* View toggle */}
          <div className="flex rounded-lg border border-border overflow-hidden" role="group" aria-label="View mode">
            <button
              onClick={() => setView('grid')}
              aria-pressed={view === 'grid'}
              aria-label="Grid view"
              className={cn(
                'p-2.5 transition-colors',
                view === 'grid' ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-accent'
              )}
            >
              <LayoutGrid className="h-4 w-4" />
            </button>
            <button
              onClick={() => setView('list')}
              aria-pressed={view === 'list'}
              aria-label="List view"
              className={cn(
                'p-2.5 transition-colors',
                view === 'list' ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-accent'
              )}
            >
              <List className="h-4 w-4" />
            </button>
          </div>
        </div>
      </div>

      {/* Upload dropzone */}
      <div {...getRootProps()} className={cn(
        'relative flex items-center justify-center rounded-lg border-2 border-dashed p-5 transition-colors cursor-pointer',
        isDragActive ? 'border-primary bg-primary/5' : 'border-border hover:border-primary/50',
        uploading && 'pointer-events-none opacity-70'
      )}>
        <input {...getInputProps()} aria-label="Upload PDF" />
        <div className="flex flex-col items-center gap-2 text-center">
          {uploading ? <Loader2 className="h-8 w-8 animate-spin text-primary" />
            : <Upload className="h-8 w-8 text-muted-foreground" />}
          <p className="text-sm font-medium">
            {uploading
              ? (uploadProgress !== null ? `Uploading… ${uploadProgress}%` : 'Starting upload…')
              : isDragActive ? 'Drop PDF here' : 'Drag & drop PDF or click to upload'}
          </p>
          <p className="text-xs text-muted-foreground">Max 25 MB • PDF only</p>
          {uploading && (
            <div className="w-56 h-1.5 rounded-full bg-muted overflow-hidden" role="progressbar"
              aria-valuenow={uploadProgress ?? 0} aria-valuemin={0} aria-valuemax={100}>
              <div
                className="h-full bg-primary transition-all duration-200"
                style={{ width: `${uploadProgress ?? 5}%` }}
              />
            </div>
          )}
        </div>
      </div>

      {/* Filters */}
      <div className="flex flex-wrap items-center gap-3">
        <div className="relative flex-1 min-w-[200px] max-w-xs">
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
          aria-label="Filter by status"
          className="h-10 rounded-lg border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          {STATUS_OPTIONS.map((s) => <option key={s} value={s}>{s === 'All' ? 'All statuses' : s}</option>)}
        </select>
        <select
          value={tagFilter}
          onChange={(e) => { setTagFilter(e.target.value); setPage(0); }}
          aria-label="Filter by tag"
          className="h-10 rounded-lg border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <option value="">All tags</option>
          {tags?.map((t) => (
            <option key={t.id} value={t.id}>{t.name} ({t.documentCount})</option>
          ))}
        </select>
        <select
          value={collectionFilter}
          onChange={(e) => { setCollectionFilter(e.target.value); setPage(0); }}
          aria-label="Filter by collection"
          className="h-10 rounded-lg border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <option value="">All collections</option>
          {collections?.map((c) => (
            <option key={c.id} value={c.id}>{c.name} ({c.documentCount})</option>
          ))}
        </select>
        <select
          value={sort}
          onChange={(e) => { setSort(e.target.value); setPage(0); }}
          aria-label="Sort documents"
          className="h-10 rounded-lg border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          {SORT_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
        {hasFilters && (
          <button
            onClick={clearFilters}
            className="inline-flex items-center gap-1.5 h-10 px-3 rounded-lg border border-border text-sm text-muted-foreground hover:bg-accent transition-colors"
          >
            <X className="h-3.5 w-3.5" /> Clear
          </button>
        )}
      </div>

      {/* Bulk selection bar */}
      {selected.size > 0 && (
        <div
          className="flex flex-wrap items-center gap-3 rounded-xl border border-primary/40 bg-primary/10 px-4 py-3"
          role="region"
          aria-label="Bulk actions"
        >
          <span className="text-sm font-medium">{selected.size} selected</span>
          <div className="flex items-center gap-2 ml-auto">
            <button
              onClick={() => setAddToCollectionOpen(true)}
              className="inline-flex items-center gap-1.5 rounded-lg border border-border bg-background px-3 py-1.5 text-sm font-medium hover:bg-accent transition-colors"
            >
              <FolderPlus className="h-4 w-4" /> Add to collection
            </button>
            <button
              onClick={() => setConfirmDelete({
                ids: Array.from(selected),
                label: `${selected.size} document${selected.size > 1 ? 's' : ''}`,
              })}
              className="inline-flex items-center gap-1.5 rounded-lg bg-destructive text-destructive-foreground px-3 py-1.5 text-sm font-medium hover:bg-destructive/90 transition-colors"
            >
              <Trash2 className="h-4 w-4" /> Delete
            </button>
            <button
              onClick={() => setSelected(new Set())}
              className="px-2 py-1.5 text-sm text-muted-foreground hover:text-foreground transition-colors"
            >
              Clear selection
            </button>
          </div>
        </div>
      )}

      {/* Select-all row (when there are documents) */}
      {docs.length > 0 && (
        <label className="flex items-center gap-2 text-sm text-muted-foreground cursor-pointer w-fit">
          <input
            type="checkbox"
            checked={allPageSelected}
            ref={(el) => { if (el) el.indeterminate = !allPageSelected && somePageSelected; }}
            onChange={toggleSelectPage}
            className="h-4 w-4 rounded border-input accent-primary"
          />
          Select all on this page
        </label>
      )}

      {/* Document list / grid */}
      {isLoading ? (
        <div className={cn(
          view === 'grid' ? 'grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4' : 'space-y-3'
        )}>
          {[...Array(8)].map((_, i) => (
            <DocumentSkeleton key={i} view={view} />
          ))}
        </div>
      ) : error ? (
        <div className="rounded-xl border border-destructive/30 p-8 text-center">
          <AlertCircle className="h-10 w-10 mx-auto text-destructive/60 mb-3" />
          <p className="text-destructive font-medium">Failed to load documents</p>
          <p className="text-sm text-muted-foreground mt-1 mb-4">Check your connection and try again.</p>
          <button
            onClick={() => queryClient.invalidateQueries({ queryKey: ['documents'] })}
            className="rounded-lg bg-primary text-primary-foreground px-4 py-2 text-sm font-medium hover:bg-primary/90 transition-colors"
          >
            Retry
          </button>
        </div>
      ) : !data || docs.length === 0 ? (
        hasFilters ? (
          <div className="rounded-xl border border-dashed p-12 text-center">
            <Search className="h-12 w-12 mx-auto text-muted-foreground/50 mb-4" />
            <h3 className="text-lg font-semibold mb-2">No documents match your filters</h3>
            <p className="text-muted-foreground mb-4">Try a different search term or clear the filters.</p>
            <button
              onClick={clearFilters}
              className="rounded-lg border border-border px-4 py-2 text-sm font-medium hover:bg-accent transition-colors"
            >
              Clear filters
            </button>
          </div>
        ) : (
          <div {...getRootProps()} className={cn(
            'relative rounded-xl border-2 border-dashed p-12 text-center transition-colors',
            isDragActive ? 'border-primary bg-primary/5' : 'border-border'
          )}>
            <input {...getInputProps()} />
            <FileText className="h-16 w-16 mx-auto text-muted-foreground/50 mb-4" />
            <h3 className="text-lg font-semibold mb-2">No documents yet</h3>
            <p className="text-muted-foreground mb-4">Drag & drop a PDF or click to upload your first document</p>
          </div>
        )
      ) : view === 'grid' ? (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          {docs.map((doc) => (
            <DocumentCard
              key={doc.id}
              doc={doc}
              selected={selected.has(doc.id)}
              onToggle={() => toggleSelected(doc.id)}
              onRetry={() => retryMutation.mutate(doc.id)}
              onDelete={() => setConfirmDelete({ ids: [doc.id], label: `"${doc.originalFilename}"` })}
            />
          ))}
        </div>
      ) : (
        <DocumentTable
          docs={docs}
          selected={selected}
          onToggle={toggleSelected}
          onRetry={(id) => retryMutation.mutate(id)}
          onDelete={(doc) => setConfirmDelete({ ids: [doc.id], label: `"${doc.originalFilename}"` })}
        />
      )}

      {/* Pagination */}
      {data && data.totalPages > 1 && (
        <div className="flex items-center justify-center gap-2">
          <button
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            disabled={page === 0}
            aria-label="Previous page"
            className="h-10 w-10 rounded-lg border border-input bg-background flex items-center justify-center hover:bg-accent disabled:opacity-50"
          >
            <ChevronLeft className="h-5 w-5" />
          </button>
          <span className="text-sm text-muted-foreground">
            Page {page + 1} of {data.totalPages}
          </span>
          <button
            onClick={() => setPage((p) => Math.min((data.totalPages || 1) - 1, p + 1))}
            disabled={page >= (data.totalPages || 1) - 1}
            aria-label="Next page"
            className="h-10 w-10 rounded-lg border border-input bg-background flex items-center justify-center hover:bg-accent disabled:opacity-50"
          >
            <ChevronRight className="h-5 w-5" />
          </button>
        </div>
      )}

      {/* Delete confirmation */}
      <ConfirmDialog
        open={confirmDelete !== null}
        title={confirmDelete && confirmDelete.ids.length > 1
          ? `Delete ${confirmDelete.ids.length} documents?`
          : 'Delete document?'}
        description={confirmDelete
          ? `Deleting ${confirmDelete.label} removes the file, its extracted text and AI summaries. This cannot be undone.`
          : ''}
        confirmLabel="Delete"
        loading={deleteMutation.isPending}
        onConfirm={() => confirmDelete && deleteMutation.mutate(confirmDelete.ids)}
        onCancel={() => setConfirmDelete(null)}
      />

      {/* Add-to-collection menu */}
      {addToCollectionOpen && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center p-4"
          role="dialog"
          aria-modal="true"
          aria-labelledby="add-collection-title"
        >
          <div className="absolute inset-0 bg-black/60 backdrop-blur-sm" onClick={() => setAddToCollectionOpen(false)} />
          <div className="relative w-full max-w-md rounded-xl border border-border bg-card p-6 shadow-2xl">
            <h2 id="add-collection-title" className="text-lg font-semibold mb-1">Add to collection</h2>
            <p className="text-sm text-muted-foreground mb-4">
              {selected.size} document{selected.size > 1 ? 's' : ''} selected
            </p>
            <div className="space-y-2 max-h-64 overflow-y-auto">
              {collections?.length === 0 && (
                <p className="text-sm text-muted-foreground py-2">
                  No collections yet — create one below.
                </p>
              )}
              {collections?.map((c) => (
                <button
                  key={c.id}
                  onClick={() => addToCollectionMutation.mutate({
                    collectionId: c.id,
                    documentIds: Array.from(selected),
                  })}
                  disabled={addToCollectionMutation.isPending}
                  className="w-full flex items-center justify-between rounded-lg border border-border px-3 py-2.5 text-sm hover:bg-accent transition-colors text-left disabled:opacity-50"
                >
                  <span className="font-medium truncate">{c.name}</span>
                  <span className="text-muted-foreground">{c.documentCount} docs</span>
                </button>
              ))}
            </div>
            <div className="mt-4 flex justify-between gap-3">
              <button
                onClick={() => { setAddToCollectionOpen(false); setNewCollectionOpen(true); }}
                className="inline-flex items-center gap-1.5 rounded-lg border border-border px-3 py-2 text-sm font-medium hover:bg-accent transition-colors"
              >
                <FolderPlus className="h-4 w-4" /> New collection
              </button>
              <button
                onClick={() => setAddToCollectionOpen(false)}
                className="rounded-lg px-3 py-2 text-sm text-muted-foreground hover:text-foreground transition-colors"
              >
                Cancel
              </button>
            </div>
          </div>
        </div>
      )}

      {/* New collection inline modal */}
      {newCollectionOpen && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center p-4"
          role="dialog"
          aria-modal="true"
          aria-labelledby="new-collection-title"
        >
          <div className="absolute inset-0 bg-black/60 backdrop-blur-sm" onClick={() => setNewCollectionOpen(false)} />
          <form
            className="relative w-full max-w-md rounded-xl border border-border bg-card p-6 shadow-2xl"
            onSubmit={(e) => {
              e.preventDefault();
              const name = newCollectionName.trim();
              if (name) createCollectionMutation.mutate(name);
            }}
          >
            <h2 id="new-collection-title" className="text-lg font-semibold mb-1">New collection</h2>
            <p className="text-sm text-muted-foreground mb-4">
              The selected documents will be added to it right away.
            </p>
            <input
              autoFocus
              value={newCollectionName}
              onChange={(e) => setNewCollectionName(e.target.value)}
              placeholder="e.g. Market research"
              maxLength={100}
              className="w-full h-10 rounded-lg border border-input bg-background px-3 text-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring mb-4"
            />
            <div className="flex justify-end gap-3">
              <button
                type="button"
                onClick={() => setNewCollectionOpen(false)}
                className="rounded-lg px-3 py-2 text-sm text-muted-foreground hover:text-foreground transition-colors"
              >
                Cancel
              </button>
              <button
                type="submit"
                disabled={!newCollectionName.trim() || createCollectionMutation.isPending}
                className="inline-flex items-center gap-2 rounded-lg bg-primary text-primary-foreground px-4 py-2 text-sm font-medium hover:bg-primary/90 transition-colors disabled:opacity-50"
              >
                {createCollectionMutation.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
                Create
              </button>
            </div>
          </form>
        </div>
      )}

      <ToastProvider>
        <ToastViewport />
      </ToastProvider>
    </div>
  );
}

function StatusBadge({ status, size = 'sm' }: { status: Document['status']; size?: 'sm' | 'md' }) {
  const variants: Record<Document['status'], string> = {
    UPLOADED: 'bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-400',
    PROCESSING: 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900/30 dark:text-yellow-400',
    READY: 'bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-400',
    FAILED: 'bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-400',
  };
  const icons = { UPLOADED: Clock, PROCESSING: Loader2, READY: CheckCircle, FAILED: AlertCircle };
  const Icon = icons[status];
  return (
    <span className={cn(
      'inline-flex items-center gap-1.5 rounded-full font-medium whitespace-nowrap',
      size === 'md' ? 'px-3 py-1 text-sm' : 'px-2.5 py-0.5 text-xs',
      variants[status]
    )}>
      <Icon className={cn('h-3 w-3', status === 'PROCESSING' && 'animate-spin')} />
      {status}
    </span>
  );
}

function DocumentCard({
  doc, selected, onToggle, onRetry, onDelete,
}: {
  doc: Document;
  selected: boolean;
  onToggle: () => void;
  onRetry: () => void;
  onDelete: () => void;
}) {
  const navigate = useNavigate();

  return (
    <div
      onClick={() => navigate(`/dashboard/documents/${doc.id}`)}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          navigate(`/dashboard/documents/${doc.id}`);
        }
      }}
      role="link"
      tabIndex={0}
      className={cn(
        'rounded-xl border p-5 hover:border-primary/50 transition-colors group relative cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
        selected && 'border-primary/60 bg-primary/5'
      )}
    >
      <div className="flex items-start gap-3 mb-3">
        <input
          type="checkbox"
          checked={selected}
          onChange={onToggle}
          onClick={(e) => e.stopPropagation()}
          aria-label={`Select ${doc.originalFilename}`}
          className="h-4 w-4 mt-1 rounded border-input accent-primary flex-shrink-0"
        />
        <FileText className="h-9 w-9 text-muted-foreground/50 group-hover:text-primary/50 transition-colors flex-shrink-0" />
        <div className="ml-auto"><StatusBadge status={doc.status} /></div>
      </div>
      <h3 className="font-medium truncate mb-1" title={doc.originalFilename}>{doc.originalFilename}</h3>
      <p className="text-xs text-muted-foreground mb-2">
        {doc.pageCount ? `${doc.pageCount} pages • ` : ''}
        {(doc.sizeBytes / 1024 / 1024).toFixed(1)} MB •{' '}
        {formatDistanceToNow(new Date(doc.createdAt), { addSuffix: true })}
      </p>
      {(doc.tags?.length || doc.collections?.length) ? (
        <div className="flex flex-wrap gap-1.5 mb-2">
          {doc.tags?.map((t) => (
            <span key={t.id} className="inline-flex items-center gap-1 rounded-full bg-accent px-2 py-0.5 text-[11px] text-accent-foreground">
              <span className="h-1.5 w-1.5 rounded-full" style={{ backgroundColor: t.color || '#7c3aed' }} />
              {t.name}
            </span>
          ))}
          {doc.collections?.map((c) => (
            <span key={c.id} className="rounded-full bg-muted px-2 py-0.5 text-[11px] text-muted-foreground truncate max-w-[100px]">
              {c.name}
            </span>
          ))}
        </div>
      ) : null}
      {doc.errorMessage && (
        <div className="text-xs text-destructive mb-2 line-clamp-2" title={doc.errorMessage}>
          Error: {doc.errorMessage}
        </div>
      )}
      <div className="flex items-center justify-end gap-2 pt-2 border-t">
        <button
          onClick={(e) => { e.stopPropagation(); navigate(`/dashboard/documents/${doc.id}`); }}
          className="inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs font-medium text-muted-foreground hover:text-foreground hover:bg-accent transition-colors"
          title="Open document"
        >
          <Eye className="h-3.5 w-3.5" /> Open
        </button>
        {doc.status !== 'READY' && (
          <button
            onClick={(e) => { e.stopPropagation(); onRetry(); }}
            className="inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs font-medium text-muted-foreground hover:text-foreground hover:bg-accent transition-colors"
            title="Retry processing"
          >
            <RotateCcw className="h-3.5 w-3.5" /> Retry
          </button>
        )}
        <button
          onClick={(e) => { e.stopPropagation(); onDelete(); }}
          className="inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs font-medium text-destructive hover:bg-destructive/10 transition-colors"
          title="Delete document"
        >
          <Trash2 className="h-3.5 w-3.5" />
        </button>
      </div>
    </div>
  );
}

function DocumentTable({
  docs, selected, onToggle, onRetry, onDelete,
}: {
  docs: Document[];
  selected: Set<string>;
  onToggle: (id: string) => void;
  onRetry: (id: string) => void;
  onDelete: (doc: Document) => void;
}) {
  const navigate = useNavigate();

  return (
    <div className="rounded-xl border overflow-hidden">
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b bg-muted/40 text-left text-xs uppercase tracking-wide text-muted-foreground">
              <th className="p-3 w-10"><span className="sr-only">Select</span></th>
              <th className="p-3 font-medium">Name</th>
              <th className="p-3 font-medium">Status</th>
              <th className="p-3 font-medium hidden sm:table-cell">Size</th>
              <th className="p-3 font-medium hidden md:table-cell">Pages</th>
              <th className="p-3 font-medium hidden lg:table-cell">Tags</th>
              <th className="p-3 font-medium hidden md:table-cell">Uploaded</th>
              <th className="p-3 font-medium text-right">Actions</th>
            </tr>
          </thead>
          <tbody>
            {docs.map((doc) => (
              <tr
                key={doc.id}
                onClick={() => navigate(`/dashboard/documents/${doc.id}`)}
                className={cn(
                  'border-b last:border-0 hover:bg-accent/50 cursor-pointer transition-colors',
                  selected.has(doc.id) && 'bg-primary/5'
                )}
              >
                <td className="p-3" onClick={(e) => e.stopPropagation()}>
                  <input
                    type="checkbox"
                    checked={selected.has(doc.id)}
                    onChange={() => onToggle(doc.id)}
                    aria-label={`Select ${doc.originalFilename}`}
                    className="h-4 w-4 rounded border-input accent-primary"
                  />
                </td>
                <td className="p-3">
                  <div className="flex items-center gap-2 min-w-0">
                    <FileText className="h-4 w-4 text-muted-foreground flex-shrink-0" />
                    <span className="font-medium truncate max-w-[220px]" title={doc.originalFilename}>
                      {doc.originalFilename}
                    </span>
                  </div>
                  {doc.errorMessage && (
                    <p className="text-xs text-destructive mt-0.5 truncate max-w-[220px]" title={doc.errorMessage}>
                      {doc.errorMessage}
                    </p>
                  )}
                </td>
                <td className="p-3"><StatusBadge status={doc.status} /></td>
                <td className="p-3 text-muted-foreground hidden sm:table-cell">
                  {(doc.sizeBytes / 1024 / 1024).toFixed(1)} MB
                </td>
                <td className="p-3 text-muted-foreground hidden md:table-cell">{doc.pageCount ?? '—'}</td>
                <td className="p-3 hidden lg:table-cell">
                  <div className="flex flex-wrap gap-1">
                    {doc.tags?.slice(0, 3).map((t) => (
                      <span key={t.id} className="rounded-full bg-accent px-2 py-0.5 text-[11px]">
                        {t.name}
                      </span>
                    ))}
                    {doc.tags && doc.tags.length > 3 && (
                      <span className="text-[11px] text-muted-foreground">+{doc.tags.length - 3}</span>
                    )}
                  </div>
                </td>
                <td className="p-3 text-muted-foreground hidden md:table-cell whitespace-nowrap">
                  {formatDistanceToNow(new Date(doc.createdAt), { addSuffix: true })}
                </td>
                <td className="p-3 text-right whitespace-nowrap" onClick={(e) => e.stopPropagation()}>
                  <div className="inline-flex items-center gap-1">
                    {doc.status !== 'READY' && (
                      <button
                        onClick={() => onRetry(doc.id)}
                        aria-label={`Retry ${doc.originalFilename}`}
                        title="Retry processing"
                        className="p-1.5 rounded-lg text-muted-foreground hover:text-foreground hover:bg-accent transition-colors"
                      >
                        <RotateCcw className="h-4 w-4" />
                      </button>
                    )}
                    <button
                      onClick={() => onDelete(doc)}
                      aria-label={`Delete ${doc.originalFilename}`}
                      title="Delete document"
                      className="p-1.5 rounded-lg text-destructive hover:bg-destructive/10 transition-colors"
                    >
                      <Trash2 className="h-4 w-4" />
                    </button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function DocumentSkeleton({ view }: { view: 'grid' | 'list' }) {
  if (view === 'list') {
    return (
      <div className="animate-pulse rounded-xl border p-4 flex items-center gap-4">
        <div className="h-4 w-4 rounded bg-muted" />
        <div className="h-4 bg-muted rounded flex-1 max-w-[240px]" />
        <div className="h-6 w-20 rounded-full bg-muted" />
        <div className="h-4 w-16 bg-muted rounded ml-auto" />
      </div>
    );
  }
  return (
    <div className="animate-pulse rounded-xl border p-5 space-y-3">
      <div className="flex items-start justify-between">
        <div className="h-9 w-9 rounded bg-muted" />
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

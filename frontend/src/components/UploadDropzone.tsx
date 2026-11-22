'use client';

import { useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useDropzone, type FileRejection } from 'react-dropzone';
import { AlertCircle, CheckCircle2, Loader2, Upload, X } from 'lucide-react';
import { api, ApiException, type DocumentUploadResponse } from '@/lib/api';
import { errorToast } from '@/lib/errors';
import { cn, formatFileSize } from '@/lib/utils';
import { useToast } from '@/hooks/useToast';

const MAX_FILE_SIZE = 25 * 1024 * 1024;
/** Two at a time: fast enough on a normal connection without saturating it. */
const CONCURRENCY = 2;

type UploadStatus = 'queued' | 'uploading' | 'done' | 'error';

interface UploadEntry {
  key: string;
  name: string;
  size: number;
  progress: number;
  status: UploadStatus;
  error?: string;
}

function uploadErrorMessage(error: unknown): string {
  if (error instanceof ApiException) {
    if (error.status === 429) {
      const seconds = error.retryAfterSeconds ?? 3600;
      const minutes = Math.max(1, Math.round(seconds / 60));
      return `Hourly upload limit reached. Try again in about ${minutes} minute${minutes === 1 ? '' : 's'}.`;
    }
    return error.error.message;
  }
  if (error instanceof Error) return error.message;
  return 'Upload failed.';
}

interface UploadDropzoneProps {
  /** Called once per successfully uploaded document. */
  onUploaded?: (documentId: string) => void;
  compact?: boolean;
  className?: string;
  /** Overrides the default bordered container (e.g. inside a dashboard card). */
  bare?: boolean;
}

/**
 * The single upload surface used by the Library, Home and Ask. Uploads several
 * PDFs with bounded concurrency and reports each file's own progress and error,
 * so one failure never hides the rest.
 */
export function UploadDropzone({ onUploaded, compact, className, bare }: UploadDropzoneProps) {
  const queryClient = useQueryClient();
  const { toast } = useToast();
  const [entries, setEntries] = useState<UploadEntry[]>([]);
  const queue = useRef<Array<{ key: string; file: File }>>([]);
  const draining = useRef(false);
  const nextKey = useRef(0);

  const update = (key: string, patch: Partial<UploadEntry>) => {
    setEntries((prev) => prev.map((e) => (e.key === key ? { ...e, ...patch } : e)));
  };

  const uploadOne = async (key: string, file: File) => {
    update(key, { status: 'uploading', progress: 0 });
    const formData = new FormData();
    formData.append('file', file);
    try {
      const result = await api.uploadWithProgress<DocumentUploadResponse>(
        '/documents',
        formData,
        (percent) => update(key, { progress: percent }),
      );
      update(key, { status: 'done', progress: 100 });
      queryClient.invalidateQueries({ queryKey: ['documents'] });
      queryClient.invalidateQueries({ queryKey: ['tags'] });
      queryClient.invalidateQueries({ queryKey: ['collections'] });
      onUploaded?.(result.id);
    } catch (error) {
      const message = uploadErrorMessage(error);
      update(key, { status: 'error', error: message });
      if (error instanceof ApiException && error.status === 429) {
        toast({ title: 'Upload limit reached', description: message, variant: 'destructive' });
      } else {
        toast(errorToast(error, 'Upload failed'));
      }
    }
  };

  const drain = async () => {
    if (draining.current) return;
    draining.current = true;
    try {
      while (queue.current.length > 0) {
        const batch = queue.current.splice(0, CONCURRENCY);
        await Promise.all(batch.map(({ key, file }) => uploadOne(key, file)));
      }
    } finally {
      draining.current = false;
    }
  };

  const addFiles = (files: File[]) => {
    if (files.length === 0) return;
    const added = files.map((file) => {
      nextKey.current += 1;
      return { key: `upload-${nextKey.current}`, file };
    });
    setEntries((prev) => [
      ...prev,
      ...added.map(({ key, file }) => ({
        key,
        name: file.name,
        size: file.size,
        progress: 0,
        status: 'queued' as UploadStatus,
      })),
    ]);
    toast({
      title: 'Upload started',
      description: files.length === 1 ? files[0].name : `${files.length} PDFs queued.`,
    });
    queue.current.push(...added);
    void drain();
  };

  const addRejected = (rejections: FileRejection[]) => {
    setEntries((prev) => [
      ...prev,
      ...rejections.map((rejection) => {
        nextKey.current += 1;
        const code = rejection.errors[0]?.code;
        const message =
          code === 'file-too-large'
            ? `${rejection.file.name} is larger than the 25 MB limit.`
            : code === 'file-invalid-type'
              ? `${rejection.file.name} was not uploaded — only PDF files are supported.`
              : rejection.errors[0]?.message ?? `${rejection.file.name} could not be uploaded.`;
        return {
          key: `upload-${nextKey.current}`,
          name: rejection.file.name,
          size: rejection.file.size,
          progress: 0,
          status: 'error' as UploadStatus,
          error: message,
        };
      }),
    ]);
  };

  const { getRootProps, getInputProps, isDragActive } = useDropzone({
    onDrop: addFiles,
    onDropRejected: addRejected,
    accept: { 'application/pdf': ['.pdf'] },
    maxSize: MAX_FILE_SIZE,
    multiple: true,
  });

  const busy = entries.some((e) => e.status === 'queued' || e.status === 'uploading');

  return (
    <div className={cn(!bare && 'rounded-xl border border-border bg-card', className)}>
      <div
        {...getRootProps()}
        className={cn(
          'flex cursor-pointer flex-col items-center justify-center gap-2 text-center transition-colors',
          !bare && 'rounded-xl border-2 border-dashed',
          compact ? 'p-4' : 'p-7',
          isDragActive
            ? 'border-primary bg-primary/5'
            : 'border-border hover:border-primary/50',
        )}
      >
        <input {...getInputProps()} aria-label="Upload PDF" />
        {busy ? (
          <Loader2 className="h-7 w-7 animate-spin text-primary" aria-hidden="true" />
        ) : (
          <Upload className="h-7 w-7 text-muted-foreground" aria-hidden="true" />
        )}
        <p className="text-sm font-medium">
          {isDragActive ? 'Drop your PDFs here' : 'Drag & drop PDFs, or click to choose files'}
        </p>
        <p className="max-w-md text-xs text-muted-foreground">
          Text-based PDFs only, up to 25 MB each. Scanned images aren&apos;t supported yet — you can
          upload several at once.
        </p>
      </div>

      {entries.length > 0 && (
        <div className="border-t border-border/70 p-3">
          <div className="mb-2 flex items-center justify-between">
            <span className="text-xs font-medium text-muted-foreground">
              {busy ? 'Uploading…' : 'Uploads'}
            </span>
            {!busy && (
              <button
                type="button"
                onClick={() => setEntries([])}
                className="inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
              >
                <X className="h-3 w-3" /> Clear
              </button>
            )}
          </div>
          <ul className="space-y-2">
            {entries.map((entry) => (
              <li key={entry.key} className="text-sm">
                <div className="flex items-center gap-2">
                  {entry.status === 'done' && (
                    <CheckCircle2 className="h-4 w-4 shrink-0 text-green-500" aria-hidden="true" />
                  )}
                  {entry.status === 'error' && (
                    <AlertCircle className="h-4 w-4 shrink-0 text-destructive" aria-hidden="true" />
                  )}
                  {(entry.status === 'queued' || entry.status === 'uploading') && (
                    <Loader2 className="h-4 w-4 shrink-0 animate-spin text-primary" aria-hidden="true" />
                  )}
                  <span className="min-w-0 flex-1 truncate" title={entry.name}>
                    {entry.name}
                  </span>
                  <span className="shrink-0 text-xs text-muted-foreground">
                    {entry.status === 'uploading' ? `${entry.progress}%` : formatFileSize(entry.size)}
                  </span>
                </div>
                {entry.status === 'uploading' && (
                  <div
                    className="mt-1 h-1 overflow-hidden rounded-full bg-muted"
                    role="progressbar"
                    aria-valuenow={entry.progress}
                    aria-valuemin={0}
                    aria-valuemax={100}
                    aria-label={`Uploading ${entry.name}`}
                  >
                    <div className="h-full bg-primary transition-all" style={{ width: `${entry.progress}%` }} />
                  </div>
                )}
                {entry.status === 'error' && entry.error && (
                  <p className="mt-0.5 text-xs text-destructive">{entry.error}</p>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

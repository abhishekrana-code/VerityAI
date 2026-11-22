import type { Document } from '@/lib/api';

/**
 * The user-facing phases of processing, independent of the finer-grained
 * processing stages stored by the backend. AI summaries are deliberately not a
 * step here: they run separately and never block asking questions.
 */
export const PROCESSING_STEPS = ['Uploaded', 'Reading', 'Indexing', 'Ready'] as const;

/** Maps the persisted processing stage onto the 4-step indicator. */
export function processingStepIndex(
  status: Document['status'],
  stage?: string | null,
): number {
  if (status === 'READY' || stage === 'COMPLETED') return 3;
  switch (stage) {
    case 'EXTRACTING':
      return 1;
    case 'CHUNKING':
    case 'INDEXING':
      return 2;
    case 'QUEUED':
    case 'UPLOADED':
      return 0;
    default:
      // PROCESSING without a recorded stage is already past upload.
      return status === 'PROCESSING' ? 1 : 0;
  }
}

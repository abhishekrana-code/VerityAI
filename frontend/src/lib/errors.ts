import { ApiException } from './api';

export interface ErrorToastProps {
  title: string;
  description?: string;
  variant: 'destructive';
}

/**
 * Maps an API failure to toast props. Rate limits (429) get a dedicated,
 * actionable message instead of the caller's generic title; everything else
 * falls back to the caller's title plus the error message.
 */
export function errorToast(err: unknown, fallbackTitle: string): ErrorToastProps {
  if (err instanceof ApiException && err.status === 429) {
    return {
      title: 'Rate limit reached',
      description:
        err.error.message || 'Too many requests — please wait a moment and try again.',
      variant: 'destructive',
    };
  }
  return {
    title: fallbackTitle,
    description: err instanceof Error ? err.message : undefined,
    variant: 'destructive',
  };
}

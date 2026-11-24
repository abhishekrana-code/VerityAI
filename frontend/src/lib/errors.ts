import { ApiException } from './api';

export interface ErrorToastProps {
  title: string;
  description?: string;
  variant: 'destructive';
}

/** Rounds a Retry-After value into the unit a person would say out loud. */
function retryLabel(seconds: number): string {
  const rounded = Math.max(1, Math.ceil(seconds));
  if (rounded < 60) {
    return `${rounded} second${rounded === 1 ? '' : 's'}`;
  }
  if (rounded < 3600) {
    const minutes = Math.ceil(rounded / 60);
    return `${minutes} minute${minutes === 1 ? '' : 's'}`;
  }
  const hours = Math.ceil(rounded / 3600);
  return `${hours} hour${hours === 1 ? '' : 's'}`;
}

/**
 * Maps an API failure to toast props. Rate limits (429) get a dedicated,
 * actionable message: the server's own text plus the wait from `Retry-After`,
 * so an exhausted AI budget says when it resets instead of "try again later".
 * Everything else falls back to the caller's title plus the error message.
 */
export function errorToast(err: unknown, fallbackTitle: string): ErrorToastProps {
  if (err instanceof ApiException && err.status === 429) {
    const message = (err.error.message || 'Too many requests').replace(/[.\s]+$/, '');
    const wait =
      err.retryAfterSeconds != null
        ? ` Try again in about ${retryLabel(err.retryAfterSeconds)}.`
        : ' Please wait a moment and try again.';
    return {
      title: 'Rate limit reached',
      description: `${message}.${wait}`,
      variant: 'destructive',
    };
  }
  return {
    title: fallbackTitle,
    description: err instanceof Error ? err.message : undefined,
    variant: 'destructive',
  };
}

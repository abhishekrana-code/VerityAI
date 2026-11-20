import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/**
 * PENDING with a visible reason is a deferred job (its queued work was dropped)
 * that can be retried now; PENDING without one is genuine work in progress.
 */
export function isDeferredStatus(
  status?: string | null,
  error?: string | null,
): boolean {
  return status === 'PENDING' && !!error;
}
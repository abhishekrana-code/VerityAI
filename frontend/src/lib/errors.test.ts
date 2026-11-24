import { describe, expect, it } from 'vitest';
import { ApiException } from './api';
import { errorToast } from './errors';

function apiException(
  status: number,
  message: string,
  code = 'ERROR',
  retryAfterSeconds: number | null = null,
): ApiException {
  return new ApiException({ status, code, message }, retryAfterSeconds);
}

describe('errorToast', () => {
  it('shows the Retry-After wait on an AI rate limit', () => {
    const props = errorToast(
      apiException(429, 'AI request limit reached.', 'AI_RATE_LIMITED', 60),
      'Failed to send',
    );

    expect(props).toEqual({
      title: 'Rate limit reached',
      description: 'AI request limit reached. Try again in about 1 minute.',
      variant: 'destructive',
    });
  });

  it('uses seconds for waits under a minute and hours for long ones', () => {
    expect(errorToast(apiException(429, '', 'AI_RATE_LIMITED', 42), 'Failed to send').description)
      .toBe('Too many requests. Try again in about 42 seconds.');
    expect(errorToast(apiException(429, 'Upload limit reached.', 'RATE_LIMITED', 3600), 'Upload failed').description)
      .toBe('Upload limit reached. Try again in about 1 hour.');
  });

  it('falls back to a generic wait when no Retry-After is sent', () => {
    const props = errorToast(apiException(429, '', 'RATE_LIMITED'), 'Upload failed');

    expect(props.title).toBe('Rate limit reached');
    expect(props.description).toBe('Too many requests. Please wait a moment and try again.');
    expect(props.variant).toBe('destructive');
  });

  it('keeps the caller title and error message for non-429 ApiExceptions', () => {
    const props = errorToast(apiException(409, 'Document already exists', 'CONFLICT'), 'Upload failed');

    expect(props).toEqual({
      title: 'Upload failed',
      description: 'Document already exists',
      variant: 'destructive',
    });
  });

  it('uses the fallback title for plain errors without a message', () => {
    const props = errorToast(new Error('network down'), 'Failed to send');

    expect(props).toEqual({
      title: 'Failed to send',
      description: 'network down',
      variant: 'destructive',
    });
  });

  it('handles non-Error values', () => {
    const props = errorToast('boom', 'Upload failed');

    expect(props).toEqual({
      title: 'Upload failed',
      description: undefined,
      variant: 'destructive',
    });
  });
});

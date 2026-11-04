import { describe, expect, it } from 'vitest';
import { ApiException } from './api';
import { errorToast } from './errors';

function apiException(status: number, message: string, code = 'ERROR'): ApiException {
  return new ApiException({ status, code, message });
}

describe('errorToast', () => {
  it('maps a 429 ApiException to a dedicated rate-limit message', () => {
    const props = errorToast(
      apiException(429, 'AI request limit reached, please try again later', 'AI_RATE_LIMITED'),
      'Failed to send'
    );

    expect(props).toEqual({
      title: 'Rate limit reached',
      description: 'AI request limit reached, please try again later',
      variant: 'destructive',
    });
  });

  it('falls back to a generic rate-limit description when the backend sends none', () => {
    const props = errorToast(apiException(429, '', 'RATE_LIMITED'), 'Upload failed');

    expect(props.title).toBe('Rate limit reached');
    expect(props.description).toBe('Too many requests — please wait a moment and try again.');
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

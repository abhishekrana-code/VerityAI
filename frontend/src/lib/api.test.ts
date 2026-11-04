import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiClient, ApiException } from './api';

type FetchArgs = [input: RequestInfo | URL, init?: RequestInit];

function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: {
      get: (name: string) =>
        name.toLowerCase() === 'content-type' ? 'application/json' : null,
    },
    json: async () => body,
    text: async () => JSON.stringify(body),
  } as unknown as Response;
}

function emptyResponse(status: number): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => null },
    json: async () => ({}),
    text: async () => '',
  } as unknown as Response;
}

function headerOf(init: RequestInit | undefined, name: string): string | undefined {
  const headers = (init?.headers ?? {}) as Record<string, string>;
  return headers[name];
}

describe('ApiClient', () => {
  const fetchMock = vi.fn<(...args: FetchArgs) => Promise<Response>>();

  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('skips CSRF provisioning for GET requests', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { ok: true }));
    const client = new ApiClient('http://api.test/v1');

    const result = await client.get<{ ok: boolean }>('/things');

    expect(result).toEqual({ ok: true });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(String(fetchMock.mock.calls[0][0])).toBe('http://api.test/v1/things');
    expect(fetchMock.mock.calls[0][1]?.method).toBe('GET');
    expect(headerOf(fetchMock.mock.calls[0][1], 'X-XSRF-TOKEN')).toBeUndefined();
  });

  it('provisions a CSRF token before POST and sends it as a header', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, { token: 'token-1' }))
      .mockResolvedValueOnce(jsonResponse(200, { id: 'c1' }));
    const client = new ApiClient('http://api.test/v1');

    const result = await client.post<{ id: string }>('/conversations', { title: 't' });

    expect(result).toEqual({ id: 'c1' });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(String(fetchMock.mock.calls[0][0])).toBe('http://api.test/v1/auth/csrf');
    expect(String(fetchMock.mock.calls[1][0])).toBe('http://api.test/v1/conversations');
    expect(headerOf(fetchMock.mock.calls[1][1], 'X-XSRF-TOKEN')).toBe('token-1');
    expect(fetchMock.mock.calls[1][1]?.body).toBe(JSON.stringify({ title: 't' }));
  });

  it('re-provisions the token and retries once after a 403', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, { token: 'token-1' }))
      .mockResolvedValueOnce(jsonResponse(403, { code: 'FORBIDDEN', message: 'Invalid CSRF token' }))
      .mockResolvedValueOnce(jsonResponse(200, { token: 'token-2' }))
      .mockResolvedValueOnce(jsonResponse(200, { id: 'c2' }));
    const client = new ApiClient('http://api.test/v1');

    const result = await client.post<{ id: string }>('/conversations', { title: 't' });

    expect(result).toEqual({ id: 'c2' });
    expect(fetchMock).toHaveBeenCalledTimes(4);
    expect(headerOf(fetchMock.mock.calls[1][1], 'X-XSRF-TOKEN')).toBe('token-1');
    expect(headerOf(fetchMock.mock.calls[3][1], 'X-XSRF-TOKEN')).toBe('token-2');
  });

  it('still sends the request when CSRF provisioning fails', async () => {
    fetchMock
      .mockRejectedValueOnce(new TypeError('network down'))
      .mockResolvedValueOnce(jsonResponse(200, { ok: true }));
    const client = new ApiClient('http://api.test/v1');

    const result = await client.post<{ ok: boolean }>('/things', { a: 1 });

    expect(result).toEqual({ ok: true });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(headerOf(fetchMock.mock.calls[1][1], 'X-XSRF-TOKEN')).toBeUndefined();
  });

  it('does not retry when a 403 persists after re-provisioning', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, { token: 'token-1' }))
      .mockResolvedValueOnce(jsonResponse(403, { code: 'FORBIDDEN', message: 'nope' }))
      .mockResolvedValueOnce(jsonResponse(200, { token: 'token-2' }))
      .mockResolvedValueOnce(jsonResponse(403, { code: 'FORBIDDEN', message: 'nope' }));
    const client = new ApiClient('http://api.test/v1');

    await expect(client.post('/conversations', { title: 't' })).rejects.toMatchObject({
      status: 403,
      code: 'FORBIDDEN',
    });
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });

  it('throws ApiException carrying status and code on error responses', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(401, { code: 'UNAUTHENTICATED', message: 'Invalid credentials' })
    );
    const client = new ApiClient('http://api.test/v1');

    const error = await client.get('/me').catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiException);
    const apiError = error as ApiException;
    expect(apiError.status).toBe(401);
    expect(apiError.code).toBe('UNAUTHENTICATED');
    expect(apiError.message).toBe('Invalid credentials');
  });

  it('resolves undefined for 204 responses', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, { token: 'token-1' }))
      .mockResolvedValueOnce(emptyResponse(204));
    const client = new ApiClient('http://api.test/v1');

    await expect(client.delete('/things/1')).resolves.toBeUndefined();
  });
});

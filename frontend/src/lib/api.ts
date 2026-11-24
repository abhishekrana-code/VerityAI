import type { ApiError } from '@/types/api';

export * from '@/types/api';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api/v1';

export class ApiClient {
  private baseUrl: string;
  private csrfToken: string | null = null;

  constructor(baseUrl: string = API_BASE_URL) {
    this.baseUrl = baseUrl;
  }

  setCsrfToken(token: string) {
    this.csrfToken = token;
  }

  private isMutating(method?: string): boolean {
    return ['POST', 'PUT', 'PATCH', 'DELETE'].includes(method || 'GET');
  }

  private async ensureCsrf(): Promise<void> {
    if (this.csrfToken) return;
    try {
      const res = await fetch(`${this.baseUrl}/auth/csrf`, { credentials: 'include' });
      if (res.ok) {
        const data = await res.json();
        this.csrfToken = data.token ?? null;
      }
    } catch {
      return;
    }
  }

  private async request<T>(
    endpoint: string,
    options: RequestInit = {}
  ): Promise<T> {
    const url = `${this.baseUrl}${endpoint}`;
    const mutating = this.isMutating(options.method);

    if (mutating) {
      await this.ensureCsrf();
    }

    const headers: HeadersInit = {
      'Content-Type': 'application/json',
      ...options.headers,
    };

    if (mutating && this.csrfToken) {
      (headers as Record<string, string>)['X-XSRF-TOKEN'] = this.csrfToken;
    }

    let response = await fetch(url, {
      ...options,
      headers,
      credentials: 'include',
    });

    if (mutating && response.status === 403) {
      this.csrfToken = null;
      await this.ensureCsrf();
      const retryHeaders: Record<string, string> = { ...(headers as Record<string, string>) };
      if (this.csrfToken) {
        retryHeaders['X-XSRF-TOKEN'] = this.csrfToken;
      } else {
        delete retryHeaders['X-XSRF-TOKEN'];
      }
      response = await fetch(url, {
        ...options,
        headers: retryHeaders,
        credentials: 'include',
      });
    }

    if (response.status === 204) {
      return undefined as T;
    }

    const contentType = response.headers.get('content-type');
    const isJson = contentType?.includes('application/json');

    if (!response.ok) {
      const errorData = isJson ? await response.json() : { message: await response.text() };
      const apiError: ApiError = {
        timestamp: new Date().toISOString(),
        status: response.status,
        code: errorData.code || 'ERROR',
        message: errorData.message || 'An error occurred',
        path: endpoint,
        requestId: response.headers.get('X-Request-ID') || '',
        fieldErrors: errorData.fieldErrors,
      };
      throw new ApiException(apiError, parseRetryAfter(response.headers.get('Retry-After')));
    }

    if (!isJson) {
      return undefined as T;
    }

    return response.json();
  }

  async get<T>(endpoint: string): Promise<T> {
    return this.request<T>(endpoint, { method: 'GET' });
  }

  async post<T>(endpoint: string, data?: unknown): Promise<T> {
    return this.request<T>(endpoint, {
      method: 'POST',
      body: data ? JSON.stringify(data) : undefined,
    });
  }

  /**
   * POST that consumes a server-sent-event answer stream. `onToken` fires as
   * text arrives; `onDone` receives the stored message (citations, evidence
   * status, usage), which is exactly what the non-streaming endpoint returns.
   * Resolves after the `done` event.
   */
  async streamAnswer<T>(
    endpoint: string,
    data: unknown,
    handlers: { onToken: (text: string) => void; onDone: (message: T) => void },
  ): Promise<void> {
    const url = `${this.baseUrl}${endpoint}`;
    await this.ensureCsrf();

    const send = () =>
      fetch(url, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'text/event-stream',
          ...(this.csrfToken ? { 'X-XSRF-TOKEN': this.csrfToken } : {}),
        },
        body: JSON.stringify(data),
        credentials: 'include',
      });

    let response = await send();
    if (response.status === 403) {
      this.csrfToken = null;
      await this.ensureCsrf();
      response = await send();
    }

    if (!response.ok || !response.body) {
      let errorData: { code?: string; message?: string; fieldErrors?: ApiError['fieldErrors'] } = {};
      try {
        errorData = await response.json();
      } catch {
        // Non-JSON error body; fall back to the generic message below.
      }
      throw new ApiException(
        {
          timestamp: new Date().toISOString(),
          status: response.status,
          code: errorData.code || 'ERROR',
          message: errorData.message || 'The answer could not be generated',
          path: endpoint,
          requestId: response.headers.get('X-Request-ID') || '',
          fieldErrors: errorData.fieldErrors,
        },
        parseRetryAfter(response.headers.get('Retry-After')),
      );
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    const handleBlock = (block: string) => {
      let event = 'message';
      const dataLines: string[] = [];
      for (const line of block.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim();
        else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''));
      }
      if (dataLines.length === 0) return;
      const payload = dataLines.join('\n');
      if (event === 'token') {
        handlers.onToken((JSON.parse(payload) as { text?: string }).text ?? '');
      } else if (event === 'done') {
        handlers.onDone(JSON.parse(payload) as T);
      } else if (event === 'error') {
        const parsed = JSON.parse(payload) as { message?: string };
        throw new Error(parsed.message || 'The answer could not be generated');
      }
    };

    try {
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer = (buffer + decoder.decode(value, { stream: true })).replace(/\r\n/g, '\n');
        let boundary: number;
        while ((boundary = buffer.indexOf('\n\n')) !== -1) {
          const block = buffer.slice(0, boundary);
          buffer = buffer.slice(boundary + 2);
          handleBlock(block);
        }
      }
    } finally {
      reader.releaseLock();
    }
  }

  async patch<T>(endpoint: string, data?: unknown): Promise<T> {
    return this.request<T>(endpoint, {
      method: 'PATCH',
      body: data ? JSON.stringify(data) : undefined,
    });
  }

  async delete<T>(endpoint: string, data?: unknown): Promise<T> {
    return this.request<T>(endpoint, {
      method: 'DELETE',
      body: data ? JSON.stringify(data) : undefined,
    });
  }

  async upload<T>(endpoint: string, formData: FormData): Promise<T> {
    const url = `${this.baseUrl}${endpoint}`;
    await this.ensureCsrf();
    const headers: Record<string, string> = {};
    if (this.csrfToken) {
      headers['X-XSRF-TOKEN'] = this.csrfToken;
    }

    let response = await fetch(url, {
      method: 'POST',
      headers,
      body: formData,
      credentials: 'include',
    });

    if (response.status === 403) {
      this.csrfToken = null;
      await this.ensureCsrf();
      if (this.csrfToken) {
        headers['X-XSRF-TOKEN'] = this.csrfToken;
      } else {
        delete headers['X-XSRF-TOKEN'];
      }
      response = await fetch(url, {
        method: 'POST',
        headers,
        body: formData,
        credentials: 'include',
      });
    }

    if (response.status === 204) {
      return undefined as T;
    }

    const contentType = response.headers.get('content-type');
    const isJson = contentType?.includes('application/json');

    if (!response.ok) {
      const errorData = isJson ? await response.json() : { message: await response.text() };
      const apiError: ApiError = {
        timestamp: new Date().toISOString(),
        status: response.status,
        code: errorData.code || 'ERROR',
        message: errorData.message || 'An error occurred',
        path: endpoint,
        requestId: response.headers.get('X-Request-ID') || '',
        fieldErrors: errorData.fieldErrors,
      };
      throw new ApiException(apiError, parseRetryAfter(response.headers.get('Retry-After')));
    }

    if (!isJson) {
      return undefined as T;
    }

    return response.json();
  }

  /**
   * Upload with progress reporting (XHR — fetch has no upload progress events).
   * Reuses the CSRF cookie/header contract, including one retry on 403.
   */
  async uploadWithProgress<T>(
    endpoint: string,
    formData: FormData,
    onProgress?: (percent: number) => void
  ): Promise<T> {
    const url = `${this.baseUrl}${endpoint}`;
    await this.ensureCsrf();

    const send = (): Promise<XMLHttpRequest> =>
      new Promise((resolve, reject) => {
        const xhr = new XMLHttpRequest();
        xhr.open('POST', url);
        xhr.withCredentials = true;
        if (this.csrfToken) {
          xhr.setRequestHeader('X-XSRF-TOKEN', this.csrfToken);
        }
        xhr.upload.onprogress = (e) => {
          if (e.lengthComputable && onProgress) {
            onProgress(Math.round((e.loaded / e.total) * 100));
          }
        };
        xhr.onload = () => resolve(xhr);
        xhr.onerror = () => reject(new NetworkUploadError());
        xhr.send(formData);
      });

    let xhr = await send();
    if (xhr.status === 403) {
      this.csrfToken = null;
      await this.ensureCsrf();
      xhr = await send();
    }

    if (xhr.status === 204) {
      return undefined as T;
    }

    const contentType = xhr.getResponseHeader('content-type');
    const isJson = contentType?.includes('application/json');
    const body = xhr.responseText;

    if (xhr.status < 200 || xhr.status >= 300) {
      let errorData: Record<string, unknown> = {};
      if (isJson) {
        try {
          errorData = JSON.parse(body);
        } catch {
          errorData = {};
        }
      }
      const apiError: ApiError = {
        timestamp: new Date().toISOString(),
        status: xhr.status,
        code: (errorData.code as string) || 'ERROR',
        message: (errorData.message as string) || 'An error occurred',
        path: endpoint,
        requestId: xhr.getResponseHeader('X-Request-ID') || '',
        fieldErrors: errorData.fieldErrors as ApiError['fieldErrors'],
      };
      throw new ApiException(
        apiError,
        parseRetryAfter(xhr.getResponseHeader('Retry-After')),
      );
    }

    if (!isJson) {
      return undefined as T;
    }
    return JSON.parse(body) as T;
  }
}

class NetworkUploadError extends Error {
  constructor() {
    super('Network error during upload');
    this.name = 'NetworkUploadError';
  }
}

export class ApiException extends Error {
  constructor(
    public readonly error: ApiError,
    /** Seconds to wait before retrying (from Retry-After on 429s), when sent. */
    public readonly retryAfterSeconds: number | null = null,
  ) {
    super(error.message);
    this.name = 'ApiException';
  }

  get status(): number {
    return this.error.status;
  }

  get code(): string {
    return this.error.code;
  }

  get fieldErrors(): ApiError['fieldErrors'] {
    return this.error.fieldErrors;
  }
}

function parseRetryAfter(value: string | null): number | null {
  if (!value) return null;
  const seconds = Number(value);
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : null;
}

export const api = new ApiClient();
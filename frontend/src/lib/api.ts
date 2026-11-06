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
      throw new ApiException(apiError);
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

  async patch<T>(endpoint: string, data?: unknown): Promise<T> {
    return this.request<T>(endpoint, {
      method: 'PATCH',
      body: data ? JSON.stringify(data) : undefined,
    });
  }

  async delete<T>(endpoint: string): Promise<T> {
    return this.request<T>(endpoint, { method: 'DELETE' });
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
      throw new ApiException(apiError);
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
      throw new ApiException(apiError);
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
  constructor(public readonly error: ApiError) {
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

export const api = new ApiClient();
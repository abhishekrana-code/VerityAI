import type { ApiError } from '@/types/api';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api/v1';

class ApiClient {
  private baseUrl: string;
  private csrfToken: string | null = null;

  constructor(baseUrl: string = API_BASE_URL) {
    this.baseUrl = baseUrl;
  }

  setCsrfToken(token: string) {
    this.csrfToken = token;
  }

  private async request<T>(
    endpoint: string,
    options: RequestInit = {}
  ): Promise<T> {
    const url = `${this.baseUrl}${endpoint}`;
    const headers: HeadersInit = {
      'Content-Type': 'application/json',
      ...options.headers,
    };

    if (this.csrfToken && ['POST', 'PUT', 'PATCH', 'DELETE'].includes(options.method || 'GET')) {
      (headers as Record<string, string>)['X-XSRF-TOKEN'] = this.csrfToken;
    }

    const response = await fetch(url, {
      ...options,
      headers,
      credentials: 'include',
    });

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
    const headers: HeadersInit = {};
    if (this.csrfToken) {
      (headers as Record<string, string>)['X-XSRF-TOKEN'] = this.csrfToken;
    }

    const response = await fetch(url, {
      method: 'POST',
      headers,
      body: formData,
      credentials: 'include',
    });

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
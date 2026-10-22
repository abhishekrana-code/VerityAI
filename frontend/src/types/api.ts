export interface User {
  id: string;
  email: string;
  displayName: string | null;
  role: string;
  createdAt: string;
  lastLoginAt: string | null;
}

export interface AuthResponse {
  user: User;
  authenticated: boolean;
}

export interface CsrfResponse {
  token: string;
  headerName: string;
  parameterName: string;
}

export interface Document {
  id: string;
  originalFilename: string;
  mimeType: string;
  sizeBytes: number;
  pageCount: number | null;
  status: 'UPLOADED' | 'PROCESSING' | 'READY' | 'FAILED';
  errorCode: string | null;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
  chunkCount?: number;
}

export interface DocumentListResponse {
  content: Document[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface DocumentUploadResponse {
  id: string;
  originalFilename: string;
  status: 'UPLOADED' | 'PROCESSING' | 'READY' | 'FAILED';
}

export interface Conversation {
  id: string;
  title: string;
  createdAt: string;
  updatedAt: string;
  archivedAt: string | null;
  messageCount: number;
  documents: Array<{ id: string; originalFilename: string }>;
}

export interface ConversationListResponse {
  content: Conversation[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface Message {
  id: string;
  role: 'USER' | 'ASSISTANT';
  content: string;
  status: 'COMPLETED' | 'FAILED';
  model: string | null;
  promptTokens: number | null;
  completionTokens: number | null;
  latencyMs: number | null;
  evidenceStatus: 'SUFFICIENT' | 'INSUFFICIENT' | 'NONE' | null;
  createdAt: string;
  citations: Citation[];
}

export interface Citation {
  chunkId: string;
  documentId: string;
  documentName: string;
  pageNumber: number;
  excerpt: string;
  rank: number;
  citationIndex: number;
}

export interface MessageListResponse {
  content: Message[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface CreateConversationRequest {
  title: string;
  documentIds?: string[];
}

export interface UpdateConversationRequest {
  title?: string;
  documentIds?: string[];
  archived?: boolean;
}

export interface AskQuestionRequest {
  question: string;
  documentIds?: string[];
}

export interface ApiError {
  timestamp: string;
  status: number;
  code: string;
  message: string;
  path: string;
  requestId: string;
  fieldErrors?: Array<{
    field: string;
    message: string;
    rejectedValue: unknown;
  }>;
}

export interface PaginationParams {
  page?: number;
  size?: number;
  sort?: string;
}

export interface DocumentListParams extends PaginationParams {
  q?: string;
  status?: string;
}
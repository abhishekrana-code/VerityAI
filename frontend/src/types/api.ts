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

export interface TagRef {
  id: string;
  name: string;
  color: string | null;
}

export interface CollectionRef {
  id: string;
  name: string;
}

export interface Document {
  id: string;
  originalFilename: string;
  mimeType: string;
  sizeBytes: number;
  pageCount: number | null;
  status: 'UPLOADED' | 'PROCESSING' | 'READY' | 'FAILED';
  sha256: string;
  errorCode: string | null;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
  chunkCount?: number;
  /** AI summary lifecycle: NONE | PENDING | READY | FAILED */
  summaryStatus?: 'NONE' | 'PENDING' | 'READY' | 'FAILED';
  summaryError?: string | null;
  /** Latest persistent processing stage (e.g. UPLOADED, QUEUED, EXTRACTING, CHUNKING, INDEXING, COMPLETED, FAILED) */
  processingStage?: string | null;
  tags?: TagRef[];
  collections?: CollectionRef[];
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

// ---------------------------------------------------------------------------
// Phase 1: processing history, summaries, tags, collections, bulk delete
// ---------------------------------------------------------------------------

export type ProcessingStage =
  | 'UPLOADED' | 'QUEUED' | 'EXTRACTING' | 'CHUNKING'
  | 'INDEXING' | 'COMPLETED' | 'FAILED';

export interface ProcessingEvent {
  id: string;
  attempt: number;
  stage: ProcessingStage;
  errorCode: string | null;
  errorMessage: string | null;
  createdAt: string;
}

export interface ProcessingHistoryResponse {
  documentId: string;
  currentStage: ProcessingStage;
  currentAttempt: number;
  events: ProcessingEvent[];
}

export type SummaryKind =
  | 'EXECUTIVE_SUMMARY' | 'KEY_FINDINGS' | 'ENTITIES'
  | 'DATES_AND_STATS' | 'TOPICS' | 'SUGGESTED_QUESTIONS';

export interface SummaryFinding {
  text: string;
  /** FACT = stated in the document, INTERPRETATION = a reading of it */
  type?: 'FACT' | 'INTERPRETATION';
}

export interface SummarySection {
  id: string;
  kind: SummaryKind;
  /** EXTRACTED = pulled from the text, GENERATED = model-written prose */
  source: 'EXTRACTED' | 'GENERATED';
  content: Record<string, unknown>;
  model: string | null;
  generatedAt: string;
}

export interface DocumentSummariesResponse {
  documentId: string;
  summaryStatus: 'NONE' | 'PENDING' | 'READY' | 'FAILED';
  summaryError: string | null;
  summaries: SummarySection[];
}

export interface Tag {
  id: string;
  name: string;
  color: string | null;
  createdAt: string;
  documentCount: number;
}

export interface Collection {
  id: string;
  name: string;
  description: string | null;
  createdAt: string;
  updatedAt: string;
  documentCount: number;
}

export interface BulkDeleteResponse {
  deleted: number;
  skippedIds: string[];
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

export interface RegisterRequest {
  email: string;
  password: string;
  displayName?: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

export interface UpdateProfileRequest {
  displayName?: string;
}
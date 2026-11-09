export interface User {
  id: string;
  email: string;
  displayName: string | null;
  role: string;
  createdAt: string;
  lastLoginAt: string | null;
}

export interface AuthResponse {
  /** null for a generic registration response and for anonymous /auth/me. */
  user: User | null;
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

export type VerificationStatus = 'NOT_REQUESTED' | 'COMPLETED' | 'PARTIAL' | 'FAILED';

/** AI grounding assessment of a claim against its cited evidence (not a probability). */
export type SupportClass = 'SUPPORTED' | 'PARTIALLY_SUPPORTED' | 'UNSUPPORTED' | 'CONFLICTING';

export interface ClaimResult {
  claimIndex: number;
  sentenceIndex: number;
  claimText: string;
  supportClass: SupportClass | null;
  explanation: string | null;
  /** 0-based citation indexes this claim relies on. */
  citationIndexes: number[];
  hasInvalidCitation: boolean;
}

export interface InvalidReference {
  claimIndex: number;
  claimText: string;
  /** Out-of-range S number as written in the text (1-based). */
  reference: number;
}

export interface ClaimVerificationResponse {
  messageId: string;
  status: VerificationStatus;
  /** Deterministic fraction of analyzed claims citing ≥1 valid source. */
  coverage: number | null;
  model: string | null;
  verifiedAt: string | null;
  /** Claims found in the message text (may exceed claims.length when capped). */
  totalClaims: number;
  error: string | null;
  claims: ClaimResult[];
  invalidReferences: InvalidReference[];
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

export interface PasswordResetRequest {
  email: string;
}

export interface PasswordResetConfirmRequest {
  token: string;
  newPassword: string;
}

// ---------------------------------------------------------------------------
// Phase 2: hybrid search (Feature 4) — now surfaced in the UI
// ---------------------------------------------------------------------------

export interface SearchRequest {
  query: string;
  documentIds?: string[];
  limit?: number;
}

export type SearchStrategy = 'keyword' | 'semantic' | 'hybrid';

export interface SearchHit {
  chunkId: string;
  documentId: string;
  documentName: string;
  pageNumber: number | null;
  chunkIndex: number | null;
  content: string;
  excerpt: string;
  rank: number;
  similarity: number | null;
  keywordMatch: boolean;
  semanticMatch: boolean;
}

export interface SearchResponse {
  query: string;
  strategy: SearchStrategy;
  model: string | null;
  count: number;
  results: SearchHit[];
}

// ---------------------------------------------------------------------------
// Phase 3: knowledge graph (Feature 7)
// ---------------------------------------------------------------------------

export type KnowledgeEntityType =
  | 'PERSON' | 'ORGANIZATION' | 'LOCATION' | 'PRODUCT' | 'EVENT'
  | 'DATE' | 'METRIC' | 'ACRONYM' | 'CONCEPT' | 'OTHER';

export interface KnowledgeGraphNode {
  id: string;
  name: string;
  type: KnowledgeEntityType;
  mentionCount: number;
  documentId: string;
  documentName: string;
  firstPage: number | null;
  evidenceExcerpt: string | null;
}

export interface KnowledgeGraphEdge {
  id: string;
  source: string;
  target: string;
  relationType: 'CO_OCCURS';
  weight: number;
  documentId: string;
  firstPage: number | null;
  evidenceExcerpt: string | null;
}

export interface KnowledgeGraphResponse {
  documentId: string | null;
  documentName: string | null;
  scope: 'DOCUMENT' | 'LIBRARY';
  status: 'NONE' | 'PENDING' | 'READY' | 'FAILED';
  error: string | null;
  generatedAt: string | null;
  entityCount: number;
  relationCount: number;
  documentCount: number;
  returnedEntities: number;
  truncated: boolean;
  nodes: KnowledgeGraphNode[];
  edges: KnowledgeGraphEdge[];
}

export interface KnowledgeGraphRebuildResponse {
  requested: number;
  rebuilt: number;
  skipped: number;
  rebuiltDocumentIds: string[];
  errors: string[];
}

// ---------------------------------------------------------------------------
// Phase 3: verification / reports / history read models
// ---------------------------------------------------------------------------

export type VerificationListStatus = 'NOT_REQUESTED' | 'PARTIAL' | 'COMPLETED';

export interface VerificationListItem {
  messageId: string;
  conversationId: string;
  conversationTitle: string | null;
  contentExcerpt: string;
  createdAt: string;
  messageModel: string | null;
  evidenceStatus: 'SUFFICIENT' | 'INSUFFICIENT' | 'NONE' | null;
  citationCount: number;
  status: VerificationListStatus;
  coverage: number | null;
  verificationModel: string | null;
  verifiedAt: string | null;
  totalClaims: number;
  analyzedClaims: number;
  classifiedClaims: number;
  invalidReferenceCount: number;
  supportCounts: Record<SupportClass, number>;
}

export interface VerificationListResponse {
  content: VerificationListItem[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface VerificationStatsResponse {
  total: number;
  verified: number;
  partial: number;
  completed: number;
  unverified: number;
}

export type ReportSummaryStatus = 'NONE' | 'PENDING' | 'READY' | 'FAILED';

export interface ReportFinding {
  text: string;
  type: 'FACT' | 'INTERPRETATION';
}

export interface ReportItem {
  documentId: string;
  documentName: string;
  pageCount: number | null;
  updatedAt: string;
  summaryStatus: ReportSummaryStatus;
  summaryError: string | null;
  sectionCount: number;
  model: string | null;
  generatedAt: string | null;
  executiveSummary: string;
  keyFindings: ReportFinding[];
  entityCount: number;
  dateStatCount: number;
  topicCount: number;
  questionCount: number;
}

export interface ReportListResponse {
  content: ReportItem[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface HistoryItem {
  id: string;
  documentId: string;
  documentName: string;
  attempt: number;
  stage: ProcessingStage;
  errorCode: string | null;
  errorMessage: string | null;
  createdAt: string;
}

export interface HistoryListResponse {
  content: HistoryItem[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

// ---------------------------------------------------------------------------
// Phase 3: contradiction detection (Feature 8)
// ---------------------------------------------------------------------------

export type ContradictionType =
  | 'NUMERIC_CONFLICT'
  | 'TEMPORAL_CONFLICT'
  | 'ATTRIBUTE_CONFLICT'
  | 'CAUSAL_CONFLICT'
  | 'POLARITY_CONFLICT';

/** Materiality of the conflict, never a detection probability. */
export type ContradictionSeverity = 'LOW' | 'MEDIUM' | 'HIGH';

export interface ContradictionItem {
  id: string;
  contradictionType: ContradictionType;
  severity: ContradictionSeverity;
  subjectEntity: string | null;
  explanation: string | null;
  model: string | null;
  detectedAt: string;

  claimA: string;
  documentAId: string;
  documentAName: string;
  pageA: number | null;
  chunkAId: string | null;

  claimB: string;
  documentBId: string;
  documentBName: string;
  pageB: number | null;
  chunkBId: string | null;
}

export interface ContradictionListResponse {
  content: ContradictionItem[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface ContradictionStatsResponse {
  total: number;
  byType: Record<ContradictionType, number>;
  bySeverity: Record<ContradictionSeverity, number>;
  lastDetectedAt: string | null;
}

export interface ContradictionDetectionResponse {
  status: 'COMPLETED' | 'FAILED' | 'SKIPPED';
  scope: 'DOCUMENT' | 'LIBRARY';
  analyzedDocumentIds: string[];
  analyzedDocuments: number;
  analyzedClaims: number;
  candidatePairs: number;
  found: number;
  stored: number;
  model: string | null;
  detectedAt: string | null;
  error: string | null;
}
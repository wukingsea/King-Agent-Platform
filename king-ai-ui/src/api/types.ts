export interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

export interface PageResult<T> {
  list: T[]
  total: number
  page: number
  size: number
  totalPages: number
  hasNext: boolean
  hasPrevious: boolean
}

export interface WorkspaceStatsView {
  agents: number
  publishedAgents: number
  knowledgeBases: number
  documents: number
  chunks: number
  sessions: number
  mcpServers: number
  skills: number
  suppliers: number
  models: number
}

export interface LoginRequest {
  username: string
  password: string
}

export interface UserView {
  id: number
  username: string
  nickname?: string
  email?: string
  role: 'ADMIN' | 'USER' | string
  status: number
  spaceIds: number[]
  mustChangePassword?: number
  createdAt: string
}

export type SpaceRole = 'OWNER' | 'ADMIN' | 'EDITOR' | 'VIEWER' | 'AUDITOR'

export interface SpaceView {
  id: number
  name: string
  code: string
  description?: string
  status: number
  role?: SpaceRole
  createdAt: string
}

export interface SpaceMemberView {
  userId: number
  username: string
  nickname?: string
  role: SpaceRole
}

export interface SpaceMemberUpdate {
  userId: number
  role: SpaceRole
}

export interface AuthResponse {
  token?: string
  user: UserView | null
  spaces: SpaceView[]
  currentSpaceId?: number
  mfaRequired?: boolean
  mfaChallenge?: string | null
}

export interface ChangePasswordRequest {
  oldPassword: string
  newPassword: string
}

export interface SsoConfig {
  enabled: boolean
  loginUrl?: string | null
  issuer?: string | null
}

export interface MfaStatusView {
  enabled: boolean
  pending: boolean
  enrolledAt?: string | null
  recoveryCodesRemaining: number
}

export interface MfaSetupResponse {
  secret: string
  otpauthUrl: string
}

export interface MfaEnableResponse {
  recoveryCodes: string[]
}

export interface AgentView {
  id: number
  spaceId: number
  name: string
  intro?: string
  modelId: number
  modelName: string
  modelIdentifier: string
  systemPrompt?: string
  temperature?: number
  maxTokens?: number
  publishStatus: number
  publishedVersion?: number
  publicId?: string
  status: number
  allowedTools?: string[]
  toolApprovalMode?: string
  fallbackModelId?: number | null
  knowledgeBaseIds: number[]
  mcpIds: number[]
  skillIds: number[]
  createdAt: string
  updatedAt?: string
}

export type ReleaseStatus =
  | 'DRAFT'
  | 'PENDING_REVIEW'
  | 'APPROVED'
  | 'CANARY'
  | 'PUBLISHED'
  | 'REJECTED'
  | 'ARCHIVED'

export interface AgentVersionView {
  versionNo: number
  status: ReleaseStatus
  publishedAt: string
  publishedBy?: number
  requestedBy?: number | null
  requestedAt?: string | null
  approvedBy?: number | null
  approvedAt?: string | null
  reviewComment?: string | null
  canaryPercent?: number | null
  evalTotal?: number | null
  evalPassed?: number | null
  evalScore?: number | null
  evalSummary?: string | null
  evalRunAt?: string | null
  configHash?: string | null
  current: boolean
}

export interface AgentEvalCaseView {
  id: number
  agentId: number
  name: string
  prompt: string
  expectedKeywords: string
  weight: number
  status: number
  createdAt: string
}

export interface AgentEvalCaseSaveRequest {
  name: string
  prompt: string
  expectedKeywords: string
  weight: number
  status: number
}

export interface AgentOption {
  id: number
  name: string
}

export interface AgentSaveRequest {
  name: string
  intro?: string
  modelId: number
  systemPrompt?: string
  temperature?: number
  maxTokens?: number
  status?: number
  allowedTools?: string[]
  toolApprovalMode?: string
  fallbackModelId?: number | null
  knowledgeBaseIds?: number[]
  mcpIds?: number[]
  skillIds?: number[]
}

export interface PublicAgentView {
  publicId: string
  name: string
  intro?: string
}

export interface SupplierView {
  id: number
  spaceId: number
  name: string
  type: string
  baseUrl: string
  apiKeyMasked: string
  hasApiKey: boolean
  headersConfigured: boolean
  status: number
  remark?: string
  modelCount: number
  createdAt: string
}

export interface SupplierSaveRequest {
  name: string
  type: string
  baseUrl?: string
  apiKey?: string
  headers?: string
  status?: number
  remark?: string
}

export interface SupplierTestResult {
  ok: boolean
  message: string
  models: string[]
}

export interface ModelView {
  id: number
  spaceId: number
  supplierId: number
  supplierName: string
  name: string
  model: string
  type: 'CHAT' | 'EMBEDDING' | string
  dimension: number
  status: number
  usageCount: number
  createdAt: string
}

export interface ModelOption {
  id: number
  name: string
  model: string
  type: string
  dimension: number
  supplierName: string
}

export interface ModelCreateRequest {
  supplierId: number
  name: string
  model: string
  type: string
  dimension?: number
  status?: number
}

export interface ModelUpdateRequest {
  name: string
  model: string
  type: string
  dimension?: number
  status?: number
}

export interface ModelImportRequest {
  models: string[]
  type?: string
  dimension?: number
}

export interface KnowledgeBaseView {
  id: number
  spaceId: number
  name: string
  description?: string
  embeddingModelId: number
  embeddingModelName: string
  chunkSize: number
  chunkOverlap: number
  topK: number
  vectorCollection: string
  embeddingDim: number
  docCount: number
  chunkCount: number
  status: number
  createdAt: string
}

export interface KnowledgeBaseSaveRequest {
  name: string
  description?: string
  embeddingModelId: number
  chunkSize?: number
  chunkOverlap?: number
  topK?: number
  status?: number
}

export interface KnowledgeDocView {
  id: number
  knowledgeBaseId: number
  name: string
  sourceType: string
  chunkCount: number
  status: string
  errorMessage?: string
  charCount: number
  allowedRoles?: string[]
  createdAt: string
  updatedAt: string
}

export interface KnowledgeDocDetail extends KnowledgeDocView {
  content?: string
}

export interface KnowledgeChunkView {
  id: number
  chunkIndex: number
  charCount: number
  content: string
}

export interface KnowledgeSearchResult {
  chunkId: number
  docId: number
  docName: string
  chunkIndex: number
  content: string
  score: number
}

export interface KnowledgeDocTextRequest {
  name: string
  content: string
}

export type McpAuthType = 'NONE' | 'OAUTH2'

export interface McpView {
  id: number
  spaceId: number
  name: string
  transport?: string
  url: string
  headersConfigured: boolean
  authType?: McpAuthType
  oauthTokenUrl?: string
  oauthClientId?: string
  oauthScopes?: string
  clientSecretConfigured?: boolean
  tokenObtained?: boolean
  oauthExpiresAt?: string | null
  status: number
  remark?: string
  agentCount: number
  createdAt: string
}

export interface McpSaveRequest {
  name: string
  transport?: string
  url: string
  headers?: string
  authType?: McpAuthType
  oauthTokenUrl?: string
  oauthClientId?: string
  oauthClientSecret?: string
  oauthScopes?: string
  status?: number
  remark?: string
}

export interface McpToolView {
  name: string
  description?: string
}

export interface McpTestResult {
  ok: boolean
  message: string
  tools: McpToolView[]
}

export interface SkillView {
  id: number
  spaceId: number
  name: string
  description?: string
  version?: string
  status: number
  fileCount: number
  createdAt: string
}

export interface SkillSaveRequest {
  name: string
  description?: string
  version?: string
  status?: number
}

export interface SkillFileView {
  id: number
  parentId?: number
  name: string
  type: number
  content?: string
  locked: number
  sortOrder?: number
  createdAt: string
  updatedAt: string
}

export interface SkillDetail {
  skill: SkillView
  files: SkillFileView[]
}

export interface SkillFileCreateRequest {
  parentId?: number
  name: string
  type?: number
  content?: string
  sortOrder?: number
}

export interface SkillFileUpdateRequest {
  name?: string
  content?: string
  sortOrder?: number
}

export interface ChatSessionView {
  id: number
  agentId: number
  ownerUserId?: number
  agentName?: string
  agentPublicId?: string
  sourceType: 'ADMIN' | 'PUBLIC' | string
  visitorId?: string
  title?: string
  messageCount: number
  lastMessageAt?: string
  createdAt: string
}

export interface ChatMessageView {
  id: number
  sessionId: number
  role: 'user' | 'assistant' | string
  content?: string
  reasoning?: string
  toolTrace?: ToolTraceItem[] | null
  citations?: CitationItem[] | null
  status: string
  model?: string
  promptTokens?: number
  completionTokens?: number
  durationMs?: number
  createdAt: string
}

export interface ChatStopResult {
  stopped: boolean
}

export interface ToolTraceItem {
  id: string
  name: string
  source?: string
  arguments?: string
  result?: string
  error?: boolean
  durationMs?: number
  running?: boolean
}

export interface CitationItem {
  index: number
  chunkId: number
  docId: number
  docName: string
  knowledgeBaseId: number
  knowledgeBaseName: string
  chunkIndex: number
  score: number
  snippet: string
}

export interface ChatMeta {
  requestId: string
  sessionId: number
  agentId: number
  agentName: string
  model: string
  modelName: string
  sourceType: string
}

export interface ChatUsage {
  promptTokens: number
  completionTokens: number
  totalTokens: number
}

export interface ChatDone {
  messageId: number
  sessionId: number
  status: string
  durationMs: number
  stopped: boolean
  truncated?: boolean
  error?: string
}

export interface ToolApprovalEvent {
  requestId: string
  callId: string
  name: string
  source: string
  arguments: string
  expiresInMs: number
}

export interface ChatRetryEvent {
  attempt: number
  reset?: boolean
  message?: string
}

export interface ChatDegradedEvent {
  reason?: string
  message?: string
  model?: string
  reset?: boolean
  contentLength?: number
  reasoningLength?: number
}

export interface ToolCallEvent {
  id: string
  name: string
  arguments: string
  source: string
}

export interface ToolResultEvent {
  id: string
  name: string
  result: string
  error: boolean
  durationMs: number
}

export interface ChatStreamHandlers {
  onMeta?: (meta: ChatMeta) => void
  onCitation?: (citation: CitationItem) => void
  onReasoning?: (delta: string) => void
  onContent?: (delta: string) => void
  onToolCall?: (call: ToolCallEvent) => void
  onToolResult?: (result: ToolResultEvent) => void
  onToolApproval?: (approval: ToolApprovalEvent) => void
  onRetry?: (retry: ChatRetryEvent) => void
  onDegraded?: (degraded: ChatDegradedEvent) => void
  onUsage?: (usage: ChatUsage) => void
  onError?: (message: string) => void
  onDone?: (done: ChatDone) => void
}

export interface UserCreateRequest {
  username: string
  password: string
  nickname?: string
  email?: string
  role: string
  status: number
  spaceIds?: number[]
}

export interface UserUpdateRequest {
  nickname?: string
  email?: string
  role: string
  status: number
  spaceIds?: number[]
}

export interface UserOption {
  id: number
  username: string
  nickname?: string
  role: string
  status: number
}

export interface SpaceCreateRequest {
  name: string
  code: string
  description?: string
  status: number
}

export interface SpaceUpdateRequest {
  name: string
  description?: string
  status: number
}

export interface HealthStatus {
  status: string
  app: string
  time: string
}

export interface PublicMeta {
  demoEnabled: boolean
}

export type LicenseState = 'ACTIVE' | 'EXPIRED' | 'DISABLED'

export interface LicenseStatusView {
  enabled: boolean
  status: LicenseState
  licenseId?: string | null
  customer?: string | null
  edition?: string | null
  issuedAt?: string | null
  expiresAt?: string | null
  limits: {
    maxSpaces: number
    maxUsers: number
    maxAgents: number
  } | null
  usage: {
    spaces: number
    users: number
    agents: number
  }
  features: string[]
}

export type RetentionType = 'SESSION' | 'MESSAGE' | 'AUDIT' | 'EXPORT'

export interface RetentionPolicyView {
  id: number
  resourceType: RetentionType
  retentionDays: number
  enabled: boolean
  archiveBeforeDelete: boolean
}

export interface RetentionPolicyUpdate {
  resourceType: RetentionType
  retentionDays: number
  enabled: boolean
  archiveBeforeDelete: boolean
}

export interface LegalHoldView {
  id: number
  resourceType: RetentionType
  resourceId: number
  reason: string
  until?: string | null
  releasedAt?: string | null
  createdAt: string
}

export interface LegalHoldCreate {
  resourceType: RetentionType
  resourceId: number
  reason: string
  until?: string | null
}

export type ExportType = 'CONVERSATIONS' | 'AUDIT' | 'MEMBERS'
export type ExportFormat = 'JSONL' | 'CSV'
export type ExportStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'EXPIRED'

export interface ExportJobView {
  id: number
  exportType: ExportType
  format: ExportFormat
  status: ExportStatus
  fileName?: string | null
  rowCount: number
  errorMessage?: string | null
  requestedBy?: number | null
  fromAt?: string | null
  toAt?: string | null
  expiresAt?: string | null
  createdAt: string
  completedAt?: string | null
}

export interface ExportCreateRequest {
  type: ExportType
  format: ExportFormat
  from?: string | null
  to?: string | null
}

export type ModerationMode = 'OFF' | 'LOCAL' | 'EXTERNAL' | 'HYBRID'
export type ModerationAction = 'BLOCK' | 'WARN'

export interface ComplianceSettingView {
  piiEnabled: boolean
  moderationMode: ModerationMode
  inputAction: ModerationAction
  outputAction: ModerationAction
  blocklist: string[]
  customPiiPatterns: string[]
}

export interface ComplianceSettingUpdate {
  piiEnabled: boolean
  moderationMode: ModerationMode
  inputAction: ModerationAction
  outputAction: ModerationAction
  blocklist: string
  customPiiPatterns: string
}

export type AccessReviewStatus = 'OPEN' | 'COMPLETED' | 'SUPERSEDED'
export type AccessReviewDecision =
  | 'PENDING'
  | 'CONFIRM'
  | 'ACKNOWLEDGE'
  | 'DOWNGRADE'
  | 'REMOVE'

export interface AccessReviewItemView {
  id: number
  subjectType: string
  subjectId: number
  subjectName: string
  role: string
  lastActivityAt?: string | null
  flags: string[]
  decision: AccessReviewDecision
  decidedBy?: number | null
  decidedAt?: string | null
  note?: string | null
}

export interface AccessReviewView {
  id: number
  spaceId: number
  status: AccessReviewStatus
  summary?: string | null
  generatedBy?: number | null
  generatedAt: string
  completedAt?: string | null
  items: AccessReviewItemView[]
}

export interface AccessReviewDecisionRequest {
  decision: Exclude<AccessReviewDecision, 'PENDING'>
  note?: string
}

export interface AuditLogView {
  id: number
  spaceId?: number | null
  actorType: string
  actorId?: number | null
  actorName?: string | null
  action: string
  resourceType?: string | null
  resourceId?: string | null
  status: string
  detail?: string | null
  ip?: string | null
  userAgent?: string | null
  requestId?: string | null
  createdAt: string
}

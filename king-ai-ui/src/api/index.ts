import { request } from './http'
import { saveBlob } from '@/utils/download'
import type {
  AccessReviewDecisionRequest,
  AccessReviewItemView,
  AccessReviewView,
  AgentEvalCaseSaveRequest,
  AgentEvalCaseView,
  AgentOption,
  AgentSaveRequest,
  AgentVersionView,
  AgentView,
  AuditLogView,
  AuthResponse,
  ChangePasswordRequest,
  ChatMessageView,
  ChatSessionView,
  ChatStopResult,
  ComplianceSettingUpdate,
  ComplianceSettingView,
  ExportCreateRequest,
  ExportJobView,
  HealthStatus,
  LegalHoldCreate,
  LegalHoldView,
  LicenseStatusView,
  MfaEnableResponse,
  MfaSetupResponse,
  MfaStatusView,
  RetentionPolicyUpdate,
  RetentionPolicyView,
  KnowledgeBaseSaveRequest,
  KnowledgeBaseView,
  KnowledgeChunkView,
  KnowledgeDocDetail,
  KnowledgeDocTextRequest,
  KnowledgeDocView,
  KnowledgeSearchResult,
  LoginRequest,
  McpSaveRequest,
  McpTestResult,
  McpView,
  ModelCreateRequest,
  ModelImportRequest,
  ModelOption,
  ModelUpdateRequest,
  ModelView,
  PageResult,
  PublicAgentView,
  PublicMeta,
  SkillDetail,
  SkillFileCreateRequest,
  SkillFileUpdateRequest,
  SkillFileView,
  SkillSaveRequest,
  SkillView,
  SsoConfig,
  SpaceCreateRequest,
  SpaceMemberUpdate,
  SpaceMemberView,
  SpaceUpdateRequest,
  SpaceView,
  SupplierSaveRequest,
  SupplierTestResult,
  SupplierView,
  UserCreateRequest,
  UserOption,
  UserUpdateRequest,
  UserView,
  WorkspaceStatsView,
} from './types'

export const authApi = {
  login: (data: LoginRequest) =>
    request<AuthResponse>({ url: '/auth/login', method: 'post', data }),
  verifyMfa: (challenge: string, code: string) =>
    request<AuthResponse>({
      url: '/auth/mfa/verify',
      method: 'post',
      data: { challenge, code },
    }),
  logout: () => request<void>({ url: '/auth/logout', method: 'post' }),
  me: () => request<AuthResponse>({ url: '/auth/me', method: 'get' }),
  changePassword: (data: ChangePasswordRequest) =>
    request<void>({ url: '/auth/password', method: 'put', data }),
  ssoConfig: () => request<SsoConfig>({ url: '/auth/sso/config', method: 'get' }),
  mfaStatus: () => request<MfaStatusView>({ url: '/auth/mfa', method: 'get' }),
  setupMfa: (password: string) =>
    request<MfaSetupResponse>({
      url: '/auth/mfa/setup',
      method: 'post',
      data: { password },
    }),
  enableMfa: (code: string) =>
    request<MfaEnableResponse>({
      url: '/auth/mfa/enable',
      method: 'post',
      data: { code },
    }),
  disableMfa: (password: string, code: string) =>
    request<void>({
      url: '/auth/mfa/disable',
      method: 'post',
      data: { password, code },
    }),
}

export const workspaceApi = {
  stats: () => request<WorkspaceStatsView>({ url: '/workspace/stats', method: 'get' }),
}

export const agentApi = {
  page: (params: { page?: number; size?: number; keyword?: string } = {}) =>
    request<PageResult<AgentView>>({ url: '/agents', method: 'get', params }),
  options: (keyword?: string) =>
    request<AgentOption[]>({ url: '/agents/options', method: 'get', params: { keyword } }),
  get: (id: number) => request<AgentView>({ url: `/agents/${id}`, method: 'get' }),
  create: (data: AgentSaveRequest) =>
    request<AgentView>({ url: '/agents', method: 'post', data }),
  update: (id: number, data: AgentSaveRequest) =>
    request<AgentView>({ url: `/agents/${id}`, method: 'put', data }),
  remove: (id: number) => request<void>({ url: `/agents/${id}`, method: 'delete' }),
  publish: (id: number) =>
    request<AgentView>({ url: `/agents/${id}/publish`, method: 'post' }),
  unpublish: (id: number) =>
    request<AgentView>({ url: `/agents/${id}/unpublish`, method: 'post' }),
  versions: (id: number) =>
    request<AgentVersionView[]>({ url: `/agents/${id}/versions`, method: 'get' }),
  rollback: (id: number, versionNo: number) =>
    request<AgentView>({
      url: `/agents/${id}/versions/${versionNo}/rollback`,
      method: 'post',
    }),
  publicInfo: (publicId: string) =>
    request<PublicAgentView>({ url: `/public/agents/${publicId}`, method: 'get' }),
}

export const agentReleaseApi = {
  evalCases: (agentId: number) =>
    request<AgentEvalCaseView[]>({
      url: `/agents/${agentId}/release/eval-cases`,
      method: 'get',
    }),
  createEvalCase: (agentId: number, data: AgentEvalCaseSaveRequest) =>
    request<AgentEvalCaseView>({
      url: `/agents/${agentId}/release/eval-cases`,
      method: 'post',
      data,
    }),
  updateEvalCase: (agentId: number, caseId: number, data: AgentEvalCaseSaveRequest) =>
    request<AgentEvalCaseView>({
      url: `/agents/${agentId}/release/eval-cases/${caseId}`,
      method: 'put',
      data,
    }),
  removeEvalCase: (agentId: number, caseId: number) =>
    request<void>({
      url: `/agents/${agentId}/release/eval-cases/${caseId}`,
      method: 'delete',
    }),
  submit: (agentId: number) =>
    request<AgentVersionView>({
      url: `/agents/${agentId}/release/submit`,
      method: 'post',
    }),
  evaluate: (agentId: number, versionNo: number) =>
    request<AgentVersionView>({
      url: `/agents/${agentId}/release/${versionNo}/evaluate`,
      method: 'post',
    }),
  approve: (agentId: number, versionNo: number, comment?: string) =>
    request<AgentVersionView>({
      url: `/agents/${agentId}/release/${versionNo}/approve`,
      method: 'post',
      data: { comment },
    }),
  reject: (agentId: number, versionNo: number, comment: string) =>
    request<AgentVersionView>({
      url: `/agents/${agentId}/release/${versionNo}/reject`,
      method: 'post',
      data: { comment },
    }),
  canary: (agentId: number, versionNo: number, percent: number) =>
    request<AgentVersionView>({
      url: `/agents/${agentId}/release/${versionNo}/canary`,
      method: 'post',
      data: { percent },
    }),
  promote: (agentId: number, versionNo: number) =>
    request<AgentVersionView>({
      url: `/agents/${agentId}/release/${versionNo}/promote`,
      method: 'post',
    }),
}

export const supplierApi = {
  page: (params: { page?: number; size?: number; keyword?: string } = {}) =>
    request<PageResult<SupplierView>>({ url: '/suppliers', method: 'get', params }),
  all: async (keyword?: string) => {
    const result = await request<PageResult<SupplierView>>({
      url: '/suppliers',
      method: 'get',
      params: { keyword, page: 1, size: 200 },
    })
    return result.list
  },
  create: (data: SupplierSaveRequest) =>
    request<SupplierView>({ url: '/suppliers', method: 'post', data }),
  update: (id: number, data: SupplierSaveRequest) =>
    request<SupplierView>({ url: `/suppliers/${id}`, method: 'put', data }),
  remove: (id: number) => request<void>({ url: `/suppliers/${id}`, method: 'delete' }),
  test: (id: number) =>
    request<SupplierTestResult>({ url: `/suppliers/${id}/test`, method: 'post' }),
  importModels: (id: number, data: ModelImportRequest) =>
    request<ModelView[]>({ url: `/suppliers/${id}/import-models`, method: 'post', data }),
}

export const modelApi = {
  list: (params?: { supplierId?: number; type?: string; keyword?: string }) =>
    request<ModelView[]>({ url: '/models', method: 'get', params }),
  options: (type: 'CHAT' | 'EMBEDDING' = 'CHAT') =>
    request<ModelOption[]>({ url: '/models/options', method: 'get', params: { type } }),
  create: (data: ModelCreateRequest) =>
    request<ModelView>({ url: '/models', method: 'post', data }),
  update: (id: number, data: ModelUpdateRequest) =>
    request<ModelView>({ url: `/models/${id}`, method: 'put', data }),
  remove: (id: number) => request<void>({ url: `/models/${id}`, method: 'delete' }),
}

export const knowledgeApi = {
  page: (params: { page?: number; size?: number; keyword?: string } = {}) =>
    request<PageResult<KnowledgeBaseView>>({
      url: '/knowledge-bases',
      method: 'get',
      params,
    }),
  list: async (keyword?: string) => {
    const result = await request<PageResult<KnowledgeBaseView>>({
      url: '/knowledge-bases',
      method: 'get',
      params: { keyword, page: 1, size: 200 },
    })
    return result.list
  },
  get: (id: number) =>
    request<KnowledgeBaseView>({ url: `/knowledge-bases/${id}`, method: 'get' }),
  create: (data: KnowledgeBaseSaveRequest) =>
    request<KnowledgeBaseView>({ url: '/knowledge-bases', method: 'post', data }),
  update: (id: number, data: KnowledgeBaseSaveRequest) =>
    request<KnowledgeBaseView>({ url: `/knowledge-bases/${id}`, method: 'put', data }),
  remove: (id: number) =>
    request<void>({ url: `/knowledge-bases/${id}`, method: 'delete' }),
  docs: (id: number, keyword?: string, page = 1, size = 100) =>
    request<PageResult<KnowledgeDocView>>({
      url: `/knowledge-bases/${id}/docs`,
      method: 'get',
      params: { keyword, page, size },
    }),
  updateDocAcl: (id: number, docId: number, allowedRoles: string[]) =>
    request<KnowledgeDocView>({
      url: `/knowledge-bases/${id}/docs/${docId}/acl`,
      method: 'put',
      data: { allowedRoles },
    }),
  createTextDoc: (id: number, data: KnowledgeDocTextRequest) =>
    request<KnowledgeDocView>({
      url: `/knowledge-bases/${id}/docs/text`,
      method: 'post',
      data,
    }),
  uploadDoc: (id: number, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return request<KnowledgeDocView>({
      url: `/knowledge-bases/${id}/docs/upload`,
      method: 'post',
      data: form,
      timeout: 180_000,
    })
  },
  docDetail: (id: number, docId: number) =>
    request<KnowledgeDocDetail>({
      url: `/knowledge-bases/${id}/docs/${docId}`,
      method: 'get',
    }),
  chunks: (id: number, docId: number, page = 1, size = 50) =>
    request<PageResult<KnowledgeChunkView>>({
      url: `/knowledge-bases/${id}/docs/${docId}/chunks`,
      method: 'get',
      params: { page, size },
    }),
  reindex: (id: number, docId: number) =>
    request<KnowledgeDocView>({
      url: `/knowledge-bases/${id}/docs/${docId}/reindex`,
      method: 'post',
    }),
  removeDoc: (id: number, docId: number) =>
    request<void>({
      url: `/knowledge-bases/${id}/docs/${docId}`,
      method: 'delete',
    }),
  search: (id: number, query: string, topK?: number) =>
    request<KnowledgeSearchResult[]>({
      url: `/knowledge-bases/${id}/search`,
      method: 'post',
      data: { query, topK },
    }),
}

export const mcpApi = {
  list: (keyword?: string) =>
    request<McpView[]>({ url: '/mcp-servers', method: 'get', params: { keyword } }),
  create: (data: McpSaveRequest) =>
    request<McpView>({ url: '/mcp-servers', method: 'post', data }),
  update: (id: number, data: McpSaveRequest) =>
    request<McpView>({ url: `/mcp-servers/${id}`, method: 'put', data }),
  remove: (id: number) =>
    request<void>({ url: `/mcp-servers/${id}`, method: 'delete' }),
  test: (id: number) =>
    request<McpTestResult>({ url: `/mcp-servers/${id}/test`, method: 'post' }),
}

export const skillApi = {
  page: (params: { page?: number; size?: number; keyword?: string } = {}) =>
    request<PageResult<SkillView>>({ url: '/skills', method: 'get', params }),
  list: async (keyword?: string) => {
    const result = await request<PageResult<SkillView>>({
      url: '/skills',
      method: 'get',
      params: { keyword, page: 1, size: 200 },
    })
    return result.list
  },
  get: (id: number) =>
    request<SkillDetail>({ url: `/skills/${id}`, method: 'get' }),
  create: (data: SkillSaveRequest) =>
    request<SkillDetail>({ url: '/skills', method: 'post', data }),
  update: (id: number, data: SkillSaveRequest) =>
    request<SkillView>({ url: `/skills/${id}`, method: 'put', data }),
  remove: (id: number) => request<void>({ url: `/skills/${id}`, method: 'delete' }),
  createFile: (id: number, data: SkillFileCreateRequest) =>
    request<SkillFileView>({ url: `/skills/${id}/files`, method: 'post', data }),
  updateFile: (id: number, fileId: number, data: SkillFileUpdateRequest) =>
    request<SkillFileView>({
      url: `/skills/${id}/files/${fileId}`,
      method: 'put',
      data,
    }),
  removeFile: (id: number, fileId: number) =>
    request<void>({ url: `/skills/${id}/files/${fileId}`, method: 'delete' }),
}

export const userApi = {
  list: (params: { page?: number; size?: number; keyword?: string }) =>
    request<PageResult<UserView>>({ url: '/users', method: 'get', params }),
  options: () => request<UserOption[]>({ url: '/users/options', method: 'get' }),
  create: (data: UserCreateRequest) =>
    request<UserView>({ url: '/users', method: 'post', data }),
  update: (id: number, data: UserUpdateRequest) =>
    request<UserView>({ url: `/users/${id}`, method: 'put', data }),
  resetPassword: (id: number, newPassword: string) =>
    request<void>({
      url: `/users/${id}/password`,
      method: 'put',
      data: { newPassword },
    }),
  remove: (id: number) => request<void>({ url: `/users/${id}`, method: 'delete' }),
}

export const spaceApi = {
  list: () => request<SpaceView[]>({ url: '/spaces', method: 'get' }),
  create: (data: SpaceCreateRequest) =>
    request<SpaceView>({ url: '/spaces', method: 'post', data }),
  update: (id: number, data: SpaceUpdateRequest) =>
    request<SpaceView>({ url: `/spaces/${id}`, method: 'put', data }),
  remove: (id: number) => request<void>({ url: `/spaces/${id}`, method: 'delete' }),
  members: (id: number) =>
    request<SpaceMemberView[]>({ url: `/spaces/${id}/members`, method: 'get' }),
  setMembers: (id: number, members: SpaceMemberUpdate[]) =>
    request<void>({
      url: `/spaces/${id}/members`,
      method: 'put',
      data: { members },
    }),
}

export const chatApi = {
  sessions: (params: {
    agentId?: number
    sourceType?: string
    keyword?: string
    page?: number
    size?: number
  }) =>
    request<PageResult<ChatSessionView>>({
      url: '/chat/sessions',
      method: 'get',
      params,
    }),
  messages: (sessionId: number) =>
    request<ChatMessageView[]>({
      url: `/chat/sessions/${sessionId}/messages`,
      method: 'get',
    }),
  rename: (sessionId: number, title: string) =>
    request<ChatSessionView>({
      url: `/chat/sessions/${sessionId}`,
      method: 'put',
      data: { title },
    }),
  remove: (sessionId: number) =>
    request<void>({ url: `/chat/sessions/${sessionId}`, method: 'delete' }),
  stop: (requestId: string) =>
    request<ChatStopResult>({
      url: `/chat/requests/${requestId}/stop`,
      method: 'post',
    }),
  approveTool: (requestId: string, callId: string, approved: boolean) =>
    request<boolean>({
      url: `/chat/requests/${requestId}/tool-approvals/${callId}`,
      method: 'post',
      data: { approved },
    }),
}

export const publicChatApi = {
  sessions: (publicId: string) =>
    request<ChatSessionView[]>({
      url: '/public/chat/sessions',
      method: 'get',
      params: { publicId },
    }),
  messages: (publicId: string, sessionId: number) =>
    request<ChatMessageView[]>({
      url: `/public/chat/sessions/${sessionId}/messages`,
      method: 'get',
      params: { publicId },
    }),
  stop: (requestId: string) =>
    request<ChatStopResult>({
      url: `/public/chat/requests/${requestId}/stop`,
      method: 'post',
    }),
  approveTool: (requestId: string, callId: string, approved: boolean) =>
    request<boolean>({
      url: `/public/chat/requests/${requestId}/tool-approvals/${callId}`,
      method: 'post',
      data: { approved },
    }),
}

export const complianceApi = {
  settings: () =>
    request<ComplianceSettingView>({ url: '/compliance-settings', method: 'get' }),
  updateSettings: (data: ComplianceSettingUpdate) =>
    request<ComplianceSettingView>({
      url: '/compliance-settings',
      method: 'put',
      data,
    }),
  retentionPolicies: () =>
    request<RetentionPolicyView[]>({
      url: '/compliance/retention-policies',
      method: 'get',
    }),
  upsertRetentionPolicy: (data: RetentionPolicyUpdate) =>
    request<RetentionPolicyView>({
      url: '/compliance/retention-policies',
      method: 'put',
      data,
    }),
  legalHolds: () =>
    request<LegalHoldView[]>({ url: '/compliance/legal-holds', method: 'get' }),
  createLegalHold: (data: LegalHoldCreate) =>
    request<LegalHoldView>({ url: '/compliance/legal-holds', method: 'post', data }),
  releaseLegalHold: (id: number) =>
    request<void>({ url: `/compliance/legal-holds/${id}`, method: 'delete' }),
  exports: (params: { page?: number; size?: number } = {}) =>
    request<PageResult<ExportJobView>>({
      url: '/compliance/exports',
      method: 'get',
      params,
    }),
  createExport: (data: ExportCreateRequest) =>
    request<ExportJobView>({ url: '/compliance/exports', method: 'post', data }),
  downloadExport: async (job: ExportJobView) => {
    const blob = await request<Blob>({
      url: `/compliance/exports/${job.id}/download`,
      method: 'get',
      responseType: 'blob',
      timeout: 300_000,
    })
    saveBlob(blob, job.fileName || `export-${job.id}.${job.format.toLowerCase()}`)
  },
  accessReviews: () =>
    request<AccessReviewView[]>({ url: '/compliance/access-reviews', method: 'get' }),
  accessReview: (id: number) =>
    request<AccessReviewView>({
      url: `/compliance/access-reviews/${id}`,
      method: 'get',
    }),
  generateAccessReview: () =>
    request<AccessReviewView>({ url: '/compliance/access-reviews', method: 'post' }),
  decideAccessReview: (id: number, itemId: number, data: AccessReviewDecisionRequest) =>
    request<AccessReviewItemView>({
      url: `/compliance/access-reviews/${id}/items/${itemId}/decision`,
      method: 'post',
      data,
    }),
  downloadAccessReviewCsv: async (id: number) => {
    const blob = await request<Blob>({
      url: `/compliance/access-reviews/${id}/export.csv`,
      method: 'get',
      responseType: 'blob',
    })
    saveBlob(blob, `access-review-${id}.csv`)
  },
}

export const auditApi = {
  page: (params: {
    spaceId?: number
    actorId?: number
    action?: string
    status?: string
    from?: string
    to?: string
    page?: number
    size?: number
  }) => request<PageResult<AuditLogView>>({ url: '/audit-logs', method: 'get', params }),
}

export const systemApi = {
  health: () => request<HealthStatus>({ url: '/health', method: 'get' }),
  meta: () => request<PublicMeta>({ url: '/public/meta', method: 'get' }),
}

export const licenseApi = {
  status: () => request<LicenseStatusView>({ url: '/license', method: 'get' }),
}

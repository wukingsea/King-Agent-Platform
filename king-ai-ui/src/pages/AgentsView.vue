<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  ChatDotRound,
  Check,
  Close,
  Connection,
  Collection,
  CopyDocument,
  Cpu,
  DataLine,
  Delete,
  Download,
  EditPen,
  MagicStick,
  MoreFilled,
  Plus,
  Search,
  Upload,
  VideoPlay,
} from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { agentApi, agentReleaseApi, knowledgeApi, mcpApi, modelApi, skillApi, workspaceApi } from '@/api'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import type {
  AgentEvalCaseSaveRequest,
  AgentEvalCaseView,
  AgentSaveRequest,
  AgentVersionView,
  AgentView,
  KnowledgeBaseView,
  McpView,
  ModelOption,
  ReleaseStatus,
  SkillView,
  WorkspaceStatsView,
} from '@/api/types'
import { copyText, fromNow } from '@/utils/format'

const CONTEXT_LIMIT = 50000

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const loading = ref(false)
const keyword = ref('')
const agents = ref<AgentView[]>([])
const agentsPage = ref(1)
const agentsTotal = ref(0)
const agentsPageSize = 20
const stats = ref<WorkspaceStatsView>({
  agents: 0,
  publishedAgents: 0,
  knowledgeBases: 0,
  documents: 0,
  chunks: 0,
  sessions: 0,
  mcpServers: 0,
  skills: 0,
  suppliers: 0,
  models: 0,
})
const models = ref<ModelOption[]>([])
const knowledgeBases = ref<KnowledgeBaseView[]>([])
const mcps = ref<McpView[]>([])
const skills = ref<SkillView[]>([])

const drawerVisible = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()
const versions = ref<AgentVersionView[]>([])
const versionsLoading = ref(false)
const rollingBack = ref<number | null>(null)
const releaseBusy = ref<string | null>(null)

const evalCases = ref<AgentEvalCaseView[]>([])
const evalLoading = ref(false)
const evalDialogVisible = ref(false)
const evalSaving = ref(false)
const editingCaseId = ref<number | null>(null)
const evalForm = reactive<AgentEvalCaseSaveRequest>({
  name: '',
  prompt: '',
  expectedKeywords: '',
  weight: 1,
  status: 0,
})

const canaryVisible = ref(false)
const canaryPercent = ref(20)
const canaryTarget = ref<AgentVersionView | null>(null)

const rejectVisible = ref(false)
const rejectComment = ref('')
const rejectTarget = ref<AgentVersionView | null>(null)

interface AgentForm {
  name: string
  intro: string
  modelId: number | undefined
  systemPrompt: string
  temperature: number
  maxTokens: number
  status: number
  allowedTools: string[]
  toolApprovalMode: string
  fallbackModelId: number | null
  knowledgeBaseIds: number[]
  mcpIds: number[]
  skillIds: number[]
}

const form = reactive<AgentForm>({
  name: '',
  intro: '',
  modelId: undefined,
  systemPrompt: '',
  temperature: 0.7,
  maxTokens: 2048,
  status: 0,
  allowedTools: [],
  toolApprovalMode: 'WRITE',
  fallbackModelId: null,
  knowledgeBaseIds: [],
  mcpIds: [],
  skillIds: [],
})

const rules: FormRules = {
  name: [
    { required: true, message: '请输入智能体名称', trigger: 'blur' },
    { max: 100, message: '名称不能超过 100 个字符', trigger: 'blur' },
  ],
  modelId: [{ required: true, message: '请选择对话模型', trigger: 'change' }],
}

const publishedCount = computed(() => stats.value.publishedAgents)
const draftCount = computed(() => Math.max(0, stats.value.agents - stats.value.publishedAgents))

function resetForm() {
  editingId.value = null
  Object.assign(form, {
    name: '',
    intro: '',
    modelId: undefined,
    systemPrompt: '',
    temperature: 0.7,
    maxTokens: 2048,
    status: 0,
    allowedTools: [],
    toolApprovalMode: 'WRITE',
    fallbackModelId: null,
    knowledgeBaseIds: [],
    mcpIds: [],
    skillIds: [],
  })
  versions.value = []
  evalCases.value = []
  evalDialogVisible.value = false
  canaryVisible.value = false
  rejectVisible.value = false
  releaseBusy.value = null
}

async function loadAgents() {
  loading.value = true
  try {
    const result = await agentApi.page({
      page: agentsPage.value,
      size: agentsPageSize,
      keyword: keyword.value.trim() || undefined,
    })
    agents.value = result.list
    agentsTotal.value = result.total
    if (result.list.length === 0 && result.total > 0 && agentsPage.value > 1) {
      agentsPage.value = Math.max(1, Math.ceil(result.total / agentsPageSize))
      await loadAgents()
      return
    }
    if (!result.list.length && agentsPage.value > 1) {
      agentsPage.value = 1
      await loadAgents()
    }
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载智能体失败')
  } finally {
    loading.value = false
  }
}

async function loadStats() {
  try {
    stats.value = await workspaceApi.stats()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载工作台统计失败')
  }
}

function searchAgents() {
  agentsPage.value = 1
  loadAgents()
}

function changeAgentsPage(page: number) {
  agentsPage.value = page
  loadAgents()
}

async function loadOptions() {
  const [modelRes, kbRes, mcpRes, skillRes] = await Promise.allSettled([
    modelApi.options('CHAT'),
    knowledgeApi.list(),
    mcpApi.list(),
    skillApi.list(),
  ])
  if (modelRes.status === 'fulfilled') models.value = modelRes.value
  if (kbRes.status === 'fulfilled') knowledgeBases.value = kbRes.value
  if (mcpRes.status === 'fulfilled') mcps.value = mcpRes.value
  if (skillRes.status === 'fulfilled') skills.value = skillRes.value
}

function openCreate() {
  resetForm()
  drawerVisible.value = true
}

function applyAgentToForm(agent: AgentView) {
  Object.assign(form, {
    name: agent.name,
    intro: agent.intro || '',
    modelId: agent.modelId,
    systemPrompt: agent.systemPrompt || '',
    temperature: agent.temperature ?? 0.7,
    maxTokens: agent.maxTokens ?? 2048,
    status: agent.status,
    allowedTools: [...(agent.allowedTools || [])],
    toolApprovalMode: agent.toolApprovalMode || 'WRITE',
    fallbackModelId: agent.fallbackModelId ?? null,
    knowledgeBaseIds: [...(agent.knowledgeBaseIds || [])],
    mcpIds: [...(agent.mcpIds || [])],
    skillIds: [...(agent.skillIds || [])],
  })
}

function openEdit(agent: AgentView) {
  resetForm()
  editingId.value = agent.id
  applyAgentToForm(agent)
  drawerVisible.value = true
  void loadVersions(agent.id)
  void loadEvalCases(agent.id)
}

async function loadVersions(agentId: number) {
  versionsLoading.value = true
  try {
    versions.value = await agentApi.versions(agentId)
  } catch {
    versions.value = []
  } finally {
    versionsLoading.value = false
  }
}

async function loadEvalCases(agentId: number) {
  evalLoading.value = true
  try {
    evalCases.value = await agentReleaseApi.evalCases(agentId)
  } catch {
    evalCases.value = []
  } finally {
    evalLoading.value = false
  }
}

function releaseLabel(status: ReleaseStatus): string {
  switch (status) {
    case 'PENDING_REVIEW':
      return '待审批'
    case 'APPROVED':
      return '已审批'
    case 'CANARY':
      return '灰度中'
    case 'PUBLISHED':
      return '已发布'
    case 'REJECTED':
      return '已驳回'
    case 'ARCHIVED':
      return '已归档'
    default:
      return '草稿'
  }
}

function releaseClass(status: ReleaseStatus): string {
  if (status === 'PUBLISHED') return 'published'
  if (status === 'PENDING_REVIEW' || status === 'CANARY') return 'version'
  if (status === 'REJECTED') return 'disabled'
  return 'draft'
}

async function refreshRelease(agentId: number) {
  await Promise.all([loadAgents(), loadVersions(agentId), loadEvalCases(agentId), loadStats()])
}

async function submitRelease(agent: AgentView | null = null) {
  const agentId = agent?.id ?? editingId.value
  if (!agentId) return
  releaseBusy.value = `submit-${agentId}`
  try {
    const version = await agentReleaseApi.submit(agentId)
    if (version.status === 'PUBLISHED') {
      ElMessage.success(`已发布 v${version.versionNo}`)
    } else {
      ElMessage.success(`已提交 v${version.versionNo} 审批，等待空间管理员审核`)
    }
    await refreshRelease(agentId)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '提交发布失败')
  } finally {
    releaseBusy.value = null
  }
}

async function evaluateVersion(version: AgentVersionView) {
  const agentId = editingId.value
  if (!agentId) return
  releaseBusy.value = `eval-${version.versionNo}`
  try {
    const result = await agentReleaseApi.evaluate(agentId, version.versionNo)
    ElMessage.success(
      `评测完成：${result.evalPassed}/${result.evalTotal} 通过，得分 ${result.evalScore ?? 0}`,
    )
    await loadVersions(agentId)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '评测失败')
  } finally {
    releaseBusy.value = null
  }
}

async function approveVersion(version: AgentVersionView) {
  const agentId = editingId.value
  if (!agentId) return
  releaseBusy.value = `approve-${version.versionNo}`
  try {
    await agentReleaseApi.approve(agentId, version.versionNo)
    ElMessage.success(`已审批通过 v${version.versionNo}`)
    await loadVersions(agentId)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '审批失败')
  } finally {
    releaseBusy.value = null
  }
}

function openReject(version: AgentVersionView) {
  rejectTarget.value = version
  rejectComment.value = ''
  rejectVisible.value = true
}

async function confirmReject() {
  const agentId = editingId.value
  const version = rejectTarget.value
  if (!agentId || !version) return
  if (!rejectComment.value.trim()) {
    ElMessage.warning('请填写驳回原因')
    return
  }
  releaseBusy.value = `reject-${version.versionNo}`
  try {
    await agentReleaseApi.reject(agentId, version.versionNo, rejectComment.value.trim())
    rejectVisible.value = false
    ElMessage.success(`已驳回 v${version.versionNo}`)
    await loadVersions(agentId)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '驳回失败')
  } finally {
    releaseBusy.value = null
  }
}

function openCanary(version: AgentVersionView) {
  canaryTarget.value = version
  canaryPercent.value = version.canaryPercent || 20
  canaryVisible.value = true
}

async function confirmCanary() {
  const agentId = editingId.value
  const version = canaryTarget.value
  if (!agentId || !version) return
  if (canaryPercent.value < 1 || canaryPercent.value > 99) {
    ElMessage.warning('灰度比例需在 1-99 之间')
    return
  }
  releaseBusy.value = `canary-${version.versionNo}`
  try {
    await agentReleaseApi.canary(agentId, version.versionNo, canaryPercent.value)
    canaryVisible.value = false
    ElMessage.success(`v${version.versionNo} 已进入 ${canaryPercent.value}% 灰度`)
    await loadVersions(agentId)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '设置灰度失败')
  } finally {
    releaseBusy.value = null
  }
}

async function promoteVersion(version: AgentVersionView) {
  const agentId = editingId.value
  if (!agentId) return
  try {
    await ElMessageBox.confirm(
      `确认将 v${version.versionNo} 正式发布？全量流量会切换到该版本。`,
      '正式发布',
      { type: 'warning', confirmButtonText: '发布', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  releaseBusy.value = `promote-${version.versionNo}`
  try {
    await agentReleaseApi.promote(agentId, version.versionNo)
    ElMessage.success(`v${version.versionNo} 已正式发布`)
    await refreshRelease(agentId)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '发布失败')
  } finally {
    releaseBusy.value = null
  }
}

function openCreateCase() {
  editingCaseId.value = null
  Object.assign(evalForm, {
    name: '',
    prompt: '',
    expectedKeywords: '',
    weight: 1,
    status: 0,
  })
  evalDialogVisible.value = true
}

function openEditCase(item: AgentEvalCaseView) {
  editingCaseId.value = item.id
  Object.assign(evalForm, {
    name: item.name,
    prompt: item.prompt,
    expectedKeywords: item.expectedKeywords,
    weight: item.weight,
    status: item.status,
  })
  evalDialogVisible.value = true
}

async function saveEvalCase() {
  const agentId = editingId.value
  if (!agentId) return
  if (!evalForm.name.trim() || !evalForm.prompt.trim() || !evalForm.expectedKeywords.trim()) {
    ElMessage.warning('请填写用例名称、评测问题与期望关键词')
    return
  }
  evalSaving.value = true
  try {
    const payload: AgentEvalCaseSaveRequest = {
      name: evalForm.name.trim(),
      prompt: evalForm.prompt.trim(),
      expectedKeywords: evalForm.expectedKeywords.trim(),
      weight: evalForm.weight,
      status: evalForm.status,
    }
    if (editingCaseId.value) {
      await agentReleaseApi.updateEvalCase(agentId, editingCaseId.value, payload)
      ElMessage.success('评测用例已更新')
    } else {
      await agentReleaseApi.createEvalCase(agentId, payload)
      ElMessage.success('评测用例已创建')
    }
    evalDialogVisible.value = false
    await loadEvalCases(agentId)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '保存评测用例失败')
  } finally {
    evalSaving.value = false
  }
}

async function removeEvalCase(item: AgentEvalCaseView) {
  const agentId = editingId.value
  if (!agentId) return
  try {
    await ElMessageBox.confirm(`确认删除评测用例「${item.name}」？`, '删除评测用例', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await agentReleaseApi.removeEvalCase(agentId, item.id)
    ElMessage.success('评测用例已删除')
    await loadEvalCases(agentId)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除评测用例失败')
  }
}

async function rollback(version: AgentVersionView) {
  if (!editingId.value) return
  try {
    await ElMessageBox.confirm(
      `回滚到 v${version.versionNo}？当前配置会被该快照覆盖，并记录为一个新的发布版本。`,
      '回滚智能体',
      { type: 'warning', confirmButtonText: '回滚', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  rollingBack.value = version.versionNo
  try {
    const updated = await agentApi.rollback(editingId.value, version.versionNo)
    const index = agents.value.findIndex((item) => item.id === updated.id)
    if (index >= 0) agents.value[index] = updated
    applyAgentToForm(updated)
    await loadVersions(updated.id)
    ElMessage.success(`已回滚到 v${version.versionNo}`)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '回滚失败')
  } finally {
    rollingBack.value = null
  }
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid || !form.modelId) return
  saving.value = true
  const payload: AgentSaveRequest = {
    name: form.name.trim(),
    intro: form.intro.trim(),
    modelId: form.modelId,
    systemPrompt: form.systemPrompt,
    temperature: form.temperature,
    maxTokens: form.maxTokens,
    status: form.status,
    allowedTools: form.allowedTools,
    toolApprovalMode: form.toolApprovalMode,
    fallbackModelId: form.fallbackModelId,
    knowledgeBaseIds: form.knowledgeBaseIds,
    mcpIds: form.mcpIds,
    skillIds: form.skillIds,
  }
  try {
    if (editingId.value) {
      await agentApi.update(editingId.value, payload)
      ElMessage.success('智能体已更新')
    } else {
      await agentApi.create(payload)
      ElMessage.success('智能体已创建')
    }
    drawerVisible.value = false
    await loadAgents()
    await loadStats()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function togglePublish(agent: AgentView) {
  if (agent.publishStatus !== 1) {
    await submitRelease(agent)
    return
  }
  try {
    const updated = await agentApi.unpublish(agent.id)
    const index = agents.value.findIndex((item) => item.id === updated.id)
    if (index >= 0) agents.value[index] = updated
    ElMessage.success('已下线智能体')
    await loadStats()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '操作失败')
  }
}

function publicUrl(agent: AgentView): string {
  return `${window.location.origin}${window.location.pathname}#/chat/${agent.publicId}`
}

async function copyLink(agent: AgentView) {
  if (!agent.publicId) {
    ElMessage.warning('请先发布智能体以生成公开链接')
    return
  }
  try {
    await copyText(publicUrl(agent))
    ElMessage.success('公开对话链接已复制')
  } catch {
    ElMessage.error('复制失败，请手动复制地址')
  }
}

function openPublic(agent: AgentView) {
  if (!agent.publicId) {
    ElMessage.warning('请先发布智能体')
    return
  }
  window.open(publicUrl(agent), '_blank', 'noopener,noreferrer')
}

async function remove(agent: AgentView) {
  try {
    await ElMessageBox.confirm(
      `确认删除智能体「${agent.name}」？存在对话记录时无法删除，请先在对话页清理相关会话。`,
      '删除智能体',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await agentApi.remove(agent.id)
    ElMessage.success('智能体已删除')
    await loadAgents()
    await loadStats()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

function handleCommand(command: string, agent: AgentView) {
  if (command === 'edit') openEdit(agent)
  if (command === 'copy') copyLink(agent)
  if (command === 'open') openPublic(agent)
  if (command === 'delete') remove(agent)
}

function palette(id: number): string {
  return `palette-${(id % 5) + 1}`
}

onMounted(async () => {
  await Promise.all([loadAgents(), loadOptions(), loadStats()])
  if (!auth.canEditSpace) return
  if (route.query.create === '1') {
    openCreate()
    return
  }
  const editId = Number(route.query.edit)
  if (editId) {
    const target = agents.value.find((agent) => agent.id === editId)
    if (target) openEdit(target)
  }
})
</script>

<template>
  <div class="page">
    <PageHeader
      title="智能体"
      description="把对话模型、系统指令、知识库、MCP 工具与技能组合成可发布的 AI 智能体。"
      eyebrow="Agents"
    >
      <template #actions>
        <el-button v-if="auth.canEditSpace" type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon>
          新建智能体
        </el-button>
      </template>
    </PageHeader>

    <div class="summary-row">
      <div class="summary-chip">
        <span class="chip-dot"></span>
        共 {{ agentsTotal }} 个
      </div>
      <div class="summary-chip">
        <span class="chip-dot green"></span>
        {{ publishedCount }} 个已发布
      </div>
      <div class="summary-chip">
        <span class="chip-dot muted"></span>
        {{ draftCount }} 个草稿
      </div>
      <div class="summary-spacer"></div>
      <div class="search-box">
        <el-input
          v-model="keyword"
          placeholder="搜索智能体名称"
          clearable
          @keyup.enter="searchAgents"
          @clear="searchAgents"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button @click="searchAgents">搜索</el-button>
      </div>
    </div>

    <div v-loading="loading" class="agent-grid-wrap">
      <div v-if="agents.length" class="agent-grid">
        <article
          v-for="agent in agents"
          :key="agent.id"
          class="agent-card panel"
        >
          <div class="card-top">
            <div class="agent-mark" :class="palette(agent.id)">
              {{ agent.name.slice(0, 1) }}
            </div>
            <div class="agent-heading">
              <div class="agent-title-row">
                <h3>{{ agent.name }}</h3>
                <span v-if="agent.publishStatus === 1" class="badge published">已发布</span>
                <span v-else class="badge draft">草稿</span>
                <span
                  v-if="agent.publishStatus === 1 && agent.publishedVersion"
                  class="badge version"
                >
                  v{{ agent.publishedVersion }}
                </span>
                <span v-if="agent.status !== 0" class="badge disabled">停用</span>
              </div>
              <p class="agent-intro">{{ agent.intro || '还没有填写简介，编辑智能体来补充它的能力说明。' }}</p>
            </div>
          </div>

          <div class="agent-meta">
            <div class="meta-row">
              <span class="meta-label">对话模型</span>
              <span class="meta-value">{{ agent.modelName || '未绑定' }}</span>
            </div>
            <div class="meta-row">
              <span class="meta-label">模型标识</span>
              <span class="meta-value mono">{{ agent.modelIdentifier || '—' }}</span>
            </div>
          </div>

          <div class="binding-row">
            <span class="binding-pill" :class="{ off: !agent.knowledgeBaseIds?.length }">
              <el-icon><Collection /></el-icon>
              知识库 {{ agent.knowledgeBaseIds?.length || 0 }}
            </span>
            <span class="binding-pill" :class="{ off: !agent.mcpIds?.length }">
              <el-icon><Connection /></el-icon>
              MCP {{ agent.mcpIds?.length || 0 }}
            </span>
            <span class="binding-pill" :class="{ off: !agent.skillIds?.length }">
              <el-icon><MagicStick /></el-icon>
              技能 {{ agent.skillIds?.length || 0 }}
            </span>
          </div>

          <div class="agent-foot">
            <span class="updated">{{ fromNow(agent.updatedAt || agent.createdAt) }}更新</span>
            <div class="card-actions">
              <el-button size="small" @click="router.push({ name: 'playground', query: { agentId: agent.id } })">
                <el-icon><ChatDotRound /></el-icon>
                对话
              </el-button>
              <template v-if="auth.canEditSpace">
                <el-button
                  size="small"
                  :disabled="agent.status !== 0 && agent.publishStatus !== 1"
                  :loading="releaseBusy === `submit-${agent.id}`"
                  @click="togglePublish(agent)"
                >
                  <el-icon>
                    <Download v-if="agent.publishStatus === 1" />
                    <Upload v-else />
                  </el-icon>
                  {{ agent.publishStatus === 1 ? '下线' : '提交发布' }}
                </el-button>
                <el-dropdown trigger="click" @command="(command: string) => handleCommand(command, agent)">
                  <el-button size="small" class="more-btn">
                    <el-icon><MoreFilled /></el-icon>
                  </el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="edit">
                        <el-icon><EditPen /></el-icon>
                        编辑智能体
                      </el-dropdown-item>
                      <el-dropdown-item command="copy" :disabled="!agent.publicId">
                        <el-icon><CopyDocument /></el-icon>
                        复制公开链接
                      </el-dropdown-item>
                      <el-dropdown-item command="open" :disabled="!agent.publicId">
                        <el-icon><ChatDotRound /></el-icon>
                        打开公开对话
                      </el-dropdown-item>
                      <el-dropdown-item command="delete" divided :disabled="!auth.canManageSpace">
                        <el-icon><Delete /></el-icon>
                        删除智能体
                      </el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </template>
            </div>
          </div>
        </article>
      </div>

      <div v-else-if="!loading" class="panel">
        <EmptyState
          title="从一个智能体开始"
          description="选择对话模型，填写系统指令，再绑定知识库、MCP 工具或技能。发布后会生成可分享的公开对话页。"
        >
          <template #icon><el-icon :size="22"><Cpu /></el-icon></template>
          <template #action>
            <el-button v-if="auth.canEditSpace" type="primary" @click="openCreate">
              <el-icon><Plus /></el-icon>
              新建智能体
            </el-button>
          </template>
        </EmptyState>
      </div>
    </div>

    <div v-if="agentsTotal > agentsPageSize" class="list-pagination">
      <el-pagination
        background
        layout="prev, pager, next"
        :current-page="agentsPage"
        :page-size="agentsPageSize"
        :total="agentsTotal"
        @current-change="changeAgentsPage"
      />
    </div>

    <el-drawer
      v-model="drawerVisible"
      :title="editingId ? '编辑智能体' : '新建智能体'"
      size="620px"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <div class="form-section">
          <div class="form-section-title">基础信息</div>
          <el-form-item label="名称" prop="name">
            <el-input v-model="form.name" maxlength="100" placeholder="例如：产品答疑助手" />
          </el-form-item>
          <el-form-item label="简介">
            <el-input
              v-model="form.intro"
              type="textarea"
              :rows="2"
              maxlength="500"
              show-word-limit
              placeholder="一句话说明这个智能体能做什么"
            />
          </el-form-item>
          <div class="inline-fields">
            <el-form-item label="状态">
              <el-switch
                v-model="form.status"
                :active-value="0"
                :inactive-value="1"
                active-text="启用"
                inactive-text="停用"
              />
            </el-form-item>
            <el-form-item label="对话模型" prop="modelId" class="grow">
              <el-select v-model="form.modelId" filterable placeholder="选择对话模型" style="width: 100%">
                <el-option
                  v-for="model in models"
                  :key="model.id"
                  :label="`${model.name} · ${model.supplierName}`"
                  :value="model.id"
                >
                  <div class="model-option">
                    <span>{{ model.name }}</span>
                    <span class="model-id">{{ model.model }}</span>
                  </div>
                </el-option>
              </el-select>
            </el-form-item>
          </div>
          <div v-if="!models.length" class="inline-hint">
            还没有可用的对话模型，请先到「供应商与模型」完成接入。
          </div>
        </div>

        <div class="form-section">
          <div class="form-section-title">系统指令</div>
          <el-form-item label="System Prompt">
            <el-input
              v-model="form.systemPrompt"
              type="textarea"
              :rows="7"
              :maxlength="CONTEXT_LIMIT"
              show-word-limit
              placeholder="描述智能体的角色、语气、边界与回答策略。留空将使用平台默认指令。"
            />
          </el-form-item>
          <div class="inline-fields">
            <el-form-item label="温度" class="grow">
              <div class="slider-line">
                <el-slider v-model="form.temperature" :min="0" :max="2" :step="0.1" />
                <span class="slider-value">{{ form.temperature.toFixed(1) }}</span>
              </div>
            </el-form-item>
            <el-form-item label="最大输出 Tokens">
              <el-input-number v-model="form.maxTokens" :min="1" :max="32768" :step="256" controls-position="right" />
            </el-form-item>
          </div>
        </div>

        <div class="form-section">
          <div class="form-section-title">工具与安全</div>
          <el-form-item label="工具白名单">
            <el-select
              v-model="form.allowedTools"
              multiple
              filterable
              allow-create
              default-first-option
              placeholder="留空表示不限制；支持通配符，如 mcp12_*"
              style="width: 100%"
            >
              <el-option label="全部 MCP 工具（mcp*）" value="mcp*" />
              <el-option label="技能文件读取（skill_read_file）" value="skill_read_file" />
            </el-select>
            <div class="field-hint">
              只允许列表中的工具参与对话；格式为 glob 通配符，匹配 MCP 工具名或完整工具名。
            </div>
          </el-form-item>
          <div class="inline-fields">
            <el-form-item label="工具确认策略" class="grow">
              <el-select v-model="form.toolApprovalMode" style="width: 100%">
                <el-option label="写操作需人工确认（推荐）" value="WRITE" />
                <el-option label="所有工具都需人工确认" value="ALL" />
                <el-option label="无需确认（不推荐）" value="NONE" />
              </el-select>
            </el-form-item>
            <el-form-item label="降级模型">
              <el-select
                v-model="form.fallbackModelId"
                clearable
                filterable
                placeholder="不启用"
                style="width: 100%"
              >
                <el-option
                  v-for="model in models"
                  :key="model.id"
                  :label="`${model.name} · ${model.supplierName}`"
                  :value="model.id"
                />
              </el-select>
            </el-form-item>
          </div>
          <div class="field-hint">
            主模型连续失败时自动切换到降级模型；写操作工具在人工批准前不会执行。
          </div>
        </div>

        <div class="form-section">
          <div class="form-section-title">能力绑定</div>
          <el-form-item label="知识库（RAG）">
            <el-select
              v-model="form.knowledgeBaseIds"
              multiple
              filterable
              collapse-tags
              collapse-tags-tooltip
              placeholder="选择需要检索的知识库"
              style="width: 100%"
            >
              <el-option
                v-for="base in knowledgeBases"
                :key="base.id"
                :label="base.name"
                :value="base.id"
              >
                <div class="model-option">
                  <span>{{ base.name }}</span>
                  <span class="model-id">{{ base.chunkCount }} chunks</span>
                </div>
              </el-option>
            </el-select>
            <div class="field-hint">对话时先检索知识库，回答会附带 chunk 级引用。</div>
          </el-form-item>
          <el-form-item label="MCP 服务">
            <el-select
              v-model="form.mcpIds"
              multiple
              filterable
              collapse-tags
              collapse-tags-tooltip
              placeholder="选择允许调用的 MCP 服务"
              style="width: 100%"
            >
              <el-option v-for="server in mcps" :key="server.id" :label="server.name" :value="server.id">
                <div class="model-option">
                  <span>{{ server.name }}</span>
                  <span class="model-id">{{ server.transport || 'HTTP' }}</span>
                </div>
              </el-option>
            </el-select>
            <div class="field-hint">工具会按 MCP 协议动态发现，由模型在多轮对话中自主调用。</div>
          </el-form-item>
          <el-form-item label="技能">
            <el-select
              v-model="form.skillIds"
              multiple
              filterable
              collapse-tags
              collapse-tags-tooltip
              placeholder="选择要注入的技能规范"
              style="width: 100%"
            >
              <el-option v-for="skill in skills" :key="skill.id" :label="skill.name" :value="skill.id">
                <div class="model-option">
                  <span>{{ skill.name }}</span>
                  <span class="model-id">v{{ skill.version || '1.0' }}</span>
                </div>
              </el-option>
            </el-select>
            <div class="field-hint">SKILL.md 会作为规范注入提示词，同时提供只读文件读取工具。</div>
          </el-form-item>
        </div>

        <div v-if="editingId" class="form-section">
          <div class="section-head">
            <div>
              <div class="form-section-title">发布审批与灰度</div>
              <p class="field-hint">提交后生成不可变版本快照，审批通过后支持灰度与全量发布。</p>
            </div>
            <el-button
              size="small"
              type="primary"
              plain
              :disabled="!auth.canEditSpace"
              :loading="releaseBusy === `submit-${editingId}`"
              @click="submitRelease()"
            >
              <el-icon><Upload /></el-icon>
              提交发布
            </el-button>
          </div>
          <div v-if="versionsLoading" class="inline-hint">正在加载版本历史…</div>
          <div v-else-if="!versions.length" class="inline-hint">
            还没有发布版本。提交发布后会进入审批流程。
          </div>
          <div v-else class="version-list">
            <div v-for="version in versions" :key="version.versionNo" class="version-row release-row">
              <div class="version-main">
                <div class="version-head">
                  <span class="version-no">v{{ version.versionNo }}</span>
                  <span class="badge" :class="releaseClass(version.status)">
                    {{ releaseLabel(version.status) }}
                  </span>
                  <span v-if="version.current" class="badge published">当前版本</span>
                  <span v-if="version.status === 'CANARY'" class="version-note">
                    {{ version.canaryPercent }}% 流量
                  </span>
                </div>
                <div class="version-meta">
                  <span>{{ fromNow(version.publishedAt) }}</span>
                  <span v-if="version.evalRunAt">
                    评测 {{ version.evalPassed ?? 0 }}/{{ version.evalTotal ?? 0 }} ·
                    {{ version.evalScore ?? 0 }} 分
                  </span>
                  <span v-else-if="version.status !== 'PUBLISHED'">未评测</span>
                </div>
                <p v-if="version.evalSummary" class="version-summary">{{ version.evalSummary }}</p>
                <p v-if="version.reviewComment" class="version-comment">
                  审批意见：{{ version.reviewComment }}
                </p>
              </div>
              <div class="version-actions">
                <el-button
                  v-if="version.status !== 'PUBLISHED' && version.status !== 'ARCHIVED'"
                  size="small"
                  :loading="releaseBusy === `eval-${version.versionNo}`"
                  @click="evaluateVersion(version)"
                >
                  <el-icon><VideoPlay /></el-icon>
                  评测
                </el-button>
                <template v-if="version.status === 'PENDING_REVIEW' && auth.canManageSpace">
                  <el-button
                    size="small"
                    type="success"
                    :loading="releaseBusy === `approve-${version.versionNo}`"
                    @click="approveVersion(version)"
                  >
                    <el-icon><Check /></el-icon>
                    通过
                  </el-button>
                  <el-button size="small" type="danger" plain @click="openReject(version)">
                    <el-icon><Close /></el-icon>
                    驳回
                  </el-button>
                </template>
                <template v-if="version.status === 'APPROVED' && auth.canManageSpace">
                  <el-button size="small" @click="openCanary(version)">
                    <el-icon><DataLine /></el-icon>
                    灰度
                  </el-button>
                  <el-button
                    size="small"
                    type="primary"
                    :loading="releaseBusy === `promote-${version.versionNo}`"
                    @click="promoteVersion(version)"
                  >
                    <el-icon><Upload /></el-icon>
                    正式发布
                  </el-button>
                </template>
                <template v-if="version.status === 'CANARY' && auth.canManageSpace">
                  <el-button size="small" @click="openCanary(version)">调整灰度</el-button>
                  <el-button
                    size="small"
                    type="primary"
                    :loading="releaseBusy === `promote-${version.versionNo}`"
                    @click="promoteVersion(version)"
                  >
                    正式发布
                  </el-button>
                </template>
                <el-button
                  v-if="version.status === 'PUBLISHED' && !version.current"
                  size="small"
                  :loading="rollingBack === version.versionNo"
                  @click="rollback(version)"
                >
                  回滚
                </el-button>
              </div>
            </div>
          </div>
        </div>

        <div v-if="editingId" class="form-section">
          <div class="section-head">
            <div>
              <div class="form-section-title">评测用例</div>
              <p class="field-hint">审批前需运行评测；存在启用用例时，得分需达到发布门槛。</p>
            </div>
            <el-button size="small" :disabled="!auth.canEditSpace" @click="openCreateCase">
              <el-icon><Plus /></el-icon>
              新增用例
            </el-button>
          </div>
          <div v-if="evalLoading" class="inline-hint">正在加载评测用例…</div>
          <div v-else-if="!evalCases.length" class="inline-hint">
            还没有评测用例。建议补充 3-5 条覆盖核心业务问题。
          </div>
          <div v-else class="eval-list">
            <div v-for="item in evalCases" :key="item.id" class="eval-row">
              <div class="eval-main">
                <div class="eval-head">
                  <strong>{{ item.name }}</strong>
                  <span class="badge" :class="item.status === 0 ? 'published' : 'draft'">
                    {{ item.status === 0 ? '启用' : '停用' }}
                  </span>
                  <span class="eval-weight">权重 {{ item.weight }}</span>
                </div>
                <p class="eval-prompt">{{ item.prompt }}</p>
                <p class="eval-keywords">关键词：{{ item.expectedKeywords }}</p>
              </div>
              <div v-if="auth.canEditSpace" class="eval-actions">
                <el-button size="small" text @click="openEditCase(item)">
                  <el-icon><EditPen /></el-icon>
                </el-button>
                <el-button size="small" text type="danger" @click="removeEvalCase(item)">
                  <el-icon><Delete /></el-icon>
                </el-button>
              </div>
            </div>
          </div>
        </div>
      </el-form>

      <template #footer>
        <div class="drawer-footer">
          <el-button @click="drawerVisible = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="submit">
            {{ editingId ? '保存修改' : '创建智能体' }}
          </el-button>
        </div>
      </template>
    </el-drawer>

    <el-dialog
      v-model="evalDialogVisible"
      :title="editingCaseId ? '编辑评测用例' : '新增评测用例'"
      width="560px"
    >
      <el-form label-position="top">
        <el-form-item label="用例名称" required>
          <el-input v-model="evalForm.name" maxlength="120" placeholder="例如：退换货政策" />
        </el-form-item>
        <el-form-item label="评测问题" required>
          <el-input
            v-model="evalForm.prompt"
            type="textarea"
            :rows="3"
            maxlength="10000"
            placeholder="发送给智能体的用户问题"
          />
        </el-form-item>
        <el-form-item label="期望关键词" required>
          <el-input
            v-model="evalForm.expectedKeywords"
            placeholder="例如：7 天,无理由,运费"
            maxlength="2000"
          />
          <div class="field-hint">
            使用逗号分隔；回答需命中全部关键词才算通过，得分按命中比例计算。
          </div>
        </el-form-item>
        <div class="inline-fields">
          <el-form-item label="权重" class="grow">
            <el-input-number v-model="evalForm.weight" :min="1" :max="10" />
          </el-form-item>
          <el-form-item label="状态">
            <el-switch
              v-model="evalForm.status"
              :active-value="0"
              :inactive-value="1"
              active-text="启用"
              inactive-text="停用"
            />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="evalDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="evalSaving" @click="saveEvalCase">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="canaryVisible" title="设置灰度比例" width="420px">
      <p class="dialog-hint">
        灰度期间按请求哈希将部分流量路由到该版本，其余流量继续使用当前发布版本。
      </p>
      <div class="canary-slider">
        <el-slider v-model="canaryPercent" :min="1" :max="99" :step="1" show-input />
      </div>
      <template #footer>
        <el-button @click="canaryVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="canaryTarget ? releaseBusy === `canary-${canaryTarget.versionNo}` : false"
          @click="confirmCanary"
        >
          确认灰度
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rejectVisible" title="驳回发布" width="460px">
      <el-form label-position="top">
        <el-form-item label="驳回原因" required>
          <el-input
            v-model="rejectComment"
            type="textarea"
            :rows="3"
            maxlength="1000"
            show-word-limit
            placeholder="说明需要调整的问题，便于提交人修改后重新提交"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button
          type="danger"
          :loading="rejectTarget ? releaseBusy === `reject-${rejectTarget.versionNo}` : false"
          @click="confirmReject"
        >
          确认驳回
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.summary-row {
  display: flex;
  align-items: center;
  gap: 9px;
  flex-wrap: wrap;
}

.summary-chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 28px;
  padding: 0 11px;
  border-radius: 8px;
  border: 1px solid var(--king-line);
  background: var(--k-fill-soft);
  font-size: 10.5px;
  color: var(--king-muted);
}

.chip-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--king-primary);
}

.chip-dot.green {
  background: var(--king-green);
}

.chip-dot.muted {
  background: #5d6888;
}

.summary-spacer {
  flex: 1;
}

.search-box {
  display: flex;
  gap: 8px;
  width: min(320px, 100%);
}

.agent-grid-wrap {
  min-height: 260px;
}

.list-pagination {
  display: flex;
  justify-content: center;
  margin-top: 20px;
}

.agent-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 14px;
}

.agent-card {
  display: flex;
  flex-direction: column;
  padding: 20px;
  transition: transform 0.2s ease, border-color 0.2s ease;
}

.agent-card:hover {
  transform: translateY(-2px);
  border-color: rgba(124, 92, 255, 0.26);
}

.card-top {
  display: flex;
  gap: 12px;
}

.agent-mark {
  display: grid;
  place-items: center;
  flex: none;
  width: 44px;
  height: 44px;
  border-radius: 13px;
  font-size: 18px;
  font-weight: 700;
  color: #fff;
  box-shadow: 0 8px 22px rgba(2, 4, 12, 0.28);
}

.palette-1 {
  background: linear-gradient(135deg, #8b5cf6, #6366f1);
}

.palette-2 {
  background: linear-gradient(135deg, #22d3ee, #0ea5e9);
}

.palette-3 {
  background: linear-gradient(135deg, #f472b6, #fb7185);
}

.palette-4 {
  background: linear-gradient(135deg, #34d399, #14b8a6);
}

.palette-5 {
  background: linear-gradient(135deg, #fbbf24, #f97316);
}

.agent-heading {
  min-width: 0;
  flex: 1;
}

.agent-title-row {
  display: flex;
  align-items: center;
  gap: 7px;
  flex-wrap: wrap;
}

.agent-title-row h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 630;
  color: var(--king-text);
}

.badge {
  padding: 2px 7px;
  border-radius: 5px;
  font-size: 8.5px;
  font-weight: 550;
}

.badge.published {
  color: var(--k-green);
  background: rgba(52, 211, 153, 0.1);
}

.badge.draft {
  color: var(--k-violet);
  background: rgba(124, 92, 255, 0.12);
}

.badge.disabled {
  color: var(--k-dim);
  background: var(--k-fill);
}

.badge.version {
  font-family: var(--king-mono);
  color: var(--k-sky);
  background: rgba(56, 189, 248, 0.1);
}

.version-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.version-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 10px;
  border-radius: 9px;
  border: 1px solid var(--king-line);
  background: var(--k-fill-soft);
  font-size: 11px;
  color: var(--king-muted);
}

.version-no {
  font-family: var(--king-mono);
  color: var(--king-text-soft);
}

.version-time {
  flex: 1;
}

.section-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.section-head .form-section-title {
  margin-bottom: 4px;
}

.section-head .field-hint {
  margin-top: 0;
}

.release-row {
  align-items: flex-start;
}

.version-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.version-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.version-meta {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  color: #7d89a8;
  font-size: 10.5px;
}

.version-summary,
.version-comment {
  margin: 0;
  font-size: 10.5px;
  line-height: 1.55;
}

.version-summary {
  color: #8b96b3;
}

.version-comment {
  color: #c9a2ff;
}

.version-note {
  font-size: 10px;
  color: var(--k-sky);
}

.version-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
  flex-wrap: wrap;
  max-width: 46%;
}

.eval-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.eval-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 10px;
  border: 1px solid var(--king-line);
  background: var(--k-fill-soft);
}

.eval-main {
  flex: 1;
  min-width: 0;
}

.eval-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--king-text-soft);
}

.eval-weight {
  font-size: 10px;
  color: #7d89a8;
}

.eval-prompt {
  margin: 6px 0 0;
  font-size: 11px;
  line-height: 1.55;
  color: var(--king-muted);
}

.eval-keywords {
  margin: 4px 0 0;
  font-size: 10.5px;
  color: #8b96b3;
}

.eval-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  flex: none;
}

.dialog-hint {
  margin: 0 0 8px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--king-muted);
}

.canary-slider {
  padding: 6px 4px 0;
}

.agent-intro {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  margin: 7px 0 0;
  font-size: 11.5px;
  line-height: 1.65;
  color: var(--king-muted);
  min-height: 38px;
}

.agent-meta {
  display: flex;
  flex-direction: column;
  gap: 7px;
  margin: 16px 0 13px;
  padding: 12px 13px;
  border-radius: 10px;
  background: var(--k-fill-soft);
  border: 1px solid var(--king-line);
}

.meta-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.meta-label {
  font-size: 10px;
  color: #596482;
}

.meta-value {
  font-size: 11px;
  color: var(--king-text-soft);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.meta-value.mono {
  font-family: var(--king-mono);
  font-size: 10px;
  color: #7f8bab;
}

.binding-row {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.binding-pill {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 8px;
  border-radius: 6px;
  font-size: 9.5px;
  color: var(--k-cyan);
  background: rgba(34, 211, 238, 0.055);
  border: 1px solid rgba(34, 211, 238, 0.12);
}

.binding-pill.off {
  color: #596482;
  background: var(--k-fill-soft);
  border-color: var(--king-line);
}

.agent-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: auto;
  padding-top: 16px;
}

.updated {
  font-size: 9px;
  color: #4f5a77;
}

.card-actions {
  display: flex;
  align-items: center;
  gap: 5px;
}

.card-actions :deep(.el-button) {
  height: 28px;
  padding: 0 10px;
  font-size: 10.5px;
}

.more-btn {
  width: 28px;
  padding: 0 !important;
}

.model-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
}

.model-id {
  font-family: var(--king-mono);
  font-size: 9.5px;
  color: var(--king-muted);
}

.form-section {
  margin-bottom: 24px;
  padding-bottom: 20px;
  border-bottom: 1px solid var(--king-line);
}

.form-section:last-child {
  border-bottom: none;
}

.form-section-title {
  margin-bottom: 14px;
  font-size: 12px;
  font-weight: 620;
  letter-spacing: 0.04em;
  color: var(--k-violet);
}

.inline-fields {
  display: flex;
  align-items: flex-start;
  gap: 18px;
}

.inline-fields .grow {
  flex: 1;
}

.slider-line {
  display: flex;
  align-items: center;
  gap: 14px;
  width: 100%;
}

.slider-line :deep(.el-slider) {
  flex: 1;
}

.slider-value {
  flex: none;
  width: 30px;
  font-family: var(--king-mono);
  font-size: 11px;
  color: var(--king-text-soft);
}

.field-hint {
  margin-top: 5px;
  font-size: 10px;
  line-height: 1.6;
  color: #596482;
}

.inline-hint {
  margin-top: -6px;
  font-size: 10.5px;
  color: var(--king-amber);
}

.drawer-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

@media (max-width: 640px) {
  .agent-grid {
    grid-template-columns: 1fr;
  }

  .agent-card {
    padding: 16px;
  }

  .inline-fields {
    flex-direction: column;
    gap: 0;
  }

  .summary-row {
    align-items: stretch;
  }

  .search-box {
    width: 100%;
  }

  .summary-spacer {
    display: none;
  }
}
</style>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Delete,
  Download,
  EditPen,
  Plus,
  Refresh,
  Search,
} from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { complianceApi } from '@/api'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import type {
  AccessReviewDecision,
  AccessReviewItemView,
  AccessReviewView,
  ComplianceSettingView,
  ExportCreateRequest,
  ExportFormat,
  ExportJobView,
  ExportStatus,
  ExportType,
  LegalHoldCreate,
  LegalHoldView,
  ModerationAction,
  ModerationMode,
  RetentionPolicyView,
  RetentionType,
} from '@/api/types'
import { formatDate, fromNow } from '@/utils/format'

type DecisionValue = Exclude<AccessReviewDecision, 'PENDING'>

const auth = useAuthStore()
const canManage = computed(() => auth.canManageSpace)
const canAudit = computed(() => auth.canAuditSpace)

const RETENTION_TYPES: Array<{ label: string; value: RetentionType }> = [
  { label: '会话', value: 'SESSION' },
  { label: '消息', value: 'MESSAGE' },
  { label: '审计日志', value: 'AUDIT' },
  { label: '导出文件', value: 'EXPORT' },
]

const RESOURCE_LABELS: Record<string, string> = Object.fromEntries(
  RETENTION_TYPES.map((item) => [item.value, item.label]),
)

const EXPORT_TYPES: Array<{ label: string; value: ExportType }> = [
  { label: '对话记录', value: 'CONVERSATIONS' },
  { label: '审计日志', value: 'AUDIT' },
  { label: '成员列表', value: 'MEMBERS' },
]

const MODERATION_MODES: Array<{ label: string; value: ModerationMode; hint: string }> = [
  { label: '关闭', value: 'OFF', hint: '不做内容审核' },
  { label: '本地规则', value: 'LOCAL', hint: '违禁词与 PII 规则' },
  { label: '外部服务', value: 'EXTERNAL', hint: '调用配置的外部审核接口' },
  { label: '混合', value: 'HYBRID', hint: '本地规则 + 外部服务' },
]

const ACTIVE_TAB_FALLBACK = 'reviews'
const activeTab = ref(canManage.value ? 'settings' : ACTIVE_TAB_FALLBACK)

function errorText(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback
}

// ---------------------------------------------------------------- settings

const settingsLoading = ref(false)
const settingsSaving = ref(false)
const settings = reactive({
  piiEnabled: false,
  moderationMode: 'OFF' as ModerationMode,
  inputAction: 'BLOCK' as ModerationAction,
  outputAction: 'BLOCK' as ModerationAction,
  blocklistText: '',
  patternsText: '',
})

function applySettings(view: ComplianceSettingView) {
  settings.piiEnabled = view.piiEnabled
  settings.moderationMode = view.moderationMode
  settings.inputAction = view.inputAction
  settings.outputAction = view.outputAction
  settings.blocklistText = (view.blocklist || []).join('\n')
  settings.patternsText = (view.customPiiPatterns || []).join('\n')
}

async function loadSettings() {
  settingsLoading.value = true
  try {
    applySettings(await complianceApi.settings())
  } catch (error) {
    ElMessage.error(errorText(error, '加载合规配置失败'))
  } finally {
    settingsLoading.value = false
  }
}

async function saveSettings() {
  settingsSaving.value = true
  try {
    applySettings(
      await complianceApi.updateSettings({
        piiEnabled: settings.piiEnabled,
        moderationMode: settings.moderationMode,
        inputAction: settings.inputAction,
        outputAction: settings.outputAction,
        blocklist: settings.blocklistText,
        customPiiPatterns: settings.patternsText,
      }),
    )
    ElMessage.success('合规配置已保存')
  } catch (error) {
    ElMessage.error(errorText(error, '保存合规配置失败'))
  } finally {
    settingsSaving.value = false
  }
}

// ---------------------------------------------------------------- retention

const policies = ref<RetentionPolicyView[]>([])
const policiesLoading = ref(false)
const policyDialog = ref(false)
const policySaving = ref(false)
const editingPolicyType = ref<RetentionType | null>(null)
const policyForm = reactive({
  resourceType: 'SESSION' as RetentionType,
  retentionDays: 180,
  enabled: true,
  archiveBeforeDelete: true,
})

const createPolicyOptions = computed(() =>
  RETENTION_TYPES.filter(
    (item) => !policies.value.some((policy) => policy.resourceType === item.value),
  ),
)
const archiveUnsupported = computed(
  () => policyForm.resourceType === 'MESSAGE' || policyForm.resourceType === 'AUDIT',
)

async function loadPolicies() {
  policiesLoading.value = true
  try {
    policies.value = await complianceApi.retentionPolicies()
  } catch (error) {
    ElMessage.error(errorText(error, '加载留存策略失败'))
  } finally {
    policiesLoading.value = false
  }
}

function openPolicyCreate() {
  if (!createPolicyOptions.value.length) {
    ElMessage.info('所有资源类型都已配置留存策略')
    return
  }
  const type = createPolicyOptions.value[0].value
  editingPolicyType.value = null
  Object.assign(policyForm, {
    resourceType: type,
    retentionDays: 180,
    enabled: true,
    archiveBeforeDelete: type !== 'MESSAGE' && type !== 'AUDIT',
  })
  policyDialog.value = true
}

function openPolicyEdit(policy: RetentionPolicyView) {
  editingPolicyType.value = policy.resourceType
  Object.assign(policyForm, {
    resourceType: policy.resourceType,
    retentionDays: policy.retentionDays,
    enabled: policy.enabled,
    archiveBeforeDelete: policy.archiveBeforeDelete,
  })
  policyDialog.value = true
}

function onPolicyTypeChange() {
  if (archiveUnsupported.value) policyForm.archiveBeforeDelete = false
}

async function submitPolicy() {
  if (policyForm.retentionDays < 1 || policyForm.retentionDays > 3650) {
    ElMessage.warning('保留天数需在 1–3650 之间')
    return
  }
  policySaving.value = true
  try {
    await complianceApi.upsertRetentionPolicy({
      resourceType: policyForm.resourceType,
      retentionDays: policyForm.retentionDays,
      enabled: policyForm.enabled,
      archiveBeforeDelete:
        archiveUnsupported.value ? false : policyForm.archiveBeforeDelete,
    })
    ElMessage.success('留存策略已保存')
    policyDialog.value = false
    await loadPolicies()
  } catch (error) {
    ElMessage.error(errorText(error, '保存留存策略失败'))
  } finally {
    policySaving.value = false
  }
}

// ---------------------------------------------------------------- legal holds

const holds = ref<LegalHoldView[]>([])
const holdsLoading = ref(false)
const holdDialog = ref(false)
const holdSaving = ref(false)
const holdForm = reactive({
  resourceType: 'SESSION' as RetentionType,
  resourceId: 1,
  reason: '',
  until: null as string | null,
})

async function loadHolds() {
  holdsLoading.value = true
  try {
    holds.value = await complianceApi.legalHolds()
  } catch (error) {
    ElMessage.error(errorText(error, '加载法律保留失败'))
  } finally {
    holdsLoading.value = false
  }
}

function openHoldCreate() {
  Object.assign(holdForm, {
    resourceType: 'SESSION',
    resourceId: 1,
    reason: '',
    until: null,
  })
  holdDialog.value = true
}

async function submitHold() {
  if (!holdForm.reason.trim()) {
    ElMessage.warning('请填写法律保留原因')
    return
  }
  if (!holdForm.resourceId || holdForm.resourceId < 1) {
    ElMessage.warning('请填写有效的资源 ID')
    return
  }
  holdSaving.value = true
  try {
    const payload: LegalHoldCreate = {
      resourceType: holdForm.resourceType,
      resourceId: holdForm.resourceId,
      reason: holdForm.reason.trim(),
      until: holdForm.until || null,
    }
    await complianceApi.createLegalHold(payload)
    ElMessage.success('法律保留已创建')
    holdDialog.value = false
    await loadHolds()
  } catch (error) {
    ElMessage.error(errorText(error, '创建法律保留失败'))
  } finally {
    holdSaving.value = false
  }
}

async function releaseHold(hold: LegalHoldView) {
  try {
    await ElMessageBox.confirm(
      `确认解除对 ${RESOURCE_LABELS[hold.resourceType] || hold.resourceType} #${hold.resourceId} 的法律保留？解除后到期数据可能被清理。`,
      '解除法律保留',
      { type: 'warning', confirmButtonText: '解除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await complianceApi.releaseLegalHold(hold.id)
    ElMessage.success('法律保留已解除')
    await loadHolds()
  } catch (error) {
    ElMessage.error(errorText(error, '解除法律保留失败'))
  }
}

// ---------------------------------------------------------------- exports

const exportJobs = ref<ExportJobView[]>([])
const exportTotal = ref(0)
const exportPage = ref(1)
const exportSize = ref(10)
const exportsLoading = ref(false)
const exportDialog = ref(false)
const exportSaving = ref(false)
const downloadingId = ref<number | null>(null)
const exportForm = reactive({
  type: 'CONVERSATIONS' as ExportType,
  format: 'CSV' as ExportFormat,
  range: null as [string, string] | null,
})
let exportTimer: number | undefined

async function loadExports(silent = false) {
  if (!silent) exportsLoading.value = true
  try {
    const result = await complianceApi.exports({
      page: exportPage.value,
      size: exportSize.value,
    })
    exportJobs.value = result.list
    exportTotal.value = result.total
  } catch (error) {
    if (!silent) ElMessage.error(errorText(error, '加载导出任务失败'))
  } finally {
    if (!silent) exportsLoading.value = false
  }
}

function stopExportPolling() {
  if (exportTimer) {
    window.clearInterval(exportTimer)
    exportTimer = undefined
  }
}

function ensureExportPolling() {
  stopExportPolling()
  const pending = exportJobs.value.some(
    (job) => job.status === 'PENDING' || job.status === 'RUNNING',
  )
  if (!pending) return
  exportTimer = window.setInterval(async () => {
    await loadExports(true)
    if (
      !exportJobs.value.some(
        (job) => job.status === 'PENDING' || job.status === 'RUNNING',
      )
    ) {
      stopExportPolling()
    }
  }, 4000)
}

function openExportCreate() {
  Object.assign(exportForm, {
    type: 'CONVERSATIONS',
    format: 'CSV',
    range: null,
  })
  exportDialog.value = true
}

async function submitExport() {
  exportSaving.value = true
  try {
    const payload: ExportCreateRequest = {
      type: exportForm.type,
      format: exportForm.format,
      from: exportForm.range?.[0] || null,
      to: exportForm.range?.[1] || null,
    }
    await complianceApi.createExport(payload)
    ElMessage.success('导出任务已创建，后台完成后可下载')
    exportDialog.value = false
    exportPage.value = 1
    await loadExports()
    ensureExportPolling()
  } catch (error) {
    ElMessage.error(errorText(error, '创建导出任务失败'))
  } finally {
    exportSaving.value = false
  }
}

function exportStatusMeta(status: ExportStatus): {
  text: string
  type: 'primary' | 'success' | 'info' | 'warning' | 'danger'
} {
  switch (status) {
    case 'PENDING':
      return { text: '排队中', type: 'info' }
    case 'RUNNING':
      return { text: '生成中', type: 'warning' }
    case 'COMPLETED':
      return { text: '已完成', type: 'success' }
    case 'FAILED':
      return { text: '失败', type: 'danger' }
    case 'EXPIRED':
      return { text: '已过期', type: 'info' }
    default:
      return { text: status || '未知', type: 'info' }
  }
}

async function downloadExport(job: ExportJobView) {
  downloadingId.value = job.id
  try {
    await complianceApi.downloadExport(job)
  } catch (error) {
    ElMessage.error(errorText(error, '下载导出文件失败'))
  } finally {
    downloadingId.value = null
  }
}

function onExportPageChange(value: number) {
  exportPage.value = value
  loadExports()
}

// ---------------------------------------------------------------- access reviews

const reviews = ref<AccessReviewView[]>([])
const reviewsLoading = ref(false)
const reviewGenerating = ref(false)
const reviewDrawer = ref(false)
const reviewDetailLoading = ref(false)
const selectedReview = ref<AccessReviewView | null>(null)
const decisionDialog = ref(false)
const decisionSaving = ref(false)
const decisionTarget = ref<AccessReviewItemView | null>(null)
const decisionForm = reactive({
  decision: 'CONFIRM' as DecisionValue,
  note: '',
})

const STATUS_LABELS: Record<string, string> = {
  OPEN: '进行中',
  COMPLETED: '已完成',
  SUPERSEDED: '已被替代',
}

const SUBJECT_LABELS: Record<string, string> = {
  MEMBER: '成员',
  SUPPLIER: '供应商密钥',
  MCP: 'MCP 配置',
  PUBLIC_AGENT: '公开智能体',
}

const DECISION_LABELS: Record<string, string> = {
  PENDING: '待处理',
  CONFIRM: '已确认',
  ACKNOWLEDGE: '已知悉',
  DOWNGRADE: '已降级',
  REMOVE: '已移除',
}

const DECISION_ACTIONS: Record<string, string> = {
  CONFIRM: '确认保留',
  ACKNOWLEDGE: '知悉',
  DOWNGRADE: '降级',
  REMOVE: '移除',
}

const FLAG_LABELS: Record<string, string> = {
  NEVER_LOGGED_IN: '从未登录',
  OWNER: '所有者',
  STALE_CREDENTIAL: '凭据长期未更新',
  PUBLIC_EXPOSURE: '公开暴露',
}

function flagLabel(flag: string): string {
  if (flag.startsWith('UNUSED_')) return `${flag.slice(7, -1)} 天未使用`
  return FLAG_LABELS[flag] || flag
}

function pendingCount(review: AccessReviewView): number {
  return review.items.filter((item) => item.decision === 'PENDING').length
}

function decisionOptions(item: AccessReviewItemView): DecisionValue[] {
  return item.subjectType === 'MEMBER'
    ? ['CONFIRM', 'DOWNGRADE', 'REMOVE']
    : ['CONFIRM', 'ACKNOWLEDGE']
}

async function loadReviews() {
  reviewsLoading.value = true
  try {
    reviews.value = await complianceApi.accessReviews()
  } catch (error) {
    ElMessage.error(errorText(error, '加载访问复核失败'))
  } finally {
    reviewsLoading.value = false
  }
}

async function generateReview() {
  reviewGenerating.value = true
  try {
    const review = await complianceApi.generateAccessReview()
    ElMessage.success('访问复核已生成')
    await loadReviews()
    await openReview(review)
  } catch (error) {
    ElMessage.error(errorText(error, '生成访问复核失败'))
  } finally {
    reviewGenerating.value = false
  }
}

async function openReview(review: AccessReviewView) {
  selectedReview.value = review
  reviewDrawer.value = true
  reviewDetailLoading.value = true
  try {
    selectedReview.value = await complianceApi.accessReview(review.id)
  } catch (error) {
    ElMessage.error(errorText(error, '加载复核详情失败'))
  } finally {
    reviewDetailLoading.value = false
  }
}

function openDecision(item: AccessReviewItemView) {
  decisionTarget.value = item
  decisionForm.decision = decisionOptions(item)[0]
  decisionForm.note = ''
  decisionDialog.value = true
}

async function submitDecision() {
  if (!selectedReview.value || !decisionTarget.value) return
  decisionSaving.value = true
  try {
    const reviewId = selectedReview.value.id
    await complianceApi.decideAccessReview(reviewId, decisionTarget.value.id, {
      decision: decisionForm.decision,
      note: decisionForm.note.trim() || undefined,
    })
    decisionDialog.value = false
    selectedReview.value = await complianceApi.accessReview(reviewId)
    await loadReviews()
    ElMessage.success('复核决策已提交')
  } catch (error) {
    ElMessage.error(errorText(error, '提交复核决策失败'))
  } finally {
    decisionSaving.value = false
  }
}

async function downloadReviewCsv(review: AccessReviewView) {
  try {
    await complianceApi.downloadAccessReviewCsv(review.id)
  } catch (error) {
    ElMessage.error(errorText(error, '下载复核报告失败'))
  }
}

// ---------------------------------------------------------------- lifecycle

onMounted(async () => {
  if (!canAudit.value) return
  const tasks: Array<Promise<unknown>> = [loadPolicies(), loadHolds(), loadReviews()]
  if (canManage.value) {
    tasks.push(loadSettings(), loadExports())
  }
  await Promise.allSettled(tasks)
  if (canManage.value) ensureExportPolling()
})

onBeforeUnmount(stopExportPolling)
</script>

<template>
  <div class="page">
    <PageHeader
      title="合规中心"
      description="统一管理内容审核、数据留存、法律保留、数据导出与访问复核。管理员可修改配置，审计员可查看与处理复核项。"
      eyebrow="Governance"
    >
      <template #actions>
        <el-button v-if="canManage" @click="loadSettings">
          <el-icon><Refresh /></el-icon>
          刷新配置
        </el-button>
      </template>
    </PageHeader>

    <EmptyState
      v-if="!canAudit"
      title="需要管理员或审计员权限"
      description="当前空间角色无权访问合规中心，请联系空间管理员。"
    />

    <template v-else>
      <el-tabs v-model="activeTab" class="compliance-tabs">
        <el-tab-pane v-if="canManage" label="合规设置" name="settings">
          <div v-loading="settingsLoading" class="panel panel-pad setting-panel">
            <div class="section-head">
              <div>
                <h3>内容审核与脱敏</h3>
                <p>审核配置按空间生效，保存后一分钟内对所有会话生效。</p>
              </div>
              <el-button type="primary" :loading="settingsSaving" @click="saveSettings">
                保存配置
              </el-button>
            </div>

            <el-form label-position="top">
              <div class="form-grid">
                <el-form-item label="审核模式">
                  <el-select v-model="settings.moderationMode" style="width: 100%">
                    <el-option
                      v-for="mode in MODERATION_MODES"
                      :key="mode.value"
                      :label="mode.label"
                      :value="mode.value"
                    >
                      <span>{{ mode.label }}</span>
                      <span class="option-hint">{{ mode.hint }}</span>
                    </el-option>
                  </el-select>
                </el-form-item>
                <el-form-item label="PII 脱敏">
                  <el-switch
                    v-model="settings.piiEnabled"
                    active-text="启用"
                    inactive-text="关闭"
                  />
                </el-form-item>
                <el-form-item label="输入命中动作">
                  <el-radio-group v-model="settings.inputAction">
                    <el-radio-button value="BLOCK">拦截</el-radio-button>
                    <el-radio-button value="WARN">告警</el-radio-button>
                  </el-radio-group>
                </el-form-item>
                <el-form-item label="输出命中动作">
                  <el-radio-group v-model="settings.outputAction">
                    <el-radio-button value="BLOCK">拦截</el-radio-button>
                    <el-radio-button value="WARN">告警</el-radio-button>
                  </el-radio-group>
                </el-form-item>
              </div>
              <el-form-item label="违禁词（每行一个，也支持逗号分隔，最多 500 条）">
                <el-input
                  v-model="settings.blocklistText"
                  type="textarea"
                  :rows="6"
                  maxlength="20000"
                  show-word-limit
                  placeholder="示例：&#10;内部机密&#10;竞品名称"
                />
              </el-form-item>
              <el-form-item label="自定义 PII 正则（每行一条，最多 200 字符）">
                <el-input
                  v-model="settings.patternsText"
                  type="textarea"
                  :rows="4"
                  maxlength="8000"
                  show-word-limit
                  placeholder="示例：\bEMP-\d{6}\b"
                />
                <div class="form-hint">
                  正则保存后立即用于输出脱敏，需避免无界嵌套量词（如 <code>(a+)+</code>）。
                </div>
              </el-form-item>
            </el-form>
          </div>
        </el-tab-pane>

        <el-tab-pane label="留存策略" name="retention">
          <div class="panel table-panel">
            <div class="section-bar">
              <div>
                <h3>数据留存</h3>
                <p>到期数据由后台任务清理；存在法律保留的数据会被跳过。</p>
              </div>
              <el-button v-if="canManage" type="primary" @click="openPolicyCreate">
                <el-icon><Plus /></el-icon>
                新增策略
              </el-button>
            </div>
            <el-table v-loading="policiesLoading" :data="policies" style="width: 100%">
              <el-table-column label="资源类型" min-width="140">
                <template #default="{ row }">
                  {{ RESOURCE_LABELS[row.resourceType] || row.resourceType }}
                </template>
              </el-table-column>
              <el-table-column label="保留天数" width="120">
                <template #default="{ row }">{{ row.retentionDays }} 天</template>
              </el-table-column>
              <el-table-column label="状态" width="110">
                <template #default="{ row }">
                  <el-tag :type="row.enabled ? 'success' : 'info'" effect="plain">
                    {{ row.enabled ? '启用' : '停用' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="删除前归档" width="130">
                <template #default="{ row }">
                  <span :class="{ muted: !row.archiveBeforeDelete }">
                    {{ row.archiveBeforeDelete ? '开启' : '关闭' }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column v-if="canManage" label="操作" width="110" fixed="right">
                <template #default="{ row }">
                  <el-button size="small" text @click="openPolicyEdit(row)">
                    <el-icon><EditPen /></el-icon>
                    编辑
                  </el-button>
                </template>
              </el-table-column>
              <template #empty>
                <EmptyState
                  title="尚未配置留存策略"
                  description="未配置的资源类型不会被自动清理。"
                />
              </template>
            </el-table>
          </div>
        </el-tab-pane>

        <el-tab-pane label="法律保留" name="holds">
          <div class="panel table-panel">
            <div class="section-bar">
              <div>
                <h3>法律保留</h3>
                <p>生效中的保留会阻止到期清理，解除后恢复常规留存策略。</p>
              </div>
              <el-button v-if="canManage" type="primary" @click="openHoldCreate">
                <el-icon><Plus /></el-icon>
                新建保留
              </el-button>
            </div>
            <el-table v-loading="holdsLoading" :data="holds" style="width: 100%">
              <el-table-column label="资源" min-width="170">
                <template #default="{ row }">
                  {{ RESOURCE_LABELS[row.resourceType] || row.resourceType }}
                  <span class="mono">#{{ row.resourceId }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="reason" label="原因" min-width="220" show-overflow-tooltip />
              <el-table-column label="截止时间" width="165">
                <template #default="{ row }">
                  {{ row.until ? formatDate(row.until) : '长期有效' }}
                </template>
              </el-table-column>
              <el-table-column label="状态" width="110">
                <template #default="{ row }">
                  <el-tag :type="row.releasedAt ? 'info' : 'warning'" effect="plain">
                    {{ row.releasedAt ? '已解除' : '生效中' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="创建时间" width="165">
                <template #default="{ row }">
                  <span :title="formatDate(row.createdAt)">{{ fromNow(row.createdAt) }}</span>
                </template>
              </el-table-column>
              <el-table-column v-if="canManage" label="操作" width="110" fixed="right">
                <template #default="{ row }">
                  <el-button
                    size="small"
                    text
                    type="danger"
                    :disabled="!!row.releasedAt"
                    @click="releaseHold(row)"
                  >
                    <el-icon><Delete /></el-icon>
                    解除
                  </el-button>
                </template>
              </el-table-column>
              <template #empty>
                <EmptyState
                  title="没有法律保留"
                  description="需要阻止某条数据被清理时，可以在此创建保留。"
                />
              </template>
            </el-table>
          </div>
        </el-tab-pane>

        <el-tab-pane v-if="canManage" label="数据导出" name="exports">
          <div class="panel table-panel">
            <div class="section-bar">
              <div>
                <h3>数据导出</h3>
                <p>导出任务由后台调度执行，完成后可在有效期内下载。</p>
              </div>
              <el-button type="primary" @click="openExportCreate">
                <el-icon><Plus /></el-icon>
                新建导出
              </el-button>
            </div>
            <el-table v-loading="exportsLoading" :data="exportJobs" style="width: 100%">
              <el-table-column label="任务" width="90">
                <template #default="{ row }"><span class="mono">#{{ row.id }}</span></template>
              </el-table-column>
              <el-table-column label="类型" width="120">
                <template #default="{ row }">
                  {{ EXPORT_TYPES.find((item) => item.value === row.exportType)?.label || row.exportType }}
                </template>
              </el-table-column>
              <el-table-column prop="format" label="格式" width="90" />
              <el-table-column label="状态" width="110">
                <template #default="{ row }">
                  <el-tag :type="exportStatusMeta(row.status).type" effect="plain">
                    {{ exportStatusMeta(row.status).text }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="行数" width="100">
                <template #default="{ row }">{{ row.rowCount }}</template>
              </el-table-column>
              <el-table-column label="创建时间" width="160">
                <template #default="{ row }">
                  <span :title="formatDate(row.createdAt)">{{ fromNow(row.createdAt) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="过期时间" width="160">
                <template #default="{ row }">
                  {{ row.expiresAt ? formatDate(row.expiresAt) : '—' }}
                </template>
              </el-table-column>
              <el-table-column label="说明" min-width="180" show-overflow-tooltip>
                <template #default="{ row }">
                  <span v-if="row.errorMessage" class="danger-text">{{ row.errorMessage }}</span>
                  <span v-else class="muted">{{ row.fileName || '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="110" fixed="right">
                <template #default="{ row }">
                  <el-button
                    size="small"
                    text
                    :disabled="row.status !== 'COMPLETED'"
                    :loading="downloadingId === row.id"
                    @click="downloadExport(row)"
                  >
                    <el-icon><Download /></el-icon>
                    下载
                  </el-button>
                </template>
              </el-table-column>
              <template #empty>
                <EmptyState
                  title="还没有导出任务"
                  description="按类型和时间范围创建导出，用于审计留档或数据迁移。"
                />
              </template>
            </el-table>

            <div v-if="exportTotal > exportSize" class="pagination-row">
              <el-pagination
                v-model:current-page="exportPage"
                v-model:page-size="exportSize"
                :total="exportTotal"
                layout="prev, pager, next"
                background
                @current-change="onExportPageChange"
              />
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="访问复核" name="reviews">
          <div class="panel table-panel">
            <div class="section-bar">
              <div>
                <h3>访问复核</h3>
                <p>定期复核成员权限、供应商密钥与公开智能体，保留处理记录。</p>
              </div>
              <el-button
                v-if="canManage"
                type="primary"
                :loading="reviewGenerating"
                @click="generateReview"
              >
                <el-icon><Search /></el-icon>
                生成复核
              </el-button>
            </div>
            <el-table v-loading="reviewsLoading" :data="reviews" style="width: 100%">
              <el-table-column label="复核" width="90">
                <template #default="{ row }"><span class="mono">#{{ row.id }}</span></template>
              </el-table-column>
              <el-table-column label="状态" width="120">
                <template #default="{ row }">
                  <el-tag
                    :type="row.status === 'OPEN' ? 'warning' : row.status === 'COMPLETED' ? 'success' : 'info'"
                    effect="plain"
                  >
                    {{ STATUS_LABELS[row.status] || row.status }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="summary" label="发现项" min-width="140" />
              <el-table-column label="待处理" width="100">
                <template #default="{ row }">{{ pendingCount(row) }}</template>
              </el-table-column>
              <el-table-column label="生成时间" width="170">
                <template #default="{ row }">
                  <span :title="formatDate(row.generatedAt)">{{ fromNow(row.generatedAt) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="230" fixed="right">
                <template #default="{ row }">
                  <el-button size="small" text @click="openReview(row)">查看</el-button>
                  <el-button size="small" text @click="downloadReviewCsv(row)">
                    <el-icon><Download /></el-icon>
                    导出 CSV
                  </el-button>
                </template>
              </el-table-column>
              <template #empty>
                <EmptyState
                  title="还没有访问复核"
                  description="生成一次复核以检查长期未使用权限和公开暴露面。"
                />
              </template>
            </el-table>
          </div>
        </el-tab-pane>
      </el-tabs>
    </template>

    <el-dialog v-model="policyDialog" :title="editingPolicyType ? '编辑留存策略' : '新增留存策略'" width="480px">
      <el-form label-position="top">
        <el-form-item label="资源类型">
          <el-select
            v-model="policyForm.resourceType"
            :disabled="!!editingPolicyType"
            style="width: 100%"
            @change="onPolicyTypeChange"
          >
            <el-option
              v-for="item in editingPolicyType ? RETENTION_TYPES : createPolicyOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="保留天数">
          <el-input-number v-model="policyForm.retentionDays" :min="1" :max="3650" style="width: 100%" />
          <div class="form-hint">允许范围 1–3650 天，到期后由后台任务清理。</div>
        </el-form-item>
        <el-form-item label="启用策略">
          <el-switch v-model="policyForm.enabled" active-text="启用" inactive-text="停用" />
        </el-form-item>
        <el-form-item label="删除前归档">
          <el-switch
            v-model="policyForm.archiveBeforeDelete"
            :disabled="archiveUnsupported"
            active-text="开启"
            inactive-text="关闭"
          />
          <div class="form-hint">
            <template v-if="archiveUnsupported">MESSAGE/AUDIT 暂不支持删除前归档。</template>
            <template v-else>目前仅会话（SESSION）实现了删除前归档。</template>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="policyDialog = false">取消</el-button>
        <el-button type="primary" :loading="policySaving" @click="submitPolicy">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="holdDialog" title="新建法律保留" width="500px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="资源类型">
            <el-select v-model="holdForm.resourceType" style="width: 100%">
              <el-option
                v-for="item in RETENTION_TYPES"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="资源 ID">
            <el-input-number v-model="holdForm.resourceId" :min="1" style="width: 100%" />
          </el-form-item>
        </div>
        <el-form-item label="保留原因">
          <el-input
            v-model="holdForm.reason"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="例如：诉讼保全，案号 2026-XXX"
          />
        </el-form-item>
        <el-form-item label="截止时间（留空表示长期有效）">
          <el-date-picker
            v-model="holdForm.until"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="选择截止时间"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="holdDialog = false">取消</el-button>
        <el-button type="primary" :loading="holdSaving" @click="submitHold">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="exportDialog" title="新建数据导出" width="500px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="导出类型">
            <el-select v-model="exportForm.type" style="width: 100%">
              <el-option
                v-for="item in EXPORT_TYPES"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="文件格式">
            <el-radio-group v-model="exportForm.format">
              <el-radio-button value="CSV">CSV</el-radio-button>
              <el-radio-button value="JSONL">JSONL</el-radio-button>
            </el-radio-group>
          </el-form-item>
        </div>
        <el-form-item label="时间范围（留空表示全部数据）">
          <el-date-picker
            v-model="exportForm.range"
            type="datetimerange"
            value-format="YYYY-MM-DDTHH:mm:ss"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="exportDialog = false">取消</el-button>
        <el-button type="primary" :loading="exportSaving" @click="submitExport">创建导出</el-button>
      </template>
    </el-dialog>

    <el-drawer
      v-model="reviewDrawer"
      :title="selectedReview ? `访问复核 #${selectedReview.id}` : '访问复核'"
      size="860px"
      destroy-on-close
    >
      <div v-loading="reviewDetailLoading">
        <template v-if="selectedReview">
          <div class="review-summary">
            <div class="summary-item">
              <span class="summary-label">状态</span>
              <el-tag
                :type="selectedReview.status === 'OPEN' ? 'warning' : selectedReview.status === 'COMPLETED' ? 'success' : 'info'"
                effect="plain"
              >
                {{ STATUS_LABELS[selectedReview.status] || selectedReview.status }}
              </el-tag>
            </div>
            <div class="summary-item">
              <span class="summary-label">生成时间</span>
              <span>{{ formatDate(selectedReview.generatedAt) }}</span>
            </div>
            <div class="summary-item">
              <span class="summary-label">发现项</span>
              <span>{{ selectedReview.items.length }}</span>
            </div>
            <div class="summary-spacer"></div>
            <el-button @click="downloadReviewCsv(selectedReview)">
              <el-icon><Download /></el-icon>
              导出 CSV
            </el-button>
          </div>

          <el-table :data="selectedReview.items" style="width: 100%">
            <el-table-column label="对象" min-width="210">
              <template #default="{ row }">
                <div class="subject-name">{{ row.subjectName }}</div>
                <div class="subject-meta">
                  {{ SUBJECT_LABELS[row.subjectType] || row.subjectType }}
                  <template v-if="row.subjectType === 'MEMBER'"> · {{ row.role }}</template>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="风险标记" min-width="170">
              <template #default="{ row }">
                <div class="flag-list">
                  <el-tag
                    v-for="flag in row.flags"
                    :key="flag"
                    size="small"
                    type="warning"
                    effect="plain"
                  >
                    {{ flagLabel(flag) }}
                  </el-tag>
                  <span v-if="!row.flags.length" class="muted">—</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="最近活动" width="150">
              <template #default="{ row }">
                {{ row.lastActivityAt ? formatDate(row.lastActivityAt) : '无记录' }}
              </template>
            </el-table-column>
            <el-table-column label="决策" width="110">
              <template #default="{ row }">
                <el-tag
                  :type="row.decision === 'PENDING' ? 'info' : 'success'"
                  effect="plain"
                >
                  {{ DECISION_LABELS[row.decision] || row.decision }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="100" fixed="right">
              <template #default="{ row }">
                <el-button
                  size="small"
                  text
                  :disabled="selectedReview.status !== 'OPEN' || row.decision !== 'PENDING'"
                  @click="openDecision(row)"
                >
                  处理
                </el-button>
              </template>
            </el-table-column>
            <template #empty>
              <EmptyState title="本次复核没有发现项" description="当前权限配置未命中风险规则。" />
            </template>
          </el-table>
        </template>
      </div>
    </el-drawer>

    <el-dialog v-model="decisionDialog" title="处理复核项" width="460px">
      <div v-if="decisionTarget" class="decision-target">
        <strong>{{ decisionTarget.subjectName }}</strong>
        <span class="muted">
          {{ SUBJECT_LABELS[decisionTarget.subjectType] || decisionTarget.subjectType }}
          <template v-if="decisionTarget.subjectType === 'MEMBER'">
            · {{ decisionTarget.role }}
          </template>
        </span>
      </div>
      <el-form label-position="top">
        <el-form-item label="决策">
          <el-radio-group v-model="decisionForm.decision">
            <el-radio-button
              v-for="option in decisionTarget ? decisionOptions(decisionTarget) : []"
              :key="option"
              :value="option"
            >
              {{ DECISION_ACTIONS[option] }}
            </el-radio-button>
          </el-radio-group>
          <div v-if="decisionTarget && decisionTarget.subjectType !== 'MEMBER'" class="form-hint">
            供应商 / MCP / 公开智能体仅支持确认或知悉，请到对应模块执行实际变更。
          </div>
          <div v-else class="form-hint">
            降级与移除会立即修改成员在空间内的角色。
          </div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="decisionForm.note"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="填写处理依据（可选）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="decisionDialog = false">取消</el-button>
        <el-button type="primary" :loading="decisionSaving" @click="submitDecision">
          提交
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.compliance-tabs {
  margin-top: 20px;
}

.setting-panel {
  padding: 22px 24px 10px;
}

.section-head,
.section-bar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  margin-bottom: 18px;
}

.section-bar {
  padding: 12px 12px 4px;
  margin-bottom: 4px;
}

.section-head h3,
.section-bar h3 {
  margin: 0 0 5px;
  font-size: 14px;
  font-weight: 650;
  color: var(--king-text);
}

.section-head p,
.section-bar p {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--king-muted);
}

.table-panel {
  padding: 6px 12px 14px;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 20px;
}

.form-hint {
  margin-top: 6px;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--king-muted);
}

.form-hint code {
  font-family: var(--king-mono);
}

.option-hint {
  margin-left: 10px;
  font-size: 11px;
  color: var(--king-muted);
}

.muted {
  color: var(--king-muted);
}

.danger-text {
  color: var(--k-red);
}

.pagination-row {
  display: flex;
  justify-content: flex-end;
  padding-top: 14px;
}

.review-summary {
  display: flex;
  align-items: center;
  gap: 24px;
  margin-bottom: 18px;
  padding: 14px 16px;
  border-radius: 12px;
  border: 1px solid var(--king-line);
  background: var(--k-fill-soft);
}

.summary-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12.5px;
}

.summary-label {
  font-size: 10.5px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--king-muted);
}

.summary-spacer {
  flex: 1;
}

.subject-name {
  font-size: 13px;
  font-weight: 580;
  color: var(--king-text);
}

.subject-meta {
  margin-top: 2px;
  font-size: 11px;
  color: var(--king-muted);
}

.flag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.decision-target {
  display: flex;
  flex-direction: column;
  gap: 3px;
  margin-bottom: 16px;
  padding: 10px 12px;
  border-radius: 10px;
  border: 1px solid var(--king-line);
  background: var(--k-fill-soft);
  font-size: 13px;
}

@media (max-width: 720px) {
  .form-grid {
    grid-template-columns: 1fr;
  }

  .section-head,
  .section-bar {
    flex-direction: column;
  }

  .review-summary {
    flex-wrap: wrap;
  }
}
</style>

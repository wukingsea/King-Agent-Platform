<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { auditApi, spaceApi } from '@/api'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import type { AuditLogView, SpaceView } from '@/api/types'
import { formatDate, fromNow } from '@/utils/format'

const auth = useAuthStore()

const loading = ref(false)
const logs = ref<AuditLogView[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(20)
const spaces = ref<SpaceView[]>([])

const filters = reactive({
  action: '',
  status: '',
  actorId: undefined as number | undefined,
  spaceId: undefined as number | undefined,
  range: null as [string, string] | null,
})

const detailVisible = ref(false)
const detail = ref<AuditLogView | null>(null)

const STATUS_META: Record<string, { text: string; type: 'success' | 'danger' | 'warning' | 'info' }> = {
  SUCCESS: { text: '成功', type: 'success' },
  FAILURE: { text: '失败', type: 'danger' },
  DENIED: { text: '拒绝', type: 'warning' },
}

function statusMeta(status: string) {
  return STATUS_META[status] || { text: status || '未知', type: 'info' as const }
}

function actorText(log: AuditLogView): string {
  if (log.actorName) return log.actorName
  return log.actorType === 'SYSTEM' ? '系统' : `#${log.actorId ?? '-'}`
}

function errorText(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback
}

async function loadLogs() {
  loading.value = true
  try {
    const result = await auditApi.page({
      action: filters.action.trim() || undefined,
      status: filters.status || undefined,
      actorId: filters.actorId || undefined,
      spaceId: auth.isAdmin ? filters.spaceId : undefined,
      from: filters.range?.[0] || undefined,
      to: filters.range?.[1] || undefined,
      page: pageNo.value,
      size: pageSize.value,
    })
    logs.value = result.list
    total.value = result.total
    if (!result.list.length && pageNo.value > 1) {
      pageNo.value = 1
      await loadLogs()
    }
  } catch (error) {
    ElMessage.error(errorText(error, '加载审计日志失败'))
  } finally {
    loading.value = false
  }
}

function search() {
  pageNo.value = 1
  loadLogs()
}

function resetFilters() {
  Object.assign(filters, {
    action: '',
    status: '',
    actorId: undefined,
    spaceId: undefined,
    range: null,
  })
  search()
}

function openDetail(log: AuditLogView) {
  detail.value = log
  detailVisible.value = true
}

async function loadSpaces() {
  if (!auth.isAdmin) return
  try {
    spaces.value = await spaceApi.list()
  } catch {
    /* the space filter is optional */
  }
}

onMounted(() => {
  loadLogs()
  loadSpaces()
})
</script>

<template>
  <div class="page">
    <PageHeader
      title="审计日志"
      description="记录登录、权限变更、数据操作与导出下载等关键事件。可按动作前缀、状态、操作人与时间范围检索。"
      eyebrow="Audit"
    >
      <template #actions>
        <el-button :loading="loading" @click="loadLogs">
          <el-icon><Refresh /></el-icon>
          刷新
        </el-button>
      </template>
    </PageHeader>

    <div class="filter-panel panel">
      <div class="filter-row">
        <el-input
          v-model="filters.action"
          placeholder="动作前缀，如 compliance."
          clearable
          class="filter-action"
          @keyup.enter="search"
          @clear="search"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-select v-model="filters.status" placeholder="全部状态" clearable class="filter-select">
          <el-option label="成功" value="SUCCESS" />
          <el-option label="失败" value="FAILURE" />
          <el-option label="拒绝" value="DENIED" />
        </el-select>
        <el-select
          v-if="auth.isAdmin"
          v-model="filters.spaceId"
          placeholder="全部空间"
          clearable
          class="filter-select"
        >
          <el-option v-for="space in spaces" :key="space.id" :label="space.name" :value="space.id" />
        </el-select>
        <el-input-number
          v-model="filters.actorId"
          :min="1"
          :controls="false"
          placeholder="操作人 ID"
          class="filter-actor"
        />
        <el-date-picker
          v-model="filters.range"
          type="datetimerange"
          value-format="YYYY-MM-DDTHH:mm:ss"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          class="filter-range"
        />
        <div class="filter-actions">
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </div>
      </div>
    </div>

    <div class="panel table-panel">
      <el-table v-loading="loading" :data="logs" style="width: 100%">
        <el-table-column label="时间" width="160">
          <template #default="{ row }">
            <span :title="formatDate(row.createdAt)">{{ fromNow(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作人" width="140">
          <template #default="{ row }">
            <div class="actor-name">{{ actorText(row) }}</div>
            <div class="actor-type">{{ row.actorType }}</div>
          </template>
        </el-table-column>
        <el-table-column label="动作" min-width="200">
          <template #default="{ row }">
            <span class="mono action-text">{{ row.action }}</span>
          </template>
        </el-table-column>
        <el-table-column label="资源" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <template v-if="row.resourceType">
              {{ row.resourceType }}<span v-if="row.resourceId" class="mono">#{{ row.resourceId }}</span>
            </template>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusMeta(row.status).type" effect="plain">
              {{ statusMeta(row.status).text }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="详情" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span :class="{ muted: !row.detail }">{{ row.detail || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="来源 IP" width="140">
          <template #default="{ row }">
            <span class="mono muted">{{ row.ip || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <EmptyState
            title="没有匹配的审计记录"
            description="调整动作前缀、状态或时间范围后重试。"
          />
        </template>
      </el-table>

      <div v-if="total > pageSize" class="pagination-row">
        <el-pagination
          v-model:current-page="pageNo"
          v-model:page-size="pageSize"
          :total="total"
          layout="total, prev, pager, next"
          background
          @current-change="loadLogs"
        />
      </div>
    </div>

    <el-drawer v-model="detailVisible" title="审计详情" size="480px" destroy-on-close>
      <template v-if="detail">
        <div class="detail-grid">
          <div class="detail-item">
            <span class="detail-label">时间</span>
            <span>{{ formatDate(detail.createdAt, 'YYYY-MM-DD HH:mm:ss') }}</span>
          </div>
          <div class="detail-item">
            <span class="detail-label">状态</span>
            <el-tag :type="statusMeta(detail.status).type" effect="plain">
              {{ statusMeta(detail.status).text }}
            </el-tag>
          </div>
          <div class="detail-item">
            <span class="detail-label">操作人</span>
            <span>{{ actorText(detail) }}（{{ detail.actorType }}）</span>
          </div>
          <div class="detail-item">
            <span class="detail-label">动作</span>
            <span class="mono">{{ detail.action }}</span>
          </div>
          <div class="detail-item">
            <span class="detail-label">资源</span>
            <span>
              {{ detail.resourceType || '—' }}
              <span v-if="detail.resourceId" class="mono">#{{ detail.resourceId }}</span>
            </span>
          </div>
          <div class="detail-item">
            <span class="detail-label">空间</span>
            <span>{{ detail.spaceId ? `#${detail.spaceId}` : '全局' }}</span>
          </div>
          <div class="detail-item">
            <span class="detail-label">来源 IP</span>
            <span class="mono">{{ detail.ip || '—' }}</span>
          </div>
          <div class="detail-item">
            <span class="detail-label">请求 ID</span>
            <span class="mono">{{ detail.requestId || '—' }}</span>
          </div>
        </div>
        <div class="detail-block">
          <span class="detail-label">详情</span>
          <p>{{ detail.detail || '—' }}</p>
        </div>
        <div class="detail-block">
          <span class="detail-label">User Agent</span>
          <p class="mono">{{ detail.userAgent || '—' }}</p>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped lang="scss">
.filter-panel {
  margin-top: 20px;
  padding: 16px;
}

.filter-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.filter-action {
  width: 220px;
}

.filter-select {
  width: 140px;
}

.filter-actor {
  width: 120px;
}

.filter-range {
  flex: 1;
  min-width: 280px;
}

.filter-actions {
  display: flex;
  gap: 8px;
  margin-left: auto;
}

.table-panel {
  margin-top: 16px;
  padding: 6px 12px 14px;
}

.actor-name {
  font-size: 12.5px;
  font-weight: 560;
  color: var(--king-text);
}

.actor-type {
  margin-top: 2px;
  font-size: 10.5px;
  color: var(--king-muted);
}

.action-text {
  font-size: 12px;
  color: var(--k-violet);
}

.muted {
  color: var(--king-muted);
}

.pagination-row {
  display: flex;
  justify-content: flex-end;
  padding-top: 14px;
}

.detail-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  padding: 16px;
  border-radius: 12px;
  border: 1px solid var(--king-line);
  background: var(--k-fill-soft);
}

.detail-item {
  display: flex;
  flex-direction: column;
  gap: 5px;
  font-size: 12.5px;
  color: var(--king-text-soft);
  word-break: break-all;
}

.detail-label {
  font-size: 10.5px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--king-muted);
}

.detail-block {
  margin-top: 18px;
}

.detail-block p {
  margin: 6px 0 0;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--king-text-soft);
  word-break: break-all;
}

@media (max-width: 720px) {
  .filter-action,
  .filter-select,
  .filter-actor,
  .filter-range {
    width: 100%;
  }

  .filter-actions {
    width: 100%;
    margin-left: 0;
  }

  .detail-grid {
    grid-template-columns: 1fr;
  }
}
</style>

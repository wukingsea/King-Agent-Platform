<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Cpu, Key, OfficeBuilding, Timer, User } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { licenseApi } from '@/api'
import { ApiError } from '@/api/http'
import type { LicenseStatusView } from '@/api/types'

const loading = ref(false)
const status = ref<LicenseStatusView | null>(null)

const limits = computed(() => status.value?.limits ?? null)
const daysLeft = computed(() => {
  const expiresAt = status.value?.expiresAt
  if (!expiresAt) return null
  const remaining = new Date(expiresAt).getTime() - Date.now()
  if (Number.isNaN(remaining)) return null
  return Math.ceil(remaining / 86_400_000)
})

const expiryText = computed(() => {
  const value = status.value?.expiresAt
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleDateString('zh-CN')
})

const stateText = computed(() => {
  if (!status.value || status.value.status === 'DISABLED') return '未启用'
  return status.value.status === 'EXPIRED' ? '已过期' : '有效'
})

const usageRows = computed(() => {
  const data = status.value
  if (!data) return []
  return [
    {
      key: 'spaces',
      label: '业务空间',
      icon: OfficeBuilding,
      used: data.usage.spaces,
      limit: limits.value?.maxSpaces ?? 0,
    },
    {
      key: 'users',
      label: '用户',
      icon: User,
      used: data.usage.users,
      limit: limits.value?.maxUsers ?? 0,
    },
    {
      key: 'agents',
      label: '智能体',
      icon: Cpu,
      used: data.usage.agents,
      limit: limits.value?.maxAgents ?? 0,
    },
  ]
})

function percent(used: number, limit: number): number {
  if (limit <= 0) return 0
  return Math.min(100, Math.round((used / limit) * 100))
}

function barClass(used: number, limit: number): string {
  const value = percent(used, limit)
  if (value >= 100) return 'danger'
  if (value >= 80) return 'warn'
  return 'ok'
}

async function load() {
  loading.value = true
  try {
    status.value = await licenseApi.status()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载许可证信息失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <PageHeader
      title="平台许可"
      description="查看离线许可证状态、授权容量与可用企业能力。许可证文件由供应商签发并离线校验。"
      eyebrow="License"
    >
      <template #actions>
        <el-button @click="load">刷新状态</el-button>
      </template>
    </PageHeader>

    <div v-loading="loading" class="license-wrap">
      <div v-if="status" class="panel license-card">
        <div class="license-top">
          <div class="license-mark" :class="status.status.toLowerCase()">
            <el-icon><Key /></el-icon>
          </div>
          <div class="license-heading">
            <div class="license-title-row">
              <h3>{{ status.enabled ? status.customer || '授权客户' : '未启用离线许可' }}</h3>
              <span class="badge" :class="status.status === 'ACTIVE' ? 'active' : 'muted'">
                {{ stateText }}
              </span>
              <span v-if="status.edition" class="badge edition">{{ status.edition }}</span>
            </div>
            <p class="license-sub">
              {{
                status.enabled
                  ? `许可证编号 ${status.licenseId || '—'} · 有效期至 ${expiryText}`
                  : '当前部署未配置许可证，容量不设限。'
              }}
            </p>
          </div>
          <div v-if="daysLeft !== null" class="days-left" :class="{ warn: daysLeft <= 30 }">
            <span class="days-value">{{ daysLeft }}</span>
            <span class="days-label">{{ daysLeft >= 0 ? '天后到期' : '天前过期' }}</span>
          </div>
        </div>

        <div v-if="status.enabled" class="meta-grid">
          <div class="meta-cell">
            <span class="meta-key">签发时间</span>
            <span class="meta-val">{{ status.issuedAt ? new Date(status.issuedAt).toLocaleDateString('zh-CN') : '—' }}</span>
          </div>
          <div class="meta-cell">
            <span class="meta-key">到期时间</span>
            <span class="meta-val">{{ expiryText }}</span>
          </div>
          <div class="meta-cell">
            <span class="meta-key">剩余天数</span>
            <span class="meta-val">{{ daysLeft ?? '—' }}</span>
          </div>
        </div>
      </div>

      <div v-if="status && status.enabled" class="usage-grid">
        <article v-for="row in usageRows" :key="row.key" class="panel usage-card">
          <div class="usage-head">
            <div class="usage-icon"><el-icon><component :is="row.icon" /></el-icon></div>
            <span class="usage-label">{{ row.label }}</span>
            <span class="usage-count">
              {{ row.used }}
              <span class="usage-limit">/ {{ row.limit > 0 ? row.limit : '不限' }}</span>
            </span>
          </div>
          <div class="usage-bar">
            <span
              class="usage-fill"
              :class="barClass(row.used, row.limit)"
              :style="{ width: row.limit > 0 ? `${percent(row.used, row.limit)}%` : '0%' }"
            ></span>
          </div>
          <p class="usage-hint">
            {{
              row.limit > 0
                ? percent(row.used, row.limit) >= 100
                  ? '已达许可上限，新增操作将被拒绝'
                  : `已使用 ${percent(row.used, row.limit)}% 的授权额度`
                : '当前版本不限制该资源数量'
            }}
          </p>
        </article>
      </div>

      <div v-if="status && status.enabled" class="panel feature-card">
        <div class="feature-head">
          <el-icon><Timer /></el-icon>
          <h3>授权能力</h3>
        </div>
        <div v-if="status.features.length" class="feature-list">
          <span v-for="feature in status.features" :key="feature" class="feature-chip">
            {{ feature }}
          </span>
        </div>
        <p v-else class="feature-empty">许可证未声明额外能力，按社区版能力运行。</p>
      </div>

      <div v-if="status && !status.enabled" class="panel">
        <EmptyState
          :icon="Key"
          title="未启用离线许可证"
          description="通过 KING_AI_LICENSE_ENABLED 与 KING_AI_LICENSE_FILE 挂载许可证文件后重启服务即可启用容量与能力限制。"
        />
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.license-wrap {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 240px;
}

.license-card {
  padding: 24px;
}

.license-top {
  display: flex;
  align-items: flex-start;
  gap: 16px;
}

.license-mark {
  flex: none;
  display: grid;
  place-items: center;
  width: 48px;
  height: 48px;
  border-radius: 14px;
  font-size: 22px;
  color: var(--k-green);
  background: linear-gradient(135deg, rgba(52, 211, 153, 0.18), rgba(34, 211, 238, 0.1));
  border: 1px solid rgba(52, 211, 153, 0.3);
}

.license-mark.expired {
  color: var(--k-red);
  background: rgba(248, 113, 113, 0.1);
  border-color: rgba(248, 113, 113, 0.3);
}

.license-mark.disabled {
  color: var(--text-muted);
  background: rgba(148, 163, 184, 0.1);
  border-color: rgba(148, 163, 184, 0.24);
}

.license-heading {
  flex: 1;
  min-width: 0;
}

.license-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.license-title-row h3 {
  margin: 0;
  font-size: 17px;
  font-weight: 620;
  color: var(--text-strong);
}

.license-sub {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-muted);
}

.days-left {
  flex: none;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  padding: 8px 14px;
  border-radius: 12px;
  border: 1px solid rgba(52, 211, 153, 0.24);
  background: rgba(52, 211, 153, 0.08);
}

.days-left.warn {
  border-color: rgba(251, 191, 36, 0.28);
  background: rgba(251, 191, 36, 0.09);
}

.days-value {
  font-family: var(--font-mono);
  font-size: 18px;
  font-weight: 620;
  color: var(--k-green);
}

.days-left.warn .days-value {
  color: var(--k-amber);
}

.days-label {
  font-size: 10px;
  color: var(--text-muted);
}

.meta-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--border-subtle);
}

.meta-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.meta-key {
  font-size: 10.5px;
  color: var(--text-faint);
}

.meta-val {
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--text-strong);
}

.usage-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}

.usage-card {
  padding: 18px;
}

.usage-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.usage-icon {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  font-size: 16px;
  color: var(--k-violet);
  background: rgba(124, 92, 255, 0.12);
  border: 1px solid rgba(124, 92, 255, 0.22);
}

.usage-label {
  flex: 1;
  font-size: 12.5px;
  color: var(--text-strong);
}

.usage-count {
  font-family: var(--font-mono);
  font-size: 15px;
  color: var(--text-strong);
}

.usage-limit {
  font-size: 11px;
  color: var(--text-muted);
}

.usage-bar {
  margin-top: 14px;
  height: 6px;
  border-radius: 999px;
  background: rgba(148, 163, 184, 0.14);
  overflow: hidden;
}

.usage-fill {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #7c5cff, #22d3ee);
}

.usage-fill.warn {
  background: linear-gradient(90deg, #fbbf24, #f59e0b);
}

.usage-fill.danger {
  background: linear-gradient(90deg, #fb7185, #ef4444);
}

.usage-hint {
  margin: 10px 0 0;
  font-size: 10.5px;
  line-height: 1.55;
  color: var(--text-faint);
}

.feature-card {
  padding: 22px;
}

.feature-head {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--k-violet);
}

.feature-head h3 {
  margin: 0;
  font-size: 13px;
  font-weight: 620;
  color: var(--text-strong);
}

.feature-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 14px;
}

.feature-chip {
  padding: 5px 11px;
  border-radius: 999px;
  border: 1px solid rgba(124, 92, 255, 0.26);
  background: rgba(124, 92, 255, 0.1);
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--k-violet);
}

.feature-empty {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--text-muted);
}

.badge {
  padding: 2px 9px;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 600;
}

.badge.active {
  color: var(--k-green);
  background: rgba(52, 211, 153, 0.12);
  border: 1px solid rgba(52, 211, 153, 0.3);
}

.badge.muted {
  color: var(--text-muted);
  background: rgba(148, 163, 184, 0.1);
  border: 1px solid rgba(148, 163, 184, 0.22);
}

.badge.edition {
  color: var(--k-violet);
  background: rgba(124, 92, 255, 0.12);
  border: 1px solid rgba(124, 92, 255, 0.26);
}

@media (max-width: 900px) {
  .usage-grid {
    grid-template-columns: 1fr;
  }

  .meta-grid {
    grid-template-columns: 1fr;
  }

  .days-left {
    display: none;
  }
}
</style>

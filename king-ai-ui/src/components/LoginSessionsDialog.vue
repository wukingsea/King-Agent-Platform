<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Monitor, Refresh, SwitchButton } from '@element-plus/icons-vue'
import { authApi } from '@/api'
import { ApiError } from '@/api/http'
import type { LoginSessionView } from '@/api/types'
import { formatDate, fromNow } from '@/utils/format'
import { useAuthStore } from '@/stores/auth'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
})

const auth = useAuthStore()
const router = useRouter()
const sessions = ref<LoginSessionView[]>([])
const loading = ref(false)
const busy = ref(false)

function deviceName(userAgent: string): string {
  if (!userAgent) return '未知设备'
  const browser = /Edg\//.test(userAgent)
    ? 'Edge'
    : /Chrome\//.test(userAgent)
      ? 'Chrome'
      : /Firefox\//.test(userAgent)
        ? 'Firefox'
        : /Safari\//.test(userAgent)
          ? 'Safari'
          : '浏览器'
  const system = /Windows/.test(userAgent)
    ? 'Windows'
    : /Android/.test(userAgent)
      ? 'Android'
      : /iPhone|iPad|iOS/.test(userAgent)
        ? 'iOS'
        : /Mac OS X|Macintosh/.test(userAgent)
          ? 'macOS'
          : /Linux/.test(userAgent)
            ? 'Linux'
            : '未知系统'
  return `${browser} · ${system}`
}

async function load() {
  loading.value = true
  try {
    sessions.value = await authApi.sessions()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载登录设备失败')
  } finally {
    loading.value = false
  }
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) void load()
  },
)

async function logoutOthers() {
  busy.value = true
  try {
    const revoked = await authApi.logoutOtherSessions()
    ElMessage.success(revoked > 0 ? `已下线 ${revoked} 个其他设备` : '没有其他在线设备')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '下线其他设备失败')
  } finally {
    busy.value = false
  }
}

async function logoutAll() {
  try {
    await ElMessageBox.confirm('退出后所有设备都需要重新登录，是否继续？', '退出全部设备', {
      type: 'warning',
      confirmButtonText: '全部退出',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  busy.value = true
  try {
    await authApi.logoutAllSessions()
    visible.value = false
    ElMessage.success('已退出全部设备')
    auth.clearLocal()
    router.replace({ name: 'login' })
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '退出全部设备失败')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="登录设备" width="560px">
    <div v-loading="loading" class="session-body">
      <p class="session-hint">
        以下为当前账号仍在线的设备。如发现陌生设备，请立即将其下线并修改密码。
      </p>
      <div v-if="sessions.length" class="session-list">
        <div v-for="session in sessions" :key="session.id" class="session-item">
          <div class="session-icon" :class="{ current: session.current }">
            <el-icon><Monitor /></el-icon>
          </div>
          <div class="session-copy">
            <div class="session-title">
              {{ deviceName(session.userAgent) }}
              <span v-if="session.current" class="badge">当前设备</span>
            </div>
            <div class="session-meta">
              <span>{{ session.clientIp || '未知 IP' }}</span>
              <span>登录于 {{ fromNow(session.createdAt) }}</span>
              <span>有效期至 {{ formatDate(session.expiresAt) }}</span>
            </div>
          </div>
        </div>
      </div>
      <el-empty v-else-if="!loading" description="暂无在线设备" :image-size="72" />
    </div>
    <template #footer>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      <el-button :icon="SwitchButton" :disabled="sessions.length < 2" :loading="busy" @click="logoutOthers">
        下线其他设备
      </el-button>
      <el-button type="danger" plain :loading="busy" @click="logoutAll">退出全部设备</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.session-body {
  min-height: 160px;
}

.session-hint {
  margin: 0 0 16px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--king-muted);
}

.session-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.session-item {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  border-radius: 12px;
  border: 1px solid var(--king-line);
  background: rgba(9, 13, 28, 0.55);
}

.session-icon {
  flex: none;
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border-radius: 11px;
  font-size: 18px;
  color: var(--king-muted);
  background: rgba(148, 163, 184, 0.1);
  border: 1px solid rgba(148, 163, 184, 0.2);
}

.session-icon.current {
  color: #a78bfa;
  background: rgba(124, 92, 255, 0.12);
  border-color: rgba(124, 92, 255, 0.3);
}

.session-copy {
  min-width: 0;
}

.session-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 580;
  color: var(--king-text);
}

.badge {
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 600;
  color: #6ee7b7;
  background: rgba(52, 211, 153, 0.12);
  border: 1px solid rgba(52, 211, 153, 0.3);
}

.session-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
  margin-top: 4px;
  font-size: 11px;
  color: var(--king-muted);
}
</style>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Key, Lock, Refresh } from '@element-plus/icons-vue'
import { authApi } from '@/api'
import { ApiError } from '@/api/http'
import type { MfaSetupResponse, MfaStatusView } from '@/api/types'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
})

const status = ref<MfaStatusView | null>(null)
const loading = ref(false)
const busy = ref(false)
const password = ref('')
const code = ref('')
const setup = ref<MfaSetupResponse | null>(null)
const recoveryCodes = ref<string[]>([])
const disableMode = ref(false)

function reset() {
  password.value = ''
  code.value = ''
  setup.value = null
  recoveryCodes.value = []
  disableMode.value = false
}

async function loadStatus() {
  loading.value = true
  try {
    status.value = await authApi.mfaStatus()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载二次验证状态失败')
  } finally {
    loading.value = false
  }
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      reset()
      void loadStatus()
    }
  },
)

async function startSetup() {
  if (!password.value) {
    ElMessage.warning('请输入当前密码')
    return
  }
  busy.value = true
  try {
    setup.value = await authApi.setupMfa(password.value)
    code.value = ''
    ElMessage.success('已生成密钥，请在身份验证器中添加后输入动态验证码')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '生成密钥失败')
  } finally {
    busy.value = false
  }
}

async function enable() {
  if (!code.value.trim()) {
    ElMessage.warning('请输入验证码')
    return
  }
  busy.value = true
  try {
    const result = await authApi.enableMfa(code.value.trim())
    recoveryCodes.value = result.recoveryCodes
    setup.value = null
    password.value = ''
    code.value = ''
    await loadStatus()
    ElMessage.success('二次验证已启用，请妥善保存恢复码')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '启用失败')
  } finally {
    busy.value = false
  }
}

async function disable() {
  if (!password.value || !code.value.trim()) {
    ElMessage.warning('请输入当前密码和验证码')
    return
  }
  busy.value = true
  try {
    await authApi.disableMfa(password.value, code.value.trim())
    disableMode.value = false
    password.value = ''
    code.value = ''
    await loadStatus()
    ElMessage.success('二次验证已停用')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '停用失败')
  } finally {
    busy.value = false
  }
}

async function copySecret() {
  if (!setup.value) return
  try {
    await navigator.clipboard.writeText(setup.value.secret)
    ElMessage.success('密钥已复制')
  } catch {
    ElMessage.warning('复制失败，请手动记录密钥')
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="账号安全 · 二次验证" width="520px">
    <div v-loading="loading" class="mfa-body">
      <div v-if="recoveryCodes.length" class="recovery-block">
        <div class="section-title">恢复码（仅显示一次）</div>
        <p class="section-hint">
          请将以下恢复码保存到安全位置。丢失身份验证器时可使用恢复码登录，每个恢复码只能使用一次。
        </p>
        <div class="code-grid">
          <span v-for="item in recoveryCodes" :key="item" class="code-pill">{{ item }}</span>
        </div>
        <el-button type="primary" @click="visible = false">我已保存</el-button>
      </div>

      <template v-else-if="status">
        <div class="status-row">
          <div class="status-icon" :class="{ on: status.enabled }">
            <el-icon><Key /></el-icon>
          </div>
          <div class="status-copy">
            <div class="status-title">
              动态口令二次验证
              <span class="badge" :class="status.enabled ? 'on' : 'off'">
                {{ status.enabled ? '已启用' : status.pending ? '待验证' : '未启用' }}
              </span>
            </div>
            <p v-if="status.enabled">
              已绑定 {{ status.recoveryCodesRemaining }} 个可用恢复码。登录时需输入身份验证器中的动态验证码。
            </p>
            <p v-else>启用后，登录除密码外还需输入身份验证器生成的 6 位动态验证码。</p>
          </div>
        </div>

        <div v-if="disableMode" class="form-block">
          <el-input v-model="password" type="password" show-password placeholder="当前密码">
            <template #prefix><el-icon><Lock /></el-icon></template>
          </el-input>
          <el-input v-model="code" placeholder="动态验证码或恢复码" maxlength="32" />
          <div class="btn-row">
            <el-button @click="disableMode = false">取消</el-button>
            <el-button type="danger" :loading="busy" @click="disable">确认停用</el-button>
          </div>
        </div>

        <div v-else-if="!status.enabled" class="form-block">
          <template v-if="!setup">
            <el-input
              v-model="password"
              type="password"
              show-password
              placeholder="请输入当前密码以生成密钥"
            >
              <template #prefix><el-icon><Lock /></el-icon></template>
            </el-input>
            <el-button type="primary" :loading="busy" @click="startSetup">
              <el-icon><Key /></el-icon>
              生成绑定密钥
            </el-button>
          </template>
          <template v-else>
            <div class="secret-box">
              <span class="secret-label">密钥</span>
              <span class="secret-value mono">{{ setup.secret }}</span>
              <el-button size="small" text @click="copySecret">复制</el-button>
            </div>
            <div class="secret-box">
              <span class="secret-label">otpauth</span>
              <span class="secret-value mono">{{ setup.otpauthUrl }}</span>
            </div>
            <el-input
              v-model="code"
              placeholder="输入身份验证器中的 6 位验证码"
              maxlength="32"
              @keyup.enter="enable"
            />
            <div class="btn-row">
              <el-button :icon="Refresh" @click="setup = null">重新生成</el-button>
              <el-button type="primary" :loading="busy" @click="enable">确认启用</el-button>
            </div>
          </template>
        </div>

        <div v-else class="form-block">
          <el-button type="danger" plain @click="disableMode = true">停用二次验证</el-button>
        </div>
      </template>
    </div>
    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.mfa-body {
  min-height: 180px;
}

.status-row {
  display: flex;
  gap: 14px;
  align-items: flex-start;
  padding: 16px;
  border-radius: 14px;
  background: rgba(124, 92, 255, 0.06);
  border: 1px solid var(--king-line);
}

.status-icon {
  flex: none;
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  border-radius: 12px;
  font-size: 20px;
  color: var(--king-muted);
  background: rgba(148, 163, 184, 0.1);
  border: 1px solid rgba(148, 163, 184, 0.2);
}

.status-icon.on {
  color: #6ee7b7;
  background: rgba(52, 211, 153, 0.1);
  border-color: rgba(52, 211, 153, 0.28);
}

.status-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-strong);
}

.status-copy p {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--king-muted);
}

.badge {
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 600;
}

.badge.on {
  color: #6ee7b7;
  background: rgba(52, 211, 153, 0.12);
  border: 1px solid rgba(52, 211, 153, 0.3);
}

.badge.off {
  color: var(--king-muted);
  background: rgba(148, 163, 184, 0.1);
  border: 1px solid rgba(148, 163, 184, 0.2);
}

.form-block {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 18px;
}

.btn-row {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.secret-box {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 10px;
  background: rgba(9, 13, 28, 0.62);
  border: 1px solid var(--border-subtle);
  font-size: 11px;
}

.secret-label {
  flex: none;
  color: var(--king-muted);
}

.secret-value {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  color: #c4b5fd;
}

.mono {
  font-family: var(--font-mono);
}

.section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-strong);
}

.section-hint {
  margin: 8px 0 14px;
  font-size: 12px;
  line-height: 1.65;
  color: var(--king-muted);
}

.code-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 18px;
}

.code-pill {
  padding: 8px 10px;
  border-radius: 8px;
  background: rgba(9, 13, 28, 0.62);
  border: 1px solid var(--border-subtle);
  font-family: var(--font-mono);
  font-size: 12px;
  letter-spacing: 0.04em;
  color: #9be8ff;
  text-align: center;
}
</style>

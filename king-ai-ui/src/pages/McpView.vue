<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  Check,
  Connection,
  Delete,
  EditPen,
  Link,
  Plus,
  Refresh,
  Search,
  Tools,
} from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { mcpApi } from '@/api'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import type { McpAuthType, McpSaveRequest, McpTestResult, McpView } from '@/api/types'
import { fromNow } from '@/utils/format'

const auth = useAuthStore()

const loading = ref(false)
const keyword = ref('')
const servers = ref<McpView[]>([])
const drawerVisible = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()

const testDialogVisible = ref(false)
const testingId = ref<number | null>(null)
const testResult = ref<McpTestResult | null>(null)
const testing = ref(false)

interface McpForm {
  name: string
  transport: string
  url: string
  headers: string
  clearHeaders: boolean
  headersConfigured: boolean
  authType: McpAuthType
  oauthTokenUrl: string
  oauthClientId: string
  oauthClientSecret: string
  oauthScopes: string
  clientSecretConfigured: boolean
  status: number
  remark: string
}

const form = reactive<McpForm>({
  name: '',
  transport: 'STREAMABLE_HTTP',
  url: '',
  headers: '',
  clearHeaders: false,
  headersConfigured: false,
  authType: 'NONE',
  oauthTokenUrl: '',
  oauthClientId: '',
  oauthClientSecret: '',
  oauthScopes: '',
  clientSecretConfigured: false,
  status: 0,
  remark: '',
})
const headersPlaceholder = 'JSON 对象，例如 {"Authorization":"Bearer xxx"}'

const rules: FormRules = {
  name: [
    { required: true, message: '请输入 MCP 名称', trigger: 'blur' },
    { max: 100, message: '名称不能超过 100 个字符', trigger: 'blur' },
  ],
  url: [
    { required: true, message: '请输入 MCP 服务地址', trigger: 'blur' },
    { pattern: /^https?:\/\//, message: '地址需以 http:// 或 https:// 开头', trigger: 'blur' },
  ],
}

const enabledCount = computed(() => servers.value.filter((item) => item.status === 0).length)
const toolAgentCount = computed(() =>
  servers.value.reduce((total, item) => total + (item.agentCount || 0), 0),
)
const oauthCount = computed(
  () => servers.value.filter((item) => item.authType === 'OAUTH2').length,
)

function tokenLabel(server: McpView): string {
  if (!server.tokenObtained) return '待获取令牌'
  if (!server.oauthExpiresAt) return '令牌已获取'
  const expires = new Date(server.oauthExpiresAt).getTime()
  if (Number.isNaN(expires)) return '令牌已获取'
  const minutes = Math.round((expires - Date.now()) / 60000)
  if (minutes <= 0) return '令牌已过期'
  if (minutes < 60) return `${minutes} 分钟后过期`
  const hours = Math.round(minutes / 60)
  if (hours < 48) return `${hours} 小时后过期`
  return `${Math.round(hours / 24)} 天后过期`
}

function resetForm() {
  editingId.value = null
  Object.assign(form, {
    name: '',
    transport: 'STREAMABLE_HTTP',
    url: '',
    headers: '',
    clearHeaders: false,
    headersConfigured: false,
    authType: 'NONE',
    oauthTokenUrl: '',
    oauthClientId: '',
    oauthClientSecret: '',
    oauthScopes: '',
    clientSecretConfigured: false,
    status: 0,
    remark: '',
  })
}

async function loadServers() {
  loading.value = true
  try {
    servers.value = await mcpApi.list(keyword.value.trim() || undefined)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载 MCP 服务失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  resetForm()
  drawerVisible.value = true
}

function openEdit(server: McpView) {
  resetForm()
  editingId.value = server.id
  Object.assign(form, {
    name: server.name,
    transport: server.transport || 'STREAMABLE_HTTP',
    url: server.url,
    headers: '',
    clearHeaders: false,
    headersConfigured: server.headersConfigured,
    authType: server.authType === 'OAUTH2' ? 'OAUTH2' : 'NONE',
    oauthTokenUrl: server.oauthTokenUrl || '',
    oauthClientId: server.oauthClientId || '',
    oauthClientSecret: '',
    oauthScopes: server.oauthScopes || '',
    clientSecretConfigured: server.clientSecretConfigured || false,
    status: server.status,
    remark: server.remark || '',
  })
  drawerVisible.value = true
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  if (form.authType === 'OAUTH2') {
    if (!form.oauthTokenUrl.trim()) {
      ElMessage.warning('请输入 OAuth Token 地址')
      return
    }
    if (!form.oauthClientId.trim()) {
      ElMessage.warning('请输入 OAuth Client ID')
      return
    }
    if (!form.clientSecretConfigured && !form.oauthClientSecret.trim()) {
      ElMessage.warning('请输入 OAuth Client Secret')
      return
    }
  }
  saving.value = true
  const headers = form.clearHeaders ? '' : form.headers.trim() || undefined
  const payload: McpSaveRequest = {
    name: form.name.trim(),
    transport: form.transport,
    url: form.url.trim(),
    headers,
    authType: form.authType,
    oauthTokenUrl: form.authType === 'OAUTH2' ? form.oauthTokenUrl.trim() : undefined,
    oauthClientId: form.authType === 'OAUTH2' ? form.oauthClientId.trim() : undefined,
    oauthClientSecret:
      form.authType === 'OAUTH2' && form.oauthClientSecret.trim()
        ? form.oauthClientSecret.trim()
        : undefined,
    oauthScopes: form.authType === 'OAUTH2' ? form.oauthScopes.trim() : undefined,
    status: form.status,
    remark: form.remark.trim(),
  }
  try {
    if (editingId.value) {
      await mcpApi.update(editingId.value, payload)
      ElMessage.success('MCP 服务已更新')
    } else {
      await mcpApi.create(payload)
      ElMessage.success('MCP 服务已创建')
    }
    drawerVisible.value = false
    await loadServers()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function test(server: McpView) {
  testDialogVisible.value = true
  testing.value = true
  testingId.value = server.id
  testResult.value = null
  try {
    testResult.value = await mcpApi.test(server.id)
  } catch (error) {
    testResult.value = {
      ok: false,
      message: error instanceof ApiError ? error.message : '连接测试失败',
      tools: [],
    }
  } finally {
    testing.value = false
  }
}

async function remove(server: McpView) {
  try {
    await ElMessageBox.confirm(
      `确认删除 MCP 服务「${server.name}」？已绑定该服务的智能体将失去相关工具。`,
      '删除 MCP 服务',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await mcpApi.remove(server.id)
    ElMessage.success('MCP 服务已删除')
    await loadServers()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

onMounted(loadServers)
</script>

<template>
  <div class="page">
    <PageHeader
      title="MCP 工具"
      description="接入 Model Context Protocol 服务，把外部工具能力暴露给智能体，由模型自主规划调用。"
      eyebrow="Tools"
    >
      <template #actions>
        <el-button v-if="auth.canManageSpace" type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon>
          接入 MCP 服务
        </el-button>
      </template>
    </PageHeader>

    <div class="summary-row">
      <div class="summary-chip">
        <span class="chip-dot"></span>
        共 {{ servers.length }} 个服务
      </div>
      <div class="summary-chip">
        <span class="chip-dot green"></span>
        {{ enabledCount }} 个启用中
      </div>
      <div class="summary-chip">
        <span class="chip-dot muted"></span>
        {{ toolAgentCount }} 个智能体引用
      </div>
      <div v-if="oauthCount" class="summary-chip">
        <span class="chip-dot"></span>
        {{ oauthCount }} 个 OAuth 2.0
      </div>
      <div class="summary-spacer"></div>
      <div class="search-box">
        <el-input
          v-model="keyword"
          placeholder="搜索 MCP 服务"
          clearable
          @keyup.enter="loadServers"
          @clear="loadServers"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button @click="loadServers">搜索</el-button>
      </div>
    </div>

    <div v-loading="loading" class="server-wrap">
      <div v-if="servers.length" class="server-grid">
        <article v-for="server in servers" :key="server.id" class="server-card panel">
          <div class="server-top">
            <div class="server-icon">
              <el-icon><Connection /></el-icon>
            </div>
            <div class="server-heading">
              <div class="server-title-row">
                <h3>{{ server.name }}</h3>
                <span class="badge" :class="server.status === 0 ? 'published' : 'draft'">
                  {{ server.status === 0 ? '启用' : '停用' }}
                </span>
              </div>
              <p class="server-remark">{{ server.remark || '未填写备注' }}</p>
            </div>
          </div>

          <div class="url-row">
            <el-icon><Link /></el-icon>
            <span class="mono">{{ server.url }}</span>
          </div>

          <div class="server-meta">
            <span class="meta-pill">{{ server.transport || 'STREAMABLE_HTTP' }}</span>
            <span class="meta-pill muted">{{ server.agentCount || 0 }} 个智能体绑定</span>
            <template v-if="server.authType === 'OAUTH2'">
              <span class="meta-pill">OAuth 2.0</span>
              <span class="meta-pill muted" :class="{ warn: !server.tokenObtained }">
                {{ tokenLabel(server) }}
              </span>
            </template>
            <span class="meta-time">{{ fromNow(server.createdAt) }}</span>
          </div>

          <div class="server-actions">
            <el-button size="small" @click="test(server)">
              <el-icon><Refresh /></el-icon>
              测试连接
            </el-button>
            <template v-if="auth.canManageSpace">
              <el-button size="small" text @click="openEdit(server)">
                <el-icon><EditPen /></el-icon>
                编辑
              </el-button>
              <el-button size="small" text type="danger" @click="remove(server)">
                <el-icon><Delete /></el-icon>
                删除
              </el-button>
            </template>
          </div>
        </article>
      </div>
      <EmptyState
        v-else-if="!loading"
        :icon="Connection"
        title="还没有接入 MCP 服务"
        description="接入一个 Streamable HTTP MCP 服务，让智能体可以查询时间、检索业务系统或调用自定义工具。"
      >
        <el-button v-if="auth.canManageSpace" type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon>
          接入第一个服务
        </el-button>
      </EmptyState>
    </div>

    <el-drawer
      v-model="drawerVisible"
      :title="editingId ? '编辑 MCP 服务' : '接入 MCP 服务'"
      size="520px"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="服务名称" prop="name">
          <el-input v-model="form.name" placeholder="例如：企业工具集" maxlength="100" />
        </el-form-item>
        <el-form-item label="传输协议">
          <el-select v-model="form.transport" style="width: 100%">
            <el-option label="Streamable HTTP" value="STREAMABLE_HTTP" />
          </el-select>
        </el-form-item>
        <el-form-item label="服务地址" prop="url">
          <el-input
            v-model="form.url"
            placeholder="https://example.com/mcp"
            class="mono-input"
          />
          <div class="form-hint">
            平台通过 JSON-RPC 2.0 执行 initialize / tools/list / tools/call。
          </div>
        </el-form-item>
        <el-form-item label="认证方式">
          <el-select v-model="form.authType" style="width: 100%">
            <el-option label="无需认证" value="NONE" />
            <el-option label="OAuth 2.0（客户端凭证）" value="OAUTH2" />
          </el-select>
          <div class="form-hint">
            OAuth 2.0 模式下平台使用客户端凭证换取访问令牌，并在过期前自动刷新。
          </div>
        </el-form-item>
        <template v-if="form.authType === 'OAUTH2'">
          <el-form-item label="Token 地址">
            <el-input
              v-model="form.oauthTokenUrl"
              placeholder="https://idp.example.com/oauth/token"
              class="mono-input"
            />
          </el-form-item>
          <el-form-item label="Client ID">
            <el-input v-model="form.oauthClientId" maxlength="200" placeholder="OAuth Client ID" />
          </el-form-item>
          <el-form-item label="Client Secret">
            <el-input
              v-model="form.oauthClientSecret"
              type="password"
              show-password
              maxlength="1000"
              :placeholder="
                form.clientSecretConfigured ? '已保存，留空表示保留原值' : 'OAuth Client Secret'
              "
            />
            <div v-if="form.clientSecretConfigured" class="form-hint">
              已保存的 Client Secret 已加密，填写新值将整体替换。
            </div>
          </el-form-item>
          <el-form-item label="Scopes">
            <el-input
              v-model="form.oauthScopes"
              maxlength="500"
              placeholder="多个 scope 用空格分隔，例如 mcp.read mcp.write"
            />
          </el-form-item>
        </template>
        <el-form-item label="自定义请求头">
          <el-input
            v-model="form.headers"
            type="textarea"
            :rows="3"
            :disabled="form.clearHeaders"
            :placeholder="editingId ? '留空则保留已保存的请求头' : headersPlaceholder"
          />
          <div v-if="editingId && form.headersConfigured" class="form-hint">
            已保存的请求头已加密，填写新值将整体替换原有配置。
          </div>
          <el-checkbox
            v-if="editingId && form.headersConfigured"
            v-model="form.clearHeaders"
            class="form-hint"
          >
            清除已保存的请求头
          </el-checkbox>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="描述该服务的用途" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch
            v-model="form.status"
            :active-value="0"
            :inactive-value="1"
            active-text="启用"
            inactive-text="停用"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="drawerVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-drawer>

    <el-dialog v-model="testDialogVisible" title="MCP 连接测试" width="560px">
      <div v-loading="testing" class="test-body">
        <template v-if="testResult">
          <div class="test-status" :class="testResult.ok ? 'ok' : 'fail'">
            <el-icon v-if="testResult.ok"><Check /></el-icon>
            <el-icon v-else><Tools /></el-icon>
            <span>{{ testResult.message }}</span>
          </div>
          <div v-if="testResult.tools.length" class="tool-list">
            <p class="tool-title">发现 {{ testResult.tools.length }} 个工具</p>
            <div v-for="tool in testResult.tools" :key="tool.name" class="tool-item">
              <div class="tool-name mono">{{ tool.name }}</div>
              <p class="tool-desc">{{ tool.description || '暂无工具描述' }}</p>
            </div>
          </div>
          <EmptyState
            v-else-if="testResult.ok"
            :icon="Tools"
            title="服务已连通"
            description="该服务当前没有声明可调用的工具。"
          />
        </template>
        <div v-else class="test-pending">正在握手并读取工具列表…</div>
      </div>
      <template #footer>
        <el-button @click="testDialogVisible = false">关闭</el-button>
        <el-button :loading="testing" @click="testingId && test(servers.find((s) => s.id === testingId)!)">
          重新测试
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.server-wrap {
  min-height: 240px;
}

.server-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));
  gap: 16px;
}

.server-card {
  padding: 22px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  transition: transform 0.18s ease, border-color 0.18s ease;
}

.server-card:hover {
  transform: translateY(-2px);
  border-color: rgba(124, 92, 255, 0.34);
}

.server-top {
  display: flex;
  gap: 14px;
  align-items: flex-start;
}

.server-icon {
  width: 44px;
  height: 44px;
  flex: 0 0 44px;
  border-radius: 13px;
  display: grid;
  place-items: center;
  font-size: 21px;
  color: var(--k-violet);
  background: linear-gradient(135deg, rgba(124, 92, 255, 0.26), rgba(56, 189, 248, 0.16));
  border: 1px solid rgba(124, 92, 255, 0.32);
}

.server-heading {
  flex: 1;
  min-width: 0;
}

.server-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.server-title-row h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--text-strong);
}

.server-remark {
  margin: 6px 0 0;
  font-size: 13px;
  line-height: 1.55;
  color: var(--text-muted);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.url-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 10px;
  background: var(--k-code-bg);
  border: 1px solid var(--border-subtle);
  color: var(--text-muted);
  font-size: 12px;
  overflow: hidden;
}

.url-row .mono {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.server-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 12px;
}

.meta-pill {
  padding: 3px 9px;
  border-radius: 999px;
  background: rgba(124, 92, 255, 0.14);
  border: 1px solid rgba(124, 92, 255, 0.26);
  color: var(--k-violet);
  font-family: var(--font-mono);
  letter-spacing: 0.02em;
}

.meta-pill.muted {
  background: rgba(148, 163, 184, 0.1);
  border-color: rgba(148, 163, 184, 0.2);
  color: var(--text-muted);
  font-family: inherit;
}

.meta-pill.warn {
  background: rgba(251, 191, 36, 0.1);
  border-color: rgba(251, 191, 36, 0.24);
  color: var(--k-amber);
}

.meta-time {
  margin-left: auto;
  color: var(--text-faint);
}

.server-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  padding-top: 12px;
  border-top: 1px solid var(--border-subtle);
}

.form-hint {
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-faint);
  line-height: 1.5;
}

.mono-input :deep(input) {
  font-family: var(--font-mono);
}

.test-body {
  min-height: 140px;
}

.test-toggle {
  display: flex;
  justify-content: flex-end;
  padding-bottom: 8px;
}

.test-pending {
  display: grid;
  place-items: center;
  min-height: 140px;
  color: var(--text-muted);
  font-size: 13px;
}

.test-status {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border-radius: 12px;
  font-size: 13px;
  line-height: 1.5;
}

.test-status.ok {
  background: rgba(52, 211, 153, 0.1);
  border: 1px solid rgba(52, 211, 153, 0.28);
  color: var(--k-green);
}

.test-status.fail {
  background: rgba(248, 113, 113, 0.1);
  border: 1px solid rgba(248, 113, 113, 0.28);
  color: var(--k-red);
}

.tool-list {
  margin-top: 18px;
}

.tool-title {
  margin: 0 0 10px;
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--text-faint);
}

.tool-item {
  padding: 12px 14px;
  border-radius: 12px;
  background: var(--k-code-bg);
  border: 1px solid var(--border-subtle);
  margin-bottom: 8px;
}

.tool-name {
  font-size: 13px;
  color: var(--k-cyan-soft);
}

.tool-desc {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.55;
  color: var(--text-muted);
}

@media (max-width: 720px) {
  .server-grid {
    grid-template-columns: 1fr;
  }
}
</style>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  Check,
  Connection,
  Cpu,
  Download,
  EditPen,
  Key,
  Plus,
  Refresh,
  Search,
  SetUp,
} from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { modelApi, supplierApi } from '@/api'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import type {
  ModelCreateRequest,
  ModelView,
  SupplierSaveRequest,
  SupplierTestResult,
  SupplierView,
} from '@/api/types'
import { formatNumber, fromNow } from '@/utils/format'

const auth = useAuthStore()

const activeTab = ref<'suppliers' | 'models'>('suppliers')

const loading = ref(false)
const suppliers = ref<SupplierView[]>([])
const supplierOptions = ref<SupplierView[]>([])
const supplierPage = ref(1)
const supplierTotal = ref(0)
const supplierPageSize = 12
const models = ref<ModelView[]>([])
const keyword = ref('')
const filterSupplierId = ref<number | undefined>()
const filterType = ref<string | undefined>()

const supplierDrawerVisible = ref(false)
const supplierSaving = ref(false)
const supplierEditingId = ref<number | null>(null)
const supplierFormRef = ref<FormInstance>()
const supplierForm = reactive({
  name: '',
  type: 'OPENAI',
  baseUrl: '',
  apiKey: '',
  headers: '',
  clearHeaders: false,
  headersConfigured: false,
  status: 0,
  remark: '',
})
const headersPlaceholder = 'JSON 对象，例如 {"X-Org-Id":"king"}'

const testVisible = ref(false)
const testing = ref(false)
const testTarget = ref<SupplierView | null>(null)
const testResult = ref<SupplierTestResult | null>(null)

const importVisible = ref(false)
const importing = ref(false)
const importTarget = ref<SupplierView | null>(null)
const importForm = reactive({
  modelsText: '',
  type: 'CHAT',
  dimension: 1536,
})

const modelDrawerVisible = ref(false)
const modelSaving = ref(false)
const modelEditingId = ref<number | null>(null)
const modelFormRef = ref<FormInstance>()
const modelForm = reactive<ModelCreateRequest>({
  supplierId: 0,
  name: '',
  model: '',
  type: 'CHAT',
  dimension: 1536,
  status: 0,
})

const supplierRules: FormRules = {
  name: [
    { required: true, message: '请输入供应商名称', trigger: 'blur' },
    { max: 100, message: '名称不能超过 100 个字符', trigger: 'blur' },
  ],
  baseUrl: [
    {
      validator: (_rule, value: string, callback) => {
        if (supplierForm.type === 'MOCK') return callback()
        if (!value || !/^https?:\/\//.test(value)) {
          return callback(new Error('请填写合法的 http/https 地址'))
        }
        callback()
      },
      trigger: 'blur',
    },
  ],
}

const modelRules: FormRules = {
  supplierId: [{ required: true, message: '请选择供应商', trigger: 'change' }],
  name: [{ required: true, message: '请输入模型显示名称', trigger: 'blur' }],
  model: [{ required: true, message: '请输入模型标识', trigger: 'blur' }],
  dimension: [
    {
      validator: (_rule, value: number, callback) => {
        if (modelForm.type !== 'EMBEDDING') return callback()
        if (!value || value <= 0) return callback(new Error('向量模型需要填写维度'))
        callback()
      },
      trigger: 'blur',
    },
  ],
}

const chatCount = computed(() => models.value.filter((model) => model.type === 'CHAT').length)
const embeddingCount = computed(
  () => models.value.filter((model) => model.type === 'EMBEDDING').length,
)
const totalUsage = computed(() =>
  models.value.reduce((total, model) => total + (model.usageCount || 0), 0),
)
const enabledSuppliers = computed(
  () => supplierOptions.value.filter((supplier) => supplier.status === 0).length,
)

function resetSupplierForm() {
  supplierEditingId.value = null
  Object.assign(supplierForm, {
    name: '',
    type: 'OPENAI',
    baseUrl: '',
    apiKey: '',
    headers: '',
    clearHeaders: false,
    headersConfigured: false,
    status: 0,
    remark: '',
  })
}

async function loadAll() {
  loading.value = true
  try {
    const [supplierPageResult, supplierAll, modelList] = await Promise.all([
      supplierApi.page({ page: supplierPage.value, size: supplierPageSize }),
      supplierApi.all(),
      modelApi.list({
        supplierId: filterSupplierId.value,
        type: filterType.value,
        keyword: keyword.value.trim() || undefined,
      }),
    ])
    suppliers.value = supplierPageResult.list
    supplierTotal.value = supplierPageResult.total
    supplierOptions.value = supplierAll
    models.value = modelList
    if (!supplierPageResult.list.length && supplierPageResult.total > 0 && supplierPage.value > 1) {
      supplierPage.value = Math.max(1, Math.ceil(supplierPageResult.total / supplierPageSize))
      await loadAll()
    }
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载模型配置失败')
  } finally {
    loading.value = false
  }
}

function changeSupplierPage(page: number) {
  supplierPage.value = page
  loadAll()
}

async function loadModels() {
  try {
    models.value = await modelApi.list({
      supplierId: filterSupplierId.value,
      type: filterType.value,
      keyword: keyword.value.trim() || undefined,
    })
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载模型失败')
  }
}

function openCreateSupplier() {
  resetSupplierForm()
  supplierDrawerVisible.value = true
}

function openEditSupplier(supplier: SupplierView) {
  resetSupplierForm()
  supplierEditingId.value = supplier.id
  Object.assign(supplierForm, {
    name: supplier.name,
    type: supplier.type,
    baseUrl: supplier.baseUrl,
    apiKey: '',
    headers: '',
    clearHeaders: false,
    headersConfigured: supplier.headersConfigured,
    status: supplier.status,
    remark: supplier.remark || '',
  })
  supplierDrawerVisible.value = true
}

async function submitSupplier() {
  const valid = await supplierFormRef.value?.validate().catch(() => false)
  if (!valid) return
  supplierSaving.value = true
  const headers = supplierForm.clearHeaders
    ? ''
    : supplierForm.headers.trim() || undefined
  const payload: SupplierSaveRequest = {
    name: supplierForm.name.trim(),
    type: supplierForm.type,
    baseUrl: supplierForm.type === 'MOCK' ? undefined : supplierForm.baseUrl.trim(),
    apiKey: supplierEditingId.value && !supplierForm.apiKey ? undefined : supplierForm.apiKey,
    headers,
    status: supplierForm.status,
    remark: supplierForm.remark.trim(),
  }
  try {
    if (supplierEditingId.value) {
      await supplierApi.update(supplierEditingId.value, payload)
      ElMessage.success('供应商已更新')
    } else {
      await supplierApi.create(payload)
      ElMessage.success('供应商已创建，现在可以导入模型')
    }
    supplierDrawerVisible.value = false
    await loadAll()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '保存失败')
  } finally {
    supplierSaving.value = false
  }
}

async function removeSupplier(supplier: SupplierView) {
  try {
    await ElMessageBox.confirm(
      `确认删除供应商「${supplier.name}」？请先删除其下的模型。`,
      '删除供应商',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await supplierApi.remove(supplier.id)
    ElMessage.success('供应商已删除')
    await loadAll()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

async function testSupplier(supplier: SupplierView) {
  testTarget.value = supplier
  testResult.value = null
  testVisible.value = true
  testing.value = true
  try {
    testResult.value = await supplierApi.test(supplier.id)
  } catch (error) {
    testResult.value = {
      ok: false,
      message: error instanceof ApiError ? error.message : '连接测试失败',
      models: [],
    }
  } finally {
    testing.value = false
  }
}

function openImport(supplier: SupplierView, discovered: string[] = []) {
  importTarget.value = supplier
  importForm.modelsText = discovered.join('\n')
  importForm.type = 'CHAT'
  importForm.dimension = 1536
  testVisible.value = false
  importVisible.value = true
}

async function submitImport() {
  if (!importTarget.value) return
  const values = importForm.modelsText
    .split(/[\n,]/)
    .map((item) => item.trim())
    .filter(Boolean)
  if (!values.length) {
    ElMessage.warning('请填写至少一个模型标识')
    return
  }
  importing.value = true
  try {
    const created = await supplierApi.importModels(importTarget.value.id, {
      models: values,
      type: importForm.type,
      dimension: importForm.type === 'EMBEDDING' ? importForm.dimension : undefined,
    })
    ElMessage.success(
      created.length ? `已导入 ${created.length} 个模型` : '模型均已存在，无需重复导入',
    )
    importVisible.value = false
    await loadAll()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '导入失败')
  } finally {
    importing.value = false
  }
}

function resetModelForm() {
  modelEditingId.value = null
  Object.assign(modelForm, {
    supplierId: suppliers.value[0]?.id || 0,
    name: '',
    model: '',
    type: 'CHAT',
    dimension: 1536,
    status: 0,
  })
}

function openCreateModel(supplier?: SupplierView) {
  resetModelForm()
  if (supplier) modelForm.supplierId = supplier.id
  modelDrawerVisible.value = true
}

function openEditModel(model: ModelView) {
  resetModelForm()
  modelEditingId.value = model.id
  Object.assign(modelForm, {
    supplierId: model.supplierId,
    name: model.name,
    model: model.model,
    type: model.type,
    dimension: model.dimension || 1536,
    status: model.status,
  })
  modelDrawerVisible.value = true
}

async function submitModel() {
  const valid = await modelFormRef.value?.validate().catch(() => false)
  if (!valid) return
  modelSaving.value = true
  try {
    if (modelEditingId.value) {
      await modelApi.update(modelEditingId.value, {
        name: modelForm.name.trim(),
        model: modelForm.model.trim(),
        type: modelForm.type,
        dimension: modelForm.type === 'EMBEDDING' ? modelForm.dimension : undefined,
        status: modelForm.status,
      })
      ElMessage.success('模型已更新')
    } else {
      await modelApi.create({
        ...modelForm,
        name: modelForm.name.trim(),
        model: modelForm.model.trim(),
        dimension: modelForm.type === 'EMBEDDING' ? modelForm.dimension : undefined,
      })
      ElMessage.success('模型已添加')
    }
    modelDrawerVisible.value = false
    await loadAll()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '保存失败')
  } finally {
    modelSaving.value = false
  }
}

async function removeModel(model: ModelView) {
  try {
    await ElMessageBox.confirm(
      `确认删除模型「${model.name}」？正在被智能体或知识库使用的模型无法删除。`,
      '删除模型',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await modelApi.remove(model.id)
    ElMessage.success('模型已删除')
    await loadAll()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

onMounted(loadAll)
</script>

<template>
  <div class="page">
    <PageHeader
      title="模型接入"
      description="管理 OpenAI 兼容供应商与对话 / 向量模型，为智能体和知识库提供推理能力。"
      eyebrow="Models"
    >
      <template #actions>
        <el-button v-if="auth.canManageSpace" type="primary" @click="activeTab === 'suppliers' ? openCreateSupplier() : openCreateModel()">
          <el-icon><Plus /></el-icon>
          {{ activeTab === 'suppliers' ? '新增供应商' : '添加模型' }}
        </el-button>
      </template>
    </PageHeader>

    <div class="summary-row">
      <div class="summary-chip">
        <span class="chip-dot"></span>
        {{ supplierTotal }} 个供应商 · {{ enabledSuppliers }} 个启用
      </div>
      <div class="summary-chip">
        <span class="chip-dot cyan"></span>
        {{ chatCount }} 个对话模型
      </div>
      <div class="summary-chip">
        <span class="chip-dot green"></span>
        {{ embeddingCount }} 个向量模型
      </div>
      <div class="summary-chip">
        <span class="chip-dot muted"></span>
        被引用 {{ totalUsage }} 次
      </div>
      <div class="summary-spacer"></div>
      <div class="search-box">
        <el-input
          v-model="keyword"
          placeholder="搜索模型名称或标识"
          clearable
          @keyup.enter="loadModels"
          @clear="loadModels"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button @click="loadModels">搜索</el-button>
      </div>
    </div>

    <div class="tab-bar">
      <button
        type="button"
        class="tab-pill"
        :class="{ active: activeTab === 'suppliers' }"
        @click="activeTab = 'suppliers'"
      >
        <el-icon><Connection /></el-icon>
        供应商
        <span class="tab-count">{{ supplierTotal }}</span>
      </button>
      <button
        type="button"
        class="tab-pill"
        :class="{ active: activeTab === 'models' }"
        @click="activeTab = 'models'"
      >
        <el-icon><Cpu /></el-icon>
        模型
        <span class="tab-count">{{ models.length }}</span>
      </button>
    </div>

    <template v-if="activeTab === 'suppliers'">
      <div v-loading="loading" class="supplier-wrap">
        <div v-if="suppliers.length" class="supplier-grid">
          <article v-for="supplier in suppliers" :key="supplier.id" class="supplier-card panel">
            <div class="supplier-top">
              <div class="supplier-icon" :class="{ mock: supplier.type === 'MOCK' }">
                <el-icon><SetUp /></el-icon>
              </div>
              <div class="supplier-heading">
                <div class="supplier-title-row">
                  <h3>{{ supplier.name }}</h3>
                  <span class="type-pill">{{ supplier.type }}</span>
                  <span class="badge" :class="supplier.status === 0 ? 'published' : 'draft'">
                    {{ supplier.status === 0 ? '启用' : '停用' }}
                  </span>
                </div>
                <p class="supplier-remark">{{ supplier.remark || '未填写备注' }}</p>
              </div>
            </div>

            <div class="supplier-fields">
              <div class="field-row">
                <span class="field-label">接口地址</span>
                <span class="field-value mono">{{ supplier.baseUrl || '—' }}</span>
              </div>
              <div class="field-row">
                <span class="field-label">API Key</span>
                <span class="field-value mono">
                  <template v-if="supplier.hasApiKey">
                    <el-icon><Key /></el-icon>
                    {{ supplier.apiKeyMasked }}
                  </template>
                  <template v-else>未配置</template>
                </span>
              </div>
              <div class="field-row">
                <span class="field-label">模型数</span>
                <span class="field-value">{{ supplier.modelCount }} 个</span>
              </div>
            </div>

            <div class="supplier-foot">
              <span class="created">{{ fromNow(supplier.createdAt) }}</span>
              <div class="supplier-actions">
                <el-button size="small" @click="testSupplier(supplier)">
                  <el-icon><Refresh /></el-icon>
                  测试
                </el-button>
                <el-button v-if="auth.canManageSpace" size="small" @click="openCreateModel(supplier)">
                  <el-icon><Plus /></el-icon>
                  模型
                </el-button>
                <el-dropdown v-if="auth.canManageSpace" trigger="click">
                  <el-button size="small" circle text>
                    <el-icon><EditPen /></el-icon>
                  </el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item @click="openEditSupplier(supplier)">
                        编辑供应商
                      </el-dropdown-item>
                      <el-dropdown-item @click="openImport(supplier)">
                        批量导入模型
                      </el-dropdown-item>
                      <el-dropdown-item divided @click="removeSupplier(supplier)">
                        删除供应商
                      </el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </div>
            </div>
          </article>
        </div>
        <EmptyState
          v-else-if="!loading"
          :icon="Connection"
          title="还没有接入供应商"
          description="接入 OpenAI 兼容服务或使用本地模拟供应商，然后导入模型供智能体使用。"
        >
          <el-button v-if="auth.canManageSpace" type="primary" @click="openCreateSupplier">
            <el-icon><Plus /></el-icon>
            新增供应商
          </el-button>
        </EmptyState>
        <div v-if="supplierTotal > supplierPageSize" class="list-pagination">
          <el-pagination
            background
            layout="prev, pager, next"
            :current-page="supplierPage"
            :page-size="supplierPageSize"
            :total="supplierTotal"
            @current-change="changeSupplierPage"
          />
        </div>
      </div>
    </template>

    <template v-else>
      <div class="filter-row">
        <el-select v-model="filterSupplierId" placeholder="全部供应商" clearable style="width: 190px" @change="loadModels">
          <el-option v-for="supplier in supplierOptions" :key="supplier.id" :label="supplier.name" :value="supplier.id" />
        </el-select>
        <el-select v-model="filterType" placeholder="全部类型" clearable style="width: 150px" @change="loadModels">
          <el-option label="对话模型" value="CHAT" />
          <el-option label="向量模型" value="EMBEDDING" />
        </el-select>
        <span class="filter-count">{{ models.length }} 个模型</span>
      </div>

      <div class="panel table-panel">
        <el-table v-loading="loading" :data="models" style="width: 100%">
          <el-table-column label="模型名称" min-width="200">
            <template #default="{ row }">
              <div class="model-cell">
                <span class="model-dot" :class="row.type === 'EMBEDDING' ? 'vector' : 'chat'"></span>
                <div>
                  <div class="model-name">{{ row.name }}</div>
                  <div class="model-id mono">{{ row.model }}</div>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="supplierName" label="供应商" min-width="140" />
          <el-table-column label="类型" width="120">
            <template #default="{ row }">
              <span class="type-pill" :class="{ vector: row.type === 'EMBEDDING' }">
                {{ row.type === 'EMBEDDING' ? '向量' : '对话' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="向量维度" width="110">
            <template #default="{ row }">
              {{ row.type === 'EMBEDDING' ? formatNumber(row.dimension) : '—' }}
            </template>
          </el-table-column>
          <el-table-column label="被引用" width="100">
            <template #default="{ row }">
              <span :class="{ 'usage-hot': row.usageCount > 0 }">{{ row.usageCount }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <span class="status-dot" :class="row.status === 0 ? 'on' : 'off'">
                {{ row.status === 0 ? '启用' : '停用' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="140">
            <template #default="{ row }">{{ fromNow(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column v-if="auth.canManageSpace" label="操作" width="140" fixed="right">
            <template #default="{ row }">
              <el-button size="small" text @click="openEditModel(row)">编辑</el-button>
              <el-button size="small" text type="danger" @click="removeModel(row)">删除</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <EmptyState
              :icon="Cpu"
              title="暂无模型"
              description="先在供应商下导入或手动添加模型，才能创建智能体与知识库。"
            >
              <el-button v-if="auth.canManageSpace" type="primary" @click="openCreateModel()">
                添加模型
              </el-button>
            </EmptyState>
          </template>
        </el-table>
      </div>
    </template>

    <el-drawer
      v-model="supplierDrawerVisible"
      :title="supplierEditingId ? '编辑供应商' : '新增供应商'"
      size="540px"
      destroy-on-close
    >
      <el-form ref="supplierFormRef" :model="supplierForm" :rules="supplierRules" label-position="top">
        <el-form-item label="供应商名称" prop="name">
          <el-input v-model="supplierForm.name" placeholder="例如：OpenAI 生产环境" maxlength="100" />
        </el-form-item>
        <el-form-item label="供应商类型">
          <el-radio-group v-model="supplierForm.type">
            <el-radio-button value="OPENAI">OpenAI 兼容</el-radio-button>
            <el-radio-button value="MOCK">本地模拟</el-radio-button>
          </el-radio-group>
          <div class="form-hint">
            本地模拟供应商无需网络，用于体验完整对话、RAG 与工具链路。
          </div>
        </el-form-item>
        <el-form-item v-if="supplierForm.type === 'OPENAI'" label="接口地址" prop="baseUrl">
          <el-input v-model="supplierForm.baseUrl" placeholder="https://api.openai.com/v1" class="mono-input" />
        </el-form-item>
        <el-form-item v-if="supplierForm.type === 'OPENAI'" label="API Key">
          <el-input
            v-model="supplierForm.apiKey"
            type="password"
            show-password
            :placeholder="supplierEditingId ? '留空则不修改已保存的密钥' : 'sk-...'"
          />
          <div class="form-hint">密钥使用 AES-GCM 加密存储，仅在服务端解密调用。</div>
        </el-form-item>
        <el-form-item label="自定义请求头">
          <el-input
            v-model="supplierForm.headers"
            type="textarea"
            :rows="3"
            :disabled="supplierForm.clearHeaders"
            :placeholder="
              supplierEditingId ? '留空则保留已保存的请求头' : headersPlaceholder
            "
          />
          <div v-if="supplierEditingId && supplierForm.headersConfigured" class="form-hint">
            已保存的请求头已加密，填写新值将整体替换原有配置。
          </div>
          <el-checkbox
            v-if="supplierEditingId && supplierForm.headersConfigured"
            v-model="supplierForm.clearHeaders"
            class="form-hint"
          >
            清除已保存的请求头
          </el-checkbox>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="supplierForm.remark" type="textarea" :rows="2" placeholder="用途说明" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch
            v-model="supplierForm.status"
            :active-value="0"
            :inactive-value="1"
            active-text="启用"
            inactive-text="停用"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="supplierDrawerVisible = false">取消</el-button>
        <el-button type="primary" :loading="supplierSaving" @click="submitSupplier">保存</el-button>
      </template>
    </el-drawer>

    <el-dialog v-model="testVisible" title="供应商连接测试" width="600px">
      <div v-loading="testing" class="test-body">
        <template v-if="testResult">
          <div class="test-status" :class="testResult.ok ? 'ok' : 'fail'">
            <el-icon v-if="testResult.ok"><Check /></el-icon>
            <el-icon v-else><Connection /></el-icon>
            <span>{{ testResult.message }}</span>
          </div>
          <div v-if="testResult.models.length" class="discovered">
            <div class="discovered-head">
              <p>可用模型</p>
              <span class="mono">{{ testResult.models.length }}</span>
            </div>
            <div class="chips">
              <span v-for="name in testResult.models.slice(0, 60)" :key="name" class="model-chip">
                {{ name }}
              </span>
              <span v-if="testResult.models.length > 60" class="model-chip more">
                +{{ testResult.models.length - 60 }}
              </span>
            </div>
          </div>
        </template>
        <div v-else class="test-pending">正在请求 /models 接口…</div>
      </div>
      <template #footer>
        <el-button @click="testVisible = false">关闭</el-button>
        <el-button
          v-if="testResult?.ok && testResult.models.length && testTarget && auth.canManageSpace"
          type="primary"
          @click="openImport(testTarget, testResult.models)"
        >
          <el-icon><Download /></el-icon>
          导入发现的模型
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="importVisible" title="批量导入模型" width="560px">
      <el-form label-position="top">
        <el-form-item label="模型标识（每行一个，也支持逗号分隔）">
          <el-input
            v-model="importForm.modelsText"
            type="textarea"
            :rows="8"
            class="mono-input"
            placeholder="gpt-4o&#10;gpt-4o-mini&#10;text-embedding-3-large"
          />
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="模型类型">
            <el-select v-model="importForm.type" style="width: 100%">
              <el-option label="对话模型" value="CHAT" />
              <el-option label="向量模型" value="EMBEDDING" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="importForm.type === 'EMBEDDING'" label="向量维度">
            <el-input-number v-model="importForm.dimension" :min="1" :max="8192" style="width: 100%" />
          </el-form-item>
        </div>
        <div class="form-hint">
          重复的模型标识会自动跳过；导入后模型默认启用，可直接在智能体中选择。
        </div>
      </el-form>
      <template #footer>
        <el-button @click="importVisible = false">取消</el-button>
        <el-button type="primary" :loading="importing" @click="submitImport">导入</el-button>
      </template>
    </el-dialog>

    <el-drawer
      v-model="modelDrawerVisible"
      :title="modelEditingId ? '编辑模型' : '添加模型'"
      size="500px"
      destroy-on-close
    >
      <el-form ref="modelFormRef" :model="modelForm" :rules="modelRules" label-position="top">
        <el-form-item label="所属供应商" prop="supplierId">
          <el-select v-model="modelForm.supplierId" style="width: 100%" :disabled="!!modelEditingId">
            <el-option v-for="supplier in supplierOptions" :key="supplier.id" :label="supplier.name" :value="supplier.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="显示名称" prop="name">
          <el-input v-model="modelForm.name" placeholder="例如：GPT-4o" maxlength="100" />
        </el-form-item>
        <el-form-item label="模型标识" prop="model">
          <el-input v-model="modelForm.model" placeholder="例如：gpt-4o" class="mono-input" maxlength="150" />
          <div class="form-hint">与供应商接口中的 model 字段一致。</div>
        </el-form-item>
        <el-form-item label="模型类型">
          <el-radio-group v-model="modelForm.type">
            <el-radio-button value="CHAT">对话</el-radio-button>
            <el-radio-button value="EMBEDDING">向量</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="modelForm.type === 'EMBEDDING'" label="向量维度" prop="dimension">
          <el-input-number v-model="modelForm.dimension" :min="1" :max="8192" style="width: 100%" />
          <div class="form-hint">需与模型实际输出维度一致，创建知识库后不可更改。</div>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch
            v-model="modelForm.status"
            :active-value="0"
            :inactive-value="1"
            active-text="启用"
            inactive-text="停用"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="modelDrawerVisible = false">取消</el-button>
        <el-button type="primary" :loading="modelSaving" @click="submitModel">保存</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped lang="scss">
.tab-bar {
  display: flex;
  gap: 8px;
  margin-bottom: 18px;
}

.tab-pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 9px 16px;
  border-radius: 999px;
  border: 1px solid var(--border-subtle);
  background: rgba(13, 18, 38, 0.6);
  color: var(--text-muted);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.16s ease;
}

.tab-pill:hover {
  color: var(--text-strong);
  border-color: rgba(124, 92, 255, 0.34);
}

.tab-pill.active {
  color: #ece7ff;
  border-color: rgba(124, 92, 255, 0.55);
  background: linear-gradient(135deg, rgba(124, 92, 255, 0.24), rgba(56, 189, 248, 0.1));
  box-shadow: 0 8px 22px -14px rgba(124, 92, 255, 0.9);
}

.tab-count {
  font-family: var(--font-mono);
  font-size: 11px;
  padding: 1px 7px;
  border-radius: 999px;
  background: rgba(148, 163, 184, 0.14);
}

.tab-pill.active .tab-count {
  background: rgba(124, 92, 255, 0.32);
  color: #d9d0ff;
}

.supplier-wrap {
  min-height: 240px;
}

.list-pagination {
  display: flex;
  justify-content: center;
  margin-top: 20px;
}

.supplier-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(370px, 1fr));
  gap: 16px;
}

.supplier-card {
  padding: 22px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  transition: transform 0.18s ease, border-color 0.18s ease;
}

.supplier-card:hover {
  transform: translateY(-2px);
  border-color: rgba(124, 92, 255, 0.32);
}

.supplier-top {
  display: flex;
  gap: 14px;
}

.supplier-icon {
  width: 44px;
  height: 44px;
  flex: 0 0 44px;
  border-radius: 13px;
  display: grid;
  place-items: center;
  font-size: 20px;
  color: #8bd8f4;
  background: linear-gradient(135deg, rgba(56, 189, 248, 0.2), rgba(124, 92, 255, 0.16));
  border: 1px solid rgba(56, 189, 248, 0.28);
}

.supplier-icon.mock {
  color: #f0d48a;
  background: linear-gradient(135deg, rgba(250, 204, 21, 0.16), rgba(124, 92, 255, 0.1));
  border-color: rgba(250, 204, 21, 0.28);
}

.supplier-heading {
  flex: 1;
  min-width: 0;
}

.supplier-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.supplier-title-row h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--text-strong);
}

.type-pill {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.06em;
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(56, 189, 248, 0.12);
  border: 1px solid rgba(56, 189, 248, 0.24);
  color: #7dd3fc;
}

.type-pill.vector {
  background: rgba(52, 211, 153, 0.12);
  border-color: rgba(52, 211, 153, 0.24);
  color: #6ee7b7;
}

.supplier-remark {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--text-muted);
  line-height: 1.55;
}

.supplier-fields {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px;
  border-radius: 12px;
  background: rgba(9, 13, 28, 0.55);
  border: 1px solid var(--border-subtle);
}

.field-row {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12px;
}

.field-label {
  flex: 0 0 66px;
  color: var(--text-faint);
}

.field-value {
  flex: 1;
  min-width: 0;
  color: var(--text-muted);
  display: inline-flex;
  align-items: center;
  gap: 6px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.supplier-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--border-subtle);
}

.created {
  font-size: 11px;
  color: var(--text-faint);
}

.supplier-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.filter-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}

.filter-count {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-faint);
}

.table-panel {
  padding: 8px 12px 12px;
}

.model-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.model-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex: 0 0 8px;
  background: #7c5cff;
  box-shadow: 0 0 10px rgba(124, 92, 255, 0.7);
}

.model-dot.vector {
  background: #34d399;
  box-shadow: 0 0 10px rgba(52, 211, 153, 0.7);
}

.model-name {
  font-size: 13px;
  font-weight: 550;
  color: var(--text-strong);
}

.model-id {
  font-size: 11px;
  color: var(--text-faint);
  margin-top: 2px;
}

.usage-hot {
  color: #c4b5fd;
  font-weight: 600;
}

.status-dot {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}

.status-dot::before {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.status-dot.on {
  color: #6ee7b7;
}

.status-dot.on::before {
  background: #34d399;
  box-shadow: 0 0 8px rgba(52, 211, 153, 0.8);
}

.status-dot.off {
  color: var(--text-faint);
}

.status-dot.off::before {
  background: #64748b;
}

.form-hint {
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-faint);
  line-height: 1.5;
}

.mono-input :deep(input),
.mono-input :deep(textarea) {
  font-family: var(--font-mono);
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.test-body {
  min-height: 140px;
}

.test-pending {
  display: grid;
  place-items: center;
  min-height: 140px;
  font-size: 13px;
  color: var(--text-muted);
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
  color: #6ee7b7;
}

.test-status.fail {
  background: rgba(248, 113, 113, 0.1);
  border: 1px solid rgba(248, 113, 113, 0.28);
  color: #fca5a5;
}

.discovered {
  margin-top: 18px;
}

.discovered-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.discovered-head p {
  margin: 0;
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--text-faint);
}

.discovered-head .mono {
  font-size: 12px;
  color: var(--text-muted);
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  max-height: 220px;
  overflow-y: auto;
}

.model-chip {
  font-family: var(--font-mono);
  font-size: 11px;
  padding: 4px 9px;
  border-radius: 8px;
  background: rgba(9, 13, 28, 0.6);
  border: 1px solid var(--border-subtle);
  color: var(--text-muted);
}

.model-chip.more {
  color: #c4b5fd;
}

@media (max-width: 720px) {
  .supplier-grid {
    grid-template-columns: 1fr;
  }

  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>

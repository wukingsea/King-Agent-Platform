<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  ElMessage,
  ElMessageBox,
  type FormInstance,
  type FormRules,
  type UploadRequestOptions,
} from 'element-plus'
import {
  Collection,
  Delete,
  Document,
  EditPen,
  Plus,
  Refresh,
  Search,
  UploadFilled,
  View,
} from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { knowledgeApi, modelApi } from '@/api'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import type {
  KnowledgeBaseSaveRequest,
  KnowledgeBaseView,
  KnowledgeChunkView,
  KnowledgeDocView,
  KnowledgeSearchResult,
  ModelOption,
} from '@/api/types'
import {
  documentStatus,
  formatDate,
  formatNumber,
  truncate,
} from '@/utils/format'

const auth = useAuthStore()
const route = useRoute()

const loading = ref(true)
const bases = ref<KnowledgeBaseView[]>([])
const basesPage = ref(1)
const basesTotal = ref(0)
const basesPageSize = 30
const baseKeyword = ref('')
const selectedId = ref<number | null>(null)
const docs = ref<KnowledgeDocView[]>([])
const docsLoading = ref(false)
const docKeyword = ref('')
const docsPage = ref(1)
const docsTotal = ref(0)
const docsPageSize = 20
const aclDialogVisible = ref(false)
const aclSaving = ref(false)
const aclDoc = ref<KnowledgeDocView | null>(null)
const aclMinRole = ref('ALL')
const uploading = ref(false)
const activeTab = ref<'docs' | 'search'>('docs')
const embeddingModels = ref<ModelOption[]>([])

const baseDialogVisible = ref(false)
const baseSaving = ref(false)
const editingBaseId = ref<number | null>(null)
const baseFormRef = ref<FormInstance>()

const docDialogVisible = ref(false)
const docSaving = ref(false)
const docForm = reactive({ name: '', content: '' })

const chunkDrawerVisible = ref(false)
const chunkLoading = ref(false)
const activeDoc = ref<KnowledgeDocView | null>(null)
const docContent = ref('')
const chunks = ref<KnowledgeChunkView[]>([])
const chunkTotal = ref(0)
const chunkPage = ref(1)
const chunkSize = 20

const searchQuery = ref('')
const searchTopK = ref(4)
const searchLoading = ref(false)
const searchResults = ref<KnowledgeSearchResult[]>([])
const searched = ref(false)

const selectedBase = computed(
  () => bases.value.find((base) => base.id === selectedId.value) || null,
)

const baseForm = reactive({
  name: '',
  description: '',
  embeddingModelId: undefined as number | undefined,
  chunkSize: 800,
  chunkOverlap: 100,
  topK: 4,
  status: 0,
})

const baseRules: FormRules = {
  name: [
    { required: true, message: '请输入知识库名称', trigger: 'blur' },
    { max: 100, message: '名称不能超过 100 个字符', trigger: 'blur' },
  ],
  embeddingModelId: [{ required: true, message: '请选择嵌入模型', trigger: 'change' }],
}

const hasPending = computed(() =>
  docs.value.some((doc) => doc.status === 'PENDING' || doc.status === 'PROCESSING'),
)

let pollTimer: number | undefined
let polling = false

function stopPolling() {
  if (pollTimer) {
    window.clearInterval(pollTimer)
    pollTimer = undefined
  }
}

function ensurePolling() {
  if (hasPending.value && !pollTimer) {
    pollTimer = window.setInterval(async () => {
      if (polling) return
      polling = true
      try {
        await loadDocs(true)
        await loadBases(true)
      } finally {
        polling = false
      }
    }, 2800)
  }
  if (!hasPending.value) stopPolling()
}

async function loadBases(silent = false) {
  if (!silent) loading.value = true
  try {
    const result = await knowledgeApi.page({
      page: basesPage.value,
      size: basesPageSize,
      keyword: baseKeyword.value.trim() || undefined,
    })
    bases.value = result.list
    basesTotal.value = result.total
    if (!result.list.length && result.total > 0 && basesPage.value > 1) {
      basesPage.value = Math.max(1, Math.ceil(result.total / basesPageSize))
      await loadBases(silent)
      return
    }
    if (selectedId.value && !result.list.some((base) => base.id === selectedId.value)) {
      selectedId.value = null
    }
    if (!selectedId.value && result.list.length) {
      await selectBase(result.list[0].id)
    }
  } catch (error) {
    if (!silent) {
      ElMessage.error(error instanceof ApiError ? error.message : '加载知识库失败')
    }
  } finally {
    if (!silent) loading.value = false
  }
}

function searchBases() {
  basesPage.value = 1
  loadBases()
}

function changeBasesPage(page: number) {
  basesPage.value = page
  selectedId.value = null
  loadBases()
}

async function loadModels() {
  try {
    embeddingModels.value = await modelApi.options('EMBEDDING')
  } catch {
    embeddingModels.value = []
  }
}

async function selectBase(id: number) {
  if (selectedId.value === id && docs.value.length) return
  selectedId.value = id
  docs.value = []
  docsTotal.value = 0
  docsPage.value = 1
  docKeyword.value = ''
  searchResults.value = []
  searched.value = false
  activeTab.value = 'docs'
  stopPolling()
  await loadDocs()
}

async function loadDocs(silent = false) {
  if (!selectedId.value) return
  if (!silent) docsLoading.value = true
  try {
    const page = await knowledgeApi.docs(
      selectedId.value,
      docKeyword.value.trim() || undefined,
      docsPage.value,
      docsPageSize,
    )
    docs.value = page.list
    docsTotal.value = page.total
    if (page.list.length === 0 && page.total > 0 && docsPage.value > 1) {
      docsPage.value = Math.max(1, Math.ceil(page.total / docsPageSize))
      await loadDocs(silent)
      return
    }
    ensurePolling()
  } catch (error) {
    if (!silent) {
      ElMessage.error(error instanceof ApiError ? error.message : '加载文档失败')
    }
  } finally {
    if (!silent) docsLoading.value = false
  }
}

function searchDocs() {
  docsPage.value = 1
  loadDocs()
}

function changeDocsPage(page: number) {
  docsPage.value = page
  loadDocs()
}

function openCreateBase() {
  editingBaseId.value = null
  Object.assign(baseForm, {
    name: '',
    description: '',
    embeddingModelId: embeddingModels.value[0]?.id,
    chunkSize: 800,
    chunkOverlap: 100,
    topK: 4,
    status: 0,
  })
  baseDialogVisible.value = true
}

function openEditBase() {
  const base = selectedBase.value
  if (!base) return
  editingBaseId.value = base.id
  Object.assign(baseForm, {
    name: base.name,
    description: base.description || '',
    embeddingModelId: base.embeddingModelId,
    chunkSize: base.chunkSize,
    chunkOverlap: base.chunkOverlap,
    topK: base.topK,
    status: base.status,
  })
  baseDialogVisible.value = true
}

async function submitBase() {
  const valid = await baseFormRef.value?.validate().catch(() => false)
  if (!valid || !baseForm.embeddingModelId) return
  if (baseForm.chunkOverlap >= baseForm.chunkSize) {
    ElMessage.warning('切片重叠必须小于切片大小')
    return
  }
  const payload: KnowledgeBaseSaveRequest = {
    name: baseForm.name.trim(),
    description: baseForm.description.trim(),
    embeddingModelId: baseForm.embeddingModelId,
    chunkSize: baseForm.chunkSize,
    chunkOverlap: baseForm.chunkOverlap,
    topK: baseForm.topK,
    status: baseForm.status,
  }
  baseSaving.value = true
  try {
    if (editingBaseId.value) {
      await knowledgeApi.update(editingBaseId.value, payload)
      ElMessage.success('知识库已更新')
      baseDialogVisible.value = false
      await loadBases()
    } else {
      const created = await knowledgeApi.create(payload)
      ElMessage.success('知识库已创建')
      baseDialogVisible.value = false
      basesPage.value = 1
      await loadBases()
      await selectBase(created.id)
    }
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '保存知识库失败')
  } finally {
    baseSaving.value = false
  }
}

async function removeBase() {
  const base = selectedBase.value
  if (!base) return
  try {
    await ElMessageBox.confirm(
      `删除知识库「${base.name}」将同时删除文档与向量索引，确认继续？`,
      '删除知识库',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await knowledgeApi.remove(base.id)
    ElMessage.success('知识库已删除')
    selectedId.value = null
    docs.value = []
    stopPolling()
    await loadBases()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

function openDocDialog() {
  docForm.name = ''
  docForm.content = ''
  docDialogVisible.value = true
}

async function submitDoc() {
  if (!selectedId.value) return
  if (!docForm.name.trim() || !docForm.content.trim()) {
    ElMessage.warning('请填写文档名称与内容')
    return
  }
  docSaving.value = true
  try {
    await knowledgeApi.createTextDoc(selectedId.value, {
      name: docForm.name.trim(),
      content: docForm.content,
    })
    ElMessage.success('文档已创建，正在建立索引')
    docDialogVisible.value = false
    docsPage.value = 1
    await loadDocs()
    await loadBases(true)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '创建文档失败')
  } finally {
    docSaving.value = false
  }
}

async function handleUpload(options: UploadRequestOptions) {
  if (!selectedId.value) return
  const file: File = options.file
  const lower = file.name.toLowerCase()
  const allowed = ['.txt', '.md', '.markdown', '.pdf']
  if (!allowed.some((suffix) => lower.endsWith(suffix))) {
    ElMessage.warning('仅支持 TXT、Markdown 与 PDF 文件')
    return
  }
  if (file.size > 20 * 1024 * 1024) {
    ElMessage.warning('文件不能超过 20MB')
    return
  }
  uploading.value = true
  try {
    await knowledgeApi.uploadDoc(selectedId.value, file)
    ElMessage.success('文件已上传，正在建立索引')
    docsPage.value = 1
    await loadDocs()
    await loadBases(true)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '上传失败')
  } finally {
    uploading.value = false
  }
}

async function reindex(doc: KnowledgeDocView) {
  if (!selectedId.value) return
  try {
    await knowledgeApi.reindex(selectedId.value, doc.id)
    ElMessage.success('已重新提交索引任务')
    await loadDocs()
    await loadBases(true)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '重建索引失败')
  }
}

async function removeDoc(doc: KnowledgeDocView) {
  if (!selectedId.value) return
  try {
    await ElMessageBox.confirm(`确认删除文档「${doc.name}」及其切片？`, '删除文档', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await knowledgeApi.removeDoc(selectedId.value, doc.id)
    ElMessage.success('文档已删除')
    await loadDocs()
    await loadBases(true)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

const aclRoleRank: Record<string, number> = {
  VIEWER: 1,
  AUDITOR: 1,
  EDITOR: 2,
  ADMIN: 3,
  OWNER: 4,
}

const aclOptions = [
  { value: 'ALL', label: '全部成员（含浏览者）' },
  { value: 'VIEWER', label: '浏览者及以上' },
  { value: 'EDITOR', label: '编辑者及以上' },
  { value: 'ADMIN', label: '管理员及以上' },
  { value: 'OWNER', label: '仅空间所有者' },
]

function aclRoleOf(doc: KnowledgeDocView): string {
  const roles = doc.allowedRoles || []
  if (!roles.length) return 'ALL'
  const minRank = Math.min(...roles.map((role) => aclRoleRank[role] ?? 1))
  if (minRank >= 4) return 'OWNER'
  if (minRank === 3) return 'ADMIN'
  if (minRank === 2) return 'EDITOR'
  return 'VIEWER'
}

function aclLabel(doc: KnowledgeDocView): string {
  const role = aclRoleOf(doc)
  if (role === 'ALL') return '全部成员'
  return aclOptions.find((option) => option.value === role)?.label || '全部成员'
}

function openAcl(doc: KnowledgeDocView) {
  aclDoc.value = doc
  aclMinRole.value = aclRoleOf(doc)
  aclDialogVisible.value = true
}

async function submitAcl() {
  if (!selectedId.value || !aclDoc.value) return
  aclSaving.value = true
  try {
    const roles = aclMinRole.value === 'ALL' ? [] : [aclMinRole.value]
    const updated = await knowledgeApi.updateDocAcl(
      selectedId.value,
      aclDoc.value.id,
      roles,
    )
    const index = docs.value.findIndex((item) => item.id === updated.id)
    if (index >= 0) docs.value[index] = updated
    ElMessage.success('文档访问范围已更新')
    aclDialogVisible.value = false
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '更新访问范围失败')
  } finally {
    aclSaving.value = false
  }
}

async function openChunks(doc: KnowledgeDocView) {
  if (!selectedId.value) return
  activeDoc.value = doc
  chunkDrawerVisible.value = true
  chunkLoading.value = true
  chunks.value = []
  chunkTotal.value = 0
  chunkPage.value = 1
  docContent.value = ''
  try {
    const [detail] = await Promise.all([
      knowledgeApi.docDetail(selectedId.value, doc.id),
      fetchChunks(1),
    ])
    docContent.value = detail.content || ''
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载切片失败')
  } finally {
    chunkLoading.value = false
  }
}

async function fetchChunks(page: number) {
  if (!selectedId.value || !activeDoc.value) return
  try {
    const data = await knowledgeApi.chunks(
      selectedId.value,
      activeDoc.value.id,
      page,
      chunkSize,
    )
    chunks.value = data.list
    chunkTotal.value = data.total
    chunkPage.value = data.page
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载切片失败')
  }
}

async function runSearch() {
  if (!selectedId.value) return
  if (!searchQuery.value.trim()) {
    ElMessage.warning('请输入检索内容')
    return
  }
  searchLoading.value = true
  searched.value = true
  try {
    searchResults.value = await knowledgeApi.search(
      selectedId.value,
      searchQuery.value.trim(),
      searchTopK.value,
    )
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '检索失败')
  } finally {
    searchLoading.value = false
  }
}

function sourceLabel(sourceType: string): string {
  if (sourceType === 'PDF') return 'PDF'
  if (sourceType === 'TEXT') return '文本'
  return sourceType || '文件'
}

onMounted(async () => {
  await Promise.all([loadBases(), loadModels()])
  if (route.query.create === '1' && auth.canEditSpace) {
    openCreateBase()
  }
  if (route.query.id) {
    const id = Number(route.query.id)
    if (bases.value.some((base) => base.id === id)) {
      await selectBase(id)
    }
  }
})

onBeforeUnmount(stopPolling)
</script>

<template>
  <div class="page knowledge-page">
    <PageHeader
      title="知识库"
      description="上传文档并自动切片、向量化。智能体对话时会混合检索多个知识库，并在回答中标注引用片段。"
      eyebrow="Knowledge"
    >
      <template #actions>
        <el-button v-if="auth.canEditSpace" type="primary" @click="openCreateBase">
          <el-icon><Plus /></el-icon>
          新建知识库
        </el-button>
      </template>
    </PageHeader>

    <div class="kb-layout">
      <aside class="panel kb-side">
        <div class="side-search">
          <el-input
            v-model="baseKeyword"
            placeholder="搜索知识库"
            clearable
            size="small"
            @keyup.enter="searchBases"
            @clear="searchBases"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
        </div>
        <div v-loading="loading" class="kb-list scroll-thin">
          <button
            v-for="base in bases"
            :key="base.id"
            type="button"
            class="kb-item"
            :class="{ active: base.id === selectedId }"
            @click="selectBase(base.id)"
          >
            <span class="kb-icon">
              <el-icon><Collection /></el-icon>
            </span>
            <span class="kb-copy">
              <span class="kb-name">{{ base.name }}</span>
              <span class="kb-meta">
                {{ base.docCount }} 篇 · {{ formatNumber(base.chunkCount) }} 片段
              </span>
              <span class="kb-model">{{ base.embeddingModelName }} · {{ base.embeddingDim }}d</span>
            </span>
          </button>
          <div v-if="!loading && !bases.length" class="kb-empty">
            <el-icon :size="20"><Collection /></el-icon>
            <span>还没有知识库</span>
          </div>
        </div>
        <div class="side-foot">
          <span>{{ basesTotal }} 个知识库</span>
          <span>本页 {{ formatNumber(bases.reduce((sum, base) => sum + base.chunkCount, 0)) }} 个片段</span>
        </div>
        <div v-if="basesTotal > basesPageSize" class="side-pagination">
          <el-pagination
            small
            background
            layout="prev, pager, next"
            :current-page="basesPage"
            :page-size="basesPageSize"
            :total="basesTotal"
            @current-change="changeBasesPage"
          />
        </div>
      </aside>

      <section class="panel kb-main">
        <template v-if="selectedBase">
          <div class="kb-head">
            <div class="kb-head-main">
              <div class="kb-mark"><el-icon><Collection /></el-icon></div>
              <div class="kb-title-wrap">
                <div class="kb-title-row">
                  <h2>{{ selectedBase.name }}</h2>
                  <span class="status-pill" :class="selectedBase.status === 0 ? 'on' : 'off'">
                    {{ selectedBase.status === 0 ? '启用' : '停用' }}
                  </span>
                </div>
                <p>{{ selectedBase.description || '未填写描述' }}</p>
              </div>
            </div>
            <div v-if="auth.canEditSpace" class="kb-head-actions">
              <el-button size="small" @click="openEditBase">
                <el-icon><EditPen /></el-icon>
                编辑
              </el-button>
              <el-button size="small" type="danger" plain @click="removeBase">
                <el-icon><Delete /></el-icon>
                删除
              </el-button>
            </div>
          </div>

          <div class="kb-config">
            <div class="config-item">
              <span>嵌入模型</span>
              <strong>{{ selectedBase.embeddingModelName }}</strong>
            </div>
            <div class="config-item">
              <span>向量集合</span>
              <strong class="mono">{{ selectedBase.vectorCollection }}</strong>
            </div>
            <div class="config-item">
              <span>切片 / 重叠</span>
              <strong>{{ selectedBase.chunkSize }} / {{ selectedBase.chunkOverlap }}</strong>
            </div>
            <div class="config-item">
              <span>默认 TopK</span>
              <strong>{{ selectedBase.topK }}</strong>
            </div>
            <div class="config-item">
              <span>文档 / 片段</span>
              <strong>{{ selectedBase.docCount }} / {{ formatNumber(selectedBase.chunkCount) }}</strong>
            </div>
          </div>

          <el-tabs v-model="activeTab" class="kb-tabs">
            <el-tab-pane label="文档" name="docs">
              <div class="doc-toolbar">
                <el-input
                  v-model="docKeyword"
                  placeholder="搜索文档名称"
                  clearable
                  size="small"
                  class="doc-keyword"
                  @keyup.enter="searchDocs"
                  @clear="searchDocs"
                >
                  <template #prefix><el-icon><Search /></el-icon></template>
                </el-input>
                <el-button size="small" @click="loadDocs">
                  <el-icon><Refresh /></el-icon>
                </el-button>
                <div class="toolbar-spacer"></div>
                <template v-if="auth.canEditSpace">
                  <el-upload
                    :show-file-list="false"
                    accept=".txt,.md,.markdown,.pdf"
                    :http-request="handleUpload"
                  >
                    <el-button size="small" :loading="uploading">
                      <el-icon><UploadFilled /></el-icon>
                      上传文件
                    </el-button>
                  </el-upload>
                  <el-button size="small" type="primary" @click="openDocDialog">
                    <el-icon><Plus /></el-icon>
                    新增文本
                  </el-button>
                </template>
              </div>

              <el-table v-loading="docsLoading" :data="docs" style="width: 100%">
                <el-table-column label="文档" min-width="240">
                  <template #default="{ row }">
                    <button type="button" class="doc-cell" @click="openChunks(row)">
                      <span class="doc-icon"><el-icon><Document /></el-icon></span>
                      <span class="doc-copy">
                        <span class="doc-name">{{ row.name }}</span>
                        <span class="doc-sub">{{ sourceLabel(row.sourceType) }} · {{ formatNumber(row.charCount) }} 字符</span>
                      </span>
                    </button>
                  </template>
                </el-table-column>
                <el-table-column label="状态" width="110">
                  <template #default="{ row }">
                    <el-tag
                      :type="documentStatus(row.status).type"
                      size="small"
                      effect="plain"
                      :class="{ 'pulse-tag': row.status === 'PENDING' || row.status === 'PROCESSING' }"
                    >
                      {{ documentStatus(row.status).text }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="片段" width="90" align="center">
                  <template #default="{ row }">{{ row.chunkCount }}</template>
                </el-table-column>
                <el-table-column label="可见范围" width="130">
                  <template #default="{ row }">
                    <span class="acl-cell">{{ aclLabel(row) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="更新时间" width="150">
                  <template #default="{ row }">
                    <span class="time-cell">{{ formatDate(row.updatedAt, 'MM-DD HH:mm') }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="230" fixed="right" align="right">
                  <template #default="{ row }">
                    <el-button link size="small" @click="openChunks(row)">
                      <el-icon><View /></el-icon>
                      切片
                    </el-button>
                    <template v-if="auth.canEditSpace">
                      <el-button link size="small" @click="openAcl(row)">
                        权限
                      </el-button>
                      <el-button link size="small" @click="reindex(row)">
                        <el-icon><Refresh /></el-icon>
                      </el-button>
                      <el-button link size="small" class="danger-link" @click="removeDoc(row)">
                        <el-icon><Delete /></el-icon>
                      </el-button>
                    </template>
                  </template>
                </el-table-column>
                <template #empty>
                  <EmptyState
                    title="知识库还是空的"
                    description="上传 TXT、Markdown 或 PDF 文件，或直接粘贴文本创建第一份知识。"
                  >
                    <template #icon><el-icon :size="22"><Document /></el-icon></template>
                  </EmptyState>
                </template>
              </el-table>

              <div v-if="docsTotal > docsPageSize" class="doc-pagination">
                <el-pagination
                  background
                  layout="total, prev, pager, next"
                  :total="docsTotal"
                  :page-size="docsPageSize"
                  :current-page="docsPage"
                  @current-change="changeDocsPage"
                />
              </div>
            </el-tab-pane>

            <el-tab-pane label="检索测试" name="search">
              <div class="search-panel">
                <el-input
                  v-model="searchQuery"
                  type="textarea"
                  :rows="3"
                  maxlength="1000"
                  placeholder="输入问题或关键词，查看向量检索命中的片段与相似度分数"
                />
                <div class="search-actions">
                  <div class="topk-field">
                    <span>召回数量</span>
                    <el-input-number v-model="searchTopK" :min="1" :max="20" size="small" />
                  </div>
                  <el-button type="primary" size="small" :loading="searchLoading" @click="runSearch">
                    <el-icon><Search /></el-icon>
                    检索
                  </el-button>
                </div>
              </div>

              <div v-loading="searchLoading" class="search-results">
                <div v-if="!searched" class="search-empty">
                  输入问题测试当前知识库的检索效果，结果按相似度排序。
                </div>
                <div v-else-if="!searchResults.length" class="search-empty">
                  没有命中片段，尝试换一种表述或调整 TopK。
                </div>
                <article
                  v-for="result in searchResults"
                  :key="result.chunkId"
                  class="result-card"
                >
                  <div class="result-head">
                    <div class="result-doc">
                      <el-icon><Document /></el-icon>
                      {{ result.docName }}
                      <span class="result-chunk">#{{ result.chunkIndex + 1 }}</span>
                    </div>
                    <span class="result-score">{{ result.score.toFixed(3) }}</span>
                  </div>
                  <div class="score-track">
                    <span :style="{ width: `${Math.min(100, Math.max(3, result.score * 100))}%` }"></span>
                  </div>
                  <p>{{ result.content }}</p>
                </article>
              </div>
            </el-tab-pane>
          </el-tabs>
        </template>

        <div v-else class="kb-placeholder">
          <EmptyState
            title="选择一个知识库"
            description="在左侧选择知识库管理文档与检索，或创建一个新的知识库开始构建 RAG。"
          >
            <template #icon><el-icon :size="22"><Collection /></el-icon></template>
            <template #action>
              <el-button v-if="auth.canEditSpace" type="primary" size="small" @click="openCreateBase">
                <el-icon><Plus /></el-icon>
                新建知识库
              </el-button>
            </template>
          </EmptyState>
        </div>
      </section>
    </div>

    <el-dialog
      v-model="baseDialogVisible"
      :title="editingBaseId ? '编辑知识库' : '新建知识库'"
      width="520px"
    >
      <el-form ref="baseFormRef" :model="baseForm" :rules="baseRules" label-position="top">
        <el-form-item label="名称" prop="name">
          <el-input v-model="baseForm.name" maxlength="100" placeholder="例如：产品手册知识库" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="baseForm.description"
            type="textarea"
            :rows="2"
            maxlength="500"
            placeholder="说明这个知识库包含哪些资料"
          />
        </el-form-item>
        <el-form-item label="嵌入模型" prop="embeddingModelId">
          <el-select
            v-model="baseForm.embeddingModelId"
            filterable
            placeholder="选择嵌入模型"
            style="width: 100%"
          >
            <el-option
              v-for="model in embeddingModels"
              :key="model.id"
              :label="`${model.name} · ${model.dimension}d`"
              :value="model.id"
            >
              <div class="model-option">
                <span>{{ model.name }}</span>
                <span class="model-id">{{ model.dimension }}d · {{ model.supplierName }}</span>
              </div>
            </el-option>
          </el-select>
          <div v-if="!embeddingModels.length" class="field-hint warn">
            还没有可用的嵌入模型，请先到「供应商与模型」接入。
          </div>
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="切片大小">
            <el-input-number v-model="baseForm.chunkSize" :min="100" :max="6000" :step="100" style="width: 100%" />
          </el-form-item>
          <el-form-item label="切片重叠">
            <el-input-number v-model="baseForm.chunkOverlap" :min="0" :max="3000" :step="50" style="width: 100%" />
          </el-form-item>
        </div>
        <div class="form-grid">
          <el-form-item label="默认 TopK">
            <el-input-number v-model="baseForm.topK" :min="1" :max="20" style="width: 100%" />
          </el-form-item>
          <el-form-item label="状态">
            <el-switch
              v-model="baseForm.status"
              :active-value="0"
              :inactive-value="1"
              active-text="启用"
              inactive-text="停用"
            />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="baseDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="baseSaving" @click="submitBase">
          {{ editingBaseId ? '保存修改' : '创建知识库' }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="docDialogVisible" title="新增文本文档" width="600px">
      <el-form label-position="top">
        <el-form-item label="文档名称">
          <el-input v-model="docForm.name" maxlength="200" placeholder="例如：产品常见问题" />
        </el-form-item>
        <el-form-item label="内容">
          <el-input
            v-model="docForm.content"
            type="textarea"
            :rows="12"
            maxlength="2000000"
            placeholder="粘贴需要索引的文本内容"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="docDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="docSaving" @click="submitDoc">创建并索引</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="aclDialogVisible" title="文档访问范围" width="460px">
      <div class="acl-doc-name">{{ aclDoc?.name }}</div>
      <el-radio-group v-model="aclMinRole" class="acl-options">
        <el-radio v-for="option in aclOptions" :key="option.value" :value="option.value">
          {{ option.label }}
        </el-radio>
      </el-radio-group>
      <p class="acl-hint">
        访问范围同时作用于智能体检索：角色不足的成员不会在对话或检索测试中命中该文档。
      </p>
      <template #footer>
        <el-button @click="aclDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="aclSaving" @click="submitAcl">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="chunkDrawerVisible" size="680px" destroy-on-close>
      <template #header>
        <div class="chunk-head">
          <div class="chunk-title">{{ activeDoc?.name || '文档切片' }}</div>
          <div class="chunk-meta">
            {{ activeDoc ? sourceLabel(activeDoc.sourceType) : '' }}
            <span class="dot-sep">·</span>
            {{ chunkTotal }} 个切片
            <span class="dot-sep">·</span>
            {{ formatNumber(activeDoc?.charCount || 0) }} 字符
          </div>
        </div>
      </template>

      <div v-loading="chunkLoading" class="chunk-body">
        <details v-if="docContent" class="doc-preview">
          <summary>查看文档原文</summary>
          <pre>{{ truncate(docContent, 12000) }}</pre>
        </details>
        <div v-if="!chunks.length && !chunkLoading" class="chunk-empty">
          暂无切片数据，请确认文档索引状态。
        </div>
        <article v-for="chunk in chunks" :key="chunk.id" class="chunk-card">
          <div class="chunk-card-head">
            <span>CHUNK #{{ chunk.chunkIndex + 1 }}</span>
            <span>{{ chunk.charCount }} 字符</span>
          </div>
          <p>{{ chunk.content }}</p>
        </article>
        <div v-if="chunkTotal > chunkSize" class="chunk-pagination">
          <el-pagination
            background
            layout="prev, pager, next"
            :total="chunkTotal"
            :page-size="chunkSize"
            :current-page="chunkPage"
            @current-change="fetchChunks"
          />
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.kb-layout {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
  gap: 14px;
  align-items: start;
}

.kb-side {
  position: sticky;
  top: 86px;
  display: flex;
  flex-direction: column;
  height: calc(100vh - 206px);
  min-height: 480px;
  overflow: hidden;
}

.side-search {
  padding: 14px 14px 10px;
}

.kb-list {
  flex: 1;
  overflow-y: auto;
  padding: 0 10px 10px;
}

.kb-item {
  display: flex;
  gap: 10px;
  width: 100%;
  padding: 11px 10px;
  margin-bottom: 3px;
  border-radius: 11px;
  border: 1px solid transparent;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
  transition: all 0.16s ease;
}

.kb-item:hover {
  background: rgba(255, 255, 255, 0.035);
}

.kb-item.active {
  border-color: rgba(124, 92, 255, 0.26);
  background: linear-gradient(100deg, rgba(124, 92, 255, 0.17), rgba(34, 211, 238, 0.03));
}

.kb-icon {
  display: grid;
  place-items: center;
  flex: none;
  width: 30px;
  height: 30px;
  border-radius: 9px;
  color: #c4b5fd;
  background: rgba(124, 92, 255, 0.1);
  border: 1px solid rgba(124, 92, 255, 0.16);
  font-size: 14px;
}

.kb-copy {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.kb-name {
  font-size: 12px;
  font-weight: 550;
  color: var(--king-text-soft);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kb-item.active .kb-name {
  color: var(--king-text);
}

.kb-meta {
  font-size: 9.5px;
  color: #6c7898;
}

.kb-model {
  font-family: var(--king-mono);
  font-size: 8.5px;
  color: #525d7a;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kb-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 40px 10px;
  font-size: 11px;
  color: var(--king-muted);
}

.side-foot {
  display: flex;
  justify-content: space-between;
  padding: 11px 14px;
  border-top: 1px solid var(--king-line);
  font-size: 9.5px;
  color: #596482;
}

.side-pagination {
  display: flex;
  justify-content: center;
  padding: 6px 0 8px;
}

.kb-main {
  min-height: calc(100vh - 206px);
  padding: 22px 24px;
}

.kb-head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
  flex-wrap: wrap;
}

.kb-head-main {
  display: flex;
  gap: 13px;
  min-width: 0;
}

.kb-mark {
  display: grid;
  place-items: center;
  flex: none;
  width: 42px;
  height: 42px;
  border-radius: 12px;
  font-size: 18px;
  color: #d8d0ff;
  background: linear-gradient(135deg, rgba(124, 92, 255, 0.25), rgba(34, 211, 238, 0.1));
  border: 1px solid rgba(124, 92, 255, 0.24);
}

.kb-title-wrap {
  min-width: 0;
}

.kb-title-row {
  display: flex;
  align-items: center;
  gap: 9px;
  flex-wrap: wrap;
}

.kb-title-row h2 {
  margin: 0;
  font-size: 19px;
  font-weight: 660;
  letter-spacing: -0.02em;
}

.status-pill {
  padding: 2px 8px;
  border-radius: 5px;
  font-size: 8.5px;
}

.status-pill.on {
  color: #8bf0c8;
  background: rgba(52, 211, 153, 0.09);
}

.status-pill.off {
  color: #9aa6c6;
  background: rgba(255, 255, 255, 0.06);
}

.kb-title-wrap p {
  margin: 5px 0 0;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--king-muted);
}

.kb-head-actions {
  display: flex;
  gap: 6px;
}

.kb-config {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 1px;
  margin: 18px 0 6px;
  border-radius: 12px;
  overflow: hidden;
  border: 1px solid var(--king-line);
  background: var(--king-line);
}

.config-item {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 11px 13px;
  background: rgba(10, 15, 30, 0.92);
}

.config-item span {
  font-size: 9px;
  color: #596482;
}

.config-item strong {
  font-size: 11px;
  font-weight: 550;
  color: var(--king-text-soft);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.config-item strong.mono {
  font-family: var(--king-mono);
  font-size: 9.5px;
}

.kb-tabs {
  margin-top: 8px;
}

.kb-tabs :deep(.el-tabs__header) {
  margin-bottom: 14px;
}

.doc-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}

.doc-keyword {
  width: 220px;
}

.toolbar-spacer {
  flex: 1;
}

.doc-cell {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 2px 0;
  border: none;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
  max-width: 100%;
}

.doc-icon {
  display: grid;
  place-items: center;
  flex: none;
  width: 29px;
  height: 29px;
  border-radius: 8px;
  color: #8ff1ff;
  background: rgba(34, 211, 238, 0.06);
  border: 1px solid rgba(34, 211, 238, 0.12);
  font-size: 13px;
}

.doc-copy {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.doc-name {
  font-size: 11.5px;
  color: var(--king-text-soft);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.doc-cell:hover .doc-name {
  color: var(--king-text);
}

.doc-sub {
  font-size: 9px;
  color: #525d7a;
}

.pulse-tag {
  animation: tag-pulse 1.6s ease-in-out infinite;
}

@keyframes tag-pulse {
  50% {
    opacity: 0.6;
  }
}

.time-cell {
  font-size: 10px;
  color: #6c7898;
}

.acl-cell {
  font-size: 10px;
  color: #9aa6c6;
}

.doc-pagination {
  display: flex;
  justify-content: flex-end;
  padding: 14px 0 4px;
}

.acl-doc-name {
  margin-bottom: 14px;
  font-size: 13px;
  font-weight: 560;
  color: var(--king-text-soft);
}

.acl-options {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 10px;
}

.acl-hint {
  margin: 16px 0 0;
  font-size: 11px;
  line-height: 1.65;
  color: var(--king-muted);
}

.danger-link {
  color: var(--king-red);
}

.kb-placeholder {
  display: grid;
  place-items: center;
  min-height: 420px;
}

.search-panel {
  padding: 14px;
  border-radius: 12px;
  border: 1px solid var(--king-line);
  background: rgba(255, 255, 255, 0.018);
}

.search-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 10px;
}

.topk-field {
  display: flex;
  align-items: center;
  gap: 9px;
  font-size: 10px;
  color: var(--king-muted);
}

.search-results {
  display: flex;
  flex-direction: column;
  gap: 9px;
  margin-top: 14px;
  min-height: 120px;
}

.search-empty {
  padding: 38px 10px;
  text-align: center;
  font-size: 11.5px;
  color: var(--king-muted);
}

.result-card {
  padding: 13px 15px;
  border-radius: 11px;
  border: 1px solid var(--king-line);
  background: rgba(255, 255, 255, 0.02);
}

.result-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
}

.result-doc {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: var(--king-text-soft);
}

.result-doc .el-icon {
  color: #8ff1ff;
}

.result-chunk {
  font-family: var(--king-mono);
  font-size: 9px;
  color: #596482;
}

.result-score {
  font-family: var(--king-mono);
  font-size: 11px;
  color: #8ff1ff;
}

.score-track {
  height: 3px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.055);
  overflow: hidden;
  margin-bottom: 9px;
}

.score-track span {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, #7c5cff, #22d3ee);
  border-radius: 999px;
}

.result-card p {
  margin: 0;
  font-size: 11.5px;
  line-height: 1.75;
  color: #8390af;
  white-space: pre-wrap;
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

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.field-hint.warn {
  color: var(--king-amber);
}

.chunk-head {
  min-width: 0;
}

.chunk-title {
  font-size: 15px;
  font-weight: 620;
  color: var(--king-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 520px;
}

.chunk-meta {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-top: 3px;
  font-size: 9.5px;
  color: var(--king-muted);
}

.dot-sep {
  opacity: 0.6;
}

.chunk-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 160px;
}

.doc-preview {
  border-radius: 10px;
  border: 1px solid var(--king-line);
  background: rgba(255, 255, 255, 0.02);
  overflow: hidden;
}

.doc-preview summary {
  padding: 10px 13px;
  font-size: 10.5px;
  color: var(--king-muted);
  cursor: pointer;
  list-style: none;
}

.doc-preview summary::-webkit-details-marker {
  display: none;
}

.doc-preview pre {
  max-height: 240px;
  margin: 0;
  padding: 0 13px 13px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: var(--king-mono);
  font-size: 10px;
  line-height: 1.7;
  color: #8390af;
}

.chunk-card {
  padding: 13px 15px;
  border-radius: 10px;
  border: 1px solid var(--king-line);
  background: rgba(255, 255, 255, 0.018);
}

.chunk-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  font-family: var(--king-mono);
  font-size: 9px;
  color: #596482;
}

.chunk-card p {
  margin: 0;
  font-size: 11.5px;
  line-height: 1.78;
  color: #8f9cb9;
  white-space: pre-wrap;
  word-break: break-word;
}

.chunk-empty {
  padding: 50px 0;
  text-align: center;
  font-size: 11.5px;
  color: var(--king-muted);
}

.chunk-pagination {
  display: flex;
  justify-content: flex-end;
  padding-top: 6px;
}

@media (max-width: 1080px) {
  .kb-layout {
    grid-template-columns: 260px minmax(0, 1fr);
  }

  .kb-config {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 860px) {
  .kb-layout {
    grid-template-columns: 1fr;
  }

  .kb-side {
    position: static;
    height: auto;
    min-height: 0;
  }

  .kb-list {
    display: flex;
    gap: 6px;
    overflow-x: auto;
    padding: 0 12px 12px;
  }

  .kb-item {
    flex: none;
    width: 240px;
    margin-bottom: 0;
  }

  .side-foot {
    display: none;
  }

  .kb-main {
    min-height: 420px;
    padding: 18px 16px;
  }

  .kb-config {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .doc-keyword {
    width: 100%;
  }

  .form-grid {
    grid-template-columns: 1fr;
    gap: 0;
  }
}
</style>

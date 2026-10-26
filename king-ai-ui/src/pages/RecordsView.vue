<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, EditPen, Memo, Search, View } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import ChatMessageItem from '@/components/chat/ChatMessageItem.vue'
import type { UiMessage } from '@/components/chat/ui'
import { agentApi, chatApi } from '@/api'
import { ApiError } from '@/api/http'
import type { AgentView, ChatMessageView, ChatSessionView } from '@/api/types'
import { formatDate, fromNow, sourceText, truncate } from '@/utils/format'

const route = useRoute()

const agents = ref<AgentView[]>([])
const sessions = ref<ChatSessionView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(15)
const agentId = ref<number | undefined>()
const sourceType = ref('')
const keyword = ref('')
const loading = ref(false)

const detailVisible = ref(false)
const detailLoading = ref(false)
const detailSession = ref<ChatSessionView | null>(null)
const detailMessages = ref<UiMessage[]>([])

const detailUserLabel = computed(() =>
  detailSession.value?.sourceType === 'PUBLIC' ? '访客' : '平台用户',
)

function toUi(message: ChatMessageView): UiMessage {
  return {
    ...message,
    key: `m-${message.id}`,
    content: message.content || '',
    reasoning: message.reasoning || '',
    toolTrace: Array.isArray(message.toolTrace)
      ? message.toolTrace.map((item) => ({ ...item, running: false }))
      : [],
    citations: Array.isArray(message.citations) ? message.citations : [],
    totalTokens: (message.promptTokens || 0) + (message.completionTokens || 0),
    streaming: false,
  }
}

async function loadAgents() {
  try {
    const result = await agentApi.page({ page: 1, size: 200 })
    agents.value = result.list
  } catch {
    /* filter is optional */
  }
}

async function loadSessions() {
  loading.value = true
  try {
    const data = await chatApi.sessions({
      agentId: agentId.value,
      sourceType: sourceType.value || undefined,
      keyword: keyword.value.trim() || undefined,
      page: page.value,
      size: size.value,
    })
    sessions.value = data.list
    total.value = data.total
    if (!data.list.length && page.value > 1) {
      page.value = 1
      await loadSessions()
    }
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载会话失败')
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  loadSessions()
}

function resetFilters() {
  agentId.value = undefined
  sourceType.value = ''
  keyword.value = ''
  page.value = 1
  loadSessions()
}

function onPageChange(value: number) {
  page.value = value
  loadSessions()
}

async function openDetail(session: ChatSessionView) {
  detailSession.value = session
  detailVisible.value = true
  detailLoading.value = true
  detailMessages.value = []
  try {
    const list = await chatApi.messages(session.id)
    detailMessages.value = list.map(toUi)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载消息失败')
  } finally {
    detailLoading.value = false
  }
}

async function openById(id: number) {
  const found = sessions.value.find((session) => session.id === id)
  await openDetail(
    found || {
      id,
      agentId: agentId.value || 0,
      agentName: '',
      sourceType: '',
      title: `会话 #${id}`,
      messageCount: 0,
      createdAt: new Date().toISOString(),
    },
  )
  if (!found) {
    // keep drawer title neutral while messages load
    detailSession.value = {
      ...detailSession.value!,
      title: `会话 #${id}`,
    }
  }
}

async function renameSession(session: ChatSessionView) {
  try {
    const { value } = await ElMessageBox.prompt('请输入新的会话标题', '重命名会话', {
      inputValue: session.title || '',
      inputValidator: (input) => {
        const text = (input || '').trim()
        if (!text) return '标题不能为空'
        if (text.length > 100) return '标题不能超过 100 个字符'
        return true
      },
      confirmButtonText: '保存',
      cancelButtonText: '取消',
    })
    const updated = await chatApi.rename(session.id, value.trim())
    const target = sessions.value.find((item) => item.id === updated.id)
    if (target) target.title = updated.title
    if (detailSession.value?.id === updated.id) detailSession.value.title = updated.title
    ElMessage.success('会话已重命名')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof ApiError ? error.message : '重命名失败')
  }
}

async function deleteSession(session: ChatSessionView) {
  try {
    await ElMessageBox.confirm('删除后会话与消息将无法恢复，确认继续？', '删除会话', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await chatApi.remove(session.id)
    if (detailSession.value?.id === session.id) {
      detailVisible.value = false
      detailSession.value = null
    }
    ElMessage.success('会话已删除')
    await loadSessions()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

onMounted(async () => {
  await loadAgents()
  await loadSessions()
  const queryId = Number(route.query.sessionId)
  if (Number.isFinite(queryId) && queryId > 0) {
    await openById(queryId)
  }
})
</script>

<template>
  <div class="page">
    <PageHeader
      title="对话记录"
      description="回溯后台调试与公开访客的全部会话，查看思考过程、MCP 工具调用与知识引用。"
      eyebrow="Conversations"
    >
      <template #actions>
        <el-button @click="loadSessions">刷新</el-button>
      </template>
    </PageHeader>

    <section class="panel filters">
      <el-select
        v-model="agentId"
        clearable
        filterable
        placeholder="全部智能体"
        class="filter-agent"
        @change="search"
      >
        <el-option v-for="agent in agents" :key="agent.id" :label="agent.name" :value="agent.id" />
      </el-select>
      <el-select v-model="sourceType" placeholder="全部来源" class="filter-source" @change="search">
        <el-option label="全部来源" value="" />
        <el-option label="后台调试" value="ADMIN" />
        <el-option label="公开访客" value="PUBLIC" />
      </el-select>
      <el-input
        v-model="keyword"
        class="filter-keyword"
        clearable
        placeholder="搜索会话标题或访客标识"
        @keyup.enter="search"
        @clear="search"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="resetFilters">重置</el-button>
      <div class="filter-count">共 {{ total }} 个会话</div>
    </section>

    <section class="panel table-panel">
      <el-table v-loading="loading" :data="sessions" style="width: 100%">
        <el-table-column label="会话" min-width="260">
          <template #default="{ row }">
            <button type="button" class="session-cell" @click="openDetail(row)">
              <span class="cell-avatar">{{ (row.agentName || 'K').slice(0, 1) }}</span>
              <span class="cell-main">
                <span class="cell-title">{{ row.title || '未命名对话' }}</span>
                <span class="cell-sub">#{{ row.id }} · {{ fromNow(row.lastMessageAt || row.createdAt) }}</span>
              </span>
            </button>
          </template>
        </el-table-column>
        <el-table-column label="智能体" min-width="150">
          <template #default="{ row }">
            <div class="agent-cell">
              <span>{{ row.agentName || '—' }}</span>
              <span class="agent-id">ID {{ row.agentId }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="110">
          <template #default="{ row }">
            <span class="source-tag" :class="row.sourceType === 'PUBLIC' ? 'public' : 'admin'">
              {{ sourceText(row.sourceType) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="访客" min-width="140">
          <template #default="{ row }">
            <span class="visitor mono" :title="row.visitorId">
              {{ row.sourceType === 'PUBLIC' ? truncate(row.visitorId, 18) : '平台用户' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="消息" width="80" align="center">
          <template #default="{ row }">{{ row.messageCount }}</template>
        </el-table-column>
        <el-table-column label="最后消息" width="150">
          <template #default="{ row }">
            <span class="time-cell">{{ formatDate(row.lastMessageAt || row.createdAt, 'MM-DD HH:mm') }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right" align="right">
          <template #default="{ row }">
            <el-button link size="small" @click="openDetail(row)">
              <el-icon><View /></el-icon>
              查看
            </el-button>
            <el-button link size="small" @click="renameSession(row)">
              <el-icon><EditPen /></el-icon>
            </el-button>
            <el-button link size="small" class="danger-link" @click="deleteSession(row)">
              <el-icon><Delete /></el-icon>
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <EmptyState
            :title="total === 0 ? '还没有对话记录' : '没有匹配的会话'"
            :description="total === 0 ? '对话测试与公开访客的会话都会显示在这里。' : '调整筛选条件后再试一次。'"
          >
            <template #icon><el-icon :size="22"><Memo /></el-icon></template>
          </EmptyState>
        </template>
      </el-table>

      <div v-if="total > size" class="pagination-row">
        <el-pagination
          background
          layout="total, prev, pager, next"
          :total="total"
          :page-size="size"
          :current-page="page"
          @current-change="onPageChange"
        />
      </div>
    </section>

    <el-drawer v-model="detailVisible" size="720px" destroy-on-close>
      <template #header>
        <div class="drawer-head">
          <div class="drawer-avatar">{{ (detailSession?.agentName || 'K').slice(0, 1) }}</div>
          <div class="drawer-copy">
            <div class="drawer-title">{{ detailSession?.title || '会话详情' }}</div>
            <div class="drawer-meta">
              <span>{{ detailSession?.agentName || '智能体' }}</span>
              <span class="dot-sep">·</span>
              <span>{{ sourceText(detailSession?.sourceType) }}</span>
              <span class="dot-sep">·</span>
              <span>{{ detailMessages.length }} 条消息</span>
              <span v-if="detailSession" class="dot-sep">·</span>
              <span v-if="detailSession" class="mono">#{{ detailSession.id }}</span>
            </div>
          </div>
        </div>
      </template>

      <div v-loading="detailLoading" class="record-messages scroll-thin">
        <div v-if="!detailLoading && !detailMessages.length" class="record-empty">
          这次会话还没有消息。
        </div>
        <ChatMessageItem
          v-for="message in detailMessages"
          :key="message.key"
          :message="message"
          :agent-name="detailSession?.agentName || 'King AI'"
          :user-label="detailUserLabel"
        />
      </div>

      <template #footer>
        <div class="drawer-footer">
          <el-button v-if="detailSession" @click="renameSession(detailSession)">
            <el-icon><EditPen /></el-icon>
            重命名
          </el-button>
          <el-button
            v-if="detailSession"
            type="danger"
            plain
            @click="deleteSession(detailSession)"
          >
            <el-icon><Delete /></el-icon>
            删除会话
          </el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.filters {
  display: flex;
  align-items: center;
  gap: 9px;
  flex-wrap: wrap;
  padding: 14px 16px;
}

.filter-agent {
  width: 190px;
}

.filter-source {
  width: 140px;
}

.filter-keyword {
  width: min(300px, 100%);
}

.filter-count {
  margin-left: auto;
  font-size: 10.5px;
  color: var(--king-muted);
}

.table-panel {
  padding: 8px 16px 16px;
}

.session-cell {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 2px 0;
  border: none;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
  max-width: 100%;
}

.cell-avatar {
  display: grid;
  place-items: center;
  flex: none;
  width: 30px;
  height: 30px;
  border-radius: 9px;
  font-size: 11px;
  font-weight: 650;
  color: #d8d0ff;
  background: rgba(124, 92, 255, 0.12);
  border: 1px solid rgba(124, 92, 255, 0.18);
}

.cell-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.cell-title {
  font-size: 12px;
  font-weight: 520;
  color: var(--king-text-soft);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-cell:hover .cell-title {
  color: var(--king-text);
}

.cell-sub {
  font-family: var(--king-mono);
  font-size: 9px;
  color: #525d7a;
}

.agent-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 11.5px;
  color: var(--king-text-soft);
}

.agent-id {
  font-size: 9px;
  color: #525d7a;
}

.source-tag {
  display: inline-flex;
  align-items: center;
  height: 21px;
  padding: 0 8px;
  border-radius: 5px;
  font-size: 9.5px;
}

.source-tag.admin {
  color: #c4b5fd;
  background: rgba(124, 92, 255, 0.1);
}

.source-tag.public {
  color: #8ff1ff;
  background: rgba(34, 211, 238, 0.08);
}

.visitor {
  font-size: 9.5px;
  color: #7f8bab;
}

.time-cell {
  font-size: 10px;
  color: #6c7898;
}

.danger-link {
  color: var(--king-red);
}

.pagination-row {
  display: flex;
  justify-content: flex-end;
  padding-top: 14px;
}

.drawer-head {
  display: flex;
  align-items: center;
  gap: 11px;
  min-width: 0;
}

.drawer-avatar {
  display: grid;
  place-items: center;
  flex: none;
  width: 36px;
  height: 36px;
  border-radius: 10px;
  font-size: 13px;
  font-weight: 680;
  color: #fff;
  background: linear-gradient(135deg, #8b5cf6, #22d3ee);
}

.drawer-copy {
  min-width: 0;
}

.drawer-title {
  font-size: 15px;
  font-weight: 620;
  color: var(--king-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 520px;
}

.drawer-meta {
  display: flex;
  gap: 5px;
  align-items: center;
  flex-wrap: wrap;
  margin-top: 3px;
  font-size: 9.5px;
  color: var(--king-muted);
}

.dot-sep {
  opacity: 0.6;
}

.record-messages {
  display: flex;
  flex-direction: column;
  gap: 22px;
  min-height: 200px;
  max-height: calc(100vh - 210px);
  overflow-y: auto;
  padding: 4px 2px 12px;
}

.record-empty {
  padding: 60px 0;
  text-align: center;
  font-size: 12px;
  color: var(--king-muted);
}

.drawer-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

@media (max-width: 720px) {
  .filter-agent,
  .filter-source,
  .filter-keyword {
    width: 100%;
  }

  .filter-count {
    margin-left: 0;
  }

  .records-table {
    overflow-x: auto;
  }

  .record-messages {
    gap: 18px;
  }
}
</style>

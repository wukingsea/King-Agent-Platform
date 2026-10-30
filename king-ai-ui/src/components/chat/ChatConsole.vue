<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Delete,
  EditPen,
  Memo,
  Plus,
  Promotion,
  VideoPause,
} from '@element-plus/icons-vue'
import ChatMessageItem from './ChatMessageItem.vue'
import type { UiMessage } from './ui'
import { ApiError } from '@/api/http'
import { chatApi, publicChatApi } from '@/api'
import { streamChat } from '@/api/sse'
import type {
  ChatMessageView,
  ChatSessionView,
  CitationItem,
  ToolApprovalEvent,
  ToolTraceItem,
} from '@/api/types'
import { fromNow } from '@/utils/format'
import { useAuthStore } from '@/stores/auth'

const props = withDefaults(
  defineProps<{
    mode?: 'admin' | 'public'
    agentId?: number
    publicId?: string
    agentName?: string
    agentIntro?: string
    variant?: 'console' | 'public'
  }>(),
  {
    mode: 'admin',
    agentId: undefined,
    publicId: undefined,
    agentName: 'King 助手',
    variant: 'console',
    agentIntro: '',
  },
)

const auth = useAuthStore()

const sessions = ref<ChatSessionView[]>([])
const sessionsLoading = ref(false)
const activeSessionId = ref<number | null>(null)
const messages = ref<UiMessage[]>([])
const messagesLoading = ref(false)
const streaming = ref(false)
const stopping = ref(false)
const requestId = ref<string | null>(null)
const draft = ref('')
const messagesEl = ref<HTMLElement | null>(null)
const railOpen = ref(false)
const stickToBottom = ref(true)
const pendingApproval = ref<ToolApprovalEvent | null>(null)
const approvalSubmitting = ref(false)

let sessionBatch = 0
let abortController: AbortController | null = null
let scrollScheduled = false

const isAdminMode = computed(() => props.mode === 'admin')
const currentSession = computed(
  () => sessions.value.find((session) => session.id === activeSessionId.value) || null,
)
const titleText = computed(() => currentSession.value?.title || '新对话')
const canSend = computed(
  () =>
    draft.value.trim().length > 0 &&
    !streaming.value &&
    (isAdminMode.value ? Boolean(props.agentId) : Boolean(props.publicId)),
)
const greeting = computed(() => {
  const name = props.agentName || '智能体'
  return [
    `${name} 能做什么？`,
    '先介绍一下你自己',
    '你可以调用哪些工具？',
  ]
})
const charCount = computed(() => draft.value.length)

function scrollToBottom(force = false) {
  if (!force && !stickToBottom.value) return
  if (scrollScheduled) return
  scrollScheduled = true
  requestAnimationFrame(() => {
    scrollScheduled = false
    const element = messagesEl.value
    if (!element) return
    element.scrollTop = element.scrollHeight
  })
}

function onMessagesScroll() {
  const element = messagesEl.value
  if (!element) return
  stickToBottom.value =
    element.scrollHeight - element.scrollTop - element.clientHeight < 160
}

function normalize(message: ChatMessageView): UiMessage {
  const trace: ToolTraceItem[] = Array.isArray(message.toolTrace)
    ? message.toolTrace.map((item: ToolTraceItem) => ({ ...item, running: false }))
    : []
  const citations: CitationItem[] = Array.isArray(message.citations) ? message.citations : []
  return {
    ...message,
    key: `m-${message.id ?? Math.random().toString(36).slice(2)}`,
    content: message.content || '',
    reasoning: message.reasoning || '',
    toolTrace: trace,
    citations,
    totalTokens: (message.promptTokens || 0) + (message.completionTokens || 0),
    streaming: false,
  }
}

function resetConversation() {
  if (abortController) {
    abortController.abort()
    abortController = null
  }
  streaming.value = false
  stopping.value = false
  requestId.value = null
  pendingApproval.value = null
  activeSessionId.value = null
  messages.value = []
  railOpen.value = false
}

async function loadSessions() {
  if (isAdminMode.value && !props.agentId) return
  if (!isAdminMode.value && !props.publicId) return
  const batch = ++sessionBatch
  sessionsLoading.value = true
  try {
    let list: ChatSessionView[] = []
    if (isAdminMode.value) {
      const page = await chatApi.sessions({
        agentId: props.agentId,
        page: 1,
        size: 50,
      })
      list = page.list
    } else {
      list = await publicChatApi.sessions(props.publicId!)
    }
    if (batch !== sessionBatch) return
    sessions.value = list
    if (
      activeSessionId.value &&
      !list.some((session) => session.id === activeSessionId.value)
    ) {
      activeSessionId.value = null
      messages.value = []
    }
  } catch (error) {
    if (batch === sessionBatch && error instanceof ApiError && error.code !== 401) {
      ElMessage.error(error.message)
    }
  } finally {
    if (batch === sessionBatch) sessionsLoading.value = false
  }
}

async function openSession(session: ChatSessionView) {
  if (streaming.value) {
    ElMessage.warning('请先停止当前生成')
    return
  }
  if (activeSessionId.value === session.id) {
    railOpen.value = false
    return
  }
  activeSessionId.value = session.id
  railOpen.value = false
  messagesLoading.value = true
  stickToBottom.value = true
  try {
    const list = isAdminMode.value
      ? await chatApi.messages(session.id)
      : await publicChatApi.messages(props.publicId!, session.id)
    messages.value = list.map(normalize)
    await nextTick()
    scrollToBottom(true)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载会话失败')
  } finally {
    messagesLoading.value = false
  }
}

function newChat() {
  if (streaming.value) {
    handleStop()
    return
  }
  activeSessionId.value = null
  messages.value = []
  draft.value = ''
  railOpen.value = false
}

async function renameSession() {
  if (!activeSessionId.value) return
  try {
    const { value } = await ElMessageBox.prompt('请输入新的会话标题', '重命名会话', {
      inputValue: currentSession.value?.title || '',
      inputValidator: (input) => {
        const text = (input || '').trim()
        if (!text) return '标题不能为空'
        if (text.length > 100) return '标题不能超过 100 个字符'
        return true
      },
      confirmButtonText: '保存',
      cancelButtonText: '取消',
    })
    const title = value.trim()
    const updated = await chatApi.rename(activeSessionId.value, title)
    const target = sessions.value.find((session) => session.id === updated.id)
    if (target) target.title = updated.title
    ElMessage.success('会话已重命名')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof ApiError ? error.message : '重命名失败')
  }
}

async function deleteSession() {
  if (!activeSessionId.value) return
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
    await chatApi.remove(activeSessionId.value)
    ElMessage.success('会话已删除')
    resetConversation()
    await loadSessions()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

function upsertToolCall(call: { id: string; name: string; arguments: string; source: string }, assistant: UiMessage) {
  if (!assistant.toolTrace) assistant.toolTrace = []
  assistant.toolTrace.push({
    id: call.id,
    name: call.name,
    arguments: call.arguments,
    source: call.source,
    running: true,
  })
  scrollToBottom()
}

function updateToolResult(
  result: { id: string; result: string; error: boolean; durationMs: number },
  assistant: UiMessage,
) {
  const item = assistant.toolTrace?.find((trace) => trace.id === result.id)
  if (item) {
    item.result = result.result
    item.error = result.error
    item.durationMs = result.durationMs
    item.running = false
  }
  scrollToBottom()
}

async function send() {
  if (!canSend.value) return
  if (isAdminMode.value && !props.agentId) {
    ElMessage.warning('请先选择智能体')
    return
  }
  if (!isAdminMode.value && !props.publicId) {
    ElMessage.warning('智能体信息缺失')
    return
  }

  const text = draft.value.trim()
  const assistant = reactive<UiMessage>({
    key: `a-${Date.now()}`,
    sessionId: activeSessionId.value || 0,
    role: 'assistant',
    content: '',
    reasoning: '',
    toolTrace: [],
    citations: [],
    status: 'STREAMING',
    model: '',
    streaming: true,
    createdAt: new Date().toISOString(),
    promptTokens: 0,
    completionTokens: 0,
    totalTokens: 0,
  })

  messages.value.push({
    key: `u-${Date.now()}`,
    sessionId: activeSessionId.value || 0,
    role: 'user',
    content: text,
    status: 'COMPLETED',
    createdAt: new Date().toISOString(),
  })
  messages.value.push(assistant)
  draft.value = ''
  streaming.value = true
  stopping.value = false
  stickToBottom.value = true
  await nextTick()
  scrollToBottom(true)

  const controller = new AbortController()
  abortController = controller

  try {
    await streamChat({
      path: isAdminMode.value ? '/api/chat/send' : '/api/public/chat/send',
      body: isAdminMode.value
        ? {
            agentId: props.agentId,
            sessionId: activeSessionId.value,
            message: text,
          }
        : {
            publicId: props.publicId,
            sessionId: activeSessionId.value,
            message: text,
          },
      signal: controller.signal,
      handlers: {
        onMeta(meta) {
          requestId.value = meta.requestId
          activeSessionId.value = meta.sessionId
          assistant.sessionId = meta.sessionId
          assistant.model = meta.modelName || meta.model
        },
        onCitation(citation) {
          assistant.citations!.push(citation)
          scrollToBottom()
        },
        onReasoning(delta) {
          assistant.reasoning = (assistant.reasoning || '') + delta
          scrollToBottom()
        },
        onContent(delta) {
          assistant.content = (assistant.content || '') + delta
          assistant.retrying = false
          scrollToBottom()
        },
        onToolCall(call) {
          upsertToolCall(call, assistant)
        },
        onToolResult(result) {
          updateToolResult(result, assistant)
        },
        onToolApproval(approval) {
          pendingApproval.value = approval
        },
        onRetry(retry) {
          if (retry.reset) {
            assistant.content = ''
            assistant.reasoning = ''
          }
          assistant.retrying = true
        },
        onDegraded(info) {
          if (info.reset) {
            assistant.content = typeof info.contentLength === 'number'
              ? (assistant.content || '').slice(0, info.contentLength)
              : ''
            assistant.reasoning = typeof info.reasoningLength === 'number'
              ? (assistant.reasoning || '').slice(0, info.reasoningLength)
              : ''
          }
          assistant.notice = info.message || '主模型不可用，已切换至备用模型'
        },
        onUsage(usage) {
          assistant.promptTokens = usage.promptTokens
          assistant.completionTokens = usage.completionTokens
          assistant.totalTokens = usage.totalTokens
        },
        onError(message) {
          assistant.error = message
          assistant.status = 'FAILED'
          pendingApproval.value = null
        },
        onDone(done) {
          assistant.status = done.status
          assistant.durationMs = done.durationMs
          assistant.retrying = false
          if (done.error && !assistant.error) assistant.error = done.error
          if (done.truncated) {
            assistant.notice = [assistant.notice, '回复达到模型长度上限，内容已截断。']
              .filter(Boolean)
              .join(' ')
          }
          pendingApproval.value = null
          requestId.value = null
        },
      },
    })
    if (assistant.status === 'STREAMING') {
      assistant.status = 'STOPPED'
    }
  } catch (error) {
    if (controller.signal.aborted) {
      assistant.status = 'STOPPED'
    } else {
      assistant.status = 'FAILED'
      assistant.error =
        error instanceof ApiError ? error.message : '连接中断，请检查后端服务后重试'
    }
  } finally {
    assistant.streaming = false
    streaming.value = false
    stopping.value = false
    requestId.value = null
    pendingApproval.value = null
    abortController = null
    await nextTick()
    scrollToBottom(true)
    await loadSessions()
  }
}

async function decideApproval(approved: boolean) {
  const approval = pendingApproval.value
  if (!approval || approvalSubmitting.value) return
  approvalSubmitting.value = true
  try {
    if (isAdminMode.value) {
      await chatApi.approveTool(approval.requestId, approval.callId, approved)
    } else {
      await publicChatApi.approveTool(approval.requestId, approval.callId, approved)
    }
    pendingApproval.value = null
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '提交工具确认失败')
  } finally {
    approvalSubmitting.value = false
  }
}

async function handleStop() {
  if (!streaming.value) return
  if (!requestId.value) {
    abortController?.abort()
    return
  }
  stopping.value = true
  try {
    if (isAdminMode.value) await chatApi.stop(requestId.value)
    else await publicChatApi.stop(requestId.value)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '停止失败')
  } finally {
    stopping.value = false
  }
}

function onKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return
  event.preventDefault()
  if (canSend.value) send()
}

function onRailSelect(session: ChatSessionView) {
  openSession(session)
}

watch(
  () => [props.agentId, props.publicId, props.mode],
  () => {
    resetConversation()
    sessions.value = []
    draft.value = ''
    loadSessions()
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  abortController?.abort()
})
</script>

<template>
  <div class="chat-console" :class="[`variant-${variant}`, { 'rail-open': railOpen }]">
    <aside class="rail">
      <div class="rail-head">
        <span class="rail-title">
          <el-icon><Memo /></el-icon>
          会话记录
        </span>
        <button type="button" class="rail-new" title="新建对话" @click="newChat">
          <el-icon><Plus /></el-icon>
        </button>
      </div>
      <div class="rail-list scroll-thin">
        <div v-if="sessionsLoading" class="rail-loading">
          <span class="spinner"></span>
          正在加载会话
        </div>
        <template v-else>
          <button
            v-for="session in sessions"
            :key="session.id"
            type="button"
            class="session-item"
            :class="{ active: session.id === activeSessionId }"
            @click="onRailSelect(session)"
          >
            <span class="session-title">{{ session.title || '未命名对话' }}</span>
            <span class="session-meta">
              <span>{{ fromNow(session.lastMessageAt || session.createdAt) }}</span>
              <span class="dot-sep">·</span>
              <span>{{ session.messageCount }} 条</span>
            </span>
          </button>
        </template>
        <div v-if="!sessionsLoading && !sessions.length" class="rail-empty">
          还没有历史会话
          <span>发送第一条消息即可开始</span>
        </div>
      </div>
      <div class="rail-foot">
        <span class="rail-foot-dot"></span>
        <template v-if="isAdminMode">后台调试会话 · 空间内可见</template>
        <template v-else>访客会话通过安全 Cookie 绑定当前浏览器</template>
      </div>
    </aside>

    <div v-if="railOpen" class="rail-mask" @click="railOpen = false"></div>

    <section class="chat-main">
      <header class="chat-head">
        <button
          type="button"
          class="head-btn rail-toggle"
          title="会话记录"
          @click="railOpen = !railOpen"
        >
          <el-icon><Memo /></el-icon>
        </button>
        <div class="head-avatar">{{ (agentName || 'K').slice(0, 1) }}</div>
        <div class="head-copy">
          <div class="head-title">{{ agentName }}</div>
          <div class="head-sub">
            <span class="live-dot"></span>
            <span class="head-session text-ellipsis">{{ titleText }}</span>
          </div>
        </div>
        <div class="head-actions">
          <button type="button" class="head-btn" title="新建对话" @click="newChat">
            <el-icon><Plus /></el-icon>
          </button>
          <button
            v-if="isAdminMode && activeSessionId"
            type="button"
            class="head-btn"
            title="重命名会话"
            @click="renameSession"
          >
            <el-icon><EditPen /></el-icon>
          </button>
          <button
            v-if="isAdminMode && activeSessionId"
            type="button"
            class="head-btn danger"
            title="删除会话"
            @click="deleteSession"
          >
            <el-icon><Delete /></el-icon>
          </button>
        </div>
      </header>

      <div ref="messagesEl" class="messages scroll-thin" @scroll="onMessagesScroll">
        <div v-if="messagesLoading" class="messages-loading">
          <span class="pulse-orb"></span>
          正在加载消息…
        </div>

        <div v-else-if="!messages.length" class="chat-empty">
          <div class="empty-orb">
            <span class="orb-ring"></span>
            <span class="orb-core">{{ (agentName || 'K').slice(0, 1) }}</span>
          </div>
          <h2>
            和 <span class="gradient-text">{{ agentName }}</span> 开始对话
          </h2>
          <p class="empty-intro">
            {{ agentIntro || '我会结合模型能力、知识库与工具，为你提供可靠的回答。' }}
          </p>
          <div class="suggestions">
            <button
              v-for="item in greeting"
              :key="item"
              type="button"
              class="suggestion"
              @click="draft = item"
            >
              <span>{{ item }}</span>
              <el-icon><Promotion /></el-icon>
            </button>
          </div>
          <div class="empty-hint">
            <span></span>
            支持流式输出、知识引用与 MCP 工具调用
          </div>
        </div>

        <div v-else class="message-list">
          <ChatMessageItem
            v-for="message in messages"
            :key="message.key"
            :message="message"
            :agent-name="agentName"
            :user-label="isAdminMode ? auth.displayName : '访客'"
          />
          <div v-if="streaming && !messages[messages.length - 1]?.content" class="stream-hint">
            <span class="pulse-orb"></span>
            {{ agentName }} 正在思考
          </div>
        </div>
      </div>

      <div class="composer-wrap">
        <div v-if="pendingApproval" class="approval-card">
          <div class="approval-head">
            <span>工具调用需要确认</span>
            <span class="approval-source">{{ pendingApproval.source }}</span>
          </div>
          <div class="approval-tool">{{ pendingApproval.name }}</div>
          <pre v-if="pendingApproval.arguments" class="approval-args">{{ pendingApproval.arguments }}</pre>
          <div class="approval-actions">
            <button
              type="button"
              class="approval-btn reject"
              :disabled="approvalSubmitting"
              @click="decideApproval(false)"
            >
              拒绝
            </button>
            <button
              type="button"
              class="approval-btn approve"
              :disabled="approvalSubmitting"
              @click="decideApproval(true)"
            >
              {{ approvalSubmitting ? '提交中' : '批准执行' }}
            </button>
          </div>
        </div>
        <div class="composer" :class="{ busy: streaming }">
          <el-input
            v-model="draft"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 6 }"
            resize="none"
            maxlength="10000"
            placeholder="输入消息，Enter 发送，Shift + Enter 换行"
            class="composer-input"
            @keydown="onKeydown"
          />
          <div class="composer-bar">
            <span class="composer-hint">
              <template v-if="streaming">生成中，可随时停止</template>
              <template v-else>Enter 发送 · Shift + Enter 换行</template>
            </span>
            <span v-if="charCount > 9000" class="char-count">{{ charCount }}/10000</span>
            <button
              v-if="streaming"
              type="button"
              class="stop-btn"
              :disabled="stopping"
              @click="handleStop"
            >
              <el-icon><VideoPause /></el-icon>
              {{ stopping ? '停止中' : '停止' }}
            </button>
            <button
              v-else
              type="button"
              class="send-btn"
              :disabled="!canSend"
              title="发送"
              @click="send"
            >
              <el-icon><Promotion /></el-icon>
            </button>
          </div>
        </div>
        <div class="composer-foot">
          <span>AI 生成内容仅供参考，请自行核验关键信息</span>
          <span v-if="isAdminMode && activeSessionId" class="session-id">
            #{{ activeSessionId }}
          </span>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.chat-console {
  display: flex;
  min-height: 0;
  height: 100%;
  overflow: hidden;
  border-radius: var(--king-radius);
  border: 1px solid var(--king-line);
  background: rgba(9, 13, 27, 0.6);
  backdrop-filter: blur(18px);
}

.variant-public {
  border: none;
  border-radius: 0;
  background: transparent;
  backdrop-filter: none;
}

.rail {
  position: relative;
  z-index: 4;
  display: flex;
  flex-direction: column;
  flex: none;
  width: 248px;
  border-right: 1px solid var(--king-line);
  background: rgba(8, 12, 24, 0.55);
}

.variant-public .rail {
  background: rgba(8, 12, 24, 0.35);
}

.rail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 58px;
  padding: 0 14px 0 16px;
  border-bottom: 1px solid var(--king-line);
}

.rail-title {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--king-text-soft);
}

.rail-new {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  border: 1px solid var(--king-line);
  background: rgba(255, 255, 255, 0.04);
  color: var(--king-text-soft);
  cursor: pointer;
  transition: all 0.18s ease;
}

.rail-new:hover {
  color: #fff;
  border-color: rgba(124, 92, 255, 0.5);
  background: rgba(124, 92, 255, 0.18);
}

.rail-list {
  flex: 1;
  overflow-y: auto;
  padding: 10px;
}

.rail-loading {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 14px 10px;
  font-size: 11px;
  color: var(--king-muted);
}

.spinner {
  width: 11px;
  height: 11px;
  border-radius: 50%;
  border: 1.5px solid rgba(255, 255, 255, 0.16);
  border-top-color: var(--king-primary);
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.session-item {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 5px;
  width: 100%;
  padding: 10px 11px;
  margin-bottom: 3px;
  border-radius: 10px;
  border: 1px solid transparent;
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: background 0.16s ease, border-color 0.16s ease;
}

.session-item:hover {
  background: rgba(255, 255, 255, 0.04);
}

.session-item.active {
  background: linear-gradient(100deg, rgba(124, 92, 255, 0.18), rgba(34, 211, 238, 0.04));
  border-color: rgba(124, 92, 255, 0.24);
}

.session-title {
  font-size: 12px;
  font-weight: 520;
  color: #a8b3cf;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-item.active .session-title {
  color: #eee9ff;
}

.session-meta {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 9.5px;
  color: #5d6888;
}

.dot-sep {
  opacity: 0.6;
}

.rail-empty {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 28px 12px;
  text-align: center;
  font-size: 11.5px;
  color: var(--king-muted);
}

.rail-empty span {
  font-size: 10px;
  color: #525d7a;
}

.rail-foot {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 12px 14px;
  border-top: 1px solid var(--king-line);
  font-size: 9.5px;
  color: #596482;
}

.rail-foot-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--king-green);
}

.chat-main {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
  min-height: 0;
}

.chat-head {
  display: flex;
  align-items: center;
  gap: 11px;
  height: 58px;
  padding: 0 16px;
  border-bottom: 1px solid var(--king-line);
  background: rgba(9, 13, 27, 0.4);
}

.head-avatar {
  display: grid;
  place-items: center;
  flex: none;
  width: 30px;
  height: 30px;
  border-radius: 9px;
  font-size: 12px;
  font-weight: 680;
  color: #fff;
  background: linear-gradient(135deg, #8b5cf6, #22d3ee);
  box-shadow: 0 5px 14px rgba(124, 92, 255, 0.26);
}

.head-copy {
  min-width: 0;
  flex: 1;
}

.head-title {
  font-size: 13px;
  font-weight: 620;
  color: var(--king-text);
}

.head-sub {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 2px;
  min-width: 0;
}

.live-dot {
  flex: none;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--king-green);
  box-shadow: 0 0 0 3px rgba(52, 211, 153, 0.12);
}

.head-session {
  font-size: 10px;
  color: #66718f;
  max-width: 340px;
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

.head-btn {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  border: 1px solid transparent;
  background: transparent;
  color: #7783a4;
  cursor: pointer;
  transition: all 0.16s ease;
}

.head-btn:hover {
  color: var(--king-text);
  background: rgba(255, 255, 255, 0.06);
  border-color: var(--king-line);
}

.head-btn.danger:hover {
  color: #ffabb7;
  background: rgba(251, 113, 133, 0.08);
  border-color: rgba(251, 113, 133, 0.2);
}

.rail-toggle {
  display: none;
}

.messages {
  position: relative;
  flex: 1;
  overflow-y: auto;
  padding: 24px clamp(16px, 4vw, 46px) 12px;
}

.messages-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
  height: 100%;
  font-size: 12px;
  color: var(--king-muted);
}

.message-list {
  display: flex;
  flex-direction: column;
  gap: 24px;
  max-width: 860px;
  margin: 0 auto;
  padding-bottom: 12px;
}

.chat-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  max-width: 620px;
  min-height: 100%;
  margin: 0 auto;
  padding: 24px 0 44px;
  text-align: center;
}

.empty-orb {
  position: relative;
  display: grid;
  place-items: center;
  width: 76px;
  height: 76px;
  margin-bottom: 24px;
}

.orb-ring {
  position: absolute;
  inset: 0;
  border-radius: 24px;
  background: conic-gradient(from 140deg, rgba(124, 92, 255, 0.5), rgba(34, 211, 238, 0.16), rgba(255, 110, 169, 0.2), rgba(124, 92, 255, 0.5));
  filter: blur(10px);
  opacity: 0.55;
  animation: rotate 10s linear infinite;
}

@keyframes rotate {
  to {
    transform: rotate(360deg);
  }
}

.orb-core {
  position: relative;
  display: grid;
  place-items: center;
  width: 60px;
  height: 60px;
  border-radius: 19px;
  font-size: 24px;
  font-weight: 720;
  color: #fff;
  background: linear-gradient(135deg, #8b5cf6, #6d6bff 60%, #22d3ee);
  border: 1px solid rgba(255, 255, 255, 0.2);
  box-shadow: 0 16px 40px rgba(91, 69, 220, 0.35);
}

.chat-empty h2 {
  margin: 0 0 10px;
  font-size: 25px;
  font-weight: 680;
  letter-spacing: -0.025em;
}

.chat-empty .empty-intro {
  max-width: 480px;
  margin: 0 0 26px;
  font-size: 12.5px;
  line-height: 1.75;
  color: var(--king-muted);
}

.suggestions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
}

.suggestion {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 9px 13px;
  border-radius: 10px;
  border: 1px solid var(--king-line);
  background: rgba(255, 255, 255, 0.025);
  color: #97a3c0;
  font-size: 11.5px;
  cursor: pointer;
  transition: all 0.18s ease;
}

.suggestion:hover {
  color: #ddd6ff;
  border-color: rgba(124, 92, 255, 0.4);
  background: rgba(124, 92, 255, 0.09);
  transform: translateY(-1px);
}

.suggestion .el-icon {
  font-size: 11px;
  opacity: 0.65;
}

.empty-hint {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 30px;
  font-size: 10px;
  color: #4f5a77;
}

.empty-hint span {
  width: 24px;
  height: 1px;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.2));
}

.stream-hint {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-left: 45px;
  font-size: 11px;
  color: var(--king-muted);
}

.pulse-orb {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--king-primary);
  animation: pulse 1.5s ease-out infinite;
}

@keyframes pulse {
  0% {
    box-shadow: 0 0 0 0 rgba(124, 92, 255, 0.4);
  }
  70% {
    box-shadow: 0 0 0 8px rgba(124, 92, 255, 0);
  }
  100% {
    box-shadow: 0 0 0 0 rgba(124, 92, 255, 0);
  }
}

.composer-wrap {
  flex: none;
  padding: 10px clamp(16px, 4vw, 46px) 16px;
  background: linear-gradient(180deg, transparent, rgba(7, 10, 23, 0.72) 30%);
}

.approval-card {
  max-width: 860px;
  margin: 0 auto 10px;
  padding: 12px 14px;
  border-radius: 13px;
  border: 1px solid rgba(251, 191, 36, 0.32);
  background: rgba(54, 39, 12, 0.55);
  box-shadow: 0 12px 32px rgba(2, 4, 12, 0.3);
}

.approval-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 11px;
  color: #fcd34d;
}

.approval-source {
  font-family: var(--king-mono);
  font-size: 9px;
  color: #a58b52;
}

.approval-tool {
  margin-top: 5px;
  font-family: var(--king-mono);
  font-size: 12.5px;
  color: #fff5d6;
}

.approval-args {
  max-height: 120px;
  margin: 7px 0 0;
  padding: 8px 10px;
  overflow: auto;
  border-radius: 8px;
  background: rgba(0, 0, 0, 0.25);
  font-family: var(--king-mono);
  font-size: 10.5px;
  line-height: 1.55;
  color: #cdbb91;
  white-space: pre-wrap;
  word-break: break-all;
}

.approval-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 10px;
}

.approval-btn {
  height: 28px;
  padding: 0 12px;
  border-radius: 8px;
  border: 1px solid transparent;
  font-size: 10.5px;
  cursor: pointer;
}

.approval-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.approval-btn.reject {
  color: #ffabb7;
  border-color: rgba(251, 113, 133, 0.25);
  background: rgba(251, 113, 133, 0.08);
}

.approval-btn.approve {
  color: #2b1c05;
  background: linear-gradient(135deg, #fcd34d, #f59e0b);
}

.composer {
  max-width: 860px;
  margin: 0 auto;
  padding: 10px 12px 8px;
  border-radius: 15px;
  border: 1px solid var(--king-line-strong);
  background: rgba(13, 19, 37, 0.92);
  box-shadow: 0 18px 44px rgba(2, 4, 12, 0.35);
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.composer:focus-within {
  border-color: rgba(124, 92, 255, 0.5);
  box-shadow: 0 18px 50px rgba(80, 55, 190, 0.2);
}

.composer.busy {
  border-color: rgba(124, 92, 255, 0.32);
}

.composer-input :deep(.el-textarea__inner) {
  background: transparent;
  border: none;
  box-shadow: none;
  padding: 4px 4px 6px;
  color: var(--king-text);
  font-size: 13.5px;
  line-height: 1.7;
}

.composer-input :deep(.el-textarea__inner::placeholder) {
  color: #566180;
}

.composer-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 2px 2px 0 4px;
}

.composer-hint {
  font-size: 9.5px;
  color: #535e7c;
}

.char-count {
  font-family: var(--king-mono);
  font-size: 9px;
  color: var(--king-amber);
}

.send-btn,
.stop-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 30px;
  border: none;
  border-radius: 9px;
  cursor: pointer;
  transition: all 0.18s ease;
  margin-left: auto;
}

.send-btn {
  width: 34px;
  color: #fff;
  background: linear-gradient(135deg, #8b5cf6, #22b8d4);
  box-shadow: 0 6px 18px rgba(124, 92, 255, 0.3);
}

.send-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  filter: brightness(1.1);
}

.send-btn:disabled {
  opacity: 0.32;
  cursor: not-allowed;
  box-shadow: none;
}

.stop-btn {
  padding: 0 11px;
  color: #ffabb7;
  background: rgba(251, 113, 133, 0.09);
  border: 1px solid rgba(251, 113, 133, 0.22);
  font-size: 10.5px;
}

.stop-btn:hover:not(:disabled) {
  background: rgba(251, 113, 133, 0.15);
}

.composer-foot {
  display: flex;
  justify-content: space-between;
  max-width: 860px;
  margin: 7px auto 0;
  padding: 0 4px;
  font-size: 9px;
  color: #4d5875;
}

.session-id {
  font-family: var(--king-mono);
}

.rail-mask {
  display: none;
}

@media (max-width: 860px) {
  .rail {
    position: absolute;
    top: 0;
    bottom: 0;
    left: 0;
    width: 272px;
    transform: translateX(-102%);
    transition: transform 0.22s ease;
    box-shadow: 24px 0 60px rgba(0, 0, 0, 0.45);
    background: #0a0f20;
  }

  .rail-open .rail {
    transform: translateX(0);
  }

  .rail-mask {
    display: block;
    position: absolute;
    inset: 0;
    z-index: 3;
    background: rgba(3, 6, 16, 0.55);
    backdrop-filter: blur(2px);
  }

  .rail-toggle {
    display: grid;
  }

  .chat-console {
    position: relative;
  }

  .messages {
    padding: 18px 14px 8px;
  }

  .message-list {
    gap: 20px;
  }

  .chat-empty h2 {
    font-size: 21px;
  }

  .chat-empty {
    padding-top: 10px;
  }

  .composer-wrap {
    padding: 8px 12px 12px;
  }

  .head-session {
    max-width: 180px;
  }
}
</style>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ArrowRight, Clock, Document, Tools, WarningFilled } from '@element-plus/icons-vue'
import MarkdownView from '@/components/MarkdownView.vue'
import type { CitationItem, ToolTraceItem } from '@/api/types'
import type { UiMessage } from './ui'
import { formatDate, sessionStatus } from '@/utils/format'

const props = defineProps<{
  message: UiMessage
  agentName?: string
  userLabel?: string
}>()

const isUser = computed(() => props.message.role === 'user')
const isAssistant = computed(() => props.message.role === 'assistant')
const citations = computed<CitationItem[]>(() =>
  Array.isArray(props.message.citations) ? props.message.citations : [],
)
const tools = computed<ToolTraceItem[]>(() =>
  Array.isArray(props.message.toolTrace) ? props.message.toolTrace : [],
)
const hasContent = computed(() => Boolean(props.message.content?.trim()))
const reasoningOpen = ref(false)

const state = computed(() => sessionStatus(props.message.status || 'COMPLETED'))
const interrupted = computed(
  () => isAssistant.value && props.message.status === 'STREAMING' && !props.message.streaming,
)

watch(
  () => props.message.streaming,
  (streaming) => {
    reasoningOpen.value = Boolean(streaming && !hasContent.value)
  },
  { immediate: true },
)

watch(
  () => props.message.content,
  (content) => {
    if (content && reasoningOpen.value) reasoningOpen.value = false
  },
)

function toolSummary(tool: ToolTraceItem): string {
  if (tool.running) return '执行中'
  if (tool.error) return '调用失败'
  if (tool.durationMs !== undefined) return `${tool.durationMs} ms`
  return '已完成'
}
</script>

<template>
  <div v-if="isUser" class="message-row user">
    <div class="user-stack">
      <div class="bubble user-bubble">{{ message.content }}</div>
      <div class="user-time">{{ formatDate(message.createdAt, 'HH:mm') }}</div>
    </div>
    <div class="avatar user-avatar">{{ (userLabel || '我').slice(0, 1) }}</div>
  </div>

  <div v-else-if="isAssistant" class="message-row assistant">
    <div class="avatar agent-avatar">{{ (agentName || 'K').slice(0, 1) }}</div>
    <div class="assistant-body">
      <div class="message-head">
        <span class="agent-name">{{ agentName || 'King AI' }}</span>
        <span v-if="message.streaming" class="state live">
          <span class="typing-dots"><i></i><i></i><i></i></span>
          正在生成
        </span>
        <span v-else class="state" :class="state.type">{{ interrupted ? '生成中断' : state.text }}</span>
        <span class="message-time">{{ formatDate(message.createdAt, 'MM-DD HH:mm') }}</span>
      </div>

      <div v-if="message.reasoning" class="reasoning">
        <button type="button" class="reasoning-toggle" @click="reasoningOpen = !reasoningOpen">
          <span class="reasoning-title">
            <el-icon><Clock /></el-icon>
            思考过程
          </span>
          <el-icon class="reasoning-arrow" :class="{ open: reasoningOpen }"><ArrowRight /></el-icon>
        </button>
        <div v-show="reasoningOpen" class="reasoning-content">{{ message.reasoning }}</div>
      </div>

      <div v-if="tools.length" class="tool-list">
        <div v-for="tool in tools" :key="tool.id" class="tool-card" :class="{ fail: tool.error, running: tool.running }">
          <div class="tool-head">
            <el-icon class="tool-icon"><Tools /></el-icon>
            <div class="tool-main">
              <div class="tool-name">{{ tool.name }}</div>
              <div class="tool-meta">
                <span>{{ tool.source || 'TOOL' }}</span>
                <span>·</span>
                <span>{{ toolSummary(tool) }}</span>
              </div>
            </div>
            <span class="tool-state" :class="{ fail: tool.error }">
              {{ tool.running ? '运行中' : tool.error ? '失败' : '成功' }}
            </span>
          </div>
          <details v-if="tool.arguments || tool.result" class="tool-details">
            <summary>查看调用详情</summary>
            <div v-if="tool.arguments" class="tool-block">
              <span class="tool-block-label">参数</span>
              <pre>{{ tool.arguments }}</pre>
            </div>
            <div v-if="tool.result" class="tool-block">
              <span class="tool-block-label">结果</span>
              <pre>{{ tool.result }}</pre>
            </div>
          </details>
        </div>
      </div>

      <div class="answer">
        <MarkdownView v-if="hasContent" :content="message.content" />
        <div v-else-if="message.streaming" class="thinking-placeholder">
          <span class="pulse-orb"></span>
          正在组织回答…
        </div>
      </div>

      <div v-if="citations.length" class="citations">
        <div class="citations-head">
          <el-icon><Document /></el-icon>
          知识引用
          <span class="citation-count">{{ citations.length }}</span>
        </div>
        <div class="citation-list">
          <details v-for="citation in citations" :key="citation.chunkId" class="citation-card">
            <summary>
              <span class="citation-index">[{{ citation.index }}]</span>
              <span class="citation-name">{{ citation.docName }}</span>
              <span class="citation-score" :title="`相似度 ${citation.score}`">
                {{ Math.round(citation.score * 100) }}%
              </span>
            </summary>
            <div class="citation-body">
              <div class="citation-origin">
                {{ citation.knowledgeBaseName }} · 片段 #{{ citation.chunkIndex + 1 }}
              </div>
              <p>{{ citation.snippet }}</p>
            </div>
          </details>
        </div>
      </div>

      <div v-if="message.error" class="error-box">
        <el-icon><WarningFilled /></el-icon>
        {{ message.error }}
      </div>
      <div v-else-if="interrupted" class="interrupted-box">
        <el-icon><WarningFilled /></el-icon>
        该回复生成过程中断，以上为已保留的部分内容。
      </div>

      <div v-if="message.retrying" class="notice-box">
        <span class="notice-dot"></span>
        模型请求中断，正在自动重试…
      </div>
      <div v-else-if="message.notice" class="notice-box">
        <span class="notice-dot"></span>
        {{ message.notice }}
      </div>

      <div v-if="!message.streaming && (message.model || message.totalTokens || message.durationMs)" class="message-foot">
        <span v-if="message.model">{{ message.model }}</span>
        <span v-if="message.totalTokens">{{ message.totalTokens }} tokens</span>
        <span v-else-if="message.promptTokens || message.completionTokens">
          {{ (message.promptTokens || 0) + (message.completionTokens || 0) }} tokens
        </span>
        <span v-if="message.durationMs">{{ (message.durationMs / 1000).toFixed(1) }}s</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.message-row {
  display: flex;
  gap: 13px;
  width: 100%;
  animation: msg-in 0.24s ease both;
}

@keyframes msg-in {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.message-row.user {
  justify-content: flex-end;
}

.user-stack {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 5px;
  max-width: min(76%, 720px);
}

.user-bubble {
  padding: 12px 16px;
  border-radius: 16px 16px 4px 16px;
  background: linear-gradient(135deg, rgba(124, 92, 255, 0.9), rgba(109, 107, 255, 0.75));
  border: 1px solid var(--king-line-strong);
  color: #fff;
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
  box-shadow: 0 12px 30px rgba(91, 69, 220, 0.22);
}

.user-time {
  padding-right: 3px;
  font-size: 10px;
  color: #5d6888;
}

.avatar {
  display: grid;
  place-items: center;
  flex: none;
  width: 32px;
  height: 32px;
  border-radius: 10px;
  font-size: 12.5px;
  font-weight: 680;
}

.user-avatar {
  color: var(--k-dim);
  background: var(--k-fill);
  border: 1px solid var(--king-line);
}

.agent-avatar {
  color: #fff;
  background: linear-gradient(135deg, #8b5cf6, #22d3ee);
  box-shadow: 0 6px 18px rgba(124, 92, 255, 0.28);
}

.assistant-body {
  min-width: 0;
  flex: 1;
  max-width: 100%;
  padding-top: 2px;
}

.message-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.agent-name {
  font-size: 12.5px;
  font-weight: 630;
  color: var(--king-text);
}

.message-time {
  font-size: 10px;
  color: #596482;
}

.state {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 18px;
  padding: 0 7px;
  border-radius: 5px;
  font-size: 9.5px;
  font-weight: 550;
  color: var(--k-dim);
  background: var(--k-fill);
}

.state.live {
  color: var(--k-violet);
  background: rgba(124, 92, 255, 0.12);
}

.state.success {
  color: var(--k-green);
  background: rgba(52, 211, 153, 0.09);
}

.state.warning {
  color: var(--k-amber);
  background: rgba(251, 191, 36, 0.09);
}

.state.danger {
  color: var(--k-red);
  background: rgba(251, 113, 133, 0.1);
}

.typing-dots {
  display: inline-flex;
  gap: 2px;
}

.typing-dots i {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: currentColor;
  animation: dot 1s ease-in-out infinite;
}

.typing-dots i:nth-child(2) {
  animation-delay: 0.15s;
}

.typing-dots i:nth-child(3) {
  animation-delay: 0.3s;
}

@keyframes dot {
  0%,
  60%,
  100% {
    opacity: 0.35;
    transform: translateY(0);
  }
  30% {
    opacity: 1;
    transform: translateY(-2px);
  }
}

.reasoning {
  margin-bottom: 10px;
  border-radius: 10px;
  border: 1px solid var(--king-line);
  background: var(--k-fill-soft);
  overflow: hidden;
}

.reasoning-toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 9px 12px;
  border: none;
  background: none;
  color: var(--king-muted);
  font-size: 11.5px;
  cursor: pointer;
}

.reasoning-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.reasoning-arrow {
  font-size: 11px;
  transition: transform 0.18s ease;
}

.reasoning-arrow.open {
  transform: rotate(90deg);
}

.reasoning-content {
  max-height: 260px;
  overflow-y: auto;
  padding: 0 13px 12px;
  font-size: 12px;
  line-height: 1.75;
  color: var(--k-dim);
  white-space: pre-wrap;
}

.tool-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 12px;
}

.tool-card {
  border-radius: 11px;
  border: 1px solid rgba(34, 211, 238, 0.14);
  background: rgba(34, 211, 238, 0.035);
  overflow: hidden;
}

.tool-card.fail {
  border-color: rgba(251, 113, 133, 0.22);
  background: rgba(251, 113, 133, 0.04);
}

.tool-card.running {
  border-color: rgba(124, 92, 255, 0.28);
}

.tool-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
}

.tool-icon {
  color: var(--k-cyan);
  font-size: 15px;
}

.tool-main {
  min-width: 0;
  flex: 1;
}

.tool-name {
  font-family: var(--king-mono);
  font-size: 11.5px;
  color: var(--k-cyan-soft);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tool-meta {
  display: flex;
  gap: 5px;
  margin-top: 2px;
  font-size: 9.5px;
  color: #60708f;
}

.tool-state {
  flex: none;
  padding: 2px 7px;
  border-radius: 5px;
  font-size: 9px;
  color: var(--k-green);
  background: rgba(52, 211, 153, 0.08);
}

.tool-state.fail {
  color: var(--k-red);
  background: rgba(251, 113, 133, 0.1);
}

.tool-details {
  border-top: 1px solid var(--king-line);
}

.tool-details summary {
  padding: 8px 12px;
  font-size: 10px;
  color: #6f7c9d;
  cursor: pointer;
  list-style: none;
}

.tool-details summary::-webkit-details-marker {
  display: none;
}

.tool-block {
  padding: 0 12px 10px;
}

.tool-block-label {
  display: block;
  margin-bottom: 4px;
  font-size: 9px;
  letter-spacing: 0.08em;
  color: #5d6888;
}

.tool-block pre {
  max-height: 220px;
  margin: 0;
  padding: 9px 10px;
  overflow: auto;
  border-radius: 7px;
  background: rgba(0, 0, 0, 0.24);
  color: var(--k-code-text);
  font-family: var(--king-mono);
  font-size: 10.5px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-all;
}

.answer {
  min-height: 0;
}

.thinking-placeholder {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 4px 0;
  font-size: 12.5px;
  color: var(--king-muted);
}

.pulse-orb {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--king-primary);
  box-shadow: 0 0 0 0 rgba(124, 92, 255, 0.45);
  animation: pulse 1.6s ease-out infinite;
}

@keyframes pulse {
  0% {
    box-shadow: 0 0 0 0 rgba(124, 92, 255, 0.45);
  }
  70% {
    box-shadow: 0 0 0 10px rgba(124, 92, 255, 0);
  }
  100% {
    box-shadow: 0 0 0 0 rgba(124, 92, 255, 0);
  }
}

.citations {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed var(--king-line);
}

.citations-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
  font-size: 10.5px;
  font-weight: 560;
  color: var(--k-cyan);
}

.citation-count {
  display: inline-grid;
  place-items: center;
  min-width: 17px;
  height: 17px;
  padding: 0 4px;
  border-radius: 5px;
  background: rgba(34, 211, 238, 0.1);
  font-size: 9px;
}

.citation-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(210px, 1fr));
  gap: 7px;
}

.citation-card {
  border-radius: 9px;
  border: 1px solid var(--king-line);
  background: var(--k-fill-soft);
  overflow: hidden;
}

.citation-card summary {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 8px 10px;
  cursor: pointer;
  list-style: none;
}

.citation-card summary::-webkit-details-marker {
  display: none;
}

.citation-index {
  font-family: var(--king-mono);
  font-size: 9.5px;
  color: var(--k-violet);
}

.citation-name {
  flex: 1;
  font-size: 10.5px;
  color: var(--k-dim);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.citation-score {
  font-family: var(--king-mono);
  font-size: 9px;
  color: var(--k-cyan);
}

.citation-body {
  padding: 0 10px 10px;
}

.citation-origin {
  margin-bottom: 5px;
  font-size: 9px;
  color: #5d6888;
}

.citation-body p {
  margin: 0;
  font-size: 10.5px;
  line-height: 1.65;
  color: var(--k-dim);
  max-height: 140px;
  overflow-y: auto;
  white-space: pre-wrap;
}

.error-box,
.interrupted-box {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  margin-top: 10px;
  padding: 9px 11px;
  border-radius: 9px;
  font-size: 11.5px;
  line-height: 1.6;
}

.error-box {
  color: var(--k-red);
  background: rgba(251, 113, 133, 0.07);
  border: 1px solid rgba(251, 113, 133, 0.18);
}

.interrupted-box {
  color: var(--k-amber);
  background: rgba(251, 191, 36, 0.06);
  border: 1px solid rgba(251, 191, 36, 0.16);
}

.notice-box {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  padding: 8px 11px;
  border-radius: 9px;
  font-size: 10.5px;
  color: var(--k-dim);
  background: rgba(124, 92, 255, 0.06);
  border: 1px solid rgba(124, 92, 255, 0.16);
}

.notice-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #a78bfa;
}

.message-foot {
  display: flex;
  gap: 12px;
  margin-top: 10px;
  font-family: var(--king-mono);
  font-size: 9px;
  color: #525d7a;
}

@media (max-width: 768px) {
  .assistant {
    gap: 9px;
  }

  .agent-avatar,
  .avatar {
    width: 28px;
    height: 28px;
    border-radius: 9px;
    font-size: 11px;
  }

  .user-stack {
    max-width: 86%;
  }

  .citation-list {
    grid-template-columns: 1fr;
  }
}
</style>

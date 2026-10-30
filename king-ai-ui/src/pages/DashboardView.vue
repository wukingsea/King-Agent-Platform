<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowRight,
  Box,
  ChatDotRound,
  Collection,
  Connection,
  Cpu,
  MagicStick,
  Memo,
  Plus,
} from '@element-plus/icons-vue'
import StatCard from '@/components/StatCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import {
  chatApi,
  mcpApi,
  modelApi,
  workspaceApi,
} from '@/api'
import { useAuthStore } from '@/stores/auth'
import type {
  ChatSessionView,
  McpView,
  ModelView,
  WorkspaceStatsView,
} from '@/api/types'
import { formatNumber, fromNow, greeting, sourceText } from '@/utils/format'

const auth = useAuthStore()
const router = useRouter()

const loading = ref(true)
const loadError = ref('')
const stats = ref<WorkspaceStatsView>({
  agents: 0,
  publishedAgents: 0,
  knowledgeBases: 0,
  documents: 0,
  chunks: 0,
  sessions: 0,
  mcpServers: 0,
  skills: 0,
  suppliers: 0,
  models: 0,
})
const sessions = ref<ChatSessionView[]>([])
const sessionTotal = ref(0)
const mcps = ref<McpView[]>([])
const models = ref<ModelView[]>([])

const publishedCount = computed(() => stats.value.publishedAgents)
const chunkTotal = computed(() => stats.value.chunks)
const docTotal = computed(() => stats.value.documents)
const bindingTotal = computed(
  () => mcps.value.reduce((sum, server) => sum + (server.agentCount || 0), 0),
)
const enabledMcps = computed(() => mcps.value.filter((server) => server.status === 0).length)
const chatModelCount = computed(
  () => models.value.filter((model) => model.type === 'CHAT' && model.status === 0).length,
)
const embeddingModelCount = computed(
  () => models.value.filter((model) => model.type === 'EMBEDDING' && model.status === 0).length,
)

const topModels = computed(() =>
  [...models.value]
    .filter((model) => model.usageCount > 0)
    .sort((a, b) => b.usageCount - a.usageCount)
    .slice(0, 5),
)
const maxUsage = computed(() => Math.max(1, ...topModels.value.map((model) => model.usageCount)))

const quickLinks = computed(() => [
  {
    title: '编排智能体',
    desc: '组合模型、指令、知识与工具',
    icon: Cpu,
    tone: 'violet',
    route: { name: 'agents', query: { create: '1' } },
  },
  {
    title: '构建知识库',
    desc: '上传文档，自动切片与向量化',
    icon: Collection,
    tone: 'cyan',
    route: { name: 'knowledge', query: { create: '1' } },
  },
  {
    title: '接入 MCP 工具',
    desc: '让智能体调用外部服务',
    icon: Connection,
    tone: 'pink',
    route: { name: 'mcp', query: { create: '1' } },
  },
  {
    title: '编写技能',
    desc: '用 SKILL.md 注入领域规范',
    icon: MagicStick,
    tone: 'green',
    route: { name: 'skills', query: { create: '1' } },
  },
])

async function load() {
  loading.value = true
  loadError.value = ''
  const [statsRes, mcpRes, modelRes, sessionRes] =
    await Promise.allSettled([
      workspaceApi.stats(),
      mcpApi.list(),
      modelApi.list(),
      chatApi.sessions({ page: 1, size: 6 }),
    ])
  const failed: string[] = []
  if (statsRes.status === 'fulfilled') stats.value = statsRes.value
  else failed.push('工作台统计')
  if (mcpRes.status === 'fulfilled') mcps.value = mcpRes.value
  else failed.push('MCP 服务')
  if (modelRes.status === 'fulfilled') models.value = modelRes.value
  else failed.push('模型列表')
  if (sessionRes.status === 'fulfilled') {
    sessions.value = sessionRes.value.list
    sessionTotal.value = sessionRes.value.total
  } else {
    failed.push('最近会话')
  }
  if (failed.length) loadError.value = `${failed.join('、')}加载失败，请检查网络后重试`
  loading.value = false
}

function openSession(session: ChatSessionView) {
  router.push({ name: 'records', query: { sessionId: session.id } })
}

onMounted(load)
</script>

<template>
  <div class="page">
    <el-alert
      v-if="loadError"
      class="load-alert"
      type="warning"
      show-icon
      :closable="false"
    >
      <template #title>
        {{ loadError }}
        <el-button link type="primary" size="small" @click="load">重试</el-button>
      </template>
    </el-alert>
    <section class="hero panel">
      <div class="hero-copy">
        <div class="hero-eyebrow">
          <span class="live-dot"></span>
          {{ auth.currentSpace?.name || 'King AI 工作台' }}
        </div>
        <h1>
          {{ greeting() }}，{{ auth.displayName }}
          <span class="gradient-text">让智能体开始工作。</span>
        </h1>
        <p>
          在同一个工作台编排模型、知识与工具。发布智能体后即可生成公开对话链接，
          访客无需登录也能获得知识增强的回答。
        </p>
        <div class="hero-actions">
          <el-button type="primary" size="large" @click="router.push({ name: 'playground' })">
            <el-icon><ChatDotRound /></el-icon>
            开始对话测试
          </el-button>
          <el-button v-if="auth.canEditSpace" size="large" @click="router.push({ name: 'agents', query: { create: '1' } })">
            <el-icon><Plus /></el-icon>
            新建智能体
          </el-button>
        </div>
        <div class="hero-pills">
          <span class="pill live"><span class="dot"></span>{{ stats.agents }} 个智能体</span>
          <span class="pill primary">{{ publishedCount }} 个已发布</span>
          <span class="pill cyan">{{ formatNumber(chunkTotal) }} 个向量片段</span>
          <span class="pill pink">{{ stats.suppliers }} 个供应商</span>
        </div>
      </div>

      <div class="hero-visual" aria-hidden="true">
        <div class="orbit orbit-1"></div>
        <div class="orbit orbit-2"></div>
        <div class="orbit orbit-3"></div>
        <div class="core">
          <span>K</span>
        </div>
        <div class="satellite sat-a"><el-icon><Collection /></el-icon> RAG</div>
        <div class="satellite sat-b"><el-icon><Connection /></el-icon> MCP</div>
        <div class="satellite sat-c"><el-icon><MagicStick /></el-icon> SKILL</div>
      </div>
    </section>

    <div class="stats-grid">
      <StatCard label="智能体" :value="stats.agents" :hint="`${publishedCount} 个已发布可用`" tone="violet">
        <template #icon><el-icon><Cpu /></el-icon></template>
      </StatCard>
      <StatCard label="知识库片段" :value="formatNumber(chunkTotal)" :hint="`${stats.knowledgeBases} 个知识库 · ${docTotal} 篇文档`" tone="cyan">
        <template #icon><el-icon><Collection /></el-icon></template>
      </StatCard>
      <StatCard label="对话会话" :value="formatNumber(sessionTotal)" hint="后台调试与公开访客会话" tone="pink">
        <template #icon><el-icon><Memo /></el-icon></template>
      </StatCard>
      <StatCard label="MCP 工具" :value="stats.mcpServers" :hint="`${enabledMcps} 个启用 · 被 ${bindingTotal} 个智能体绑定`" tone="green">
        <template #icon><el-icon><Connection /></el-icon></template>
      </StatCard>
    </div>

    <div class="lower-grid">
      <section class="panel panel-pad recent-panel">
        <div class="panel-head">
          <h2 class="section-title">
            <el-icon><ChatDotRound /></el-icon>
            最近会话
          </h2>
          <router-link class="more-link" :to="{ name: 'records' }">
            查看全部
            <el-icon><ArrowRight /></el-icon>
          </router-link>
        </div>

        <el-skeleton v-if="loading" :rows="4" animated />
        <EmptyState
          v-else-if="!sessions.length"
          title="还没有对话记录"
          description="从对话测试开始，或发布智能体后让访客发起第一段对话。"
        >
          <template #icon><el-icon :size="22"><ChatDotRound /></el-icon></template>
          <template #action>
            <el-button size="small" type="primary" @click="router.push({ name: 'playground' })">
              开始对话
            </el-button>
          </template>
        </EmptyState>
        <div v-else class="session-feed">
          <button
            v-for="session in sessions"
            :key="session.id"
            type="button"
            class="feed-row"
            @click="openSession(session)"
          >
            <span class="feed-avatar">{{ (session.agentName || 'K').slice(0, 1) }}</span>
            <span class="feed-main">
              <span class="feed-title">{{ session.title || '未命名对话' }}</span>
              <span class="feed-meta">
                {{ session.agentName || '智能体' }}
                <span class="dot-sep">·</span>
                {{ sourceText(session.sourceType) }}
                <span class="dot-sep">·</span>
                {{ session.messageCount }} 条消息
              </span>
            </span>
            <span class="feed-time">{{ fromNow(session.lastMessageAt || session.createdAt) }}</span>
          </button>
        </div>
      </section>

      <section class="panel panel-pad models-panel">
        <div class="panel-head">
          <h2 class="section-title">
            <el-icon><Box /></el-icon>
            模型使用排行
          </h2>
          <router-link class="more-link" :to="{ name: 'models' }">
            管理模型
            <el-icon><ArrowRight /></el-icon>
          </router-link>
        </div>

        <div class="model-summary">
          <div class="model-stat">
            <strong>{{ models.length }}</strong>
            <span>全部模型</span>
          </div>
          <div class="model-stat">
            <strong>{{ chatModelCount }}</strong>
            <span>对话模型</span>
          </div>
          <div class="model-stat">
            <strong>{{ embeddingModelCount }}</strong>
            <span>嵌入模型</span>
          </div>
        </div>

        <el-skeleton v-if="loading" :rows="3" animated />
        <div v-else-if="!topModels.length" class="models-empty">
          <el-icon :size="20"><Box /></el-icon>
          <p>模型还没有被智能体使用</p>
          <span>在供应商与模型中接入模型后开始编排</span>
        </div>
        <div v-else class="usage-list">
          <div v-for="model in topModels" :key="model.id" class="usage-row">
            <div class="usage-head">
              <span class="usage-name">{{ model.name }}</span>
              <span class="usage-count">{{ model.usageCount }} 次</span>
            </div>
            <div class="usage-track">
              <span
                class="usage-fill"
                :style="{ width: `${Math.max(6, (model.usageCount / maxUsage) * 100)}%` }"
              ></span>
            </div>
            <div class="usage-sub">{{ model.supplierName }} · {{ model.model }}</div>
          </div>
        </div>
      </section>
    </div>

    <section class="panel panel-pad">
      <div class="panel-head">
        <h2 class="section-title">
          <el-icon><MagicStick /></el-icon>
          快速开始
        </h2>
        <span class="quick-note">从能力中心连接你的业务场景</span>
      </div>
      <div class="quick-grid">
        <button
          v-for="item in quickLinks"
          :key="item.title"
          type="button"
          class="quick-card"
          :class="item.tone"
          @click="router.push(item.route)"
        >
          <span class="quick-icon"><el-icon><component :is="item.icon" /></el-icon></span>
          <span class="quick-copy">
            <strong>{{ item.title }}</strong>
            <span>{{ item.desc }}</span>
          </span>
          <el-icon class="quick-arrow"><ArrowRight /></el-icon>
        </button>
      </div>
      <div class="footer-strip">
        <span>{{ stats.skills }} 个技能</span>
        <span class="dot-sep">·</span>
        <span>{{ stats.mcpServers }} 个 MCP 服务</span>
        <span class="dot-sep">·</span>
        <span>{{ stats.knowledgeBases }} 个知识库</span>
        <span class="footer-brand">King AI Platform</span>
      </div>
    </section>
  </div>
</template>

<style scoped>
.load-alert {
  margin-bottom: 16px;
}

.hero {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1.25fr) minmax(280px, 0.75fr);
  gap: 30px;
  padding: 38px 40px;
  overflow: hidden;
  background:
    radial-gradient(680px 300px at 88% 12%, rgba(124, 92, 255, 0.2), transparent 68%),
    radial-gradient(520px 260px at 70% 100%, rgba(34, 211, 238, 0.08), transparent 70%),
    var(--king-panel);
}

.hero::after {
  content: '';
  position: absolute;
  inset: 0;
  background-image: linear-gradient(rgba(255, 255, 255, 0.022) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.022) 1px, transparent 1px);
  background-size: 44px 44px;
  mask-image: radial-gradient(circle at 84% 24%, #000, transparent 62%);
  pointer-events: none;
}

.hero-copy {
  position: relative;
  z-index: 1;
}

.hero-eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 18px;
  padding: 5px 11px;
  border-radius: 999px;
  border: 1px solid var(--king-line);
  background: rgba(255, 255, 255, 0.03);
  font-size: 10.5px;
  color: var(--king-text-soft);
}

.live-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--king-green);
  box-shadow: 0 0 0 4px rgba(52, 211, 153, 0.12);
}

.hero h1 {
  max-width: 640px;
  margin: 0 0 14px;
  font-size: clamp(28px, 3vw, 40px);
  line-height: 1.28;
  letter-spacing: -0.03em;
  font-weight: 680;
}

.hero h1 span {
  display: block;
}

.hero-copy > p {
  max-width: 560px;
  margin: 0;
  font-size: 13px;
  line-height: 1.85;
  color: var(--king-text-soft);
}

.hero-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 26px;
}

.hero-actions .el-button {
  height: 42px;
  border-radius: 11px;
}

.hero-pills {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 24px;
}

.hero-visual {
  position: relative;
  display: grid;
  place-items: center;
  min-height: 260px;
}

.orbit {
  position: absolute;
  border-radius: 50%;
  border: 1px solid rgba(255, 255, 255, 0.075);
}

.orbit-1 {
  width: 170px;
  height: 170px;
  border-color: rgba(124, 92, 255, 0.22);
  animation: ring 22s linear infinite;
}

.orbit-2 {
  width: 240px;
  height: 240px;
  border-style: dashed;
  border-color: rgba(34, 211, 238, 0.14);
  animation: ring 34s linear infinite reverse;
}

.orbit-3 {
  width: 310px;
  height: 310px;
  border-color: rgba(255, 255, 255, 0.055);
}

@keyframes ring {
  to {
    transform: rotate(360deg);
  }
}

.core {
  position: relative;
  display: grid;
  place-items: center;
  width: 94px;
  height: 94px;
  border-radius: 28px;
  background: linear-gradient(140deg, rgba(139, 92, 246, 0.95), rgba(109, 107, 255, 0.85) 55%, rgba(34, 211, 238, 0.85));
  border: 1px solid rgba(255, 255, 255, 0.2);
  box-shadow: 0 20px 60px rgba(91, 69, 220, 0.45), inset 0 1px 0 rgba(255, 255, 255, 0.25);
}

.core span {
  font-size: 40px;
  font-weight: 760;
  color: #fff;
  text-shadow: 0 4px 18px rgba(0, 0, 0, 0.3);
}

.satellite {
  position: absolute;
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 6px 10px;
  border-radius: 9px;
  border: 1px solid var(--king-line-strong);
  background: rgba(11, 16, 32, 0.9);
  backdrop-filter: blur(10px);
  font-family: var(--king-mono);
  font-size: 9px;
  color: #a9b6d4;
  box-shadow: 0 10px 26px rgba(2, 4, 12, 0.3);
}

.satellite .el-icon {
  color: #8ff1ff;
}

.sat-a {
  top: 22px;
  right: 22%;
}

.sat-b {
  bottom: 46px;
  right: 4%;
}

.sat-c {
  bottom: 54px;
  left: 6%;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.lower-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) minmax(320px, 0.9fr);
  gap: 14px;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.section-title .el-icon {
  color: #9b83ff;
}

.more-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: var(--king-muted);
  transition: color 0.16s ease;
}

.more-link:hover {
  color: var(--king-text);
}

.session-feed {
  display: flex;
  flex-direction: column;
}

.feed-row {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 11px 8px;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
  transition: background 0.16s ease;
}

.feed-row:hover {
  background: rgba(255, 255, 255, 0.035);
}

.feed-row + .feed-row {
  border-top: 1px solid var(--king-line);
}

.feed-avatar {
  display: grid;
  place-items: center;
  flex: none;
  width: 32px;
  height: 32px;
  border-radius: 9px;
  font-size: 12px;
  font-weight: 650;
  color: #d8d0ff;
  background: rgba(124, 92, 255, 0.12);
  border: 1px solid rgba(124, 92, 255, 0.2);
}

.feed-main {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
  flex: 1;
}

.feed-title {
  font-size: 12.5px;
  font-weight: 520;
  color: var(--king-text-soft);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.feed-meta {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 9.5px;
  color: #5d6888;
}

.dot-sep {
  opacity: 0.6;
}

.feed-time {
  flex: none;
  font-size: 9.5px;
  color: #5d6888;
}

.model-summary {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin-bottom: 18px;
}

.model-stat {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.025);
  border: 1px solid var(--king-line);
}

.model-stat strong {
  font-size: 18px;
  font-weight: 660;
}

.model-stat span {
  font-size: 9.5px;
  color: var(--king-muted);
}

.models-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 24px 0 10px;
  color: var(--king-muted);
  text-align: center;
}

.models-empty p {
  margin: 4px 0 0;
  font-size: 11.5px;
  color: var(--king-text-soft);
}

.models-empty span {
  font-size: 9.5px;
  color: #525d7a;
}

.usage-list {
  display: flex;
  flex-direction: column;
  gap: 13px;
}

.usage-head {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 5px;
}

.usage-name {
  font-size: 11.5px;
  color: var(--king-text-soft);
  font-weight: 520;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.usage-count {
  flex: none;
  font-family: var(--king-mono);
  font-size: 9.5px;
  color: #8ff1ff;
}

.usage-track {
  height: 4px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.055);
  overflow: hidden;
}

.usage-fill {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, #7c5cff, #22d3ee);
  box-shadow: 0 0 12px rgba(124, 92, 255, 0.4);
}

.usage-sub {
  margin-top: 4px;
  font-size: 9px;
  color: #525d7a;
  font-family: var(--king-mono);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.quick-note {
  font-size: 10px;
  color: #596482;
}

.quick-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.quick-card {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 14px;
  border-radius: 13px;
  border: 1px solid var(--king-line);
  background: rgba(255, 255, 255, 0.022);
  color: inherit;
  cursor: pointer;
  text-align: left;
  transition: all 0.18s ease;
}

.quick-card:hover {
  transform: translateY(-2px);
  border-color: rgba(124, 92, 255, 0.32);
  background: rgba(124, 92, 255, 0.055);
}

.quick-icon {
  display: grid;
  place-items: center;
  flex: none;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  color: #cfc6ff;
  background: rgba(124, 92, 255, 0.12);
  border: 1px solid rgba(124, 92, 255, 0.2);
}

.quick-card.cyan .quick-icon {
  color: #a5f3ff;
  background: rgba(34, 211, 238, 0.08);
  border-color: rgba(34, 211, 238, 0.16);
}

.quick-card.pink .quick-icon {
  color: #ffc2da;
  background: rgba(255, 110, 169, 0.08);
  border-color: rgba(255, 110, 169, 0.16);
}

.quick-card.green .quick-icon {
  color: #a7f3d0;
  background: rgba(52, 211, 153, 0.07);
  border-color: rgba(52, 211, 153, 0.16);
}

.quick-copy {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
  flex: 1;
}

.quick-copy strong {
  font-size: 12px;
  font-weight: 580;
  color: var(--king-text-soft);
}

.quick-copy span {
  font-size: 9.5px;
  color: #596482;
  line-height: 1.5;
}

.quick-arrow {
  flex: none;
  font-size: 12px;
  color: #4f5a77;
}

.footer-strip {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px solid var(--king-line);
  font-size: 9.5px;
  color: #525d7a;
}

.footer-brand {
  margin-left: auto;
  font-family: var(--king-mono);
  letter-spacing: 0.06em;
  color: #657092;
}

@media (max-width: 1180px) {
  .hero {
    grid-template-columns: 1fr;
  }

  .hero-visual {
    display: none;
  }

  .stats-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .lower-grid {
    grid-template-columns: 1fr;
  }

  .quick-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .hero {
    padding: 26px 20px;
  }

  .hero h1 {
    font-size: 26px;
  }

  .stats-grid {
    grid-template-columns: 1fr;
  }

  .quick-grid {
    grid-template-columns: 1fr;
  }

  .footer-brand {
    display: none;
  }
}
</style>

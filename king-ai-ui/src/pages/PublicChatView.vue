<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Connection, Lock } from '@element-plus/icons-vue'
import BrandLogo from '@/components/BrandLogo.vue'
import ChatConsole from '@/components/chat/ChatConsole.vue'
import { agentApi } from '@/api'
import { ApiError } from '@/api/http'
import type { PublicAgentView } from '@/api/types'

const route = useRoute()
const router = useRouter()

const loading = ref(true)
const error = ref('')
const agent = ref<PublicAgentView | null>(null)

async function load() {
  const publicId = String(route.params.publicId || '')
  if (!publicId) {
    error.value = '访问地址缺少智能体标识'
    loading.value = false
    return
  }
  loading.value = true
  error.value = ''
  agent.value = null
  try {
    agent.value = await agentApi.publicInfo(publicId)
    document.title = `${agent.value.name} · King AI`
  } catch (err) {
    error.value =
      err instanceof ApiError && err.code === 404
        ? '该智能体不存在或尚未发布，请联系分享者确认链接。'
        : err instanceof ApiError
          ? err.message
          : '加载智能体失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

watch(
  () => route.params.publicId,
  () => load(),
)

onMounted(load)
</script>

<template>
  <div class="public-page">
    <div class="public-aurora"></div>
    <header class="public-topbar">
      <BrandLogo :size="30" />
      <div class="topbar-right">
        <span class="powered">
          <span class="live-dot"></span>
          AI 智能体在线
        </span>
        <el-button size="small" text @click="router.push({ name: 'login' })">
          <el-icon><Lock /></el-icon>
          管理入口
        </el-button>
      </div>
    </header>

    <main class="public-main">
      <div v-if="loading" class="state-card">
        <span class="pulse-orb"></span>
        <p>正在加载智能体…</p>
      </div>

      <div v-else-if="error" class="state-card">
        <div class="state-icon">
          <el-icon><Connection /></el-icon>
        </div>
        <h1>无法打开对话</h1>
        <p>{{ error }}</p>
        <el-button @click="router.push({ name: 'login' })">
          <el-icon><ArrowLeft /></el-icon>
          返回平台
        </el-button>
      </div>

      <div v-else-if="agent" class="public-shell">
        <div class="agent-banner">
          <div class="banner-avatar">{{ agent.name.slice(0, 1) }}</div>
          <div class="banner-copy">
            <h1>{{ agent.name }}</h1>
            <p>{{ agent.intro || '你好，有什么可以帮你？' }}</p>
          </div>
          <span class="banner-chip">King AI</span>
        </div>
        <div class="console-wrap">
          <ChatConsole
            mode="public"
            variant="public"
            :public-id="agent.publicId"
            :agent-name="agent.name"
            :agent-intro="agent.intro || ''"
          />
        </div>
      </div>
    </main>

    <footer class="public-foot">
      <span>由 King AI 平台驱动 · 内容由 AI 生成，请自行核验</span>
      <span class="mono">{{ agent?.publicId || '' }}</span>
    </footer>
  </div>
</template>

<style scoped lang="scss">
.public-page {
  position: relative;
  height: 100vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background:
    radial-gradient(900px 500px at 12% -8%, rgba(124, 92, 255, 0.2), transparent 62%),
    radial-gradient(780px 480px at 92% 0%, rgba(56, 189, 248, 0.13), transparent 60%),
    #060913;
}

.public-aurora {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background-image:
    linear-gradient(rgba(148, 163, 184, 0.028) 1px, transparent 1px),
    linear-gradient(90deg, rgba(148, 163, 184, 0.028) 1px, transparent 1px);
  background-size: 46px 46px;
  mask-image: radial-gradient(circle at 50% 20%, #000 30%, transparent 78%);
}

.public-topbar {
  position: relative;
  z-index: 2;
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 28px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.1);
  background: rgba(6, 9, 19, 0.6);
  backdrop-filter: blur(14px);
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 16px;
}

.powered {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
  color: var(--text-muted);
}

.live-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #34d399;
  box-shadow: 0 0 10px rgba(52, 211, 153, 0.9);
  animation: pulse-live 2.4s ease-in-out infinite;
}

@keyframes pulse-live {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.45;
  }
}

.public-main {
  position: relative;
  z-index: 1;
  flex: 1;
  min-height: 0;
  width: 100%;
  max-width: 1160px;
  margin: 0 auto;
  padding: 22px 28px 12px;
  display: flex;
  flex-direction: column;
}

.public-shell {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.agent-banner {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 18px;
  border-radius: 16px;
  border: 1px solid rgba(124, 92, 255, 0.26);
  background: linear-gradient(135deg, rgba(124, 92, 255, 0.16), rgba(56, 189, 248, 0.06));
  backdrop-filter: blur(12px);
}

.banner-avatar {
  width: 44px;
  height: 44px;
  flex: 0 0 44px;
  border-radius: 13px;
  display: grid;
  place-items: center;
  font-size: 19px;
  font-weight: 650;
  color: #f2edff;
  background: linear-gradient(135deg, #7c5cff, #38bdf8);
  box-shadow: 0 10px 26px -14px rgba(124, 92, 255, 0.9);
}

.banner-copy {
  flex: 1;
  min-width: 0;
}

.banner-copy h1 {
  margin: 0;
  font-size: 18px;
  font-weight: 650;
  color: var(--text-strong);
}

.banner-copy p {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--text-muted);
  line-height: 1.5;
}

.banner-chip {
  flex: 0 0 auto;
  font-size: 10px;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  padding: 4px 10px;
  border-radius: 999px;
  border: 1px solid rgba(148, 163, 184, 0.2);
  color: var(--text-faint);
}

.console-wrap {
  flex: 1;
  min-height: 0;
  border-radius: 18px;
  border: 1px solid rgba(148, 163, 184, 0.13);
  overflow: hidden;
  background: rgba(8, 12, 26, 0.66);
  backdrop-filter: blur(16px);
  box-shadow: 0 34px 90px -50px rgba(0, 0, 0, 0.95);
}

.public-foot {
  position: relative;
  z-index: 1;
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 18px;
  padding: 10px 28px 16px;
  font-size: 11px;
  color: var(--text-faint);
  flex-wrap: wrap;
}

.public-foot .mono {
  opacity: 0.7;
}

.state-card {
  margin: auto;
  width: min(480px, 100%);
  padding: 44px 34px;
  border-radius: 20px;
  border: 1px solid rgba(148, 163, 184, 0.14);
  background: rgba(10, 15, 31, 0.72);
  backdrop-filter: blur(16px);
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
}

.state-card h1 {
  margin: 0;
  font-size: 20px;
  color: var(--text-strong);
}

.state-card p {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-muted);
}

.state-icon {
  width: 54px;
  height: 54px;
  border-radius: 16px;
  display: grid;
  place-items: center;
  font-size: 24px;
  color: #fca5a5;
  background: rgba(248, 113, 113, 0.1);
  border: 1px solid rgba(248, 113, 113, 0.24);
}

.pulse-orb {
  width: 38px;
  height: 38px;
  border-radius: 50%;
  background: radial-gradient(circle at 34% 30%, #c4b5fd, #7c5cff 55%, #38bdf8);
  box-shadow: 0 0 34px rgba(124, 92, 255, 0.6);
  animation: pulse-orb 1.6s ease-in-out infinite;
}

@keyframes pulse-orb {
  0%,
  100% {
    transform: scale(0.92);
    opacity: 0.8;
  }
  50% {
    transform: scale(1.06);
    opacity: 1;
  }
}

@media (max-width: 760px) {
  .public-topbar {
    padding: 12px 16px;
  }

  .powered {
    display: none;
  }

  .public-main {
    padding: 14px 12px 8px;
  }

  .agent-banner {
    padding: 12px 14px;
  }

  .banner-chip {
    display: none;
  }

  .public-foot {
    padding: 8px 16px 12px;
  }
}
</style>

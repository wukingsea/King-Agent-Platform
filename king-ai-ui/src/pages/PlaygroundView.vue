<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ChatDotRound, EditPen, Plus } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import ChatConsole from '@/components/chat/ChatConsole.vue'
import { agentApi } from '@/api'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import type { AgentView } from '@/api/types'
import { copyText } from '@/utils/format'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const loading = ref(true)
const agents = ref<AgentView[]>([])
const selectedId = ref<number | undefined>(undefined)

const selectedAgent = computed(
  () => agents.value.find((agent) => agent.id === selectedId.value) || null,
)
const availableAgents = computed(() => agents.value.filter((agent) => agent.status === 0))

async function load() {
  loading.value = true
  try {
    const result = await agentApi.page({ page: 1, size: 200 })
    const list = result.list
    agents.value = list
    const fromQuery = Number(route.query.agentId)
    const candidate = list.find(
      (agent) => agent.id === fromQuery && agent.status === 0,
    )
    selectedId.value =
      candidate?.id ||
      list.find((agent) => agent.status === 0)?.id ||
      undefined
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载智能体失败')
  } finally {
    loading.value = false
  }
}

function onAgentChange(value: number) {
  selectedId.value = value
  router.replace({ name: 'playground', query: { agentId: value } })
}

function editAgent() {
  router.push({ name: 'agents', query: { edit: selectedAgent.value?.id } })
}

async function copyPublicLink() {
  const agent = selectedAgent.value
  if (!agent?.publicId || agent.publishStatus !== 1) {
    ElMessage.warning('该智能体尚未发布，暂无公开链接')
    return
  }
  await copyText(`${window.location.origin}${window.location.pathname}#/chat/${agent.publicId}`)
  ElMessage.success('公开对话链接已复制')
}

onMounted(load)
</script>

<template>
  <div class="page playground-page">
    <PageHeader
      title="对话测试"
      description="在发布前验证系统指令、知识库检索、MCP 工具与技能是否符合预期。支持随时停止与多轮工具调用。"
      eyebrow="Playground"
    >
      <template #actions>
        <template v-if="auth.isAdmin && selectedAgent?.publicId && selectedAgent.publishStatus === 1">
          <el-button @click="copyPublicLink">复制公开链接</el-button>
        </template>
        <el-button v-if="selectedAgent && auth.canEditSpace" @click="editAgent">
          <el-icon><EditPen /></el-icon>
          编辑智能体
        </el-button>
        <el-select
          v-if="availableAgents.length"
          :model-value="selectedId"
          class="agent-select"
          filterable
          placeholder="选择智能体"
          @change="onAgentChange"
        >
          <el-option
            v-for="agent in availableAgents"
            :key="agent.id"
            :label="agent.name"
            :value="agent.id"
          >
            <div class="agent-option">
              <span>{{ agent.name }}</span>
              <span class="option-model">{{ agent.modelName }}</span>
            </div>
          </el-option>
        </el-select>
      </template>
    </PageHeader>

    <div v-loading="loading" class="playground-shell">
      <ChatConsole
        v-if="selectedAgent"
        :key="selectedAgent.id"
        mode="admin"
        variant="console"
        :agent-id="selectedAgent.id"
        :agent-name="selectedAgent.name"
        :agent-intro="selectedAgent.intro"
      />
      <div v-else-if="!loading" class="panel">
        <EmptyState
          title="还没有可对话的智能体"
          description="创建一个智能体并绑定对话模型，就可以在这里进行流式对话测试。"
        >
          <template #icon><el-icon :size="22"><ChatDotRound /></el-icon></template>
          <template #action>
            <el-button
              v-if="auth.canEditSpace"
              type="primary"
              @click="router.push({ name: 'agents', query: { create: '1' } })"
            >
              <el-icon><Plus /></el-icon>
              新建智能体
            </el-button>
          </template>
        </EmptyState>
      </div>
    </div>
  </div>
</template>

<style scoped>
.playground-page {
  height: calc(100vh - 142px);
  min-height: 560px;
}

.playground-shell {
  flex: 1;
  min-height: 0;
}

.playground-shell > .panel {
  height: 100%;
}

.agent-select {
  width: 210px;
}

.agent-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
}

.option-model {
  font-size: 9.5px;
  color: var(--king-muted);
  font-family: var(--king-mono);
}

@media (max-width: 640px) {
  .playground-page {
    height: calc(100vh - 126px);
    min-height: 480px;
  }

  .agent-select {
    width: 100%;
  }
}
</style>

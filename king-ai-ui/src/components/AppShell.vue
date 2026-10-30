<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowDown,
  Box,
  ChatDotRound,
  Collection,
  Connection,
  Cpu,
  DocumentChecked,
  Key,
  Lock,
  MagicStick,
  Memo,
  Menu,
  Monitor,
  Odometer,
  OfficeBuilding,
  SwitchButton,
  Tickets,
  User,
} from '@element-plus/icons-vue'
import BrandLogo from './BrandLogo.vue'
import LoginSessionsDialog from './LoginSessionsDialog.vue'
import MfaSettingsDialog from './MfaSettingsDialog.vue'
import { useAuthStore } from '@/stores/auth'
import { authApi, systemApi } from '@/api'
import { ApiError } from '@/api/http'
import type { SpaceRole } from '@/api/types'
import { PASSWORD_PATTERN, PASSWORD_RULE_MESSAGE } from '@/utils/password'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const mobileOpen = ref(false)
const online = ref(true)
const pwdVisible = ref(false)
const pwdLoading = ref(false)
const mfaVisible = ref(false)
const sessionsVisible = ref(false)
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirm: '' })

interface NavItem {
  name: string
  label: string
  icon: Component
  admin?: boolean
  roles?: SpaceRole[]
}

interface NavGroup {
  label: string
  items: NavItem[]
}

const navGroups = computed<NavGroup[]>(() => [
  {
    label: '工作台',
    items: [
      { name: 'dashboard', label: '概览', icon: Odometer },
      { name: 'agents', label: '智能体', icon: Cpu },
      { name: 'playground', label: '对话测试', icon: ChatDotRound },
      { name: 'records', label: '对话记录', icon: Memo },
    ],
  },
  {
    label: '能力中心',
    items: [
      { name: 'knowledge', label: '知识库', icon: Collection },
      { name: 'mcp', label: 'MCP 工具', icon: Connection },
      { name: 'skills', label: '技能', icon: MagicStick },
    ],
  },
  {
    label: '模型与接入',
    items: [{ name: 'models', label: '供应商与模型', icon: Box }],
  },
  {
    label: '治理',
    items: [
      {
        name: 'compliance',
        label: '合规中心',
        icon: DocumentChecked,
        roles: ['OWNER', 'ADMIN', 'AUDITOR'],
      },
      {
        name: 'audit-logs',
        label: '审计日志',
        icon: Tickets,
        roles: ['OWNER', 'ADMIN', 'AUDITOR'],
      },
    ],
  },
  {
    label: '系统',
    items: [
      { name: 'users', label: '用户管理', icon: User, admin: true },
      { name: 'spaces', label: '空间管理', icon: OfficeBuilding, admin: true },
      { name: 'license', label: '平台许可', icon: Key, admin: true },
    ],
  },
])

const visibleGroups = computed(() =>
  navGroups.value
    .map((group) => ({
      ...group,
      items: group.items.filter(
        (item) =>
          (!item.admin || auth.isAdmin) &&
          (!item.roles || auth.hasSpaceRole(item.roles)),
      ),
    }))
    .filter((group) => group.items.length > 0),
)

const pageTitle = computed(() => (route.meta.title as string) || '控制台')
const avatarText = computed(() => auth.displayName.slice(0, 1).toUpperCase())

async function checkHealth() {
  try {
    const data = await systemApi.health()
    online.value = data.status === 'UP'
  } catch {
    online.value = false
  }
}

function onSpaceChange(value: number) {
  if (value === auth.currentSpaceId) return
  auth.switchSpace(value)
  ElMessage.success('已切换空间，正在刷新工作台')
  window.setTimeout(() => window.location.reload(), 280)
}

function handleUserCommand(command: string) {
  if (command === 'password') {
    pwdForm.oldPassword = ''
    pwdForm.newPassword = ''
    pwdForm.confirm = ''
    pwdVisible.value = true
  }
  if (command === 'mfa') {
    mfaVisible.value = true
  }
  if (command === 'sessions') {
    sessionsVisible.value = true
  }
  if (command === 'logout') {
    auth.logout().finally(() => router.replace({ name: 'login' }))
  }
}

async function submitPassword() {
  if (!pwdForm.oldPassword) {
    ElMessage.warning('请输入当前密码')
    return
  }
  if (!PASSWORD_PATTERN.test(pwdForm.newPassword)) {
    ElMessage.warning(PASSWORD_RULE_MESSAGE)
    return
  }
  if (pwdForm.newPassword !== pwdForm.confirm) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  pwdLoading.value = true
  try {
    await authApi.changePassword({
      oldPassword: pwdForm.oldPassword,
      newPassword: pwdForm.newPassword,
    })
    pwdVisible.value = false
    ElMessage.success('密码已更新，请重新登录')
    auth.clearLocal()
    router.replace({ name: 'login' })
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '修改密码失败')
  } finally {
    pwdLoading.value = false
  }
}

watch(
  () => route.fullPath,
  () => {
    mobileOpen.value = false
  },
)

let timer: number | undefined
onMounted(() => {
  checkHealth()
  timer = window.setInterval(checkHealth, 60_000)
})
onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
})
</script>

<template>
  <div class="shell">
    <aside class="sidebar" :class="{ open: mobileOpen }">
      <div class="brand-row">
        <BrandLogo :size="36" />
      </div>

      <div class="space-switcher">
        <div class="space-label">当前空间</div>
        <el-select
          :model-value="auth.currentSpaceId || undefined"
          class="space-select"
          size="small"
          :teleported="true"
          @change="onSpaceChange"
        >
          <el-option
            v-for="space in auth.spaces"
            :key="space.id"
            :label="space.name"
            :value="space.id"
          />
        </el-select>
      </div>

      <nav class="nav scroll-thin">
        <div v-for="group in visibleGroups" :key="group.label" class="nav-group">
          <div class="nav-group-label">{{ group.label }}</div>
          <router-link
            v-for="item in group.items"
            :key="item.name"
            :to="{ name: item.name }"
            class="nav-item"
            active-class="active"
          >
            <el-icon class="nav-icon"><component :is="item.icon" /></el-icon>
            <span>{{ item.label }}</span>
            <span v-if="item.name === 'agents'" class="nav-glow"></span>
          </router-link>
        </div>
      </nav>

      <div class="sidebar-foot">
        <div class="foot-status">
          <span class="status-dot" :class="{ off: !online }"></span>
          <span>{{ online ? '服务运行正常' : '服务连接异常' }}</span>
          <span class="version">v1.0</span>
        </div>
        <el-dropdown trigger="click" placement="top-start" @command="handleUserCommand">
          <button class="user-card">
            <span class="avatar">{{ avatarText }}</span>
            <span class="user-meta">
              <span class="user-name">{{ auth.displayName }}</span>
              <span class="user-role">{{ auth.isAdmin ? '平台管理员' : '空间成员' }}</span>
            </span>
            <el-icon class="chevron"><ArrowDown /></el-icon>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="password">
                <el-icon><Lock /></el-icon>
                修改密码
              </el-dropdown-item>
              <el-dropdown-item command="mfa">
                <el-icon><Key /></el-icon>
                账号安全
              </el-dropdown-item>
              <el-dropdown-item command="sessions">
                <el-icon><Monitor /></el-icon>
                登录设备
              </el-dropdown-item>
              <el-dropdown-item command="logout" divided>
                <el-icon><SwitchButton /></el-icon>
                退出登录
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </aside>

    <div v-if="mobileOpen" class="sidebar-mask" @click="mobileOpen = false"></div>

    <div class="main">
      <header class="topbar">
        <button class="menu-btn" type="button" aria-label="打开菜单" @click="mobileOpen = true">
          <el-icon><Menu /></el-icon>
        </button>
        <div class="crumb">
          <span class="crumb-root">King AI</span>
          <span class="crumb-sep">/</span>
          <strong>{{ pageTitle }}</strong>
        </div>
        <div class="top-actions">
          <div class="health" :class="{ off: !online }">
            <span class="status-dot" :class="{ off: !online }"></span>
            <span class="health-text">{{ online ? '在线' : '离线' }}</span>
          </div>
          <el-dropdown trigger="click" @command="handleUserCommand">
            <button class="top-avatar" type="button" :title="auth.displayName">
              {{ avatarText }}
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="password">
                  <el-icon><Lock /></el-icon>
                  修改密码
                </el-dropdown-item>
                <el-dropdown-item command="mfa">
                  <el-icon><Key /></el-icon>
                  账号安全
                </el-dropdown-item>
                <el-dropdown-item command="logout" divided>
                  <el-icon><SwitchButton /></el-icon>
                  退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="content scroll-thin">
        <div class="content-inner">
          <router-view v-slot="{ Component: PageComponent }">
            <transition name="view-fade" mode="out-in">
              <component :is="PageComponent" />
            </transition>
          </router-view>
        </div>
      </main>
    </div>

    <el-dialog v-model="pwdVisible" title="修改登录密码" width="440px">
      <el-form label-position="top">
        <el-form-item label="当前密码">
          <el-input v-model="pwdForm.oldPassword" type="password" show-password autocomplete="current-password" />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input
            v-model="pwdForm.newPassword"
            type="password"
            show-password
            autocomplete="new-password"
            :placeholder="PASSWORD_RULE_MESSAGE"
          />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input v-model="pwdForm.confirm" type="password" show-password autocomplete="new-password" />
        </el-form-item>
      </el-form>
      <div class="pwd-hint">修改成功后当前登录状态会失效，需要重新登录。</div>
      <template #footer>
        <el-button @click="pwdVisible = false">取消</el-button>
        <el-button type="primary" :loading="pwdLoading" @click="submitPassword">确认修改</el-button>
      </template>
    </el-dialog>

    <MfaSettingsDialog v-model="mfaVisible" />
    <LoginSessionsDialog v-model="sessionsVisible" />
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  min-height: 100vh;
}

.sidebar {
  position: fixed;
  inset: 0 auto 0 0;
  z-index: 40;
  display: flex;
  flex-direction: column;
  width: 248px;
  background: rgba(7, 11, 22, 0.86);
  border-right: 1px solid var(--king-line);
  backdrop-filter: blur(22px);
}

.brand-row {
  padding: 22px 20px 16px;
}

.space-switcher {
  margin: 0 14px 14px;
  padding: 10px 12px 12px;
  border-radius: 14px;
  background: linear-gradient(140deg, rgba(124, 92, 255, 0.1), rgba(34, 211, 238, 0.045));
  border: 1px solid var(--king-line);
}

.space-label {
  margin-bottom: 6px;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.14em;
  color: var(--king-muted);
}

.space-select {
  width: 100%;
}

.space-select :deep(.el-select__wrapper) {
  background: transparent;
  border: none;
  padding-left: 0;
}

.space-select :deep(.el-select__selected-item) {
  font-size: 13px;
  font-weight: 560;
  color: var(--king-text);
}

.nav {
  flex: 1;
  overflow-y: auto;
  padding: 4px 12px 20px;
}

.nav-group + .nav-group {
  margin-top: 18px;
}

.nav-group-label {
  padding: 0 12px 7px;
  font-size: 9.5px;
  font-weight: 650;
  letter-spacing: 0.18em;
  color: #5d6888;
  text-transform: uppercase;
}

.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 11px;
  height: 40px;
  padding: 0 12px;
  margin: 2px 0;
  border-radius: 11px;
  color: #929dbb;
  font-size: 13px;
  font-weight: 520;
  transition: background 0.18s ease, color 0.18s ease;
}

.nav-item:hover {
  background: rgba(255, 255, 255, 0.045);
  color: var(--king-text);
}

.nav-icon {
  font-size: 17px;
  color: #7582a4;
  transition: color 0.18s ease;
}

.nav-item.active {
  color: #fff;
  background: linear-gradient(100deg, rgba(124, 92, 255, 0.26), rgba(34, 211, 238, 0.07));
  border: 1px solid rgba(124, 92, 255, 0.25);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.06);
}

.nav-item.active .nav-icon {
  color: #c4b5fd;
}

.nav-glow {
  display: none;
}

.sidebar-foot {
  padding: 14px;
  border-top: 1px solid var(--king-line);
}

.foot-status {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 0 6px 10px;
  font-size: 10.5px;
  color: var(--king-muted);
}

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--king-green);
  box-shadow: 0 0 0 4px rgba(52, 211, 153, 0.13);
}

.status-dot.off {
  background: var(--king-red);
  box-shadow: 0 0 0 4px rgba(251, 113, 133, 0.12);
}

.version {
  margin-left: auto;
  font-family: var(--king-mono);
  opacity: 0.7;
}

.user-card {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 9px 10px;
  border-radius: 12px;
  border: 1px solid var(--king-line);
  background: rgba(255, 255, 255, 0.035);
  color: var(--king-text);
  cursor: pointer;
  text-align: left;
  transition: background 0.18s ease;
}

.user-card:hover {
  background: rgba(255, 255, 255, 0.065);
}

.avatar,
.top-avatar {
  display: grid;
  place-items: center;
  flex: none;
  width: 32px;
  height: 32px;
  border-radius: 10px;
  font-size: 13px;
  font-weight: 680;
  color: #fff;
  background: linear-gradient(135deg, #8b5cf6, #22d3ee);
  box-shadow: 0 6px 16px rgba(124, 92, 255, 0.3);
}

.user-meta {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
  flex: 1;
}

.user-name {
  font-size: 12.5px;
  font-weight: 580;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-role {
  font-size: 10px;
  color: var(--king-muted);
}

.chevron {
  color: var(--king-muted);
  font-size: 12px;
}

.main {
  flex: 1;
  min-width: 0;
  margin-left: 248px;
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

.topbar {
  position: sticky;
  top: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  gap: 14px;
  height: 66px;
  padding: 0 30px;
  background: rgba(7, 10, 23, 0.72);
  border-bottom: 1px solid var(--king-line);
  backdrop-filter: blur(20px);
}

.menu-btn {
  display: none;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 9px;
  border: 1px solid var(--king-line);
  background: rgba(255, 255, 255, 0.04);
  color: var(--king-text);
}

.crumb {
  display: flex;
  align-items: center;
  gap: 9px;
  font-size: 12.5px;
  color: var(--king-muted);
}

.crumb-root {
  letter-spacing: 0.03em;
}

.crumb-sep {
  opacity: 0.4;
}

.crumb strong {
  color: var(--king-text-soft);
  font-weight: 560;
}

.top-actions {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-left: auto;
}

.health {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 11px;
  border-radius: 999px;
  border: 1px solid rgba(52, 211, 153, 0.18);
  background: rgba(52, 211, 153, 0.06);
  font-size: 11px;
  color: #8ce9c4;
}

.health.off {
  border-color: rgba(251, 113, 133, 0.2);
  background: rgba(251, 113, 133, 0.07);
  color: #ffabb7;
}

.top-avatar {
  width: 32px;
  height: 32px;
  border: none;
  cursor: pointer;
}

.content {
  flex: 1;
  overflow-x: hidden;
}

.content-inner {
  width: 100%;
  max-width: 1440px;
  margin: 0 auto;
  padding: 28px 32px 48px;
}

.sidebar-mask {
  display: none;
}

.view-fade-enter-active,
.view-fade-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.view-fade-enter-from {
  opacity: 0;
  transform: translateY(6px);
}

.view-fade-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

.pwd-hint {
  margin-top: -2px;
  padding: 10px 12px;
  border-radius: 10px;
  background: rgba(251, 191, 36, 0.07);
  border: 1px solid rgba(251, 191, 36, 0.16);
  color: #fcd88d;
  font-size: 11.5px;
}

@media (max-width: 960px) {
  .sidebar {
    transform: translateX(-102%);
    transition: transform 0.24s ease;
    box-shadow: 30px 0 80px rgba(0, 0, 0, 0.5);
  }

  .sidebar.open {
    transform: translateX(0);
  }

  .main {
    margin-left: 0;
  }

  .menu-btn {
    display: inline-flex;
  }

  .sidebar-mask {
    display: block;
    position: fixed;
    inset: 0;
    z-index: 35;
    background: rgba(3, 6, 16, 0.6);
    backdrop-filter: blur(3px);
  }

  .topbar {
    padding: 0 18px;
  }
}

@media (max-width: 640px) {
  .content-inner {
    padding: 20px 16px 40px;
  }

  .health-text {
    display: none;
  }

  .health {
    padding: 6px 8px;
  }

  .crumb-root,
  .crumb-sep {
    display: none;
  }
}
</style>

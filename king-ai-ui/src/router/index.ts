import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/pages/LoginView.vue'),
    meta: { title: '登录' },
  },
  {
    path: '/change-password',
    name: 'change-password',
    component: () => import('@/pages/ChangePasswordView.vue'),
    meta: { title: '修改初始密码' },
  },
  {
    path: '/',
    component: () => import('@/components/AppShell.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/pages/DashboardView.vue'),
        meta: { title: '概览' },
      },
      {
        path: 'agents',
        name: 'agents',
        component: () => import('@/pages/AgentsView.vue'),
        meta: { title: '智能体' },
      },
      {
        path: 'playground',
        name: 'playground',
        component: () => import('@/pages/PlaygroundView.vue'),
        meta: { title: '对话测试' },
      },
      {
        path: 'records',
        name: 'records',
        component: () => import('@/pages/RecordsView.vue'),
        meta: { title: '对话记录' },
      },
      {
        path: 'knowledge',
        name: 'knowledge',
        component: () => import('@/pages/KnowledgeView.vue'),
        meta: { title: '知识库' },
      },
      {
        path: 'mcp',
        name: 'mcp',
        component: () => import('@/pages/McpView.vue'),
        meta: { title: 'MCP 工具' },
      },
      {
        path: 'skills',
        name: 'skills',
        component: () => import('@/pages/SkillsView.vue'),
        meta: { title: '技能' },
      },
      {
        path: 'models',
        name: 'models',
        component: () => import('@/pages/ModelsView.vue'),
        meta: { title: '供应商与模型' },
      },
      {
        path: 'users',
        name: 'users',
        component: () => import('@/pages/UsersView.vue'),
        meta: { title: '用户管理', admin: true },
      },
      {
        path: 'spaces',
        name: 'spaces',
        component: () => import('@/pages/SpacesView.vue'),
        meta: { title: '业务空间' },
      },
    ],
  },
  {
    path: '/chat/:publicId',
    name: 'public-chat',
    component: () => import('@/pages/PublicChatView.vue'),
    meta: { title: '智能体对话', public: true },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/pages/NotFoundView.vue'),
    meta: { title: '页面不存在', public: true },
  },
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  if (to.meta.public) return true

  if (to.name === 'login') {
    if (!auth.checked) await auth.ensureSession()
    if (auth.authenticated && auth.user) {
      return auth.user.mustChangePassword === 1
        ? { name: 'change-password' }
        : { name: 'dashboard' }
    }
    return true
  }

  if (!auth.checked) await auth.ensureSession()

  if (!auth.authenticated || !auth.user) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  if (auth.user.mustChangePassword === 1) {
    if (to.name === 'change-password') return true
    return { name: 'change-password' }
  }

  if (to.name === 'change-password') {
    return { name: 'dashboard' }
  }

  if (to.meta.admin && !auth.isAdmin) {
    return { name: 'dashboard' }
  }

  return true
})

router.afterEach((to) => {
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} · King AI` : 'King AI · 智能体平台'
})

export default router

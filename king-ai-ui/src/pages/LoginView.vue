<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { ArrowRight, Connection, Key, Lock, User } from '@element-plus/icons-vue'
import BrandLogo from '@/components/BrandLogo.vue'
import { useAuthStore } from '@/stores/auth'
import { authApi, systemApi } from '@/api'
import { ApiError } from '@/api/http'
import type { SsoConfig } from '@/api/types'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const demoEnabled = ref(false)
const sso = ref<SsoConfig | null>(null)
const ssoError = ref('')
const mfaChallenge = ref<string | null>(null)
const mfaCode = ref('')
const submittingMfa = ref(false)

onMounted(async () => {
  if (typeof route.query.sso_error === 'string') {
    ssoError.value = route.query.sso_error
  }
  const [meta, ssoConfig] = await Promise.allSettled([
    systemApi.meta(),
    authApi.ssoConfig(),
  ])
  demoEnabled.value =
    meta.status === 'fulfilled' ? meta.value.demoEnabled : false
  sso.value = ssoConfig.status === 'fulfilled' ? ssoConfig.value : null
})

const formRef = ref<FormInstance>()
const form = reactive({ username: '', password: '' })
const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

const features = [
  { title: 'Agent 编排', desc: '模型、指令、知识、工具与技能自由组合' },
  { title: 'RAG 知识库', desc: '文档切片、向量检索、回答自动附带引用' },
  { title: 'MCP 工具', desc: '统一发现与调用外部工具，多轮自动执行' },
  { title: '空间隔离', desc: '多租户空间、角色权限与加密密钥管理' },
]

function completeLogin() {
  ElMessage.success('登录成功，欢迎回来')
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard'
  router.replace(redirect)
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  try {
    const data = await auth.login({ username: form.username.trim(), password: form.password })
    if (data.mfaRequired) {
      mfaChallenge.value = data.mfaChallenge || null
      mfaCode.value = ''
      ElMessage.info('请输入身份验证器中的动态验证码')
      return
    }
    completeLogin()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '登录失败，请稍后重试')
  }
}

async function submitMfa() {
  const challenge = mfaChallenge.value
  const code = mfaCode.value.trim()
  if (!challenge) return
  if (!code) {
    ElMessage.warning('请输入验证码或恢复码')
    return
  }
  submittingMfa.value = true
  try {
    await auth.verifyMfa(challenge, code)
    completeLogin()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '验证失败，请稍后重试')
  } finally {
    submittingMfa.value = false
  }
}

function backToPassword() {
  mfaChallenge.value = null
  mfaCode.value = ''
  form.password = ''
}

function startSso() {
  if (!sso.value?.loginUrl) {
    ElMessage.warning('SSO 登录暂不可用，请联系管理员')
    return
  }
  window.location.assign(sso.value.loginUrl)
}

function fillDemo() {
  form.username = 'admin'
  form.password = 'admin123'
}
</script>

<template>
  <div class="login-page">
    <div class="grid-bg"></div>
    <div class="orb orb-a"></div>
    <div class="orb orb-b"></div>
    <div class="orb orb-c"></div>

    <header class="login-brand">
      <BrandLogo :size="40" />
      <span class="edition">Open Agent Platform</span>
    </header>

    <section class="hero">
      <div class="badge">
        <span class="dot"></span>
        Agent OS · 云版 MVP
      </div>
      <h1>
        让每个团队拥有
        <span class="gradient-text">自己的 AI 智能体</span>
      </h1>
      <p class="lead">
        从模型接入到 Agent 发布，King AI 把知识库、MCP 工具与技能编排进同一个工作台。
        流式对话、可回溯的会话记录，让智能体真正走进业务流程。
      </p>

      <div class="feature-grid">
        <div v-for="feature in features" :key="feature.title" class="feature">
          <span class="feature-mark"></span>
          <div>
            <strong>{{ feature.title }}</strong>
            <p>{{ feature.desc }}</p>
          </div>
        </div>
      </div>

      <div class="hero-foot">
        <div class="mini-stat">
          <strong>SSE</strong>
          <span>流式推理与工具事件</span>
        </div>
        <div class="mini-stat">
          <strong>RAG</strong>
          <span>多知识库混合检索</span>
        </div>
        <div class="mini-stat">
          <strong>RBAC</strong>
          <span>空间级资源隔离</span>
        </div>
      </div>
    </section>

    <section class="auth-wrap">
      <div class="auth-card">
        <div class="card-head">
          <div class="card-eyebrow">{{ mfaChallenge ? 'TWO-FACTOR' : 'WELCOME BACK' }}</div>
          <h2>{{ mfaChallenge ? '二次验证' : '登录 King AI' }}</h2>
          <p>
            {{ mfaChallenge ? '输入身份验证器中的 6 位动态验证码，或使用恢复码' : '使用平台账号进入智能体工作台' }}
          </p>
        </div>

        <div v-if="ssoError" class="sso-error">
          <el-icon><Key /></el-icon>
          <span>{{ ssoError }}</span>
        </div>

        <el-form
          v-if="!mfaChallenge"
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          size="large"
          @keyup.enter="submit"
        >
          <el-form-item label="用户名" prop="username">
            <el-input v-model="form.username" placeholder="请输入用户名" autocomplete="username">
              <template #prefix>
                <el-icon><User /></el-icon>
              </template>
            </el-input>
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              show-password
              placeholder="请输入登录密码"
              autocomplete="current-password"
            >
              <template #prefix>
                <el-icon><Lock /></el-icon>
              </template>
            </el-input>
          </el-form-item>

          <el-button
            class="login-btn"
            type="primary"
            size="large"
            :loading="auth.loading"
            @click="submit"
          >
            进入工作台
            <el-icon class="btn-arrow"><ArrowRight /></el-icon>
          </el-button>
        </el-form>

        <el-form v-else label-position="top" size="large" @keyup.enter="submitMfa">
          <el-form-item label="验证码">
            <el-input
              v-model="mfaCode"
              placeholder="6 位动态验证码或恢复码"
              autocomplete="one-time-code"
              maxlength="32"
            >
              <template #prefix>
                <el-icon><Key /></el-icon>
              </template>
            </el-input>
          </el-form-item>
          <el-button
            class="login-btn"
            type="primary"
            size="large"
            :loading="submittingMfa"
            @click="submitMfa"
          >
            验证并进入
            <el-icon class="btn-arrow"><ArrowRight /></el-icon>
          </el-button>
          <el-button class="back-btn" text size="small" @click="backToPassword">
            返回账号密码登录
          </el-button>
        </el-form>

        <template v-if="!mfaChallenge">
          <el-button
            v-if="sso?.enabled"
            class="sso-btn"
            size="large"
            @click="startSso"
          >
            <el-icon><Connection /></el-icon>
            使用企业 SSO 登录
          </el-button>

          <div v-if="demoEnabled" class="demo-box">
            <div class="demo-copy">
              <span class="demo-label">演示账号</span>
              <span class="demo-value">admin / admin123</span>
            </div>
            <el-button text size="small" @click="fillDemo">一键填入</el-button>
          </div>

          <p v-if="demoEnabled" class="card-foot">
            首次启动会自动创建演示空间、智能体、知识库与 MCP 服务。
          </p>
          <p v-else class="card-foot">
            账号由平台管理员统一创建，如需访问请联系所在空间的管理员。
          </p>
        </template>
      </div>

      <p class="legal">King AI Platform · 开放智能体平台 · v1.0.0</p>
    </section>
  </div>
</template>

<style scoped>
.login-page {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(380px, 520px);
  align-items: center;
  gap: clamp(32px, 6vw, 110px);
  min-height: 100vh;
  padding: 96px clamp(28px, 7vw, 110px) 56px;
  overflow: hidden;
}

.grid-bg {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(var(--k-fill-soft) 1px, transparent 1px),
    linear-gradient(90deg, var(--k-fill-soft) 1px, transparent 1px);
  background-size: 56px 56px;
  mask-image: radial-gradient(circle at 34% 40%, #000 0%, transparent 68%);
  pointer-events: none;
}

.orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(85px);
  pointer-events: none;
  animation: drift 16s ease-in-out infinite alternate;
}

.orb-a {
  top: -12%;
  left: 4%;
  width: 520px;
  height: 520px;
  background: rgba(124, 92, 255, 0.24);
}

.orb-b {
  right: -6%;
  bottom: -18%;
  width: 560px;
  height: 560px;
  background: rgba(34, 211, 238, 0.13);
  animation-delay: -6s;
}

.orb-c {
  top: 34%;
  left: 42%;
  width: 300px;
  height: 300px;
  background: rgba(255, 110, 169, 0.08);
  animation-delay: -10s;
}

@keyframes drift {
  from {
    transform: translate3d(0, 0, 0) scale(1);
  }
  to {
    transform: translate3d(26px, -22px, 0) scale(1.08);
  }
}

.login-brand {
  position: absolute;
  top: 32px;
  left: clamp(28px, 7vw, 110px);
  display: flex;
  align-items: center;
  gap: 18px;
  z-index: 2;
}

.edition {
  padding: 5px 11px;
  border-radius: 999px;
  border: 1px solid var(--king-line);
  background: var(--k-fill-soft);
  font-size: 10px;
  letter-spacing: 0.12em;
  color: var(--king-muted);
  text-transform: uppercase;
}

.hero {
  position: relative;
  z-index: 1;
  max-width: 660px;
  animation: rise 0.6s ease both;
}

@keyframes rise {
  from {
    opacity: 0;
    transform: translateY(18px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 28px;
  padding: 0 12px;
  border-radius: 999px;
  border: 1px solid rgba(124, 92, 255, 0.3);
  background: rgba(124, 92, 255, 0.1);
  color: var(--k-violet-soft);
  font-size: 11px;
  letter-spacing: 0.04em;
}

.badge .dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--king-cyan);
  box-shadow: 0 0 10px rgba(34, 211, 238, 0.8);
}

.hero h1 {
  margin: 24px 0 18px;
  font-size: clamp(38px, 4.6vw, 62px);
  line-height: 1.16;
  letter-spacing: -0.035em;
  font-weight: 700;
}

.hero h1 span {
  display: block;
}

.lead {
  max-width: 560px;
  margin: 0;
  font-size: 14.5px;
  line-height: 1.85;
  color: var(--king-text-soft);
}

.feature-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px 26px;
  margin-top: 38px;
}

.feature {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.feature-mark {
  flex: none;
  width: 7px;
  height: 7px;
  margin-top: 8px;
  border-radius: 2px;
  transform: rotate(45deg);
  background: linear-gradient(135deg, #a78bfa, #22d3ee);
  box-shadow: 0 0 12px rgba(124, 92, 255, 0.6);
}

.feature strong {
  display: block;
  margin-bottom: 5px;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--king-text);
}

.feature p {
  margin: 0;
  font-size: 12px;
  line-height: 1.65;
  color: var(--king-muted);
}

.hero-foot {
  display: flex;
  gap: 34px;
  margin-top: 42px;
  padding-top: 24px;
  border-top: 1px solid var(--king-line);
}

.mini-stat {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.mini-stat strong {
  font-family: var(--king-mono);
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.06em;
  color: var(--k-violet-soft);
}

.mini-stat span {
  font-size: 10.5px;
  color: var(--king-muted);
}

.auth-wrap {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 18px;
  animation: rise 0.6s 0.1s ease both;
}

.auth-card {
  position: relative;
  padding: 38px 36px 30px;
  border-radius: 26px;
  border: 1px solid var(--king-line-strong);
  background:
    linear-gradient(160deg, var(--k-fill), transparent 42%),
    rgba(12, 17, 33, 0.82);
  box-shadow: 0 40px 100px rgba(2, 4, 12, 0.55);
  backdrop-filter: blur(26px);
}

.auth-card::before {
  content: '';
  position: absolute;
  inset: -1px;
  border-radius: inherit;
  padding: 1px;
  background: linear-gradient(140deg, rgba(139, 92, 246, 0.5), transparent 30%, transparent 70%, rgba(34, 211, 238, 0.24));
  -webkit-mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
  -webkit-mask-composite: xor;
  mask-composite: exclude;
  pointer-events: none;
}

.card-head {
  margin-bottom: 26px;
}

.card-eyebrow {
  margin-bottom: 10px;
  font-family: var(--king-mono);
  font-size: 9.5px;
  letter-spacing: 0.24em;
  color: #8f7dff;
}

.card-head h2 {
  margin: 0 0 8px;
  font-size: 25px;
  font-weight: 680;
  letter-spacing: -0.02em;
}

.card-head p {
  margin: 0;
  font-size: 12.5px;
  color: var(--king-muted);
}

.login-btn {
  width: 100%;
  height: 46px;
  margin-top: 8px;
  font-size: 14px;
  font-weight: 600;
  border-radius: 12px;
  background: linear-gradient(120deg, #7c5cff 0%, #6f6bff 55%, #22b8d4 100%);
  border: none;
}

.btn-arrow {
  margin-left: 8px;
}

.back-btn {
  width: 100%;
  margin: 10px 0 0;
  color: var(--king-muted);
}

.sso-error {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 18px;
  padding: 10px 12px;
  border-radius: 10px;
  border: 1px solid rgba(248, 113, 113, 0.28);
  background: rgba(248, 113, 113, 0.09);
  color: var(--k-red);
  font-size: 12px;
  line-height: 1.5;
}

.sso-btn {
  width: 100%;
  height: 44px;
  margin-top: 18px;
  border-radius: 12px;
  gap: 8px;
  border-color: rgba(124, 92, 255, 0.36);
  background: rgba(124, 92, 255, 0.1);
  color: var(--k-violet-soft);
}

.sso-btn:hover {
  border-color: rgba(124, 92, 255, 0.6);
  background: rgba(124, 92, 255, 0.18);
  color: #fff;
}

.demo-box {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 22px;
  padding: 12px 14px;
  border-radius: 12px;
  border: 1px dashed var(--king-line-strong);
  background: var(--k-fill-soft);
}

.demo-copy {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.demo-label {
  font-size: 10px;
  letter-spacing: 0.08em;
  color: var(--king-muted);
}

.demo-value {
  font-family: var(--king-mono);
  font-size: 12px;
  color: var(--king-text-soft);
}

.card-foot {
  margin: 20px 0 0;
  font-size: 11px;
  line-height: 1.7;
  color: #66718f;
}

.legal {
  margin: 0;
  text-align: center;
  font-size: 10.5px;
  color: #525d7a;
}

.auth-card :deep(.el-form-item) {
  margin-bottom: 20px;
}

.auth-card :deep(.el-form-item__label) {
  padding-bottom: 6px;
  font-size: 12px;
}

@media (max-width: 1024px) {
  .login-page {
    grid-template-columns: 1fr;
    justify-items: center;
    padding-top: 120px;
  }

  .hero {
    display: none;
  }

  .auth-wrap {
    width: min(100%, 460px);
  }

  .login-brand {
    left: 50%;
    transform: translateX(-50%);
    top: 26px;
  }
}

@media (max-width: 520px) {
  .login-page {
    padding: 104px 18px 36px;
  }

  .auth-card {
    padding: 30px 22px 24px;
    border-radius: 22px;
  }

  .edition {
    display: none;
  }
}

/* --- 浅色主题适配 --- */
html.light .auth-card {
  background: var(--king-panel);
}

html.light .sso-btn:hover {
  color: var(--k-violet-soft);
}
</style>

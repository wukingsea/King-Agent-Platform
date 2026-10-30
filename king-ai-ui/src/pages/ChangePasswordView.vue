<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { ArrowRight, Lock } from '@element-plus/icons-vue'
import BrandLogo from '@/components/BrandLogo.vue'
import { authApi } from '@/api'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { PASSWORD_PATTERN, PASSWORD_RULE_MESSAGE } from '@/utils/password'

const auth = useAuthStore()
const router = useRouter()

const formRef = ref<FormInstance>()
const submitting = ref(false)
const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

const rules: FormRules = {
  oldPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    {
      pattern: PASSWORD_PATTERN,
      message: PASSWORD_RULE_MESSAGE,
      trigger: 'blur',
    },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (value !== form.newPassword) callback(new Error('两次输入的新密码不一致'))
        else callback()
      },
      trigger: 'blur',
    },
  ],
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    await authApi.changePassword({
      oldPassword: form.oldPassword,
      newPassword: form.newPassword,
    })
    auth.clearLocal()
    ElMessage.success('密码已更新，请使用新密码重新登录')
    router.replace({ name: 'login' })
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '修改密码失败')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="password-page">
    <div class="grid-bg"></div>
    <div class="orb orb-a"></div>
    <div class="orb orb-b"></div>

    <header class="brand">
      <BrandLogo :size="38" />
    </header>

    <section class="card">
      <div class="card-head">
        <div class="eyebrow">SECURITY CHECK</div>
        <h1>请先修改初始密码</h1>
        <p>为保护空间数据，首次登录或密码被重置后必须设置新密码。</p>
      </div>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        @keyup.enter="submit"
      >
        <el-form-item label="当前密码" prop="oldPassword">
          <el-input
            v-model="form.oldPassword"
            type="password"
            show-password
            placeholder="请输入当前密码"
            autocomplete="current-password"
          >
            <template #prefix><el-icon><Lock /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input
            v-model="form.newPassword"
            type="password"
            show-password
            placeholder="8-72 位，包含大小写字母和数字"
            autocomplete="new-password"
          >
            <template #prefix><el-icon><Lock /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-form-item label="确认新密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            show-password
            placeholder="请再次输入新密码"
            autocomplete="new-password"
          >
            <template #prefix><el-icon><Lock /></el-icon></template>
          </el-input>
        </el-form-item>

        <el-button class="submit" type="primary" size="large" :loading="submitting" @click="submit">
          更新密码并重新登录
          <el-icon><ArrowRight /></el-icon>
        </el-button>
      </el-form>

      <p class="foot">修改成功后当前会话将失效，所有设备需要重新登录。</p>
    </section>
  </div>
</template>

<style scoped>
.password-page {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 96px 24px 48px;
  overflow: hidden;
}

.grid-bg {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.024) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.024) 1px, transparent 1px);
  background-size: 56px 56px;
  mask-image: radial-gradient(circle at 50% 40%, #000 0%, transparent 70%);
  pointer-events: none;
}

.orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(90px);
  pointer-events: none;
}

.orb-a {
  top: -16%;
  left: 12%;
  width: 480px;
  height: 480px;
  background: rgba(124, 92, 255, 0.22);
}

.orb-b {
  right: 4%;
  bottom: -20%;
  width: 520px;
  height: 520px;
  background: rgba(34, 211, 238, 0.12);
}

.brand {
  position: absolute;
  top: 30px;
  left: clamp(24px, 7vw, 96px);
  z-index: 2;
}

.card {
  position: relative;
  z-index: 1;
  width: min(440px, 100%);
  padding: 38px 34px 30px;
  border-radius: 22px;
  border: 1px solid var(--king-line-strong);
  background: rgba(11, 16, 32, 0.82);
  backdrop-filter: blur(18px);
  box-shadow: 0 34px 90px -48px rgba(0, 0, 0, 0.95);
}

.card-head {
  margin-bottom: 24px;
}

.eyebrow {
  font-size: 10px;
  letter-spacing: 0.2em;
  color: #8b7cf6;
  text-transform: uppercase;
}

.card-head h1 {
  margin: 10px 0 8px;
  font-size: 24px;
  font-weight: 680;
  color: var(--king-text);
}

.card-head p {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--king-muted);
}

.submit {
  width: 100%;
  margin-top: 6px;
}

.foot {
  margin: 18px 0 0;
  font-size: 11px;
  line-height: 1.6;
  color: var(--king-muted);
}
</style>

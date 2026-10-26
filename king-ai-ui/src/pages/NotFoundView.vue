<script setup lang="ts">
import { useRouter } from 'vue-router'
import { ArrowLeft, Compass } from '@element-plus/icons-vue'

const router = useRouter()
</script>

<template>
  <div class="not-found">
    <div class="orb">
      <span class="ring ring-1"></span>
      <span class="ring ring-2"></span>
      <el-icon><Compass /></el-icon>
    </div>
    <p class="code mono">404</p>
    <h1>页面走丢了</h1>
    <p class="desc">你访问的地址不存在，或者页面已经被移动。</p>
    <div class="actions">
      <el-button type="primary" @click="router.push({ name: 'dashboard' })">
        <el-icon><ArrowLeft /></el-icon>
        返回工作台
      </el-button>
    </div>
  </div>
</template>

<style scoped lang="scss">
.not-found {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 32px;
  text-align: center;
  background:
    radial-gradient(700px 400px at 50% -10%, rgba(124, 92, 255, 0.2), transparent 65%),
    #060913;
}

.orb {
  position: relative;
  width: 96px;
  height: 96px;
  display: grid;
  place-items: center;
  font-size: 34px;
  color: #c4b5fd;
  margin-bottom: 8px;
}

.ring {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  border: 1px solid rgba(124, 92, 255, 0.32);
}

.ring-1 {
  animation: spin 9s linear infinite;
  border-top-color: #7c5cff;
}

.ring-2 {
  inset: 12px;
  animation: spin 6s linear infinite reverse;
  border-bottom-color: #38bdf8;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.code {
  margin: 0;
  font-size: 12px;
  letter-spacing: 0.5em;
  color: var(--text-faint);
}

h1 {
  margin: 0;
  font-size: 26px;
  font-weight: 680;
  color: var(--text-strong);
}

.desc {
  margin: 0 0 10px;
  font-size: 13px;
  color: var(--text-muted);
}

.actions {
  display: flex;
  gap: 10px;
}
</style>

<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

function handleUnauthorized() {
  auth.clearLocal()
  if (route.name !== 'login' && route.name !== 'public-chat') {
    router.replace({ name: 'login', query: { redirect: route.fullPath } })
  }
}

onMounted(() => window.addEventListener('king:unauthorized', handleUnauthorized))
onBeforeUnmount(() => window.removeEventListener('king:unauthorized', handleUnauthorized))
</script>

<template>
  <router-view />
</template>

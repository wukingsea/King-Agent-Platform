<script setup lang="ts">
defineProps<{
  title?: string
  description?: string
}>()
</script>

<template>
  <div class="empty-state">
    <div class="empty-icon">
      <slot name="icon">
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
          <rect x="3.5" y="4.5" width="17" height="15" rx="3" />
          <path d="M8 9.5h8M8 13h5" stroke-linecap="round" />
        </svg>
      </slot>
    </div>
    <strong>{{ title || '暂无数据' }}</strong>
    <span v-if="description" class="empty-desc">{{ description }}</span>
    <div v-if="$slots.action" class="empty-action">
      <slot name="action" />
    </div>
  </div>
</template>

<style scoped>
.empty-desc {
  max-width: 380px;
  font-size: 12.5px;
  line-height: 1.7;
}

.empty-action {
  margin-top: 4px;
}
</style>

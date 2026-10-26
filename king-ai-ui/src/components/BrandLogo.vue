<script setup lang="ts">
import { useId } from 'vue'

withDefaults(
  defineProps<{
    size?: number
    subtitle?: boolean
    compact?: boolean
  }>(),
  { size: 38, subtitle: true, compact: false },
)

const uid = useId().replace(/:/g, '')
</script>

<template>
  <div class="brand" :class="{ compact }">
    <svg :width="size" :height="size" viewBox="0 0 64 64" class="mark" aria-hidden="true">
      <defs>
        <linearGradient :id="`brand-${uid}`" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stop-color="#a78bfa" />
          <stop offset="0.55" stop-color="#7c5cff" />
          <stop offset="1" stop-color="#22d3ee" />
        </linearGradient>
      </defs>
      <rect x="1" y="1" width="62" height="62" rx="17" fill="rgba(255,255,255,0.04)" stroke="rgba(255,255,255,0.12)" />
      <path
        d="M15 41.5 L13 21.5 L24.5 30 L32 17.5 L39.5 30 L51 21.5 L49 41.5 Z"
        :fill="`url(#brand-${uid})`"
      />
      <rect x="16" y="45.5" width="32" height="3.5" rx="1.75" fill="#22d3ee" opacity="0.65" />
    </svg>
    <div v-if="!compact" class="text">
      <div class="name">King AI</div>
      <div v-if="subtitle" class="tagline">AGENT PLATFORM</div>
    </div>
  </div>
</template>

<style scoped>
.brand {
  display: flex;
  align-items: center;
  gap: 12px;
}

.mark {
  flex: none;
  filter: drop-shadow(0 8px 22px rgba(124, 92, 255, 0.35));
}

.name {
  font-size: 19px;
  font-weight: 720;
  letter-spacing: -0.02em;
  line-height: 1.15;
  background: linear-gradient(100deg, #ffffff 10%, #cfc6ff 55%, #8ff1ff 100%);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.tagline {
  margin-top: 3px;
  font-size: 8.5px;
  font-weight: 600;
  letter-spacing: 0.32em;
  color: var(--king-muted);
}
</style>

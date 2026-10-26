<script setup lang="ts">
defineProps<{
  label: string
  value: string | number
  hint?: string
  tone?: 'violet' | 'cyan' | 'pink' | 'green' | 'amber'
}>()
</script>

<template>
  <div class="stat-card panel" :class="tone || 'violet'">
    <div class="stat-top">
      <span class="stat-label">{{ label }}</span>
      <div class="stat-icon">
        <slot name="icon" />
      </div>
    </div>
    <div class="stat-value">{{ value }}</div>
    <div v-if="hint" class="stat-hint">{{ hint }}</div>
  </div>
</template>

<style scoped>
.stat-card {
  position: relative;
  overflow: hidden;
  padding: 18px 20px 17px;
  min-height: 122px;
  transition: transform 0.2s ease, border-color 0.2s ease;
}

.stat-card::after {
  content: '';
  position: absolute;
  width: 130px;
  height: 130px;
  right: -46px;
  bottom: -72px;
  border-radius: 50%;
  background: var(--glow, rgba(124, 92, 255, 0.28));
  filter: blur(24px);
  opacity: 0.55;
}

.stat-card:hover {
  transform: translateY(-2px);
  border-color: rgba(255, 255, 255, 0.14);
}

.stat-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.stat-label {
  font-size: 12.5px;
  color: var(--king-muted);
  font-weight: 520;
}

.stat-icon {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border-radius: 10px;
  color: #d5ccff;
  background: rgba(255, 255, 255, 0.055);
  border: 1px solid var(--king-line);
  font-size: 15px;
}

.stat-value {
  margin-top: 14px;
  font-size: 30px;
  font-weight: 700;
  letter-spacing: -0.03em;
  line-height: 1;
}

.stat-hint {
  margin-top: 9px;
  font-size: 11.5px;
  color: var(--king-muted);
}

.violet {
  --glow: rgba(124, 92, 255, 0.34);
}

.cyan {
  --glow: rgba(34, 211, 238, 0.26);
}

.cyan .stat-icon {
  color: #a5f3ff;
}

.pink {
  --glow: rgba(255, 110, 169, 0.26);
}

.pink .stat-icon {
  color: #ffc2da;
}

.green {
  --glow: rgba(52, 211, 153, 0.24);
}

.green .stat-icon {
  color: #a7f3d0;
}

.amber {
  --glow: rgba(251, 191, 36, 0.22);
}

.amber .stat-icon {
  color: #fde68a;
}
</style>

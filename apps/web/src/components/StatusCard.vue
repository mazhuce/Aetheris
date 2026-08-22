<script setup lang="ts">
defineProps<{
  label: string
  detail: string
  state: 'ok' | 'pending' | 'error'
}>()
</script>

<template>
  <div class="glass glass-hover status-card">
    <div class="status-row">
      <span class="status-dot" :class="state" />
      <span class="status-label">{{ label }}</span>
    </div>
    <div class="status-detail">{{ detail }}</div>
  </div>
</template>

<style scoped>
.status-card {
  padding: 18px 20px;
}

.status-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.status-label {
  font-size: 14px;
  font-weight: 600;
}

.status-detail {
  font-size: 12px;
  color: var(--text-secondary);
}

.status-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
}

.status-dot.ok {
  background: var(--success);
  animation: pulse 2s infinite;
}

.status-dot.pending {
  background: var(--warning);
}

.status-dot.error {
  background: var(--danger);
}

@keyframes pulse {
  0% {
    box-shadow: 0 0 0 0 rgba(52, 211, 153, 0.4);
  }
  70% {
    box-shadow: 0 0 0 8px rgba(52, 211, 153, 0);
  }
  100% {
    box-shadow: 0 0 0 0 rgba(52, 211, 153, 0);
  }
}
</style>

<script setup lang="ts">
defineProps<{
  title?: string
  subtitle?: string
}>()
</script>

<template>
  <div class="glass glass-hover card">
    <header v-if="title || subtitle" class="card-head">
      <h3 v-if="title">{{ title }}</h3>
      <p v-if="subtitle">{{ subtitle }}</p>
    </header>
    <slot />
  </div>
</template>

<style scoped>
.card {
  padding: 22px;
}

.card-head {
  margin-bottom: 16px;
}

.card-head h3 {
  margin: 0 0 4px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.card-head p {
  margin: 0;
  font-size: 13px;
  color: var(--text-secondary);
}
</style>

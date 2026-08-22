<script setup lang="ts">
import type { Component } from 'vue'

defineProps<{
  icon: Component
  title: string
  desc: string
}>()
</script>

<template>
  <div class="glass glass-hover feature-card">
    <div class="feature-icon">
      <el-icon :size="20"><component :is="icon" /></el-icon>
    </div>
    <h4>{{ title }}</h4>
    <p>{{ desc }}</p>
  </div>
</template>

<style scoped>
.feature-card {
  padding: 22px;
}

.feature-icon {
  width: 42px;
  height: 42px;
  display: grid;
  place-items: center;
  border-radius: 12px;
  background: linear-gradient(120deg, rgba(109, 124, 255, 0.18), rgba(45, 212, 191, 0.14));
  color: #a5b4ff;
  margin-bottom: 16px;
}

.feature-card h4 {
  margin: 0 0 6px;
  font-size: 15px;
  font-weight: 600;
}

.feature-card p {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-secondary);
}
</style>

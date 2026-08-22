<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { pingSystem, listConfigs, type PingResult, type SystemConfig } from '@/api/system'
import GlassCard from '@/components/GlassCard.vue'
import StatusCard from '@/components/StatusCard.vue'
import FeatureCard from '@/components/FeatureCard.vue'
import {
  Connection,
  DataAnalysis,
  DocumentChecked,
  Grid,
  Share,
  MagicStick
} from '@element-plus/icons-vue'

type State = 'ok' | 'pending' | 'error'

const gatewayState = ref<State>('pending')
const systemState = ref<State>('pending')
const dbState = ref<State>('pending')
const ping = ref<PingResult | null>(null)
const configs = ref<SystemConfig[]>([])

const features = [
  { icon: Connection, title: '数据接入', desc: '统一采集离线与实时数据，屏蔽异构数据源差异。' },
  { icon: DataAnalysis, title: '元数据管理', desc: '沉淀库表与字段资产，让数据可发现、可理解。' },
  { icon: DocumentChecked, title: '数据质量', desc: '规则校验与评分告警，守护数据可信度。' },
  { icon: Grid, title: '数据治理', desc: '血缘、目录与安全策略，构建企业级治理体系。' },
  { icon: Share, title: '服务开放', desc: '以 API 与 SQL 方式把数据资产开放给下游。' },
  { icon: MagicStick, title: 'AI / Agent', desc: '智能问答与指标解读，让平台具备思考能力。' }
]

const statusLabel = (state: State) => {
  if (state === 'ok') return '在线'
  if (state === 'error') return '离线'
  return '检测中'
}

async function refresh() {
  gatewayState.value = 'pending'
  systemState.value = 'pending'
  dbState.value = 'pending'

  try {
    ping.value = await pingSystem()
    gatewayState.value = 'ok'
    systemState.value = 'ok'
  } catch {
    ping.value = null
    gatewayState.value = 'error'
    systemState.value = 'error'
    return
  }

  try {
    configs.value = await listConfigs()
    dbState.value = 'ok'
  } catch {
    configs.value = []
    dbState.value = 'error'
  }
}

onMounted(refresh)
</script>

<template>
  <div class="app-shell">
    <div class="grid-overlay"></div>

    <div class="page">
      <nav class="topbar">
        <div class="brand">
          <span class="brand-mark"></span>
          <span class="brand-name">Aetheris</span>
        </div>
        <div class="nav-links">
          <a href="#status">系统状态</a>
          <a href="#features">能力蓝图</a>
          <a href="#data">实时数据</a>
        </div>
      </nav>

      <header class="hero">
        <p class="eyebrow">Personal Data Platform · Evolving to Enterprise</p>
        <h1>让数据流动，<br /><span class="gradient-text">让平台生长。</span></h1>
        <p class="hero-sub">
          Aetheris 以 Java 微服务为基座，统一数据的接入、治理与开放，从个人数据中台稳步演进为企业级数据平台。
        </p>
        <div class="hero-actions">
          <a class="btn btn-primary" href="#status">查看系统状态</a>
          <a class="btn btn-ghost" href="#features">了解能力蓝图</a>
        </div>
      </header>

      <section id="status" class="section">
        <div class="section-head">
          <h2>系统状态</h2>
          <button class="btn btn-ghost btn-sm" @click="refresh">重新检测</button>
        </div>
        <div class="status-grid">
          <StatusCard label="API Gateway" :state="gatewayState" :detail="`统一入口 · ${statusLabel(gatewayState)}`" />
          <StatusCard label="System Service" :state="systemState" :detail="ping ? `v${ping.version} · ${ping.service}` : '等待服务响应'" />
          <StatusCard label="PostgreSQL" :state="dbState" :detail="`数据持久层 · ${statusLabel(dbState)}`" />
        </div>
      </section>

      <section id="features" class="section">
        <div class="section-head">
          <h2>能力蓝图</h2>
          <span class="section-note">按阶段逐步解锁</span>
        </div>
        <div class="feature-grid">
          <FeatureCard v-for="feature in features" :key="feature.title" :icon="feature.icon" :title="feature.title" :desc="feature.desc" />
        </div>
      </section>

      <section id="data" class="section">
        <div class="section-head">
          <h2>实时数据</h2>
          <span class="section-note">来自 system_config</span>
        </div>
        <GlassCard>
          <div v-if="configs.length" class="config-list">
            <div v-for="config in configs" :key="config.id" class="config-row">
              <span class="config-key">{{ config.configKey }}</span>
              <span class="config-value">{{ config.configValue }}</span>
              <span class="config-desc">{{ config.description }}</span>
            </div>
          </div>
          <div v-else class="empty">数据库暂不可用，启动服务后自动显示。</div>
        </GlassCard>
      </section>

      <footer class="footer">
        <span>Aetheris · 0.1.0</span>
        <span>以克制与秩序，构建数据的优雅。</span>
      </footer>
    </div>
  </div>
</template>

<style scoped>
.page {
  position: relative;
  z-index: 1;
  max-width: 1080px;
  margin: 0 auto;
  padding: 0 32px 64px;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 24px 0;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.brand-mark {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  background: linear-gradient(135deg, #6d7cff, #2dd4bf);
  box-shadow: 0 8px 24px -8px rgba(109, 124, 255, 0.8);
}

.brand-name {
  font-size: 16px;
  font-weight: 650;
  letter-spacing: -0.01em;
}

.nav-links {
  display: flex;
  gap: 26px;
}

.nav-links a {
  color: var(--text-secondary);
  text-decoration: none;
  font-size: 13px;
  transition: color 0.2s ease;
}

.nav-links a:hover {
  color: var(--text);
}

.hero {
  padding: 72px 0 64px;
}

.hero h1 {
  margin: 18px 0 20px;
  font-size: clamp(38px, 7vw, 68px);
  line-height: 1.08;
  font-weight: 650;
  letter-spacing: -0.035em;
}

.hero-sub {
  max-width: 560px;
  margin: 0;
  font-size: 16px;
  line-height: 1.8;
  color: var(--text-secondary);
}

.hero-actions {
  display: flex;
  gap: 14px;
  margin-top: 32px;
  flex-wrap: wrap;
}

.section {
  margin-top: 28px;
  scroll-margin-top: 24px;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 18px;
}

.section-head h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 620;
  letter-spacing: -0.02em;
}

.section-note {
  font-size: 12px;
  color: var(--text-faint);
}

.status-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}

.feature-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}

.config-list {
  display: flex;
  flex-direction: column;
}

.config-row {
  display: grid;
  grid-template-columns: 1fr 1.2fr 1.4fr;
  gap: 16px;
  padding: 14px 4px;
  border-bottom: 1px solid var(--border);
  font-size: 13px;
}

.config-row:last-child {
  border-bottom: none;
}

.config-key {
  font-family: 'JetBrains Mono', 'SFMono-Regular', Consolas, monospace;
  color: #a5b4ff;
}

.config-value {
  color: var(--text);
}

.config-desc {
  color: var(--text-faint);
}

.empty {
  padding: 24px 4px;
  color: var(--text-faint);
  font-size: 13px;
}

.footer {
  display: flex;
  justify-content: space-between;
  margin-top: 64px;
  padding-top: 24px;
  border-top: 1px solid var(--border);
  font-size: 12px;
  color: var(--text-faint);
}

@media (max-width: 820px) {
  .status-grid,
  .feature-grid {
    grid-template-columns: 1fr;
  }

  .nav-links {
    display: none;
  }

  .config-row {
    grid-template-columns: 1fr;
    gap: 4px;
  }
}
</style>

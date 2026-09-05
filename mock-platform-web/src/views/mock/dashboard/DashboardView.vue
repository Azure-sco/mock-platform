<script setup lang="ts">
import { computed, onMounted } from 'vue'
import HttpErrorAlert from '../../../components/HttpErrorAlert.vue'
import { useHealthStore } from '../../../stores/health'
import { useSessionStore } from '../../../stores/session'

const health = useHealthStore()
const session = useSessionStore()
const summary = computed(() => health.summary)

function metric(value: number | undefined, suffix = '') {
  return value === undefined ? '—' : `${value}${suffix}`
}

function rate(value: number | undefined) {
  return value === undefined ? '—' : `${value.toFixed(1)}%`
}

const healthLabel = computed(() => {
  if (health.checkStatus === 'IDLE') return '未检查'
  if (health.checkStatus === 'CHECKING') return '检查中'
  return health.allHealthy ? '正常' : '异常'
})

onMounted(() => health.refresh())
</script>

<template>
  <section class="dashboard">
    <div class="page-heading">
      <div>
        <p class="eyebrow">OPERATIONS OVERVIEW</p>
        <h2>{{ session.environment }} 运行概览</h2>
        <p>聚焦服务可用性、请求质量和需要处理的异常。</p>
      </div>
      <el-button type="primary" :loading="health.loading" @click="health.refresh">刷新状态</el-button>
    </div>

    <HttpErrorAlert />
    <el-alert v-if="health.summaryFailed" type="error" :closable="false" show-icon title="运行指标加载失败，指标暂以 — 展示；服务健康状态仍可独立查看。" />

    <div class="metric-grid">
      <article class="metric-card metric-card-primary">
        <div class="metric-label">服务健康</div>
        <strong :class="health.allHealthy ? 'status-up' : health.checkStatus === 'IDLE' ? '' : 'status-down'">{{ healthLabel }}</strong>
        <small>Control {{ health.control?.status ?? '—' }} · Runtime {{ health.runtime === 'UNKNOWN' ? '—' : health.runtime }}</small>
      </article>
      <article class="metric-card">
        <div class="metric-label">24h 请求 / 命中率</div>
        <strong>{{ metric(summary?.requests) }} / {{ rate(summary?.hitRate) }}</strong>
        <small>真实聚合请求指标</small>
      </article>
      <article class="metric-card" :class="{ 'metric-card-alert': (summary?.noMatchRequests ?? 0) > 0 }">
        <div class="metric-label">未匹配请求</div>
        <strong>{{ metric(summary?.noMatchRequests) }}</strong>
        <small><RouterLink to="/mock/requests">查看请求记录 →</RouterLink></small>
      </article>
      <article class="metric-card">
        <div class="metric-label">24h P95</div>
        <strong>{{ metric(summary?.p95DurationMs, ' ms') }}</strong>
        <small>Runtime 端到端耗时</small>
      </article>
      <article class="metric-card">
        <div class="metric-label">Callback 成功率</div>
        <strong>{{ rate(summary?.callbackSuccessRate) }}</strong>
        <small>重试 {{ metric(summary?.callbackRetries) }} · <RouterLink to="/mock/callbacks">处理异常 →</RouterLink></small>
      </article>
      <article class="metric-card">
        <div class="metric-label">配置对象</div>
        <strong>{{ metric(summary?.providers) }} / {{ metric(summary?.apis) }}</strong>
        <small>Provider / API · 场景 {{ metric(summary?.scenarios) }}</small>
      </article>
      <article class="metric-card">
        <div class="metric-label">不可变发布</div>
        <strong>{{ metric(summary?.releases) }}</strong>
        <small><RouterLink to="/mock/releases">检查发布状态 →</RouterLink></small>
      </article>
    </div>

    <el-card shadow="never" class="operation-card">
      <template #header><div class="card-heading"><span>异常处理入口</span><el-tag effect="plain">{{ session.environment }}</el-tag></div></template>
      <div class="operation-links">
        <RouterLink to="/mock/requests"><strong>请求诊断</strong><span>按 Trace、Provider、API 与时间定位未命中或错误请求。</span></RouterLink>
        <RouterLink to="/mock/callbacks"><strong>Callback 任务</strong><span>查看失败、重试和投递不确定状态。</span></RouterLink>
        <RouterLink to="/mock/releases"><strong>发布状态</strong><span>核对当前环境的 Active Release 与 Activation Version。</span></RouterLink>
        <RouterLink to="/mock/audits"><strong>审计追踪</strong><span>按 Request ID 追踪管理操作与结果。</span></RouterLink>
      </div>
    </el-card>

    <p class="last-checked">最近检查：{{ health.lastCheckedAt || '尚未检查' }}</p>
  </section>
</template>

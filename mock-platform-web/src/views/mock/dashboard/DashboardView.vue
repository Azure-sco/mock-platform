<script setup lang="ts">
import { onMounted } from 'vue'
import { useErrorStore } from '../../../stores/errors'
import { useHealthStore } from '../../../stores/health'
import { useSessionStore } from '../../../stores/session'

const health = useHealthStore()
const errors = useErrorStore()
const session = useSessionStore()

onMounted(() => health.refresh())
</script>

<template>
  <section class="dashboard">
    <div class="page-heading">
      <div>
        <p class="eyebrow">TECHNICAL PROOF OF CONCEPT</p>
        <h2>第三方调用链路状态</h2>
        <p>验证 SDK、控制面、Runtime 与假真实服务的 M0 最小闭环。</p>
      </div>
      <el-button type="primary" :loading="health.loading" @click="health.refresh">刷新状态</el-button>
    </div>

    <el-alert
      v-if="errors.latest"
      type="error"
      show-icon
      closable
      :title="`${errors.latest.code}: ${errors.latest.message}`"
      :description="errors.latest.requestId ? `Request ID: ${errors.latest.requestId}` : undefined"
      @close="errors.clear"
    />

    <div class="metric-grid">
      <article class="metric-card">
        <div class="metric-label">Control</div>
        <strong :class="health.control?.status === 'UP' ? 'status-up' : 'status-down'">
          {{ health.control?.status || 'UNKNOWN' }}
        </strong>
        <small>{{ health.control?.service || 'mock-platform-control' }}</small>
      </article>
      <article class="metric-card">
        <div class="metric-label">Runtime</div>
        <strong :class="health.runtime === 'UP' ? 'status-up' : 'status-down'">{{ health.runtime }}</strong>
        <small>HTTP/1.1 · max body 1MB</small>
      </article>
      <article class="metric-card">
        <div class="metric-label">Environment</div>
        <strong>{{ session.environment }}</strong>
        <small>生产环境强制 REAL</small>
      </article>
      <article class="metric-card">
        <div class="metric-label">M0 Fixtures</div>
        <strong>4</strong>
        <small>OA 2 · CPS_EQB 2</small>
      </article>
    </div>

    <div class="dashboard-grid">
      <el-card shadow="never" class="capability-card">
        <template #header>
          <div class="card-heading">
            <span>已验证能力</span>
            <el-tag type="success" effect="plain">M0</el-tag>
          </div>
        </template>
        <el-table :data="[
          { client: 'JDK 8 / Boot 2', transport: 'RestTemplate', sample: 'OA multipart', modes: 'REAL → MOCK' },
          { client: 'JDK 17 / Boot 3', transport: 'OpenFeign', sample: 'CPS_EQB JSON', modes: 'REAL / MOCK / CANARY' },
        ]" style="width: 100%">
          <el-table-column prop="client" label="客户端" min-width="150" />
          <el-table-column prop="transport" label="传输" min-width="130" />
          <el-table-column prop="sample" label="样板" min-width="150" />
          <el-table-column prop="modes" label="路由模式" min-width="170" />
        </el-table>
      </el-card>

      <el-card shadow="never" class="scope-card">
        <template #header>
          <div class="card-heading"><span>当前交付边界</span></div>
        </template>
        <ul class="scope-list">
          <li><span class="check">✓</span><div><strong>凭证剥离</strong><p>Mock 副本移除 Token、Cookie 与 Signature。</p></div></li>
          <li><span class="check">✓</span><div><strong>失败策略</strong><p>FAST_FAIL、受限回真实与固定兜底响应。</p></div></li>
          <li><span class="check">✓</span><div><strong>连接断开 PoC</strong><p>Runtime 收到请求后绝不回真实目标。</p></div></li>
          <li><span class="future">M1</span><div><strong>管理闭环</strong><p>Provider、Scenario、Release 等功能尚未开放。</p></div></li>
        </ul>
      </el-card>
    </div>

    <p class="last-checked">最近检查：{{ health.lastCheckedAt || '尚未检查' }}</p>
  </section>
</template>

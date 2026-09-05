<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useHealthStore } from '../stores/health'
import { useSessionStore, type Environment } from '../stores/session'

const route = useRoute()
const session = useSessionStore()
const health = useHealthStore()
const helpVisible = ref(false)
const title = computed(() => String(route.meta.title || 'Mock 平台'))
const environment = computed({
  get: () => session.environment,
  set: (value: Environment) => { session.environment = value },
})
const healthPill = computed(() => {
  if (health.checkStatus === 'IDLE') return { label: '未检查', className: 'pending' }
  if (health.checkStatus === 'CHECKING') return { label: '检查中', className: 'checking' }
  if (health.allHealthy) return { label: '服务正常', className: 'healthy' }
  return { label: '服务异常', className: 'unhealthy' }
})
const navGroups = [
  {
    label: '运行',
    items: [
      { to: '/mock/dashboard', icon: '⌁', label: '运行概览' },
      { to: '/mock/requests', icon: '≋', label: '请求记录' },
      { to: '/mock/callbacks', icon: '↗', label: '回调任务' },
      { to: '/mock/flows', icon: '⌘', label: '流程实例' },
    ],
  },
  {
    label: '配置',
    items: [
      { to: '/mock/providers', icon: '◈', label: 'Provider 管理' },
      { to: '/mock/apis', icon: '⌗', label: 'API 管理' },
      { to: '/mock/contracts', icon: '◇', label: '契约管理' },
      { to: '/mock/scenarios', icon: '◎', label: '场景管理' },
      { to: '/mock/flow-definitions', icon: '⑂', label: '流程定义' },
      { to: '/mock/sdk-configs', icon: '⚙', label: 'SDK Config' },
    ],
  },
  {
    label: '治理',
    items: [
      { to: '/mock/releases', icon: '⬆', label: '发布与回滚' },
      { to: '/mock/approvals', icon: '✓', label: '审批中心' },
      { to: '/mock/security-policies', icon: '◆', label: '安全策略' },
      { to: '/mock/audits', icon: '◷', label: '审计日志' },
    ],
  },
]
</script>

<template>
  <div class="app-shell" :class="{ 'sidebar-collapsed': session.sidebarCollapsed }">
    <aside class="sidebar">
      <div class="brand">
        <span class="brand-mark">XT</span>
        <div v-if="!session.sidebarCollapsed"><strong>巡天 Mock</strong><small>第三方接口平台</small></div>
      </div>
      <nav class="navigation" aria-label="主导航">
        <section v-for="group in navGroups" :key="group.label" class="nav-group">
          <p v-if="!session.sidebarCollapsed" class="nav-group-title">{{ group.label }}</p>
          <RouterLink v-for="item in group.items" :key="item.to" :to="item.to" class="nav-item" :title="item.label">
            <span class="nav-icon" aria-hidden="true">{{ item.icon }}</span>
            <span v-if="!session.sidebarCollapsed">{{ item.label }}</span>
          </RouterLink>
        </section>
      </nav>
      <button class="sidebar-help" type="button" title="帮助与交付说明" @click="helpVisible = true">
        <span class="nav-icon">?</span><span v-if="!session.sidebarCollapsed">帮助与交付说明</span>
      </button>
    </aside>

    <section class="app-main">
      <header class="topbar">
        <div class="topbar-left">
          <button class="sidebar-toggle" type="button" aria-label="切换侧边栏" @click="session.toggleSidebar">{{ session.sidebarCollapsed ? '›' : '‹' }}</button>
          <div><p class="breadcrumb">MOCK PLATFORM / {{ session.environment }}</p><h1>{{ title }}</h1></div>
        </div>
        <div class="topbar-right">
          <span class="health-pill" :class="healthPill.className">{{ healthPill.label }}</span>
          <el-select v-model="environment" class="environment-select" aria-label="当前环境">
            <el-option label="TEST" value="TEST" /><el-option label="UAT" value="UAT" />
          </el-select>
          <div class="operator">
            <span class="operator-avatar">{{ session.displayName.slice(0, 1) }}</span>
            <div><strong>{{ session.displayName }}</strong><small>{{ session.operatorId }}</small></div>
          </div>
        </div>
      </header>
      <main class="page-content"><RouterView /></main>
    </section>

    <el-drawer v-model="helpVisible" title="帮助与交付说明" size="420px">
      <div class="help-content">
        <h3>操作边界</h3>
        <p>Provider、API 与契约是跨环境共享配置；Release、SDK Config 和 Flow Instance 按环境隔离。切换顶栏环境会清空并刷新这些环境专属页面。</p>
        <h3>交付阶段</h3>
        <p>M1 建立资源和契约版本；M2 覆盖场景、审批与原子发布；M3—M5 扩展流程、回调、安全和审计能力。</p>
        <h3>故障处理</h3>
        <p>优先从运行概览进入请求、Callback、发布和审计页面。指标“—”表示尚未加载或获取失败，不代表零。</p>
      </div>
    </el-drawer>
  </div>
</template>

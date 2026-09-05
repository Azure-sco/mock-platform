<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useHealthStore } from '../stores/health'
import { useSessionStore } from '../stores/session'

const route = useRoute()
const session = useSessionStore()
const health = useHealthStore()
const title = computed(() => String(route.meta.title || 'Mock 平台'))
</script>

<template>
  <div class="app-shell" :class="{ 'sidebar-collapsed': session.sidebarCollapsed }">
    <aside class="sidebar">
      <div class="brand">
        <span class="brand-mark">XT</span>
        <div v-if="!session.sidebarCollapsed">
          <strong>巡天 Mock</strong>
          <small>第三方接口平台</small>
        </div>
      </div>

      <nav class="navigation" aria-label="主导航">
        <RouterLink to="/mock/dashboard" class="nav-item" title="运行概览">
          <span class="nav-dot" />
          <span v-if="!session.sidebarCollapsed">运行概览</span>
        </RouterLink>
        <RouterLink to="/mock/providers" class="nav-item" title="Provider 管理">
          <span class="nav-dot" />
          <span v-if="!session.sidebarCollapsed">Provider 管理</span>
        </RouterLink>
        <RouterLink to="/mock/apis" class="nav-item" title="API 管理">
          <span class="nav-dot" />
          <span v-if="!session.sidebarCollapsed">API 管理</span>
        </RouterLink>
        <RouterLink to="/mock/contracts" class="nav-item" title="契约管理">
          <span class="nav-dot" />
          <span v-if="!session.sidebarCollapsed">契约管理</span>
        </RouterLink>
        <RouterLink to="/mock/scenarios" class="nav-item" title="场景管理">
          <span class="nav-dot" />
          <span v-if="!session.sidebarCollapsed">场景管理</span>
        </RouterLink>
        <RouterLink to="/mock/flow-definitions" class="nav-item" title="流程定义">
          <span class="nav-dot" />
          <span v-if="!session.sidebarCollapsed">流程定义</span>
        </RouterLink>
        <RouterLink to="/mock/flows" class="nav-item" title="流程实例">
          <span class="nav-dot" />
          <span v-if="!session.sidebarCollapsed">流程实例</span>
        </RouterLink>
        <RouterLink to="/mock/callbacks" class="nav-item" title="回调任务">
          <span class="nav-dot" />
          <span v-if="!session.sidebarCollapsed">回调任务</span>
        </RouterLink>
        <RouterLink to="/mock/approvals" class="nav-item" title="审批中心">
          <span class="nav-dot" />
          <span v-if="!session.sidebarCollapsed">审批中心</span>
        </RouterLink>
        <RouterLink to="/mock/security-policies" class="nav-item" title="安全策略">
          <span class="nav-dot" />
          <span v-if="!session.sidebarCollapsed">安全策略</span>
        </RouterLink>
        <RouterLink to="/mock/sdk-configs" class="nav-item" title="SDK Config">
          <span class="nav-dot" />
          <span v-if="!session.sidebarCollapsed">SDK Config</span>
        </RouterLink>
        <RouterLink to="/mock/releases" class="nav-item" title="发布与回滚">
          <span class="nav-dot" />
          <span v-if="!session.sidebarCollapsed">发布与回滚</span>
        </RouterLink>
        <RouterLink to="/mock/requests" class="nav-item" title="请求记录">
          <span class="nav-dot" />
          <span v-if="!session.sidebarCollapsed">请求记录</span>
        </RouterLink>
        <RouterLink to="/mock/audits" class="nav-item" title="审计日志">
          <span class="nav-dot" />
          <span v-if="!session.sidebarCollapsed">审计日志</span>
        </RouterLink>
      </nav>

      <div v-if="!session.sidebarCollapsed" class="scope-note">
        <span>MVP · M1—M5</span>
        <p>三方样板、发布、流程、回调、安全与审计。</p>
      </div>
    </aside>

    <section class="app-main">
      <header class="topbar">
        <div class="topbar-left">
          <button class="sidebar-toggle" type="button" aria-label="切换侧边栏" @click="session.toggleSidebar">
            {{ session.sidebarCollapsed ? '›' : '‹' }}
          </button>
          <div>
            <p class="breadcrumb">MOCK PLATFORM / {{ session.environment }}</p>
            <h1>{{ title }}</h1>
          </div>
        </div>
        <div class="topbar-right">
          <span class="health-pill" :class="health.allHealthy ? 'healthy' : 'pending'">
            {{ health.allHealthy ? '服务正常' : '等待健康检查' }}
          </span>
          <el-select v-model="session.environment" class="environment-select" aria-label="当前环境">
            <el-option label="TEST" value="TEST" />
            <el-option label="UAT" value="UAT" />
          </el-select>
          <div class="operator">
            <span class="operator-avatar">{{ session.displayName.slice(0, 1) }}</span>
            <div>
              <strong>{{ session.displayName }}</strong>
              <small>{{ session.operatorId }}</small>
            </div>
          </div>
        </div>
      </header>

      <main class="page-content">
        <RouterView />
      </main>
    </section>
  </div>
</template>

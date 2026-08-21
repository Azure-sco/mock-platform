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
        <RouterLink to="/mock/dashboard" class="nav-item">
          <span class="nav-dot" />
          <span v-if="!session.sidebarCollapsed">运行概览</span>
        </RouterLink>
      </nav>

      <div v-if="!session.sidebarCollapsed" class="scope-note">
        <span>PHASE 0 · M0</span>
        <p>当前仅开放技术验证与健康概览。</p>
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

import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { getControlHealth, getDashboardSummary, getRuntimeHealth } from '../api/platform'
import type { DashboardSummary, HealthCheckStatus, HealthState, PlatformHealth } from '../types/platform'

export const useHealthStore = defineStore('health', () => {
  const loading = ref(false)
  const checkStatus = ref<HealthCheckStatus>('IDLE')
  const control = ref<PlatformHealth | null>(null)
  const runtime = ref<HealthState>('UNKNOWN')
  const summary = ref<DashboardSummary | null>(null)
  const summaryFailed = ref(false)
  const lastCheckedAt = ref<string | null>(null)
  const allHealthy = computed(() => control.value?.status === 'UP' && runtime.value === 'UP')

  async function refresh() {
    if (loading.value) return
    loading.value = true
    checkStatus.value = 'CHECKING'
    try {
      const [controlResult, runtimeResult, summaryResult] = await Promise.allSettled([
        getControlHealth(),
        getRuntimeHealth(),
        getDashboardSummary(),
      ])
      control.value = controlResult.status === 'fulfilled' ? controlResult.value : null
      runtime.value = runtimeResult.status === 'fulfilled' ? runtimeResult.value : 'DOWN'
      summary.value = summaryResult.status === 'fulfilled' ? summaryResult.value : null
      summaryFailed.value = summaryResult.status === 'rejected'
      checkStatus.value = controlResult.status === 'fulfilled' && runtimeResult.status === 'fulfilled'
        ? 'SUCCESS'
        : 'ERROR'
      lastCheckedAt.value = new Date().toLocaleString('zh-CN', { hour12: false })
    } finally {
      loading.value = false
    }
  }

  return {
    loading,
    checkStatus,
    control,
    runtime,
    summary,
    summaryFailed,
    lastCheckedAt,
    allHealthy,
    refresh,
  }
})

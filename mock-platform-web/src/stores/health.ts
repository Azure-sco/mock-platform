import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { getControlHealth, getDashboardSummary, getRuntimeHealth } from '../api/platform'
import type { DashboardSummary, HealthState, PlatformHealth } from '../types/platform'

export const useHealthStore = defineStore('health', () => {
  const loading = ref(false)
  const control = ref<PlatformHealth | null>(null)
  const runtime = ref<HealthState>('UNKNOWN')
  const summary = ref<DashboardSummary | null>(null)
  const lastCheckedAt = ref<string | null>(null)
  const allHealthy = computed(() => control.value?.status === 'UP' && runtime.value === 'UP')

  async function refresh() {
    loading.value = true
    try {
      const [controlResult, runtimeResult, summaryResult] = await Promise.allSettled([
        getControlHealth(),
        getRuntimeHealth(),
        getDashboardSummary(),
      ])
      control.value = controlResult.status === 'fulfilled' ? controlResult.value : null
      runtime.value = runtimeResult.status === 'fulfilled' ? runtimeResult.value : 'DOWN'
      summary.value = summaryResult.status === 'fulfilled' ? summaryResult.value : null
      lastCheckedAt.value = new Date().toLocaleString('zh-CN', { hour12: false })
    } finally {
      loading.value = false
    }
  }

  return { loading, control, runtime, summary, lastCheckedAt, allHealthy, refresh }
})

import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useSessionStore = defineStore('session', () => {
  const operatorId = ref('local-viewer')
  const displayName = ref('本地查看者')
  const roles = ref(['MOCK_VIEWER'])
  const permissions = ref(['mock:view'])
  const environment = ref('TEST')
  const sidebarCollapsed = ref(false)

  function toggleSidebar() {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }

  return {
    operatorId,
    displayName,
    roles,
    permissions,
    environment,
    sidebarCollapsed,
    toggleSidebar,
  }
})

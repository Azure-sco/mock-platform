import { defineStore } from 'pinia'
import { ref } from 'vue'

export type Environment = 'TEST' | 'UAT'

export const useSessionStore = defineStore('session', () => {
  const operatorId = ref('local-admin')
  const displayName = ref('本地管理员')
  const roles = ref(['MOCK_ADMIN', 'MOCK_VIEWER'])
  const permissions = ref([
    'mock:view',
    'mock:provider:view',
    'mock:provider:edit',
    'mock:api:view',
    'mock:api:edit',
    'mock:contract:view',
    'mock:contract:edit',
    'mock:contract:publish',
    'mock:scenario:view',
    'mock:scenario:edit',
    'mock:scenario:disable',
    'mock:flow-definition:view',
    'mock:flow-definition:edit',
    'mock:flow:view',
    'mock:flow:transition',
    'mock:flow:reset',
    'mock:flow:delete',
    'mock:callback:view',
    'mock:callback:retry',
    'mock:callback:cancel',
    'mock:approval:view',
    'mock:approval:approve',
    'mock:security-policy:view',
    'mock:security-policy:edit',
    'mock:security-policy:publish',
    'mock:sdk-config:view',
    'mock:sdk-config:edit',
    'mock:sdk-config:publish',
    'mock:release:view',
    'mock:release:publish',
    'mock:release:rollback',
    'mock:request:view',
  ])
  const environment = ref<Environment>('TEST')
  const sidebarCollapsed = ref(false)

  function toggleSidebar() {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }

  function hasRole(role: string) {
    return roles.value.includes(role)
  }

  return {
    operatorId,
    displayName,
    roles,
    permissions,
    environment,
    sidebarCollapsed,
    toggleSidebar,
    hasRole,
  }
})

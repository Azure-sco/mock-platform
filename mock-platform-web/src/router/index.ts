import { createRouter, createWebHistory } from 'vue-router'
import AppLayout from '../layout/AppLayout.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/mock/dashboard' },
    {
      path: '/mock',
      component: AppLayout,
      children: [
        {
          path: 'dashboard',
          name: 'mock-dashboard',
          component: () => import('../views/mock/dashboard/DashboardView.vue'),
          meta: { title: '运行概览' },
        },
        {
          path: 'providers',
          name: 'mock-providers',
          component: () => import('../views/mock/providers/ProviderListView.vue'),
          meta: { title: 'Provider 管理' },
        },
        {
          path: 'apis',
          name: 'mock-apis',
          component: () => import('../views/mock/apis/ApiListView.vue'),
          meta: { title: 'API 管理' },
        },
        {
          path: 'contracts',
          name: 'mock-contracts',
          component: () => import('../views/mock/contracts/ContractListView.vue'),
          meta: { title: '契约管理' },
        },
        {
          path: 'scenarios',
          name: 'mock-scenarios',
          component: () => import('../views/mock/scenarios/ScenarioListView.vue'),
          meta: { title: '场景管理' },
        },
        {
          path: 'approvals',
          name: 'mock-approvals',
          component: () => import('../views/mock/approvals/ApprovalListView.vue'),
          meta: { title: '审批中心' },
        },
        {
          path: 'security-policies',
          name: 'mock-security-policies',
          component: () => import('../views/mock/security-policies/SecurityPolicyView.vue'),
          meta: { title: '安全策略' },
        },
        {
          path: 'sdk-configs',
          name: 'mock-sdk-configs',
          component: () => import('../views/mock/sdk-configs/SdkConfigView.vue'),
          meta: { title: 'SDK Config' },
        },
        {
          path: 'releases',
          name: 'mock-releases',
          component: () => import('../views/mock/releases/ReleaseListView.vue'),
          meta: { title: '发布与回滚' },
        },
        {
          path: 'requests',
          name: 'mock-requests',
          component: () => import('../views/mock/requests/RequestLogView.vue'),
          meta: { title: '请求记录' },
        },
        {
          path: 'flow-definitions',
          name: 'mock-flow-definitions',
          component: () => import('../views/mock/flow-definitions/FlowDefinitionView.vue'),
          meta: { title: '流程定义' },
        },
        {
          path: 'flows',
          name: 'mock-flows',
          component: () => import('../views/mock/flows/FlowInstanceView.vue'),
          meta: { title: '流程实例' },
        },
        {
          path: 'callbacks',
          name: 'mock-callbacks',
          component: () => import('../views/mock/callbacks/CallbackTaskView.vue'),
          meta: { title: '回调任务' },
        },
        {
          path: 'audits',
          name: 'mock-audits',
          component: () => import('../views/mock/audits/AuditLogView.vue'),
          meta: { title: '审计日志' },
        },
      ],
    },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('../views/NotFoundView.vue') },
  ],
})

router.afterEach((route) => {
  document.title = route.meta.title ? `${String(route.meta.title)} · 巡天 Mock 平台` : '巡天 Mock 平台'
})

export default router

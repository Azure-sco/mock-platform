import { createRouter, createWebHistory } from 'vue-router'
import AppLayout from '../layout/AppLayout.vue'
import DashboardView from '../views/mock/dashboard/DashboardView.vue'
import ProviderListView from '../views/mock/providers/ProviderListView.vue'
import ApiListView from '../views/mock/apis/ApiListView.vue'
import ContractListView from '../views/mock/contracts/ContractListView.vue'
import ScenarioListView from '../views/mock/scenarios/ScenarioListView.vue'
import ApprovalListView from '../views/mock/approvals/ApprovalListView.vue'
import SecurityPolicyView from '../views/mock/security-policies/SecurityPolicyView.vue'
import SdkConfigView from '../views/mock/sdk-configs/SdkConfigView.vue'
import ReleaseListView from '../views/mock/releases/ReleaseListView.vue'
import RequestLogView from '../views/mock/requests/RequestLogView.vue'
import FlowDefinitionView from '../views/mock/flow-definitions/FlowDefinitionView.vue'
import FlowInstanceView from '../views/mock/flows/FlowInstanceView.vue'
import CallbackTaskView from '../views/mock/callbacks/CallbackTaskView.vue'
import AuditLogView from '../views/mock/audits/AuditLogView.vue'
import NotFoundView from '../views/NotFoundView.vue'

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
          component: DashboardView,
          meta: { title: '运行概览' },
        },
        {
          path: 'providers',
          name: 'mock-providers',
          component: ProviderListView,
          meta: { title: 'Provider 管理' },
        },
        {
          path: 'apis',
          name: 'mock-apis',
          component: ApiListView,
          meta: { title: 'API 管理' },
        },
        {
          path: 'contracts',
          name: 'mock-contracts',
          component: ContractListView,
          meta: { title: '契约管理' },
        },
        {
          path: 'scenarios',
          name: 'mock-scenarios',
          component: ScenarioListView,
          meta: { title: '场景管理' },
        },
        {
          path: 'approvals',
          name: 'mock-approvals',
          component: ApprovalListView,
          meta: { title: '审批中心' },
        },
        {
          path: 'security-policies',
          name: 'mock-security-policies',
          component: SecurityPolicyView,
          meta: { title: '安全策略' },
        },
        {
          path: 'sdk-configs',
          name: 'mock-sdk-configs',
          component: SdkConfigView,
          meta: { title: 'SDK Config' },
        },
        {
          path: 'releases',
          name: 'mock-releases',
          component: ReleaseListView,
          meta: { title: '发布与回滚' },
        },
        {
          path: 'requests',
          name: 'mock-requests',
          component: RequestLogView,
          meta: { title: '请求记录' },
        },
        {
          path: 'flow-definitions',
          name: 'mock-flow-definitions',
          component: FlowDefinitionView,
          meta: { title: '流程定义' },
        },
        {
          path: 'flows',
          name: 'mock-flows',
          component: FlowInstanceView,
          meta: { title: '流程实例' },
        },
        {
          path: 'callbacks',
          name: 'mock-callbacks',
          component: CallbackTaskView,
          meta: { title: '回调任务' },
        },
        {
          path: 'audits',
          name: 'mock-audits',
          component: AuditLogView,
          meta: { title: '审计日志' },
        },
      ],
    },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: NotFoundView },
  ],
})

router.afterEach((route) => {
  document.title = route.meta.title ? `${String(route.meta.title)} · 巡天 Mock 平台` : '巡天 Mock 平台'
})

export default router

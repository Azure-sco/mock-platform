import { createRouter, createWebHistory } from 'vue-router'
import AppLayout from '../layout/AppLayout.vue'
import DashboardView from '../views/mock/dashboard/DashboardView.vue'
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
      ],
    },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: NotFoundView },
  ],
})

router.afterEach((route) => {
  document.title = route.meta.title ? `${String(route.meta.title)} · 巡天 Mock 平台` : '巡天 Mock 平台'
})

export default router

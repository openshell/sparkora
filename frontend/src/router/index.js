import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '../store/user'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/LoginView.vue') },
  { path: '/', name: 'home', component: () => import('../views/ProjectList.vue'), meta: { auth: true } },
  { path: '/projects/new', name: 'project-new', component: () => import('../views/ProjectEdit.vue'), meta: { auth: true } },
  { path: '/projects/:id', name: 'project-detail', component: () => import('../views/ProjectDetail.vue'), meta: { auth: true } }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  const store = useUserStore()
  if (to.meta.auth && !store.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
})

export default router

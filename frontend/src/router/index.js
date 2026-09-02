import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '../store/user'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/LoginView.vue') },
  { path: '/', name: 'home', component: () => import('../views/ProjectList.vue'), meta: { auth: true } },
  { path: '/projects/new', name: 'project-new', component: () => import('../views/ProjectEdit.vue'), meta: { auth: true } },
  {
    path: '/projects/:id',
    component: () => import('../views/project/ProjectLayout.vue'),
    meta: { auth: true },
    children: [
      { path: '', redirect: { name: 'project-brief' } },
      { path: 'brief', name: 'project-brief', component: () => import('../views/project/StepBrief.vue') },
      { path: 'versions', name: 'project-versions', component: () => import('../views/project/StepVersions.vue') },
      { path: 'images', name: 'project-images', component: () => import('../views/project/StepImages.vue') },
      { path: 'preview', name: 'project-preview', component: () => import('../views/project/StepPreview.vue') },
      { path: 'publish', name: 'project-publish', component: () => import('../views/project/StepPublish.vue') }
    ]
  },
  { path: '/styles', name: 'styles', component: () => import('../views/StyleLibrary.vue'), meta: { auth: true } },
  { path: '/images', name: 'images', component: () => import('../views/ImageLibrary.vue'), meta: { auth: true } },
  { path: '/car', name: 'car', component: () => import('../views/CarLibrary.vue'), meta: { auth: true } },
  { path: '/car/sync', name: 'car-sync', component: () => import('../views/CarSync.vue'), meta: { auth: true } },
  { path: '/car/:id', name: 'car-detail', component: () => import('../views/CarDetail.vue'), meta: { auth: true } }
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

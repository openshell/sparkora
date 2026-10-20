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
      { path: 'preview', name: 'project-preview', component: () => import('../views/project/StepPreview.vue') },
      { path: 'publish', name: 'project-publish', component: () => import('../views/project/StepPublish.vue') },
      // 10-19-channel-editor-publish：王朝小程序三步工作台（生成可选 → 编辑 → 推送）
      { path: 'byd-generate', name: 'project-byd-generate', component: () => import('../views/project/BydGenerateStep.vue') },
      { path: 'byd-editor', name: 'project-byd-editor', component: () => import('../views/project/BydEditorStep.vue') },
      { path: 'byd-push', name: 'project-byd-push', component: () => import('../views/project/BydPushStep.vue') }
    ]
  },
  { path: '/styles', name: 'styles', component: () => import('../views/StyleLibrary.vue'), meta: { auth: true } },
  { path: '/images', name: 'images', component: () => import('../views/ImageLibrary.vue'), meta: { auth: true } },
  { path: '/car/sync', name: 'car-sync', component: () => import('../views/CarSync.vue'), meta: { auth: true } },
  { path: '/car/:id', name: 'car-detail', component: () => import('../views/CarDetail.vue'), meta: { auth: true } },
  { path: '/knowledge', name: 'knowledge', component: () => import('../views/KnowledgeCenter.vue'), meta: { auth: true } },
  { path: '/settings', name: 'settings', component: () => import('../views/SettingsView.vue'), meta: { auth: true } }
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

<template>
  <div class="topbar">
    <div class="brand">Sparkora<span>☆</span></div>
    <div class="nav">
      <router-link v-if="user.isLoggedIn" to="/" class="nav-link">项目</router-link>
      <router-link v-if="user.isEditorOrAbove" to="/styles" class="nav-link">风格库</router-link>
    </div>
    <div class="actions">
      <template v-if="user.isLoggedIn">
        <span class="user-info desktop-only">
          {{ user.user?.displayName || user.user?.username }}
          <el-tag size="small" type="info">{{ user.role }}</el-tag>
        </span>
        <el-button size="small" @click="onLogout">登出</el-button>
      </template>
      <template v-else>
        <el-button size="small" type="primary" @click="$router.push('/login')">登录</el-button>
      </template>
    </div>
  </div>
</template>

<script setup>
import { useUserStore } from '../store/user'
import { useRouter } from 'vue-router'
const user = useUserStore()
const router = useRouter()
const onLogout = () => {
  user.logout()
  router.push('/login')
}
</script>

<style scoped>
.actions { display: flex; align-items: center; gap: 12px; }
.nav { display: flex; gap: 16px; margin-left: 24px; }
.nav-link { color: inherit; text-decoration: none; font-size: 14px; opacity: .85; }
.nav-link.router-link-active { opacity: 1; font-weight: 600; }
.user-info { display: flex; align-items: center; gap: 6px; font-size: 14px; }
@media (max-width: 768px) {
  .desktop-only { display: none; }
}
</style>

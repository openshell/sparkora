<template>
  <div class="topbar">
    <div class="brand">Sparkora<span>☆</span></div>
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
.user-info { display: flex; align-items: center; gap: 6px; font-size: 14px; }
@media (max-width: 768px) {
  .desktop-only { display: none; }
}
</style>

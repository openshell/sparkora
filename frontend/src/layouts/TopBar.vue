<template>
  <div class="topbar">
    <div class="brand serif" @click="$router.push('/')">Sparkora<span>☆</span></div>
    <nav class="nav">
      <router-link v-if="user.isLoggedIn" to="/" class="nav-link">项目</router-link>
      <router-link v-if="user.isLoggedIn" to="/images" class="nav-link">图库</router-link>
      <router-link v-if="user.isEditorOrAbove" to="/styles" class="nav-link">风格库</router-link>
    </nav>
    <div class="actions">
      <template v-if="user.isLoggedIn">
        <span class="user-info desktop-only">
          {{ user.user?.displayName || user.user?.username }}
          <el-tag size="small" type="info" effect="plain" round>{{ user.role }}</el-tag>
        </span>
        <el-button size="small" @click="onLogout">登出</el-button>
      </template>
      <template v-else>
        <el-button size="small" type="primary" @click="$router.push('/login')">登录</el-button>
      </template>
      <!-- 明暗切换:记忆到 localStorage -->
      <button class="theme-toggle" :title="theme.mode === 'dark' ? '切换到浅色' : '切换到深色'"
              :aria-label="theme.mode === 'dark' ? '切换到浅色' : '切换到深色'" @click="theme.toggle()">
        <el-icon :size="16"><Moon v-if="theme.mode === 'light'" /><Sunny v-else /></el-icon>
      </button>
    </div>
  </div>
</template>

<script setup>
import { useUserStore } from '../store/user'
import { useThemeStore } from '../store/theme'
import { useRouter } from 'vue-router'
import { Sunny, Moon } from '@element-plus/icons-vue'

const user = useUserStore()
const theme = useThemeStore()
const router = useRouter()
const onLogout = () => {
  user.logout()
  router.push('/login')
}
</script>

<style scoped>
.actions { display: flex; align-items: center; gap: 12px; }
.nav { display: flex; gap: 18px; margin-left: 28px; }
.nav-link { color: var(--muted); text-decoration: none; font-size: 14px; padding: 4px 0; border-bottom: 2px solid transparent; }
.nav-link.router-link-active { color: var(--ink); font-weight: 600; border-bottom-color: var(--brand); }
.user-info { display: flex; align-items: center; gap: 6px; font-size: 14px; }

.theme-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: 1px solid var(--line);
  border-radius: 50%;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
  transition: color .2s, border-color .2s;
}
.theme-toggle:hover { color: var(--brand); border-color: var(--brand); }

@media (max-width: 768px) {
  .desktop-only { display: none; }
  .topbar { padding: 0 14px; }
  .nav { margin-left: 14px; gap: 12px; }
  .theme-toggle { width: 44px; height: 44px; margin-right: -8px; } /* 触控目标 ≥44px */
}
</style>
<template>
  <div class="app-shell">
    <!-- 左侧模块导航栏 -->
    <aside class="rail" :class="{ collapsed }">
      <div class="rail-brand" role="banner">
        <span class="brand-mark" aria-hidden="true">◈</span>
        <span v-if="!collapsed" class="brand-text">Sparkora</span>
      </div>

      <nav class="rail-nav" aria-label="模块导航">
        <router-link
          v-for="mod in visibleModules" :key="mod.to"
          class="rail-item" :to="mod.to" :class="{ active: activeKey === mod.to }"
          :title="collapsed ? mod.name : ''"
        >
          <el-icon :size="17"><component :is="mod.icon" /></el-icon>
          <span v-if="!collapsed" class="rail-label">{{ mod.name }}</span>
        </router-link>
      </nav>

      <div class="rail-bottom">
        <div class="rail-sep" />
        <router-link
          v-if="visibleSettings" class="rail-item" :to="visibleSettings.to"
          :class="{ active: activeKey === visibleSettings.to }" :title="collapsed ? visibleSettings.name : ''"
        >
          <el-icon :size="17"><component :is="visibleSettings.icon" /></el-icon>
          <span v-if="!collapsed" class="rail-label">{{ visibleSettings.name }}</span>
        </router-link>

        <div class="rail-user">
          <template v-if="user.isLoggedIn">
            <div class="user-chip" :title="collapsed ? displayName : ''">
              <span class="avatar">{{ initial }}</span>
              <template v-if="!collapsed">
                <span class="user-name" :title="displayName">{{ displayName }}</span>
                <el-tag size="small" type="info" effect="plain" round>{{ user.role }}</el-tag>
                <button class="logout-btn" title="登出" @click="onLogout">
                  <el-icon :size="14"><SwitchButton /></el-icon>
                </button>
              </template>
            </div>
          </template>
          <button v-else class="rail-login" @click="$router.push('/login')">登录</button>
        </div>
      </div>
    </aside>

    <!-- 主工作区 -->
    <div class="app-shell__main">
      <header class="ctxbar">
        <div class="ctxbar-left">
          <button class="shell-btn" :title="collapsed ? '展开导航' : '收起导航'" @click="toggleRail">
            <el-icon :size="15"><component :is="collapsed ? Expand : Fold" /></el-icon>
          </button>
          <nav v-if="header.crumbs.length" class="breadcrumb" aria-label="面包屑">
            <template v-for="(c, i) in header.crumbs" :key="i">
              <span v-if="i > 0" class="crumb-sep" aria-hidden="true">/</span>
              <span class="crumb" :class="{ last: i === header.crumbs.length - 1 }" :title="c.label">{{ c.label }}</span>
            </template>
          </nav>
          <span v-else class="crumb module-only" v-text="moduleName" />
        </div>
        <div class="ctxbar-right">
          <template v-for="(a, i) in header.actions" :key="i">
            <el-button
              size="small" :type="a.type || 'default'" :plain="a.plain" :disabled="a.disabled"
              :loading="a.loading" :icon="a.icon"
              @click="a.onClick"
            >{{ a.label }}</el-button>
          </template>
          <button class="theme-toggle" :title="theme.mode === 'dark' ? '切换到浅色' : '切换到深色'"
                  :aria-label="theme.mode === 'dark' ? '切换到浅色' : '切换到深色'" @click="theme.toggle()">
            <el-icon :size="15"><Moon v-if="theme.mode === 'light'" /><Sunny v-else /></el-icon>
          </button>
        </div>
      </header>

      <div class="app-shell__body">
        <slot />
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { SwitchButton, Fold, Expand, Moon, Sunny } from '@element-plus/icons-vue'
import { useUserStore } from '../store/user'
import { useThemeStore } from '../store/theme'
import { NAV_MODULES, NAV_SETTINGS, navKeyFor } from '../constants/nav'
import { providePageHeader } from '../composables/usePageHeader'

const user = useUserStore()
const theme = useThemeStore()
const route = useRoute()
const router = useRouter()

// 页面 → 上下文条通道
const header = providePageHeader()

// rail 折叠态:记忆到 localStorage
const collapsed = ref(localStorage.getItem('sparkora_rail') === '1')
const toggleRail = () => {
  collapsed.value = !collapsed.value
  localStorage.setItem('sparkora_rail', collapsed.value ? '1' : '0')
}

const displayName = computed(() => user.user?.displayName || user.user?.username || '')
const initial = computed(() => displayName.value ? displayName.value.slice(0, 1).toUpperCase() : '?')

// 模块可见性(角色门槛)与高亮
const visibleModules = computed(() => NAV_MODULES.filter((m) => requirePass(m.require)))
const visibleSettings = computed(() => (requirePass(NAV_SETTINGS.require) ? NAV_SETTINGS : null))
const activeKey = computed(() => navKeyFor(route.path))
const moduleName = computed(() => {
  const hit = [...NAV_MODULES, NAV_SETTINGS].find((m) => m.to === activeKey.value)
  return hit ? hit.name : ''
})

const requirePass = (level) => (level === 'editor' ? user.isEditorOrAbove : user.isLoggedIn)

const onLogout = () => {
  user.logout()
  router.push('/login')
}

// dev-only:导航集合必须覆盖 router 的全部 auth 路由(避免新增页面后变成孤岛)
onMounted(() => {
  if (import.meta.env.DEV) {
    const authPaths = router.getRoutes()
      .filter((r) => r.meta.auth && !r.path.includes(':'))
      .map((r) => r.path)
    const covered = authPaths.filter((p) => navKeyFor(p))
    if (covered.length !== authPaths.length) {
      console.warn('[sparkora] 路由未被导航覆盖:', authPaths.filter((p) => !covered.includes(p)))
    }
  }
})
</script>

<style scoped>
.rail {
  width: 220px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: var(--card);
  border-right: 1px solid var(--line);
  transition: width .18s ease;
}
.rail.collapsed { width: 56px; }

.rail-brand {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  height: 48px;
  padding: 0 var(--sp-6);
  border-bottom: 1px solid var(--line);
}
.collapsed .rail-brand { padding: 0; justify-content: center; }
.brand-mark { color: var(--brand); font-size: 16px; }
.brand-text { font-weight: 700; font-size: var(--fs-16); letter-spacing: -.01em; }

.rail-nav {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: var(--sp-2) var(--sp-4);
  display: flex;
  flex-direction: column;
  gap: var(--sp-1);
}
.collapsed .rail-nav { padding: var(--sp-2) var(--sp-4); }

.rail-item {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  height: var(--control-h-md);
  padding: 0 var(--sp-4);
  border-radius: var(--radius-sm);
  color: var(--muted);
  font-size: var(--fs-14);
  text-decoration: none;
  transition: background .15s ease, color .15s ease;
}
.collapsed .rail-item { justify-content: center; padding: 0; }
.rail-item:hover { background: var(--n-50); color: var(--ink); }
.rail-item.active {
  background: var(--brand-weak);
  color: var(--brand-strong);
  font-weight: 500;
}

.rail-bottom { padding: var(--sp-2) var(--sp-4) var(--sp-4); }
.rail-sep { border-top: 1px solid var(--line); margin: var(--sp-2) 0 var(--sp-3); }

.collapsed .rail-bottom { padding: var(--sp-2) var(--sp-4); }

.rail-user { margin-top: var(--sp-2); }
.user-chip {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-2);
  border-radius: var(--radius-sm);
  background: var(--n-50);
  min-width: 0;
}
.collapsed .user-chip { justify-content: center; background: transparent; padding: 0; }
.avatar {
  flex-shrink: 0;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: var(--brand);
  color: #fff;
  font-size: var(--fs-12);
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.user-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--fs-13);
  color: var(--ink);
}
.logout-btn {
  border: none;
  background: transparent;
  color: var(--faint);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  padding: var(--sp-2);
  border-radius: var(--radius-xs);
}
.logout-btn:hover { color: var(--err); background: var(--n-100); }
.rail-login {
  width: 100%;
  height: var(--control-h-sm);
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--ink);
  cursor: pointer;
  font-size: var(--fs-13);
}
.rail-login:hover { border-color: var(--brand); color: var(--brand); }

/* ---------- 上下文条 ---------- */
.ctxbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  height: 44px;
  padding: 0 var(--sp-5) 0 var(--sp-4);
  border-bottom: 1px solid var(--line);
  background: var(--card);
  flex-shrink: 0;
}
.ctxbar-left { display: flex; align-items: center; gap: var(--sp-3); min-width: 0; }
.ctxbar-right { display: flex; align-items: center; gap: var(--sp-3); flex-shrink: 0; }

.shell-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: var(--radius-xs);
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}
.shell-btn:hover { background: var(--n-100); color: var(--ink); }

.breadcrumb { display: flex; align-items: center; gap: var(--sp-2); min-width: 0; }
.crumb {
  font-size: var(--fs-13);
  color: var(--muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 320px;
}
.crumb.last { color: var(--ink); font-weight: 500; }
.crumb-sep { color: var(--faint); font-size: var(--fs-13); }
.module-only { color: var(--ink); font-weight: 500; font-size: var(--fs-13); }

.theme-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: 1px solid var(--line);
  border-radius: var(--radius-xs);
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}
.theme-toggle:hover { color: var(--brand); border-color: var(--brand); }
</style>
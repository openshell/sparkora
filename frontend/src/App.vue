<template>
  <div class="app-root">
    <DesktopGuard />
    <AppShell v-if="useShell">
      <router-view />
    </AppShell>
    <router-view v-else />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppShell from './layouts/AppShell.vue'
import DesktopGuard from './layouts/DesktopGuard.vue'

const route = useRoute()
// 登录页不套外壳(全屏品牌+表单);其余全部进入工作台
const useShell = computed(() => route.name !== 'login')
</script>

<style>
html, body, #app { height: 100%; }
.app-root { min-height: 100%; }
</style>
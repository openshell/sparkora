import { defineStore } from 'pinia'

// 主题模式:'light' | 'dark',持久化 localStorage;dark 通过 html.dark + EP dark CSS vars 生效
export const useThemeStore = defineStore('theme', {
  state: () => ({
    mode: localStorage.getItem('sparkora_theme') === 'dark' ? 'dark' : 'light'
  }),
  actions: {
    apply() {
      const root = document.documentElement
      this.mode === 'dark' ? root.classList.add('dark') : root.classList.remove('dark')
    },
    toggle() {
      this.mode = this.mode === 'dark' ? 'light' : 'dark'
      localStorage.setItem('sparkora_theme', this.mode)
      this.apply()
    }
  }
})
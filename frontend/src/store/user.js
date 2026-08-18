import { defineStore } from 'pinia'
import { authApi } from '../api'

export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('sparkora_token') || '',
    user: JSON.parse(localStorage.getItem('sparkora_user') || 'null')
  }),
  getters: {
    isLoggedIn: (s) => !!s.token,
    role: (s) => s.user?.role || '',
    isEditorOrAbove: (s) => s.user?.role === 'ADMIN' || s.user?.role === 'EDITOR'
  },
  actions: {
    async login(username, password) {
      const res = await authApi.login({ username, password })
      this.token = res.data.token
      this.user = {
        userId: res.data.userId,
        username: res.data.username,
        displayName: res.data.displayName,
        role: res.data.role
      }
      localStorage.setItem('sparkora_token', this.token)
      localStorage.setItem('sparkora_user', JSON.stringify(this.user))
    },
    logout() {
      authApi.logout().catch(() => {})
      this.token = ''
      this.user = null
      localStorage.removeItem('sparkora_token')
      localStorage.removeItem('sparkora_user')
    }
  }
})

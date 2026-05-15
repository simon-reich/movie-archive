import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref<string | null>(null)
  const userEmail = ref<string | null>(null)

  function setAuth(token: string, email: string): void {
    accessToken.value = token
    userEmail.value = email
  }

  function clearAuth(): void {
    accessToken.value = null
    userEmail.value = null
  }

  async function refresh(): Promise<void> {
    // Throws on failure — callers catch and handle
    const data = await $fetch<{ accessToken: string; email: string }>('/api/auth/refresh', {
      method: 'POST',
      credentials: 'include',
    })
    setAuth(data.accessToken, data.email)
  }

  const isAuthenticated = computed(() => !!accessToken.value)

  return { accessToken, userEmail, isAuthenticated, setAuth, clearAuth, refresh }
})

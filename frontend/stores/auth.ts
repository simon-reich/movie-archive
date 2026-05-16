import { defineStore } from 'pinia'
import { computed } from 'vue'

export const useAuthStore = defineStore('auth', () => {
  // Drive auth state from the readable access_token cookie.
  // useCookie works on both SSR (reads request headers) and client (reads document.cookie),
  // so there is no plugin, no async round-trip, and no hydration timing issues.
  const accessTokenCookie = useCookie<string | null>('access_token', { default: () => null })
  const sessionEmailCookie = useCookie<string | null>('session_email', { default: () => null })

  // Kept for backwards-compat with any code that calls setAuth/clearAuth directly (e.g. after login).
  // Writing to the cookie ref also updates document.cookie and the SSR cookie state.
  function setAuth(token: string, email: string): void {
    accessTokenCookie.value = token
    sessionEmailCookie.value = email
  }

  function clearAuth(): void {
    accessTokenCookie.value = null
    sessionEmailCookie.value = null
  }

  async function refresh(): Promise<void> {
    // Still used for proactive token rotation (e.g. on app focus), but NOT required for
    // page-load auth — the access_token cookie handles that without an async round-trip.
    const data = await $fetch<{ accessToken: string; email: string }>('/api/auth/refresh', {
      method: 'POST',
      credentials: 'include',
    })
    setAuth(data.accessToken, data.email)
  }

  const accessToken = computed(() => accessTokenCookie.value)
  const userEmail = computed(() => sessionEmailCookie.value)
  const isAuthenticated = computed(() => !!accessTokenCookie.value)

  return { accessToken, userEmail, isAuthenticated, setAuth, clearAuth, refresh }
})

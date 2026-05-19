import { useAuthStore } from '@/stores/auth'

export function useAuth() {
  const authStore = useAuthStore()

  async function login(email: string, password: string): Promise<void> {
    const data = await $fetch<{ accessToken: string; email: string }>('/api/auth/login', {
      method: 'POST',
      body: { email, password },
      credentials: 'include',
    })
    authStore.setAuth(data.accessToken, data.email)
    await navigateTo('/')
  }

  async function signup(email: string, password: string): Promise<void> {
    await $fetch('/api/auth/signup', {
      method: 'POST',
      body: { email, password },
      credentials: 'include',
    })
    // No setAuth — no auto-login after signup (D-09)
    await navigateTo('/verify-email-sent')
  }

  async function logout(): Promise<void> {
    try {
      await $fetch('/api/auth/logout', {
        method: 'POST',
        credentials: 'include',
      })
    } finally {
      authStore.clearAuth()
      await navigateTo('/login')
    }
  }

  async function verifyEmail(token: string): Promise<void> {
    await $fetch('/api/auth/verify-email', {
      method: 'POST',
      body: { token },
      credentials: 'include',
    })
  }

  async function forgotPassword(email: string): Promise<void> {
    await $fetch('/api/auth/forgot-password', {
      method: 'POST',
      body: { email },
      credentials: 'include',
    })
  }

  async function resetPassword(token: string, newPassword: string): Promise<void> {
    await $fetch('/api/auth/reset-password', {
      method: 'POST',
      body: { token, newPassword },
      credentials: 'include',
    })
  }

  return { login, signup, logout, verifyEmail, forgotPassword, resetPassword }
}

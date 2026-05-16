import { useAuthStore } from '@/stores/auth'

export function useSettings() {
  const authStore = useAuthStore()

  function authHeaders(): Record<string, string> {
    return authStore.accessToken ? { Authorization: `Bearer ${authStore.accessToken}` } : {}
  }

  async function saveApiKey(provider: 'tmdb' | 'omdb', key: string): Promise<void> {
    await $fetch<void>(`/api/settings/api-keys/${provider}` as string, {
      method: 'PUT',
      body: { key },
      credentials: 'include',
      headers: authHeaders(),
    })
  }

  async function loadApiKeys(): Promise<{ tmdb: string | null; omdb: string | null }> {
    return await $fetch<{ tmdb: string | null; omdb: string | null }>('/api/settings/api-keys' as string, {
      credentials: 'include',
      headers: authHeaders(),
    })
  }

  async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
    await $fetch<void>('/api/settings/password' as string, {
      method: 'POST',
      body: { currentPassword, newPassword },
      credentials: 'include',
      headers: authHeaders(),
    })
    // D-05: clear auth store BEFORE navigating to /login (Pitfall 5 — prevents redirect loop)
    authStore.clearAuth()
    await navigateTo('/login')
  }

  async function changeEmail(newEmail: string): Promise<void> {
    await $fetch<void>('/api/settings/email' as string, {
      method: 'POST',
      body: { newEmail },
      credentials: 'include',
      headers: authHeaders(),
    })
  }

  return { saveApiKey, loadApiKeys, changePassword, changeEmail }
}

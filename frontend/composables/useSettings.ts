import { useAuthStore } from '@/stores/auth'

export function useSettings() {
  const authStore = useAuthStore()

  async function saveApiKey(provider: 'tmdb' | 'omdb', key: string): Promise<void> {
    await $fetch(`/api/settings/api-keys/${provider}`, {
      method: 'PUT',
      body: { key },
      credentials: 'include',
    })
  }

  async function loadApiKeys(): Promise<{ tmdb: string | null; omdb: string | null }> {
    return await $fetch('/api/settings/api-keys', {
      credentials: 'include',
    })
  }

  async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
    await $fetch('/api/settings/password', {
      method: 'POST',
      body: { currentPassword, newPassword },
      credentials: 'include',
    })
    // D-05: clear auth store BEFORE navigating to /login (Pitfall 5 — prevents redirect loop)
    authStore.clearAuth()
    await navigateTo('/login')
  }

  async function changeEmail(newEmail: string): Promise<void> {
    await $fetch('/api/settings/email', {
      method: 'POST',
      body: { newEmail },
      credentials: 'include',
    })
  }

  return { saveApiKey, loadApiKeys, changePassword, changeEmail }
}

export function useSettings() {
  // Read the access token directly from the cookie — works on both SSR and client.
  // useCookie is a Nuxt auto-import; no store reference needed here.
  const accessTokenCookie = useCookie<string | null>('access_token')

  function authHeaders(): Record<string, string> {
    return accessTokenCookie.value
      ? { Authorization: `Bearer ${accessTokenCookie.value}` }
      : {}
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

  async function deleteApiKey(provider: 'tmdb' | 'omdb'): Promise<void> {
    await $fetch<void>(`/api/settings/api-keys/${provider}` as string, {
      method: 'DELETE',
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
    // D-05: clear auth store BEFORE navigating to /login (prevents redirect loop)
    useAuthStore().clearAuth()
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

  return { saveApiKey, deleteApiKey, loadApiKeys, changePassword, changeEmail }
}

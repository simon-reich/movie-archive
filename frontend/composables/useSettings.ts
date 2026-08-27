import { ref } from 'vue'
import { fetchEventSource } from '@microsoft/fetch-event-source'

export interface WikiReloadProgress {
  processed: number
  total: number
  complete: boolean
  lastMovieTitle: string | null
  lastMovieStatus: string | null
  etaSeconds: number
}

export function useSettings() {
  // Read the access token directly from the cookie — works on both SSR and client.
  // useCookie is a Nuxt auto-import; no store reference needed here.
  const accessTokenCookie = useCookie<string | null>('access_token')

  function authHeaders(): Record<string, string> {
    return accessTokenCookie.value
      ? { Authorization: `Bearer ${accessTokenCookie.value}` }
      : {}
  }

  const currentUserId = ref<string | null>(null)

  // Fetches GET /api/users/me once and caches the id — never re-fetched on
  // subsequent calls within the same composable instance.
  async function getCurrentUserId(): Promise<string> {
    if (!currentUserId.value) {
      const data = await $fetch<{ id: string }>('/api/users/me' as string, {
        credentials: 'include',
        headers: authHeaders(),
      })
      currentUserId.value = data.id
    }
    return currentUserId.value
  }

  // Triggers the existing Phase 8 batch-reload endpoint for the current user.
  // Maps 202 to 'started' and 503 to 'already-running'; any other error is
  // rethrown so the page-level catch-all can show a generic failure message.
  async function triggerWikiReload(): Promise<'started' | 'already-running'> {
    const userId = await getCurrentUserId()
    try {
      await $fetch(`/api/admin/wiki-reload/${userId}` as string, {
        method: 'POST',
        credentials: 'include',
        headers: authHeaders(),
      })
      return 'started'
    } catch (err: unknown) {
      const e = err as { response?: { status?: number } }
      if (e?.response?.status === 503) return 'already-running'
      throw err
    }
  }

  // Subscribes to the live SSE progress stream for the given user's wiki-reload run
  // (D-14-03). Mirrors useBulkImport.ts#subscribeToProgress exactly — never native
  // EventSource, since this app's JWT scheme requires a header-based Authorization.
  // Returns an unsubscribe function.
  function subscribeToWikiReloadProgress(
    userId: string,
    onProgress: (p: WikiReloadProgress) => void
  ): () => void {
    const ctrl = new AbortController()
    fetchEventSource(`/api/admin/wiki-reload/${userId}/progress`, {
      headers: authHeaders(),
      signal: ctrl.signal,
      async onopen() {
        // no-op: default fetch-event-source behavior already validates content-type on open
      },
      onmessage(ev) {
        if (ev.event === 'progress' || ev.event === 'complete') {
          onProgress(JSON.parse(ev.data) as WikiReloadProgress)
        }
      },
      onerror(err) {
        // Stop the library's default retry-forever behavior on a fatal error (e.g. 403/404)
        throw err
      },
    })
    return () => ctrl.abort()
  }

  // Requests a clean halt of the current user's in-progress wiki-reload run (D-14-04).
  async function stopWikiReload(): Promise<void> {
    const userId = await getCurrentUserId()
    await $fetch(`/api/admin/wiki-reload/${userId}/stop` as string, {
      method: 'POST',
      credentials: 'include',
      headers: authHeaders(),
    })
  }

  async function saveApiKey(provider: 'tmdb' | 'omdb', key: string): Promise<void> {
    await $fetch(`/api/settings/api-keys/${provider}` as string, {
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
    await $fetch(`/api/settings/api-keys/${provider}` as string, {
      method: 'DELETE',
      credentials: 'include',
      headers: authHeaders(),
    })
  }

  async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
    await $fetch('/api/settings/password' as string, {
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
    await $fetch('/api/settings/email' as string, {
      method: 'POST',
      body: { newEmail },
      credentials: 'include',
      headers: authHeaders(),
    })
  }

  return {
    saveApiKey,
    deleteApiKey,
    loadApiKeys,
    changePassword,
    changeEmail,
    getCurrentUserId,
    triggerWikiReload,
    subscribeToWikiReloadProgress,
    stopWikiReload,
  }
}
